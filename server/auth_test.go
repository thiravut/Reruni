package main

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
)

// setupTestServer opens the Postgres DB pointed at by TEST_DATABASE_URL,
// runs migrations, truncates all tables, and returns a configured test
// server + teardown. If TEST_DATABASE_URL is empty the test is skipped so
// machines without a local Postgres still pass `go test`.
//
// Example:
//   docker run --rm -d -p 5433:5432 -e POSTGRES_PASSWORD=test -e POSTGRES_DB=rerun_test postgres:16
//   TEST_DATABASE_URL=postgres://postgres:test@127.0.0.1:5433/rerun_test?sslmode=disable go test ./...
func setupTestServer(t *testing.T) (*httptest.Server, func()) {
	t.Helper()

	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL not set — skipping integration test")
	}

	tmpDir, err := os.MkdirTemp("", "tkrtest-")
	if err != nil {
		t.Fatalf("mkdir temp: %v", err)
	}
	uploadsDir = filepath.Join(tmpDir, "uploads")
	_ = os.MkdirAll(uploadsDir, 0o755)

	raw, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatalf("open db: %v", err)
	}
	if err := runMigrations(raw); err != nil {
		t.Fatalf("run migrations: %v", err)
	}
	if _, err := raw.Exec(`
		TRUNCATE TABLE
			tiktok_accounts, stripe_events, subscriptions, commands,
			banners, live_sessions, pair_tokens, devices, videos,
			sessions, users
		RESTART IDENTITY CASCADE`); err != nil {
		t.Fatalf("truncate: %v", err)
	}
	db = &DB{DB: raw}

	// Reset rate limiter so prior tests don't bleed in.
	rateBucketsMu.Lock()
	rateBuckets = map[string]*rateBucket{}
	rateBucketsMu.Unlock()

	srv := httptest.NewServer(buildRouter())
	return srv, func() {
		srv.Close()
		raw.Close()
		os.RemoveAll(tmpDir)
	}
}

func postJSON(t *testing.T, srv *httptest.Server, jar http.CookieJar, path string, body any) *http.Response {
	t.Helper()
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest("POST", srv.URL+path, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	client := &http.Client{Jar: jar}
	res, err := client.Do(req)
	if err != nil {
		t.Fatalf("POST %s: %v", path, err)
	}
	return res
}

func getJSON(t *testing.T, srv *httptest.Server, jar http.CookieJar, path string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest("GET", srv.URL+path, nil)
	client := &http.Client{Jar: jar}
	res, err := client.Do(req)
	if err != nil {
		t.Fatalf("GET %s: %v", path, err)
	}
	return res
}

// -----------------------------------------------------------------------------
// Validation
// -----------------------------------------------------------------------------

func TestValidateEmail(t *testing.T) {
	cases := []struct {
		in   string
		want bool
	}{
		{"foo@bar.com", true},
		{"FOO@BAR.COM", true},
		{"", false},
		{"not-an-email", false},
		{"user@", false},
	}
	for _, c := range cases {
		_, ok := validateEmail(c.in)
		if ok != c.want {
			t.Errorf("validateEmail(%q) = %v, want %v", c.in, ok, c.want)
		}
	}
}

func TestValidatePassword(t *testing.T) {
	cases := []struct {
		in   string
		want bool
	}{
		{"short1", false},
		{"longenoughbutnodigit", false},
		{"12345678", false}, // digits only — no letter
		{"abcd1234", true},
		{"P@ssw0rd", true},
	}
	for _, c := range cases {
		got := validatePassword(c.in)
		if got != c.want {
			t.Errorf("validatePassword(%q) = %v, want %v", c.in, got, c.want)
		}
	}
}

// -----------------------------------------------------------------------------
// Integration: signup → me → logout
// -----------------------------------------------------------------------------

func TestSignupLoginFlow(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	jar, _ := newCookieJar()

	// Bad email
	res := postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "not-an-email", "password": "abcd1234",
	})
	if res.StatusCode != 400 {
		t.Fatalf("expected 400, got %d", res.StatusCode)
	}

	// Weak password
	res = postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "a@b.com", "password": "short",
	})
	if res.StatusCode != 400 {
		t.Fatalf("expected 400, got %d", res.StatusCode)
	}

	// Good signup
	res = postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "alice@example.com", "password": "abcd1234",
	})
	if res.StatusCode != 201 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("expected 201, got %d: %s", res.StatusCode, body)
	}

	// Cookie set
	if len(jar.Cookies(mustURL(srv.URL))) == 0 {
		t.Fatalf("expected session cookie set")
	}

	// /me works
	res = getJSON(t, srv, jar, "/api/auth/me")
	if res.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", res.StatusCode)
	}
	var me struct {
		User User `json:"user"`
	}
	_ = json.NewDecoder(res.Body).Decode(&me)
	if me.User.Email != "alice@example.com" {
		t.Fatalf("wrong me: %+v", me)
	}

	// Reset the rate bucket so the duplicate-signup attempt isn't 429-blocked.
	rateBucketsMu.Lock()
	rateBuckets = map[string]*rateBucket{}
	rateBucketsMu.Unlock()

	// Duplicate signup
	res = postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "alice@example.com", "password": "abcd1234",
	})
	if res.StatusCode != 409 {
		t.Fatalf("expected 409, got %d", res.StatusCode)
	}

	// Wrong password login
	jar2, _ := newCookieJar()
	res = postJSON(t, srv, jar2, "/api/auth/login", map[string]string{
		"email": "alice@example.com", "password": "wrongpass1",
	})
	if res.StatusCode != 401 {
		t.Fatalf("expected 401, got %d", res.StatusCode)
	}

	// Correct login
	res = postJSON(t, srv, jar2, "/api/auth/login", map[string]string{
		"email": "alice@example.com", "password": "abcd1234",
	})
	if res.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", res.StatusCode)
	}

	// Logout
	res = postJSON(t, srv, jar, "/api/auth/logout", nil)
	if res.StatusCode != 204 {
		t.Fatalf("expected 204, got %d", res.StatusCode)
	}
}

// -----------------------------------------------------------------------------
// Integration: video CRUD scoped to user
// -----------------------------------------------------------------------------

func TestVideoUploadAndScope(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	jarA, _ := newCookieJar()
	res := postJSON(t, srv, jarA, "/api/auth/signup", map[string]string{
		"email": "user-a@x.com", "password": "abcd1234",
	})
	if res.StatusCode != 201 {
		t.Fatalf("signup A: %d", res.StatusCode)
	}
	// Feature endpoints require an active subscription per api-contract §2.7b.
	seedActiveSubscription(t, "user-a@x.com", "starter")

	// Upload a video for A.
	uploadRes := uploadVideo(t, srv, jarA, "alpha.mp4", []byte("fake video data"))
	if uploadRes.StatusCode != 201 {
		t.Fatalf("upload: %d", uploadRes.StatusCode)
	}

	// List for A — should have 1.
	res = getJSON(t, srv, jarA, "/api/videos")
	if res.StatusCode != 200 {
		t.Fatalf("list: %d", res.StatusCode)
	}
	var listA struct {
		Items []Video `json:"items"`
		Total int     `json:"total"`
	}
	_ = json.NewDecoder(res.Body).Decode(&listA)
	if listA.Total != 1 {
		t.Fatalf("expected 1 video for A, got %d", listA.Total)
	}

	// User B sees nothing.
	jarB, _ := newCookieJar()
	res = postJSON(t, srv, jarB, "/api/auth/signup", map[string]string{
		"email": "user-b@x.com", "password": "abcd1234",
	})
	if res.StatusCode != 201 {
		t.Fatalf("signup B: %d", res.StatusCode)
	}
	seedActiveSubscription(t, "user-b@x.com", "starter")

	res = getJSON(t, srv, jarB, "/api/videos")
	if res.StatusCode != 200 {
		t.Fatalf("list B: %d", res.StatusCode)
	}
	var listB struct {
		Total int `json:"total"`
	}
	_ = json.NewDecoder(res.Body).Decode(&listB)
	if listB.Total != 0 {
		t.Fatalf("expected 0 for B, got %d", listB.Total)
	}
}

// uploadVideo is a tiny multipart helper used by the test above.
func uploadVideo(t *testing.T, srv *httptest.Server, jar http.CookieJar, filename string, data []byte) *http.Response {
	t.Helper()
	var buf bytes.Buffer
	boundary := "----TestBoundary"
	buf.WriteString("--" + boundary + "\r\n")
	buf.WriteString(`Content-Disposition: form-data; name="file"; filename="` + filename + `"` + "\r\n")
	buf.WriteString("Content-Type: video/mp4\r\n\r\n")
	buf.Write(data)
	buf.WriteString("\r\n--" + boundary + "--\r\n")

	req, _ := http.NewRequest("POST", srv.URL+"/api/videos", &buf)
	req.Header.Set("Content-Type", "multipart/form-data; boundary="+boundary)
	client := &http.Client{Jar: jar}
	res, err := client.Do(req)
	if err != nil {
		t.Fatalf("upload: %v", err)
	}
	return res
}

// -----------------------------------------------------------------------------
// Integration: admin gating
// -----------------------------------------------------------------------------

func TestAdminGating(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	// A plain user with only a portal cookie should be REJECTED by the admin
	// surface — and notably with 401 UNAUTHORIZED rather than 403 FORBIDDEN
	// because the request has no admin-scoped session cookie at all. 403 is
	// reserved for the case where the request DOES carry an admin cookie but
	// the user has somehow lost admin role.
	jarUser, _ := newCookieJar()
	postJSON(t, srv, jarUser, "/api/auth/signup", map[string]string{
		"email": "u@x.com", "password": "abcd1234",
	})
	res := getJSON(t, srv, jarUser, "/api/admin/users")
	if res.StatusCode != 401 {
		t.Fatalf("expected 401 for portal-only user, got %d", res.StatusCode)
	}

	// Promote and obtain an admin-scoped session via the new /api/admin/auth/login.
	adminJar := loginAsAdmin(t, srv, "admin@x.com", "abcd1234")
	res = getJSON(t, srv, adminJar, "/api/admin/users")
	if res.StatusCode != 200 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("expected 200, got %d: %s", res.StatusCode, body)
	}
}

// -----------------------------------------------------------------------------
// Integration: pair token issued + listed (DB-backed)
// -----------------------------------------------------------------------------

func TestPairTokenIssue(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()
	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "p@x.com", "password": "abcd1234",
	})
	// Pair is a feature endpoint — requires active subscription.
	seedActiveSubscription(t, "p@x.com", "starter")
	res := postJSON(t, srv, jar, "/api/pair/token", nil)
	if res.StatusCode != 201 {
		t.Fatalf("pair token: %d", res.StatusCode)
	}
	var body struct {
		Token  string `json:"token"`
		QrURL  string `json:"qr_url"`
	}
	_ = json.NewDecoder(res.Body).Decode(&body)
	if len(body.Token) != 16 {
		t.Fatalf("expected 16-char token, got %q", body.Token)
	}
	if !strings.Contains(body.QrURL, body.Token) {
		t.Fatalf("qr url missing token: %q", body.QrURL)
	}
	// QR endpoint should serve PNG.
	res = getJSON(t, srv, jar, body.QrURL)
	if res.StatusCode != 200 {
		t.Fatalf("qr fetch: %d", res.StatusCode)
	}
	if ct := res.Header.Get("Content-Type"); ct != "image/png" {
		t.Fatalf("expected image/png, got %q", ct)
	}
}

// -----------------------------------------------------------------------------
// Integration: admin reset-password + forced change flow
// -----------------------------------------------------------------------------

// promoteToAdmin flips a freshly-signed-up user to role='admin'. The next
// request reads role from users on each call (see lookupSession), so the
// existing session immediately gains admin powers.
func promoteToAdmin(t *testing.T, email string) {
	t.Helper()
	if _, err := db.Exec("UPDATE users SET role='admin' WHERE email=?", email); err != nil {
		t.Fatalf("promote: %v", err)
	}
}

// signupReturnsUser is a tiny helper that decodes {"user": {...}} from auth
// responses so tests can assert on must_change_password.
type userEnvelope struct {
	User User `json:"user"`
}

func decodeUser(t *testing.T, res *http.Response) User {
	t.Helper()
	var env userEnvelope
	if err := json.NewDecoder(res.Body).Decode(&env); err != nil {
		t.Fatalf("decode user: %v", err)
	}
	return env.User
}

func TestAuthResponsesIncludeMustChangePassword(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	jar, _ := newCookieJar()
	res := postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "fresh@x.com", "password": "abcd1234",
	})
	if res.StatusCode != 201 {
		t.Fatalf("signup: %d", res.StatusCode)
	}
	u := decodeUser(t, res)
	if u.MustChangePassword {
		t.Fatalf("expected must_change_password=false on signup, got true")
	}

	// /me should also include the field.
	res = getJSON(t, srv, jar, "/api/auth/me")
	if res.StatusCode != 200 {
		t.Fatalf("me: %d", res.StatusCode)
	}
	u = decodeUser(t, res)
	if u.MustChangePassword {
		t.Fatalf("expected must_change_password=false on /me, got true")
	}

	// Login response should also include the field.
	jar2, _ := newCookieJar()
	res = postJSON(t, srv, jar2, "/api/auth/login", map[string]string{
		"email": "fresh@x.com", "password": "abcd1234",
	})
	if res.StatusCode != 200 {
		t.Fatalf("login: %d", res.StatusCode)
	}
	u = decodeUser(t, res)
	if u.MustChangePassword {
		t.Fatalf("expected must_change_password=false on login, got true")
	}
}

func TestAdminResetForcesPasswordChange(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	// Admin signs up + becomes admin, then logs into the backoffice scope so
	// the jar carries `tkr_admin_session` (required by /api/admin/* routes
	// under the two-cookie design).
	adminJar := loginAsAdmin(t, srv, "admin@x.com", "abcd1234")

	// Target user signs up.
	userJar, _ := newCookieJar()
	postJSON(t, srv, userJar, "/api/auth/signup", map[string]string{
		"email": "target@x.com", "password": "abcd1234",
	})

	// Look up target user id.
	var targetID int64
	if err := db.QueryRow("SELECT id FROM users WHERE email='target@x.com'").Scan(&targetID); err != nil {
		t.Fatalf("find target: %v", err)
	}

	// Count sessions before reset.
	var before int
	_ = db.QueryRow("SELECT COUNT(*) FROM sessions WHERE user_id=?", targetID).Scan(&before)
	if before == 0 {
		t.Fatalf("expected target to have at least one session before reset")
	}

	// Admin resets password.
	res := postJSON(t, srv, adminJar,
		"/api/admin/users/"+strconv.FormatInt(targetID, 10)+"/reset-password", nil)
	if res.StatusCode != 200 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("reset: %d %s", res.StatusCode, body)
	}
	var reset struct {
		TempPassword string `json:"temp_password"`
	}
	_ = json.NewDecoder(res.Body).Decode(&reset)
	if reset.TempPassword == "" {
		t.Fatalf("expected temp_password in response")
	}

	// Sessions wiped.
	var after int
	_ = db.QueryRow("SELECT COUNT(*) FROM sessions WHERE user_id=?", targetID).Scan(&after)
	if after != 0 {
		t.Fatalf("expected sessions wiped after reset, got %d", after)
	}

	// must_change_password flag set in DB.
	var mcp bool
	_ = db.QueryRow("SELECT must_change_password FROM users WHERE id=?", targetID).Scan(&mcp)
	if !mcp {
		t.Fatalf("expected must_change_password=true after admin reset")
	}

	// Login with temp password works AND surfaces the flag.
	loginJar, _ := newCookieJar()
	res = postJSON(t, srv, loginJar, "/api/auth/login", map[string]string{
		"email": "target@x.com", "password": reset.TempPassword,
	})
	if res.StatusCode != 200 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("login with temp: %d %s", res.StatusCode, body)
	}
	u := decodeUser(t, res)
	if !u.MustChangePassword {
		t.Fatalf("expected must_change_password=true on login after reset, got false")
	}
}

func TestChangePasswordHappyPath(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "cp@x.com", "password": "abcd1234",
	})

	// Add a second session for this user so we can confirm it is invalidated.
	var uid int64
	_ = db.QueryRow("SELECT id FROM users WHERE email='cp@x.com'").Scan(&uid)
	_, _ = db.Exec(
		"INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)",
		"other-session-token", uid, time.Now().Add(time.Hour),
	)

	res := postJSON(t, srv, jar, "/api/auth/change-password", map[string]string{
		"current_password": "abcd1234",
		"new_password":     "newpass99",
	})
	if res.StatusCode != 200 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("change-password: %d %s", res.StatusCode, body)
	}
	u := decodeUser(t, res)
	if u.MustChangePassword {
		t.Fatalf("expected must_change_password=false after change")
	}

	// Other session should be gone, current session should remain.
	var otherCount int
	_ = db.QueryRow("SELECT COUNT(*) FROM sessions WHERE token=?", "other-session-token").Scan(&otherCount)
	if otherCount != 0 {
		t.Fatalf("expected other session invalidated, got %d", otherCount)
	}

	// /me should still work (current session preserved).
	res = getJSON(t, srv, jar, "/api/auth/me")
	if res.StatusCode != 200 {
		t.Fatalf("me after change: %d", res.StatusCode)
	}

	// Old password should fail; new password should succeed.
	jar2, _ := newCookieJar()
	res = postJSON(t, srv, jar2, "/api/auth/login", map[string]string{
		"email": "cp@x.com", "password": "abcd1234",
	})
	if res.StatusCode != 401 {
		t.Fatalf("expected old pw to fail, got %d", res.StatusCode)
	}
	res = postJSON(t, srv, jar2, "/api/auth/login", map[string]string{
		"email": "cp@x.com", "password": "newpass99",
	})
	if res.StatusCode != 200 {
		t.Fatalf("expected new pw login ok, got %d", res.StatusCode)
	}
}

func TestChangePasswordErrors(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "errs@x.com", "password": "abcd1234",
	})

	// No session.
	jarNoAuth, _ := newCookieJar()
	res := postJSON(t, srv, jarNoAuth, "/api/auth/change-password", map[string]string{
		"current_password": "abcd1234",
		"new_password":     "newpass99",
	})
	if res.StatusCode != 401 {
		t.Fatalf("expected 401 no session, got %d", res.StatusCode)
	}

	// Wrong current password.
	res = postJSON(t, srv, jar, "/api/auth/change-password", map[string]string{
		"current_password": "wrongpass1",
		"new_password":     "newpass99",
	})
	if res.StatusCode != 401 {
		t.Fatalf("expected 401 wrong current, got %d", res.StatusCode)
	}

	// Weak new password.
	res = postJSON(t, srv, jar, "/api/auth/change-password", map[string]string{
		"current_password": "abcd1234",
		"new_password":     "short",
	})
	if res.StatusCode != 400 {
		t.Fatalf("expected 400 weak new pw, got %d", res.StatusCode)
	}
}

// -----------------------------------------------------------------------------
// Two-cookie design — Portal + Backoffice independent sessions
// -----------------------------------------------------------------------------
//
// The Portal binds to `tkr_session` (scope='portal') and the Backoffice binds
// to `tkr_admin_session` (scope='admin'). Browsers ignore port when scoping
// cookies, so without distinct names the two SPAs would share an identity —
// these tests pin that invariant.

// TestPortalAndAdminSessionsIndependent exercises the happy path of "logged
// in as a regular user in Portal AND logged in as an admin in Backoffice at
// the same time, in the same client". Each scope returns its own identity
// from /me without leaking into the other.
func TestPortalAndAdminSessionsIndependent(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	// 1. Sign up a portal user. The signup endpoint sets tkr_session.
	portalJar, _ := newCookieJar()
	res := postJSON(t, srv, portalJar, "/api/auth/signup", map[string]string{
		"email": "dual@x.com", "password": "abcd1234",
	})
	if res.StatusCode != 201 {
		t.Fatalf("portal signup: %d", res.StatusCode)
	}

	// 2. Promote the same email to admin in DB, then create a SEPARATE jar that
	//    only carries the admin cookie. This mimics opening Backoffice in a
	//    fresh tab/window where the user logs in distinctly.
	promoteToAdmin(t, "dual@x.com")
	adminJar, _ := newCookieJar()
	res = postJSON(t, srv, adminJar, "/api/admin/auth/login", map[string]string{
		"email": "dual@x.com", "password": "abcd1234",
	})
	if res.StatusCode != 200 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("admin login: %d %s", res.StatusCode, body)
	}

	// 3. Cookie names must differ — portalJar holds tkr_session, adminJar
	//    holds tkr_admin_session.
	pCookies := portalJar.Cookies(mustURL(srv.URL))
	if len(pCookies) != 1 || pCookies[0].Name != "tkr_session" {
		t.Fatalf("portal jar cookies = %+v; want exactly tkr_session", pCookies)
	}
	aCookies := adminJar.Cookies(mustURL(srv.URL))
	if len(aCookies) != 1 || aCookies[0].Name != "tkr_admin_session" {
		t.Fatalf("admin jar cookies = %+v; want exactly tkr_admin_session", aCookies)
	}
	if pCookies[0].Value == aCookies[0].Value {
		t.Fatalf("portal and admin sessions share the same token — they must be distinct rows")
	}

	// 4. /api/auth/me returns identity via the portal cookie.
	res = getJSON(t, srv, portalJar, "/api/auth/me")
	if res.StatusCode != 200 {
		t.Fatalf("portal me: %d", res.StatusCode)
	}
	portalMe := decodeUser(t, res)
	if portalMe.Email != "dual@x.com" {
		t.Fatalf("portal me email = %q, want dual@x.com", portalMe.Email)
	}

	// 5. /api/admin/auth/me returns identity via the admin cookie.
	res = getJSON(t, srv, adminJar, "/api/admin/auth/me")
	if res.StatusCode != 200 {
		t.Fatalf("admin me: %d", res.StatusCode)
	}
	adminMe := decodeUser(t, res)
	if adminMe.Email != "dual@x.com" || adminMe.Role != "admin" {
		t.Fatalf("admin me = %+v; want email=dual@x.com role=admin", adminMe)
	}

	// 6. Cross-checks: portal cookie cannot read admin /me, admin cookie cannot
	//    read portal /me. (These overlap the dedicated tests below but are
	//    cheap to assert here while the jars are warm.)
	res = getJSON(t, srv, portalJar, "/api/admin/auth/me")
	if res.StatusCode != 401 {
		t.Fatalf("portal cookie hitting admin /me: expected 401, got %d", res.StatusCode)
	}
	res = getJSON(t, srv, adminJar, "/api/auth/me")
	if res.StatusCode != 401 {
		t.Fatalf("admin cookie hitting portal /me: expected 401, got %d", res.StatusCode)
	}
}

// TestAdminLoginRejectsNonAdmin guarantees that the backoffice login endpoint
// will not mint an admin-scoped session for a role='user' account, even with
// correct credentials. The status is 403 FORBIDDEN — distinct from 401 — so
// the SPA can surface a "this account is not allowed here" message.
func TestAdminLoginRejectsNonAdmin(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	jar, _ := newCookieJar()
	res := postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "regular@x.com", "password": "abcd1234",
	})
	if res.StatusCode != 201 {
		t.Fatalf("signup: %d", res.StatusCode)
	}

	// Attempt backoffice login with the same credentials — should be rejected.
	adminJar, _ := newCookieJar()
	res = postJSON(t, srv, adminJar, "/api/admin/auth/login", map[string]string{
		"email": "regular@x.com", "password": "abcd1234",
	})
	if res.StatusCode != 403 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("expected 403 FORBIDDEN, got %d: %s", res.StatusCode, body)
	}

	// Critically: NO admin cookie should have been set.
	if cs := adminJar.Cookies(mustURL(srv.URL)); len(cs) > 0 {
		t.Fatalf("expected no cookies set on rejected admin login, got %+v", cs)
	}

	// And no admin-scoped session row should exist for this user.
	var n int
	_ = db.QueryRow(
		`SELECT COUNT(*) FROM sessions s JOIN users u ON u.id = s.user_id
		 WHERE u.email='regular@x.com' AND s.scope='admin'`,
	).Scan(&n)
	if n != 0 {
		t.Fatalf("expected 0 admin sessions for non-admin user, got %d", n)
	}
}

// TestPortalEndpointRejectsAdminSession confirms that an admin-scoped cookie
// cannot be presented to a Portal feature endpoint (e.g. /api/videos). The
// request looks "logged in" at the cookie level but `requireAuth` reads only
// `tkr_session`, so a jar carrying just `tkr_admin_session` is treated as
// having no session at all → 401 UNAUTHORIZED.
func TestPortalEndpointRejectsAdminSession(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	// Sign up + promote + admin login → adminJar holds ONLY tkr_admin_session.
	adminJar := loginAsAdmin(t, srv, "admin@x.com", "abcd1234")

	// Hit a portal feature endpoint with the admin cookie. Expect 401, NOT 200
	// and NOT 403 — the middleware should treat the request as unauthenticated.
	res := getJSON(t, srv, adminJar, "/api/videos")
	if res.StatusCode != http.StatusUnauthorized {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("admin cookie on /api/videos: expected 401, got %d: %s", res.StatusCode, body)
	}
}

// TestAdminEndpointRejectsPortalSession is the mirror image: a portal cookie
// presented to an admin endpoint should be rejected with 401, even if the
// underlying user has role='admin' (so the only "missing piece" is the right
// cookie scope).
func TestAdminEndpointRejectsPortalSession(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	portalJar, _ := newCookieJar()
	res := postJSON(t, srv, portalJar, "/api/auth/signup", map[string]string{
		"email": "u2@x.com", "password": "abcd1234",
	})
	if res.StatusCode != 201 {
		t.Fatalf("signup: %d", res.StatusCode)
	}
	// Promote the user — but do NOT login via /api/admin/auth/login. The portal
	// cookie alone must not unlock /api/admin/*.
	promoteToAdmin(t, "u2@x.com")

	res = getJSON(t, srv, portalJar, "/api/admin/users")
	if res.StatusCode != http.StatusUnauthorized {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("portal cookie on /api/admin/users: expected 401, got %d: %s",
			res.StatusCode, body)
	}
}

// -----------------------------------------------------------------------------
// Rate limiter
// -----------------------------------------------------------------------------

func TestRateLimiter(t *testing.T) {
	// Clear any state across tests.
	rateBucketsMu.Lock()
	rateBuckets = map[string]*rateBucket{}
	rateBucketsMu.Unlock()
	window := 60 * 1_000_000_000 // 60 seconds in nanoseconds
	for i := 0; i < 3; i++ {
		if !rateAllow("test:bucket", 3, time.Duration(window)) {
			t.Fatalf("call %d: expected allowed", i)
		}
	}
	if rateAllow("test:bucket", 3, time.Duration(window)) {
		t.Fatalf("4th call should be denied")
	}
}
