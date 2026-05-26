package main

import (
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"testing"
)

func newCookieJar() (http.CookieJar, error) {
	return cookiejar.New(nil)
}

func mustURL(s string) *url.URL {
	u, err := url.Parse(s)
	if err != nil {
		panic(err)
	}
	return u
}

// loginAsAdmin signs up the email, promotes to admin, then logs in via the
// backoffice-scoped endpoint so the returned jar carries `tkr_admin_session`.
// Used by tests that previously relied on requireAdmin accepting the portal
// cookie — the new design requires a distinct admin cookie.
func loginAsAdmin(t *testing.T, srv *httptest.Server, email, password string) http.CookieJar {
	t.Helper()
	jar, _ := newCookieJar()
	// Signup via portal flow (sets a portal cookie which we ignore).
	res := postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": email, "password": password,
	})
	res.Body.Close()
	// Promote to admin via direct SQL (mirrors promoteToAdmin in auth_test.go).
	if _, err := db.Exec("UPDATE users SET role='admin' WHERE email=?", email); err != nil {
		t.Fatalf("promote %s: %v", email, err)
	}
	// Backoffice login — sets `tkr_admin_session` in a fresh jar so the
	// returned cookie store contains only the admin scope. Tests that need
	// simultaneous portal + admin cookies should sign in twice with two jars.
	adminJar, _ := newCookieJar()
	res = postJSON(t, srv, adminJar, "/api/admin/auth/login", map[string]string{
		"email": email, "password": password,
	})
	if res.StatusCode != 200 {
		t.Fatalf("admin login %s: %d", email, res.StatusCode)
	}
	res.Body.Close()
	return adminJar
}
