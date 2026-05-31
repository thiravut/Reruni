package main

// Tests for the Stripe-backed billing subsystem.
//
// These tests do NOT hit the real Stripe API — they exercise:
//   1. /api/billing/tiers shape + env-driven Price ID population
//   2. requireActiveSubscription gating (402 when no active sub)
//   3. Webhook signature verification (valid + invalid)
//   4. Webhook idempotency (same event.id → second call is no-op)
//
// Webhook payloads are constructed in-test and signed with the same
// stripe/webhook helper the production handler uses.

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"testing"
	"time"
)

// -----------------------------------------------------------------------------
// Helpers — seed a subscription row directly (bypass Stripe)
// -----------------------------------------------------------------------------

func seedActiveSubscription(t *testing.T, userEmail, tier string) {
	t.Helper()
	var uid int64
	if err := db.QueryRow("SELECT id FROM users WHERE email=?", userEmail).Scan(&uid); err != nil {
		t.Fatalf("seed: lookup user %q: %v", userEmail, err)
	}
	now := time.Now().UTC()
	end := now.Add(30 * 24 * time.Hour)
	_, err := db.Exec(`
		INSERT INTO subscriptions
		  (user_id, stripe_customer_id, stripe_subscription_id, stripe_price_id,
		   tier, status, current_period_start, current_period_end,
		   cancel_at_period_end, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, 'active', ?, ?, 0, ?, ?)`,
		uid, "cus_test_"+strconv.FormatInt(uid, 10),
		"sub_test_"+strconv.FormatInt(uid, 10),
		"price_test_"+tier,
		tier, now, end, now, now,
	)
	if err != nil {
		t.Fatalf("seed subscription: %v", err)
	}
}

// -----------------------------------------------------------------------------
// /api/billing/tiers
// -----------------------------------------------------------------------------

func TestBillingTiersHiddenWhenPriceUnset(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	t.Setenv("STRIPE_PRICE_PER_DEVICE", "")

	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "tiers-empty@x.com", "password": "abcd1234",
	})

	res := getJSON(t, srv, jar, "/api/billing/tiers")
	if res.StatusCode != 200 {
		t.Fatalf("tiers: %d", res.StatusCode)
	}
	var body struct {
		Tiers []Tier `json:"tiers"`
	}
	_ = json.NewDecoder(res.Body).Decode(&body)
	if len(body.Tiers) != 0 {
		t.Fatalf("expected 0 tiers when price unset, got %d", len(body.Tiers))
	}
}

func TestBillingTiersFlatPricing(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	t.Setenv("STRIPE_PRICE_PER_DEVICE", "price_per_device_test")

	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "tiers-flat@x.com", "password": "abcd1234",
	})

	res := getJSON(t, srv, jar, "/api/billing/tiers")
	var body struct {
		Tiers []Tier `json:"tiers"`
	}
	_ = json.NewDecoder(res.Body).Decode(&body)
	if len(body.Tiers) != 1 {
		t.Fatalf("expected 1 flat tier, got %d", len(body.Tiers))
	}
	got := body.Tiers[0]
	if got.Key != FlatTierKey || got.PriceTHB != 299 || got.Devices != 1 {
		t.Fatalf("unexpected flat tier row: %+v", got)
	}
}

// -----------------------------------------------------------------------------
// /api/auth/me includes subscription_status
// -----------------------------------------------------------------------------

func TestMeIncludesSubscriptionStatus(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "me@x.com", "password": "abcd1234",
	})

	// Before any subscription row → status='none'.
	res := getJSON(t, srv, jar, "/api/auth/me")
	if res.StatusCode != 200 {
		t.Fatalf("/me: %d", res.StatusCode)
	}
	var me struct {
		User User `json:"user"`
	}
	_ = json.NewDecoder(res.Body).Decode(&me)
	if me.User.SubscriptionStatus != "none" {
		t.Fatalf("expected status=none, got %q", me.User.SubscriptionStatus)
	}

	// After seeding → status='active', tier='starter'.
	seedActiveSubscription(t, "me@x.com", "starter")
	res = getJSON(t, srv, jar, "/api/auth/me")
	_ = json.NewDecoder(res.Body).Decode(&me)
	if me.User.SubscriptionStatus != "active" || me.User.SubscriptionTier != "starter" {
		t.Fatalf("expected active/starter, got %q/%q",
			me.User.SubscriptionStatus, me.User.SubscriptionTier)
	}
}

// -----------------------------------------------------------------------------
// requireActiveSubscription gating
// -----------------------------------------------------------------------------

func TestFeatureGating402WithoutSubscription(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "nogate@x.com", "password": "abcd1234",
	})

	// /api/videos should 402 without a subscription.
	res := getJSON(t, srv, jar, "/api/videos")
	if res.StatusCode != http.StatusPaymentRequired {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("expected 402, got %d: %s", res.StatusCode, body)
	}
	var errBody struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	_ = json.NewDecoder(res.Body).Decode(&errBody)
	if errBody.Error.Code != "SUBSCRIPTION_REQUIRED" {
		t.Fatalf("expected code=SUBSCRIPTION_REQUIRED, got %q", errBody.Error.Code)
	}
}

func TestFeatureGatingAllowsWithActiveSubscription(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "ok@x.com", "password": "abcd1234",
	})
	seedActiveSubscription(t, "ok@x.com", "starter")

	res := getJSON(t, srv, jar, "/api/videos")
	if res.StatusCode != 200 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("expected 200, got %d: %s", res.StatusCode, body)
	}
}

func TestFeatureGatingAdminBypass(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "adm@x.com", "password": "abcd1234",
	})
	promoteToAdmin(t, "adm@x.com")

	res := getJSON(t, srv, jar, "/api/videos")
	if res.StatusCode != 200 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("admin should bypass — got %d: %s", res.StatusCode, body)
	}
}

func TestBillingEndpointsBypassSubscriptionGate(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "pending@x.com", "password": "abcd1234",
	})

	// /api/billing/subscription must NOT 402 even without an active sub.
	res := getJSON(t, srv, jar, "/api/billing/subscription")
	if res.StatusCode != 200 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("subscription endpoint should be reachable, got %d: %s", res.StatusCode, body)
	}
	var body struct {
		Subscription *Subscription `json:"subscription"`
	}
	_ = json.NewDecoder(res.Body).Decode(&body)
	if body.Subscription != nil {
		t.Fatalf("expected null subscription, got %+v", body.Subscription)
	}
}

// -----------------------------------------------------------------------------
// Webhook — signature verification + idempotency
// -----------------------------------------------------------------------------

const testWebhookSecret = "whsec_testsecretvalue1234567890"

// signStripePayload constructs a Stripe-Signature header matching the
// algorithm in stripe-go/webhook (HMAC-SHA256 over "<timestamp>.<payload>").
func signStripePayload(t *testing.T, payload []byte, secret string, now time.Time) string {
	t.Helper()
	ts := now.Unix()
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(strconv.FormatInt(ts, 10) + "." + string(payload)))
	return "t=" + strconv.FormatInt(ts, 10) + ",v1=" + hex.EncodeToString(mac.Sum(nil))
}

func TestWebhookRejectsMissingSecret(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	t.Setenv("STRIPE_WEBHOOK_SECRET", "")

	res, err := http.Post(srv.URL+"/api/billing/webhook", "application/json", bytes.NewReader([]byte("{}")))
	if err != nil {
		t.Fatalf("post: %v", err)
	}
	if res.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("expected 503 when secret unset, got %d", res.StatusCode)
	}
}

func TestWebhookRejectsBadSignature(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	t.Setenv("STRIPE_WEBHOOK_SECRET", testWebhookSecret)

	payload := []byte(`{"id":"evt_test_1","type":"invoice.paid","data":{"object":{}}}`)
	req, _ := http.NewRequest("POST", srv.URL+"/api/billing/webhook", bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Stripe-Signature", "t=1,v1=deadbeef")

	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("post: %v", err)
	}
	if res.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400 for bad sig, got %d", res.StatusCode)
	}
}

func TestWebhookValidSignatureProcessesAndDedupes(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	t.Setenv("STRIPE_WEBHOOK_SECRET", testWebhookSecret)

	// Seed a user + a pending subscription with a known stripe_subscription_id
	// so invoice.paid can flip its status to 'active'.
	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "wh@x.com", "password": "abcd1234",
	})
	var uid int64
	_ = db.QueryRow("SELECT id FROM users WHERE email='wh@x.com'").Scan(&uid)
	_, err := db.Exec(`
		INSERT INTO subscriptions
		  (user_id, stripe_customer_id, stripe_subscription_id, tier, status, created_at, updated_at)
		VALUES (?, 'cus_x', 'sub_x', 'starter', 'pending', ?, ?)`,
		uid, time.Now(), time.Now(),
	)
	if err != nil {
		t.Fatalf("seed: %v", err)
	}

	// Build a valid invoice.paid event payload (Stripe's full event envelope).
	payload := []byte(`{
		"id": "evt_test_paid_1",
		"object": "event",
		"type": "invoice.paid",
		"data": {
			"object": {
				"id": "in_test",
				"subscription": "sub_x"
			}
		}
	}`)
	sig := signStripePayload(t, payload, testWebhookSecret, time.Now())

	// First POST → process + flip to active.
	req, _ := http.NewRequest("POST", srv.URL+"/api/billing/webhook", bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Stripe-Signature", sig)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("post: %v", err)
	}
	if res.StatusCode != 200 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("first webhook: %d %s", res.StatusCode, body)
	}

	// Verify status flipped.
	var status string
	_ = db.QueryRow("SELECT status FROM subscriptions WHERE user_id=?", uid).Scan(&status)
	if status != "active" {
		t.Fatalf("expected status=active after invoice.paid, got %q", status)
	}

	// stripe_events row exists.
	var cnt int
	_ = db.QueryRow("SELECT COUNT(*) FROM stripe_events WHERE stripe_event_id='evt_test_paid_1'").Scan(&cnt)
	if cnt != 1 {
		t.Fatalf("expected 1 stripe_events row, got %d", cnt)
	}

	// Second POST with same event.id → idempotent no-op, still 200.
	req2, _ := http.NewRequest("POST", srv.URL+"/api/billing/webhook", bytes.NewReader(payload))
	req2.Header.Set("Content-Type", "application/json")
	// New signature for the new (later) timestamp — payload still has same event.id.
	req2.Header.Set("Stripe-Signature", signStripePayload(t, payload, testWebhookSecret, time.Now()))
	res2, _ := http.DefaultClient.Do(req2)
	if res2.StatusCode != 200 {
		body, _ := io.ReadAll(res2.Body)
		t.Fatalf("dedup webhook: %d %s", res2.StatusCode, body)
	}
	_ = db.QueryRow("SELECT COUNT(*) FROM stripe_events WHERE stripe_event_id='evt_test_paid_1'").Scan(&cnt)
	if cnt != 1 {
		t.Fatalf("expected still 1 stripe_events row after dedup, got %d", cnt)
	}
}

func TestWebhookSubscriptionDeletedSetsCanceled(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	t.Setenv("STRIPE_WEBHOOK_SECRET", testWebhookSecret)

	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "del@x.com", "password": "abcd1234",
	})
	seedActiveSubscription(t, "del@x.com", "growth")

	payload := []byte(`{
		"id": "evt_test_del_1",
		"object": "event",
		"type": "customer.subscription.deleted",
		"data": {
			"object": {
				"id": "sub_test_2",
				"status": "canceled"
			}
		}
	}`)
	// Update seeded row's stripe_subscription_id to match.
	_, _ = db.Exec("UPDATE subscriptions SET stripe_subscription_id='sub_test_2' WHERE 1=1")

	sig := signStripePayload(t, payload, testWebhookSecret, time.Now())
	req, _ := http.NewRequest("POST", srv.URL+"/api/billing/webhook", bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Stripe-Signature", sig)
	res, _ := http.DefaultClient.Do(req)
	if res.StatusCode != 200 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("webhook: %d %s", res.StatusCode, body)
	}
	var status string
	_ = db.QueryRow("SELECT status FROM subscriptions WHERE stripe_subscription_id='sub_test_2'").Scan(&status)
	if status != "canceled" {
		t.Fatalf("expected canceled, got %q", status)
	}
}

func TestWebhookInvoicePaymentFailedSetsPastDue(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	t.Setenv("STRIPE_WEBHOOK_SECRET", testWebhookSecret)

	jar, _ := newCookieJar()
	postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
		"email": "pd@x.com", "password": "abcd1234",
	})
	seedActiveSubscription(t, "pd@x.com", "starter")

	payload := []byte(`{
		"id": "evt_test_pd_1",
		"object": "event",
		"type": "invoice.payment_failed",
		"data": {
			"object": {
				"id": "in_test",
				"subscription": "sub_test_pd"
			}
		}
	}`)
	_, _ = db.Exec("UPDATE subscriptions SET stripe_subscription_id='sub_test_pd' WHERE 1=1")

	sig := signStripePayload(t, payload, testWebhookSecret, time.Now())
	req, _ := http.NewRequest("POST", srv.URL+"/api/billing/webhook", bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Stripe-Signature", sig)
	res, _ := http.DefaultClient.Do(req)
	if res.StatusCode != 200 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("webhook: %d %s", res.StatusCode, body)
	}
	var status string
	_ = db.QueryRow("SELECT status FROM subscriptions WHERE stripe_subscription_id='sub_test_pd'").Scan(&status)
	if status != "past_due" {
		t.Fatalf("expected past_due, got %q", status)
	}
}

// -----------------------------------------------------------------------------
// /api/admin/subscriptions
// -----------------------------------------------------------------------------

func TestAdminListSubscriptions(t *testing.T) {
	srv, done := setupTestServer(t)
	defer done()

	// Admin user — uses the backoffice login so the jar carries an admin-scoped
	// cookie (required by /api/admin/* under the two-cookie design).
	adminJar := loginAsAdmin(t, srv, "admin2@x.com", "abcd1234")

	// Two regular users with subs.
	for _, e := range []string{"a@x.com", "b@x.com"} {
		jar, _ := newCookieJar()
		postJSON(t, srv, jar, "/api/auth/signup", map[string]string{
			"email": e, "password": "abcd1234",
		})
		seedActiveSubscription(t, e, "starter")
	}

	res := getJSON(t, srv, adminJar, "/api/admin/subscriptions?status=active&limit=10&offset=0")
	if res.StatusCode != 200 {
		body, _ := io.ReadAll(res.Body)
		t.Fatalf("list subs: %d %s", res.StatusCode, body)
	}
	var body struct {
		Items []map[string]any `json:"items"`
		Total int              `json:"total"`
	}
	_ = json.NewDecoder(res.Body).Decode(&body)
	if body.Total != 2 || len(body.Items) != 2 {
		t.Fatalf("expected 2 active subs, got total=%d len=%d", body.Total, len(body.Items))
	}
	// Owner email should be set.
	for _, item := range body.Items {
		if item["owner_email"] == "" {
			t.Fatalf("missing owner_email: %+v", item)
		}
		if item["tier"] != "starter" {
			t.Fatalf("expected tier starter, got %v", item["tier"])
		}
	}
}
