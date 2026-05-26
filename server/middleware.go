package main

import (
	"context"
	"encoding/json"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// -----------------------------------------------------------------------------
// Context keys for user / session
// -----------------------------------------------------------------------------

type ctxKey int

const (
	ctxKeyUser ctxKey = iota
	ctxKeySession
)

func userFromCtx(r *http.Request) *User {
	v, _ := r.Context().Value(ctxKeyUser).(*User)
	return v
}

func sessionFromCtx(r *http.Request) *Session {
	v, _ := r.Context().Value(ctxKeySession).(*Session)
	return v
}

// -----------------------------------------------------------------------------
// Error / JSON helpers (canonical writeError per tech-spec §8)
// -----------------------------------------------------------------------------

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, code, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"error": map[string]string{
			"code":    code,
			"message": msg,
		},
	})
}

// writeErr is the legacy error writer kept around so the existing POC
// handlers still compile while we migrate. New code should use writeError.
func writeErr(w http.ResponseWriter, status int, msg string) {
	writeError(w, status, "ERROR", msg)
}

// -----------------------------------------------------------------------------
// requireAuth / requireAdmin
// -----------------------------------------------------------------------------

// requireAuth wraps a handler and ensures the request carries a valid PORTAL
// session cookie (tkr_session, scope='portal'). The resolved *User and
// *Session are attached to the request context. Admin-cookie sessions are
// intentionally rejected here so that being logged in as admin in another tab
// does not leak into Portal endpoints.
func requireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie(portalCookieName)
		if err != nil || c.Value == "" {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "no session")
			return
		}
		sess, user, err := lookupSession(c.Value, sessionScopePortal)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "invalid session")
			return
		}
		ctx := context.WithValue(r.Context(), ctxKeyUser, user)
		ctx = context.WithValue(ctx, ctxKeySession, sess)
		next.ServeHTTP(w, r.WithContext(ctx))
	}
}

// requireAdmin wraps a handler and ensures the request carries a valid ADMIN
// session cookie (tkr_admin_session, scope='admin') AND that the underlying
// user still has role='admin'. The role check defends against an admin being
// demoted between login and request — the cookie is rejected immediately.
func requireAdmin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie(adminCookieName)
		if err != nil || c.Value == "" {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "no admin session")
			return
		}
		sess, user, err := lookupSession(c.Value, sessionScopeAdmin)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "invalid admin session")
			return
		}
		if user == nil || user.Role != "admin" {
			// Cookie present and scoped correctly, but the user is no longer an
			// admin — kill the stale session so a refresh re-authenticates.
			deleteSession(sess.Token)
			clearSessionCookieNamed(w, adminCookieName)
			writeError(w, http.StatusForbidden, "FORBIDDEN", "admin required")
			return
		}
		ctx := context.WithValue(r.Context(), ctxKeyUser, user)
		ctx = context.WithValue(ctx, ctxKeySession, sess)
		next.ServeHTTP(w, r.WithContext(ctx))
	}
}

// requireActiveSubscription wraps requireAuth and additionally rejects users
// whose subscription is not 'active'. Admins bypass — they can always reach
// feature endpoints for support/diagnosis.
//
// Apply AFTER auth, BEFORE the feature handler. Returns 402 PAYMENT_REQUIRED
// with code SUBSCRIPTION_REQUIRED so the SPA can prompt /subscribe.
func requireActiveSubscription(next http.HandlerFunc) http.HandlerFunc {
	return requireAuth(func(w http.ResponseWriter, r *http.Request) {
		u := userFromCtx(r)
		if u == nil {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "no session")
			return
		}
		if u.Role == "admin" {
			next.ServeHTTP(w, r)
			return
		}
		if !hasActiveSubscription(u.ID) {
			writeError(w, http.StatusPaymentRequired, "SUBSCRIPTION_REQUIRED",
				"subscription required to access this feature")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// -----------------------------------------------------------------------------
// CORS
// -----------------------------------------------------------------------------

// corsMiddleware honours the CORS_ORIGINS env var (comma separated). When a
// browser sends the Origin header and it appears in the allow list we mirror
// it back and permit credentials. OPTIONS preflights short-circuit with 204.
func corsMiddleware(next http.Handler) http.Handler {
	allowed := parseAllowedOrigins(os.Getenv("CORS_ORIGINS"))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" && isOriginAllowed(origin, allowed) {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Access-Control-Allow-Credentials", "true")
			w.Header().Set("Vary", "Origin")
			w.Header().Set("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Accept")
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func parseAllowedOrigins(s string) []string {
	if s == "" {
		return nil
	}
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func isOriginAllowed(origin string, allowed []string) bool {
	for _, a := range allowed {
		if a == "*" || a == origin {
			return true
		}
	}
	return false
}

// -----------------------------------------------------------------------------
// Request logging
// -----------------------------------------------------------------------------

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: 200}
		next.ServeHTTP(rec, r)
		// Skip noisy static file fetches
		if !strings.HasPrefix(r.URL.Path, "/uploads/") {
			logf("%s %s %d %s", r.Method, r.URL.Path, rec.status, time.Since(start))
		}
	})
}

// -----------------------------------------------------------------------------
// Rate limiter (in-memory, sliding window)
// -----------------------------------------------------------------------------

type rateBucket struct {
	mu       sync.Mutex
	requests []time.Time
}

var (
	rateBucketsMu sync.Mutex
	rateBuckets   = map[string]*rateBucket{}
)

// rateAllow returns true if the keyed bucket has fewer than `limit` hits in
// the trailing `window`. Each call records the hit only when allowed.
func rateAllow(key string, limit int, window time.Duration) bool {
	rateBucketsMu.Lock()
	b, ok := rateBuckets[key]
	if !ok {
		b = &rateBucket{}
		rateBuckets[key] = b
	}
	rateBucketsMu.Unlock()

	b.mu.Lock()
	defer b.mu.Unlock()

	cutoff := time.Now().Add(-window)
	kept := b.requests[:0]
	for _, t := range b.requests {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	b.requests = kept

	if len(b.requests) >= limit {
		return false
	}
	b.requests = append(b.requests, time.Now())
	return true
}

func clientIP(r *http.Request) string {
	if xf := r.Header.Get("X-Forwarded-For"); xf != "" {
		if i := strings.Index(xf, ","); i >= 0 {
			return strings.TrimSpace(xf[:i])
		}
		return strings.TrimSpace(xf)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// -----------------------------------------------------------------------------
// Env helpers
// -----------------------------------------------------------------------------

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getenvInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return fallback
}

// isSecureEnv returns true when running behind TLS / production proxy. For v1
// we toggle by the SECURE_COOKIES env var (set by deployment scripts).
func isSecureEnv() bool {
	v := strings.ToLower(os.Getenv("SECURE_COOKIES"))
	return v == "1" || v == "true" || v == "yes"
}

func logf(format string, args ...any) {
	log.Printf(format, args...)
}
