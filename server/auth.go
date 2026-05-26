package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/base32"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"net/mail"
	"strings"
	"time"
	"unicode"

	"golang.org/x/crypto/bcrypt"
)

const (
	// portalCookieName is the Portal/member-facing session cookie.
	// adminCookieName is the Backoffice/admin-facing session cookie.
	// Browsers ignore port when scoping cookies, so two cookies are required to
	// keep Portal (e.g. :5173) and Backoffice (e.g. :5174) sessions independent
	// in the same browser.
	portalCookieName = "tkr_session"
	adminCookieName  = "tkr_admin_session"

	// sessionCookieName is the legacy alias for portalCookieName. Retained for
	// internal references where the meaning is unambiguous. New code should
	// prefer the scope-explicit names above.
	sessionCookieName = portalCookieName

	// Session scopes — must match the values stored in sessions.scope.
	sessionScopePortal = "portal"
	sessionScopeAdmin  = "admin"

	defaultSessionTTL = 30 * 24 * time.Hour
	bcryptCost        = 12
)

// -----------------------------------------------------------------------------
// Token helpers
// -----------------------------------------------------------------------------

func generateSessionToken() string {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return base64.URLEncoding.WithPadding(base64.NoPadding).EncodeToString(b)
}

func generatePairToken() string {
	b := make([]byte, 10) // 80 bits -> 16-char base32
	_, _ = rand.Read(b)
	return strings.ToUpper(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b))
}

func generateTempPassword() string {
	// 9 random bytes -> 12 base64 chars (no padding) — URL-safe-ish.
	b := make([]byte, 9)
	_, _ = rand.Read(b)
	return base64.URLEncoding.WithPadding(base64.NoPadding).EncodeToString(b)
}

// -----------------------------------------------------------------------------
// Validation
// -----------------------------------------------------------------------------

func validateEmail(s string) (string, bool) {
	s = strings.TrimSpace(strings.ToLower(s))
	if len(s) == 0 || len(s) > 255 {
		return "", false
	}
	addr, err := mail.ParseAddress(s)
	if err != nil {
		return "", false
	}
	return strings.ToLower(addr.Address), true
}

// validatePassword: min 8 chars, at least one letter AND one digit.
func validatePassword(p string) bool {
	if len(p) < 8 || len(p) > 200 {
		return false
	}
	var hasLetter, hasDigit bool
	for _, r := range p {
		switch {
		case unicode.IsLetter(r):
			hasLetter = true
		case unicode.IsDigit(r):
			hasDigit = true
		}
	}
	return hasLetter && hasDigit
}

// -----------------------------------------------------------------------------
// Session CRUD
// -----------------------------------------------------------------------------

// createSession inserts a session row for the given user with the requested
// scope ('portal' or 'admin'). Callers should pass sessionScopePortal /
// sessionScopeAdmin to avoid typos.
func createSession(userID int64, scope string) (*Session, error) {
	token := generateSessionToken()
	expires := time.Now().Add(sessionTTL())
	_, err := db.Exec(
		"INSERT INTO sessions (token, user_id, scope, expires_at) VALUES (?, ?, ?, ?)",
		token, userID, scope, expires,
	)
	if err != nil {
		return nil, err
	}
	return &Session{
		Token:     token,
		UserID:    userID,
		Scope:     scope,
		ExpiresAt: expires,
		CreatedAt: time.Now(),
	}, nil
}

// lookupSession resolves a session token AND enforces that its scope matches
// `expectedScope`. A token issued for one scope (e.g. 'portal') cannot be used
// to authenticate against the other scope's endpoints, even if the underlying
// user is otherwise valid. Pass empty string to skip the scope check (used by
// logout where any token is acceptable).
func lookupSession(token, expectedScope string) (*Session, *User, error) {
	if token == "" {
		return nil, nil, sql.ErrNoRows
	}
	var s Session
	var u User
	err := db.QueryRow(`
		SELECT s.token, s.user_id, s.scope, s.created_at, s.expires_at,
		       u.id, u.email, u.role, u.must_change_password, u.created_at
		FROM sessions s
		JOIN users u ON u.id = s.user_id
		WHERE s.token = ?`, token,
	).Scan(&s.Token, &s.UserID, &s.Scope, &s.CreatedAt, &s.ExpiresAt,
		&u.ID, &u.Email, &u.Role, &u.MustChangePassword, &u.CreatedAt)
	if err != nil {
		return nil, nil, err
	}
	if time.Now().After(s.ExpiresAt) {
		_, _ = db.Exec("DELETE FROM sessions WHERE token = ?", token)
		return nil, nil, sql.ErrNoRows
	}
	if expectedScope != "" && s.Scope != expectedScope {
		// Treat scope mismatch as "no such session" so the response is the same
		// as a missing cookie — never disclose that a token exists in another
		// scope.
		return nil, nil, sql.ErrNoRows
	}
	return &s, &u, nil
}

func deleteSession(token string) {
	_, _ = db.Exec("DELETE FROM sessions WHERE token = ?", token)
}

func sessionTTL() time.Duration {
	if v := getenvInt("SESSION_TTL_HOURS", 720); v > 0 {
		return time.Duration(v) * time.Hour
	}
	return defaultSessionTTL
}

// setSessionCookieNamed writes a session cookie with the given name. Used by
// both Portal (tkr_session) and Backoffice (tkr_admin_session) flows so the
// two cookies stay independent in the browser jar.
func setSessionCookieNamed(w http.ResponseWriter, name, token string, expires time.Time) {
	cookie := &http.Cookie{
		Name:     name,
		Value:    token,
		Path:     "/",
		Expires:  expires,
		MaxAge:   int(time.Until(expires).Seconds()),
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   isSecureEnv(),
	}
	http.SetCookie(w, cookie)
}

// clearSessionCookieNamed expires the named cookie immediately.
func clearSessionCookieNamed(w http.ResponseWriter, name string) {
	cookie := &http.Cookie{
		Name:     name,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   isSecureEnv(),
	}
	http.SetCookie(w, cookie)
}

// setSessionCookie / clearSessionCookie are convenience wrappers that target
// the Portal cookie. Kept for legacy callers; Backoffice flows pass an
// explicit cookie name via the *Named variants above.
func setSessionCookie(w http.ResponseWriter, token string, expires time.Time) {
	setSessionCookieNamed(w, portalCookieName, token, expires)
}

func clearSessionCookie(w http.ResponseWriter) {
	clearSessionCookieNamed(w, portalCookieName)
}

// -----------------------------------------------------------------------------
// Handlers: signup, login, logout, me
// -----------------------------------------------------------------------------

type signupReq struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

func signupHandler(w http.ResponseWriter, r *http.Request) {
	if !rateAllow("signup:"+clientIP(r), 3, time.Hour) {
		writeError(w, http.StatusTooManyRequests, "RATE_LIMITED", "Too many signups, try later")
		return
	}
	var body signupReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "invalid JSON")
		return
	}
	email, ok := validateEmail(body.Email)
	if !ok {
		writeError(w, http.StatusBadRequest, "INVALID_EMAIL", "Email format invalid")
		return
	}
	if !validatePassword(body.Password) {
		writeError(w, http.StatusBadRequest, "WEAK_PASSWORD", "Password must be at least 8 chars with letter and digit")
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(body.Password), bcryptCost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	res, err := db.Exec(
		"INSERT INTO users (email, password_hash, role) VALUES (?, ?, 'user')",
		email, string(hash),
	)
	if err != nil {
		// likely UNIQUE constraint
		if strings.Contains(err.Error(), "UNIQUE") || strings.Contains(err.Error(), "constraint") {
			writeError(w, http.StatusConflict, "EMAIL_TAKEN", "Email already registered")
			return
		}
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	id, _ := res.LastInsertId()

	user := &User{ID: id, Email: email, Role: "user", CreatedAt: time.Now()}
	sess, err := createSession(user.ID, sessionScopePortal)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	setSessionCookieNamed(w, portalCookieName, sess.Token, sess.ExpiresAt)
	enrichUserSubscription(user)
	writeJSON(w, http.StatusCreated, map[string]any{
		"user":       user,
		"expires_at": sess.ExpiresAt,
	})
}

type loginReq struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

func loginHandler(w http.ResponseWriter, r *http.Request) {
	if !rateAllow("login:"+clientIP(r), 5, time.Minute) {
		writeError(w, http.StatusTooManyRequests, "RATE_LIMITED", "Too many attempts")
		return
	}
	var body loginReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "invalid JSON")
		return
	}
	email, ok := validateEmail(body.Email)
	if !ok {
		writeError(w, http.StatusUnauthorized, "INVALID_CREDENTIALS", "Email or password incorrect")
		return
	}

	var u User
	var hash string
	err := db.QueryRow(
		"SELECT id, email, password_hash, role, must_change_password, created_at FROM users WHERE email = ?", email,
	).Scan(&u.ID, &u.Email, &hash, &u.Role, &u.MustChangePassword, &u.CreatedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeError(w, http.StatusUnauthorized, "INVALID_CREDENTIALS", "Email or password incorrect")
			return
		}
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(body.Password)); err != nil {
		writeError(w, http.StatusUnauthorized, "INVALID_CREDENTIALS", "Email or password incorrect")
		return
	}

	sess, err := createSession(u.ID, sessionScopePortal)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	setSessionCookieNamed(w, portalCookieName, sess.Token, sess.ExpiresAt)
	enrichUserSubscription(&u)
	writeJSON(w, http.StatusOK, map[string]any{
		"user":       &u,
		"expires_at": sess.ExpiresAt,
	})
}

func logoutHandler(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(portalCookieName); err == nil {
		deleteSession(c.Value)
	}
	clearSessionCookieNamed(w, portalCookieName)
	w.WriteHeader(http.StatusNoContent)
}

func meHandler(w http.ResponseWriter, r *http.Request) {
	u := userFromCtx(r)
	if u == nil {
		writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "no session")
		return
	}
	enrichUserSubscription(u)
	writeJSON(w, http.StatusOK, map[string]any{"user": u})
}

// -----------------------------------------------------------------------------
// Change password (required after admin reset; usable anytime by the user)
// -----------------------------------------------------------------------------

type changePasswordReq struct {
	CurrentPassword string `json:"current_password"`
	NewPassword     string `json:"new_password"`
}

func changePasswordHandler(w http.ResponseWriter, r *http.Request) {
	u := userFromCtx(r)
	sess := sessionFromCtx(r)
	if u == nil || sess == nil {
		writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "no session")
		return
	}

	var body changePasswordReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "invalid JSON")
		return
	}

	// Verify current password.
	var currentHash string
	if err := db.QueryRow("SELECT password_hash FROM users WHERE id=?", u.ID).Scan(&currentHash); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if err := bcrypt.CompareHashAndPassword([]byte(currentHash), []byte(body.CurrentPassword)); err != nil {
		writeError(w, http.StatusUnauthorized, "INVALID_CREDENTIALS", "รหัสผ่านปัจจุบันไม่ถูกต้อง")
		return
	}

	// Validate new password against tech-spec §3 rules.
	if !validatePassword(body.NewPassword) {
		writeError(w, http.StatusBadRequest, "WEAK_PASSWORD", "รหัสผ่านใหม่ต้องมีอย่างน้อย 8 ตัวอักษร พร้อมตัวอักษรและตัวเลข")
		return
	}

	newHash, err := bcrypt.GenerateFromPassword([]byte(body.NewPassword), bcryptCost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	// Update password + clear must_change_password flag.
	if _, err := db.Exec(
		"UPDATE users SET password_hash=?, must_change_password=0 WHERE id=?",
		string(newHash), u.ID,
	); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	// Invalidate all OTHER sessions for this user (keep current).
	_, _ = db.Exec(
		"DELETE FROM sessions WHERE user_id=? AND token<>?",
		u.ID, sess.Token,
	)

	// Return refreshed user object.
	var refreshed User
	if err := db.QueryRow(
		"SELECT id, email, role, must_change_password, created_at FROM users WHERE id=?", u.ID,
	).Scan(&refreshed.ID, &refreshed.Email, &refreshed.Role, &refreshed.MustChangePassword, &refreshed.CreatedAt); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	enrichUserSubscription(&refreshed)
	writeJSON(w, http.StatusOK, map[string]any{"user": &refreshed})
}

// -----------------------------------------------------------------------------
// Admin auth handlers (/api/admin/auth/*) — backoffice-scoped
//
// These mirror the portal-facing handlers above but:
//   - Read / write the `tkr_admin_session` cookie instead of `tkr_session`
//   - Create sessions with scope='admin'
//   - Refuse to issue a session unless the authenticating user has role='admin'
//
// The split lets Portal and Backoffice maintain independent identities in the
// same browser (cookies are not partitioned by port).
// -----------------------------------------------------------------------------

func adminLoginHandler(w http.ResponseWriter, r *http.Request) {
	// Share the login rate-limiter bucket with the portal login — the limit is
	// per-IP-and-flow, not per-endpoint.
	if !rateAllow("admin-login:"+clientIP(r), 5, time.Minute) {
		writeError(w, http.StatusTooManyRequests, "RATE_LIMITED", "Too many attempts")
		return
	}
	var body loginReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "invalid JSON")
		return
	}
	email, ok := validateEmail(body.Email)
	if !ok {
		writeError(w, http.StatusUnauthorized, "INVALID_CREDENTIALS", "Email or password incorrect")
		return
	}

	var u User
	var hash string
	err := db.QueryRow(
		"SELECT id, email, password_hash, role, must_change_password, created_at FROM users WHERE email = ?", email,
	).Scan(&u.ID, &u.Email, &hash, &u.Role, &u.MustChangePassword, &u.CreatedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeError(w, http.StatusUnauthorized, "INVALID_CREDENTIALS", "Email or password incorrect")
			return
		}
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(body.Password)); err != nil {
		writeError(w, http.StatusUnauthorized, "INVALID_CREDENTIALS", "Email or password incorrect")
		return
	}
	// Backoffice gate — only role='admin' may obtain an admin session. Returning
	// 403 (not 401) signals "you exist, you're just not allowed here", which the
	// backoffice SPA surfaces with a distinct message.
	if u.Role != "admin" {
		writeError(w, http.StatusForbidden, "FORBIDDEN", "บัญชีนี้ไม่มีสิทธิ์เข้าใช้ Backoffice")
		return
	}

	sess, err := createSession(u.ID, sessionScopeAdmin)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	setSessionCookieNamed(w, adminCookieName, sess.Token, sess.ExpiresAt)
	enrichUserSubscription(&u)
	writeJSON(w, http.StatusOK, map[string]any{
		"user":       &u,
		"expires_at": sess.ExpiresAt,
	})
}

func adminLogoutHandler(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(adminCookieName); err == nil {
		deleteSession(c.Value)
	}
	clearSessionCookieNamed(w, adminCookieName)
	w.WriteHeader(http.StatusNoContent)
}

func adminMeHandler(w http.ResponseWriter, r *http.Request) {
	u := userFromCtx(r)
	if u == nil {
		writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "no session")
		return
	}
	enrichUserSubscription(u)
	writeJSON(w, http.StatusOK, map[string]any{"user": u})
}

// adminChangePasswordHandler mirrors changePasswordHandler but operates on the
// admin session and only invalidates other admin-scoped sessions (so the
// admin's parallel portal session, if any, is left alone).
func adminChangePasswordHandler(w http.ResponseWriter, r *http.Request) {
	u := userFromCtx(r)
	sess := sessionFromCtx(r)
	if u == nil || sess == nil {
		writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "no session")
		return
	}

	var body changePasswordReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "invalid JSON")
		return
	}

	var currentHash string
	if err := db.QueryRow("SELECT password_hash FROM users WHERE id=?", u.ID).Scan(&currentHash); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if err := bcrypt.CompareHashAndPassword([]byte(currentHash), []byte(body.CurrentPassword)); err != nil {
		writeError(w, http.StatusUnauthorized, "INVALID_CREDENTIALS", "รหัสผ่านปัจจุบันไม่ถูกต้อง")
		return
	}
	if !validatePassword(body.NewPassword) {
		writeError(w, http.StatusBadRequest, "WEAK_PASSWORD", "รหัสผ่านใหม่ต้องมีอย่างน้อย 8 ตัวอักษร พร้อมตัวอักษรและตัวเลข")
		return
	}

	newHash, err := bcrypt.GenerateFromPassword([]byte(body.NewPassword), bcryptCost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if _, err := db.Exec(
		"UPDATE users SET password_hash=?, must_change_password=0 WHERE id=?",
		string(newHash), u.ID,
	); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	// Invalidate every OTHER session for this user (across both scopes) — a
	// password change should bounce all devices, even the user's own portal
	// session in a parallel tab. The current admin session is preserved.
	_, _ = db.Exec(
		"DELETE FROM sessions WHERE user_id=? AND token<>?",
		u.ID, sess.Token,
	)

	var refreshed User
	if err := db.QueryRow(
		"SELECT id, email, role, must_change_password, created_at FROM users WHERE id=?", u.ID,
	).Scan(&refreshed.ID, &refreshed.Email, &refreshed.Role, &refreshed.MustChangePassword, &refreshed.CreatedAt); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	enrichUserSubscription(&refreshed)
	writeJSON(w, http.StatusOK, map[string]any{"user": &refreshed})
}

// -----------------------------------------------------------------------------
// Bootstrap admin
// -----------------------------------------------------------------------------

// bootstrapAdmin inserts an admin user from env vars if the users table is empty.
func bootstrapAdmin() {
	email := getenv("BOOTSTRAP_ADMIN_EMAIL", "")
	pw := getenv("BOOTSTRAP_ADMIN_PASSWORD", "")
	if email == "" || pw == "" {
		return
	}
	cleanEmail, ok := validateEmail(email)
	if !ok {
		return
	}

	// Only bootstrap if there are no admins at all (safer than "no users").
	var n int
	if err := db.QueryRow("SELECT COUNT(*) FROM users WHERE role='admin'").Scan(&n); err != nil {
		return
	}
	if n > 0 {
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(pw), bcryptCost)
	if err != nil {
		return
	}

	// Upsert: if email exists as a regular user, promote; else insert.
	var uid int64
	row := db.QueryRow("SELECT id FROM users WHERE email = ?", cleanEmail)
	if err := row.Scan(&uid); err == nil {
		_, _ = db.Exec("UPDATE users SET role='admin', password_hash=? WHERE id=?", string(hash), uid)
	} else {
		_, _ = db.Exec(
			"INSERT INTO users (email, password_hash, role) VALUES (?, ?, 'admin')",
			cleanEmail, string(hash),
		)
	}
}
