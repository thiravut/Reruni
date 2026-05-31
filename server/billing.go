package main

// Stripe-backed billing — handlers + webhook processing for the flat
// per-device subscription model (299 THB/device/month) documented in
// cost-analysis-gcp.md §6.
//
// Pricing pivot 2026-05-23: previously 3 tiers (Starter/Growth/Pro);
// now single per-device price + customer-selected quantity.
//
// Spec sources:
//   - api-contract.md §2.7b — REST endpoints
//   - tech-spec.md §5     — subscriptions + stripe_events tables
//   - tech-spec.md §6     — required env vars
//
// External deps: only github.com/stripe/stripe-go/v76 (added with go get).
// Webhook handler must use raw body (io.ReadAll) BEFORE parsing so the
// signature check stays valid.

import (
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	stripe "github.com/stripe/stripe-go/v76"
	billingportalsession "github.com/stripe/stripe-go/v76/billingportal/session"
	checkoutsession "github.com/stripe/stripe-go/v76/checkout/session"
	stripecustomer "github.com/stripe/stripe-go/v76/customer"
	stripesub "github.com/stripe/stripe-go/v76/subscription"
	stripewebhook "github.com/stripe/stripe-go/v76/webhook"
)

// -----------------------------------------------------------------------------
// Tier catalog (flat per-device pricing)
// -----------------------------------------------------------------------------

// Tier describes the public-facing subscription model — surfaced verbatim from
// /api/billing/tiers. Price in THB integer (no float for money).
//
// As of 2026-05-23, there is exactly one "tier" — flat 299 THB per device per
// month. The Devices field represents the unit (1 device = 1 unit of the
// price); customers pick quantity at checkout time. Kept as a slice in the
// API response for client-side backwards compatibility.
type Tier struct {
	Key           string `json:"key"`
	Name          string `json:"name"`
	Devices       int    `json:"devices"`
	PriceTHB      int    `json:"price_thb"`
	StripePriceID string `json:"stripe_price_id"`
}

// FlatTierKey is the only tier key under the per-device pricing model.
const FlatTierKey = "device"

// MinDeviceQuantity / MaxDeviceQuantity bound the checkout quantity input.
const (
	MinDeviceQuantity = 1
	MaxDeviceQuantity = 10000
)

// tierCatalog returns the runtime list of tiers. With flat pricing there's
// exactly one entry. Hidden when STRIPE_PRICE_PER_DEVICE is unset so an
// unconfigured Stripe Dashboard surfaces clearly instead of crashing with
// empty checkout sessions.
func tierCatalog() []Tier {
	priceID := getenv("STRIPE_PRICE_PER_DEVICE", "")
	if priceID == "" {
		logBillingMissingPrice(FlatTierKey)
		return []Tier{}
	}
	return []Tier{
		{
			Key:           FlatTierKey,
			Name:          "Per Device",
			Devices:       1,
			PriceTHB:      299,
			StripePriceID: priceID,
		},
	}
}

// tierByKey looks up a single tier (only those with a configured Price ID).
func tierByKey(key string) (Tier, bool) {
	for _, t := range tierCatalog() {
		if t.Key == key {
			return t, true
		}
	}
	return Tier{}, false
}

// Log each missing price exactly once so we don't spam logs on every list.
var loggedMissingPrice = map[string]bool{}

func logBillingMissingPrice(tier string) {
	if loggedMissingPrice[tier] {
		return
	}
	loggedMissingPrice[tier] = true
	log.Printf("billing warn: STRIPE_PRICE_PER_DEVICE not set — flat tier hidden from /api/billing/tiers")
}

// -----------------------------------------------------------------------------
// Stripe client init
// -----------------------------------------------------------------------------

func initStripe() {
	key := getenv("STRIPE_SECRET_KEY", "")
	if key == "" {
		log.Printf("billing warn: STRIPE_SECRET_KEY empty — Stripe API calls will fail")
	}
	stripe.Key = key
}

func stripeEnabled() bool {
	return getenv("STRIPE_SECRET_KEY", "") != ""
}

// -----------------------------------------------------------------------------
// Subscription DB helpers
// -----------------------------------------------------------------------------

// getSubscriptionByUser fetches the user's row, or returns sql.ErrNoRows.
func getSubscriptionByUser(userID int64) (*Subscription, error) {
	var s Subscription
	var cpStart, cpEnd sql.NullTime
	var stripeSubID, stripePriceID sql.NullString
	err := db.QueryRow(`
		SELECT id, user_id, stripe_customer_id, stripe_subscription_id, stripe_price_id,
		       tier, status, device_quota, current_period_start, current_period_end,
		       cancel_at_period_end, created_at, updated_at
		FROM subscriptions WHERE user_id=?`, userID,
	).Scan(&s.ID, &s.UserID, &s.StripeCustomerID, &stripeSubID, &stripePriceID,
		&s.Tier, &s.Status, &s.DeviceQuota, &cpStart, &cpEnd,
		&s.CancelAtPeriodEnd, &s.CreatedAt, &s.UpdatedAt)
	if err != nil {
		return nil, err
	}
	if stripeSubID.Valid {
		s.StripeSubscriptionID = stripeSubID.String
	}
	if stripePriceID.Valid {
		s.StripePriceID = stripePriceID.String
	}
	if cpStart.Valid {
		t := cpStart.Time.UTC()
		s.CurrentPeriodStart = &t
	}
	if cpEnd.Valid {
		t := cpEnd.Time.UTC()
		s.CurrentPeriodEnd = &t
	}
	return &s, nil
}

// getSubscriptionByStripeSubID is the webhook lookup path.
func getSubscriptionByStripeSubID(stripeSubID string) (*Subscription, error) {
	var userID int64
	err := db.QueryRow("SELECT user_id FROM subscriptions WHERE stripe_subscription_id=?", stripeSubID).Scan(&userID)
	if err != nil {
		return nil, err
	}
	return getSubscriptionByUser(userID)
}

// enrichUserSubscription fills the billing + quota fields on User in one
// DB pass before serializing to portal/backoffice. Status defaults to
// "none" when no row exists.
//
// Also populates OnboardingStep (from users.onboarding_step) and the
// paired-device count for the dashboard quota badge.
func enrichUserSubscription(u *User) {
	if u == nil {
		return
	}
	u.SubscriptionStatus = "none"
	u.SubscriptionTier = ""
	u.DeviceQuota = 0
	var status, tier string
	var quota int
	err := db.QueryRow(
		"SELECT status, tier, device_quota FROM subscriptions WHERE user_id=?", u.ID,
	).Scan(&status, &tier, &quota)
	if err == nil {
		u.SubscriptionStatus = status
		u.SubscriptionTier = tier
		u.DeviceQuota = quota
	}

	// Onboarding step — pulled separately so a missing subscription row
	// doesn't suppress the step (e.g. user still on `welcome`).
	var step string
	if err := db.QueryRow("SELECT onboarding_step FROM users WHERE id=?", u.ID).Scan(&step); err == nil {
		u.OnboardingStep = step
	}

	// Paired device count for the quota badge.
	var paired int
	if err := db.QueryRow(
		"SELECT COUNT(*) FROM devices WHERE owner_user_id=?", u.ID,
	).Scan(&paired); err == nil {
		u.DevicesPaired = paired
	}
}

// hasActiveSubscription is the middleware fast-path: returns true if a row
// exists with status='active'.
func hasActiveSubscription(userID int64) bool {
	var status string
	err := db.QueryRow("SELECT status FROM subscriptions WHERE user_id=?", userID).Scan(&status)
	if err != nil {
		return false
	}
	return status == "active"
}

// -----------------------------------------------------------------------------
// Handlers
// -----------------------------------------------------------------------------

// getSubscriptionHandler — GET /api/billing/subscription
func getSubscriptionHandler(w http.ResponseWriter, r *http.Request) {
	u := userFromCtx(r)
	sub, err := getSubscriptionByUser(u.ID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeJSON(w, http.StatusOK, map[string]any{"subscription": nil})
			return
		}
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"subscription": sub})
}

// syncSubscriptionHandler — POST /api/billing/sync
//
// Pulls latest subscription state directly from Stripe API for the current
// user and upserts to local DB. Use when webhooks aren't configured (local
// dev without Stripe CLI) or to manually reconcile after a payment that the
// webhook missed.
func syncSubscriptionHandler(w http.ResponseWriter, r *http.Request) {
	u := userFromCtx(r)

	// Must have a Stripe customer record (set during checkout).
	row, err := getSubscriptionByUser(u.ID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeError(w, http.StatusNotFound, "NO_SUBSCRIPTION", "ยังไม่ได้เริ่มสมัครสมาชิก")
			return
		}
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if row.StripeCustomerID == "" {
		writeError(w, http.StatusNotFound, "NO_CUSTOMER", "ยังไม่มี Stripe customer record")
		return
	}

	// Fetch all subscriptions for this customer from Stripe.
	params := &stripe.SubscriptionListParams{
		Customer: stripe.String(row.StripeCustomerID),
	}
	params.Filters.AddFilter("status", "", "all")
	iter := stripesub.List(params)

	var latest *stripe.Subscription
	for iter.Next() {
		s := iter.Subscription()
		// Pick the most recently created (Stripe returns sorted desc by default).
		if latest == nil || s.Created > latest.Created {
			latest = s
		}
	}
	if err := iter.Err(); err != nil {
		writeError(w, http.StatusBadGateway, "STRIPE_ERROR", err.Error())
		return
	}

	if latest == nil {
		log.Printf("billing: sync found no subscriptions for user %d (customer %s)", u.ID, row.StripeCustomerID)
		writeJSON(w, http.StatusOK, map[string]any{"subscription": row, "synced": false})
		return
	}

	if err := upsertSubscriptionFromStripe(u.ID, row.StripeCustomerID, latest); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	updated, err := getSubscriptionByUser(u.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	log.Printf("billing: sync updated user %d → status=%s tier=%s", u.ID, updated.Status, updated.Tier)
	writeJSON(w, http.StatusOK, map[string]any{"subscription": updated, "synced": true})
}

// listTiersHandler — GET /api/billing/tiers
func listTiersHandler(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"tiers": tierCatalog()})
}

// createCheckoutSessionHandler — POST /api/billing/checkout-session
//
// Flat pricing: client supplies Quantity (device count). Tier field is
// optional and ignored — kept for backwards compatibility with older clients.
type checkoutSessionReq struct {
	Tier     string `json:"tier"`
	Quantity int    `json:"quantity"`
}

func createCheckoutSessionHandler(w http.ResponseWriter, r *http.Request) {
	if !stripeEnabled() {
		writeError(w, http.StatusServiceUnavailable, "UNAVAILABLE", "Stripe not configured")
		return
	}
	u := userFromCtx(r)

	var body checkoutSessionReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "invalid JSON")
		return
	}
	tier, ok := tierByKey(FlatTierKey)
	if !ok {
		writeError(w, http.StatusServiceUnavailable, "UNAVAILABLE", "Stripe price not configured")
		return
	}
	quantity := body.Quantity
	if quantity <= 0 {
		quantity = MinDeviceQuantity
	}
	if quantity > MaxDeviceQuantity {
		writeError(w, http.StatusBadRequest, "INVALID_QUANTITY",
			"quantity exceeds maximum")
		return
	}

	// Block re-subscribe when user already has an active sub (use portal instead).
	existing, err := getSubscriptionByUser(u.ID)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if existing != nil && existing.Status == "active" {
		writeError(w, http.StatusConflict, "ALREADY_SUBSCRIBED", "already subscribed; use portal to change")
		return
	}

	// Create or reuse Stripe Customer.
	customerID := ""
	if existing != nil {
		customerID = existing.StripeCustomerID
	}
	if customerID == "" {
		cp := &stripe.CustomerParams{
			Email: stripe.String(u.Email),
		}
		cp.AddMetadata("user_id", intToString(u.ID))
		c, err := stripecustomer.New(cp)
		if err != nil {
			writeError(w, http.StatusBadGateway, "STRIPE_ERROR", err.Error())
			return
		}
		customerID = c.ID
	}

	// Insert / upsert pending subscription row so /auth/me reflects pending state
	// even before checkout completes.
	now := time.Now().UTC()
	if existing == nil {
		_, err := db.Exec(`
			INSERT INTO subscriptions (user_id, stripe_customer_id, tier, status, created_at, updated_at)
			VALUES (?, ?, ?, 'pending', ?, ?)`,
			u.ID, customerID, tier.Key, now, now,
		)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
	} else {
		_, err := db.Exec(`
			UPDATE subscriptions SET stripe_customer_id=?, tier=?, status=?, updated_at=?
			WHERE user_id=?`,
			customerID, tier.Key, pendingStatusOrKeep(existing.Status), now, u.ID,
		)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
	}

	successURL := getenv("PORTAL_SUCCESS_URL", "http://localhost:5173/billing/success")
	cancelURL := getenv("PORTAL_CANCEL_URL", "http://localhost:5173/billing/cancel")

	cp := &stripe.CheckoutSessionParams{
		Mode:                stripe.String(string(stripe.CheckoutSessionModeSubscription)),
		Customer:            stripe.String(customerID),
		SuccessURL:          stripe.String(successURL),
		CancelURL:           stripe.String(cancelURL),
		ClientReferenceID:   stripe.String(intToString(u.ID)),
		AllowPromotionCodes: stripe.Bool(true),
		LineItems: []*stripe.CheckoutSessionLineItemParams{
			{Price: stripe.String(tier.StripePriceID), Quantity: stripe.Int64(int64(quantity))},
		},
	}
	cp.AddMetadata("user_id", intToString(u.ID))
	cp.AddMetadata("tier", tier.Key)
	cp.AddMetadata("quantity", intToString(int64(quantity)))

	cs, err := checkoutsession.New(cp)
	if err != nil {
		writeError(w, http.StatusBadGateway, "STRIPE_ERROR", err.Error())
		return
	}

	// Onboarding hook — user has committed quantity and is heading to Stripe.
	// Webhook will later advance payment → install_apk once Stripe confirms.
	advanceOnboardingIfAt(u.ID, StepPickQuantity, StepPayment)

	writeJSON(w, http.StatusOK, map[string]any{"checkout_url": cs.URL})
}

// createPortalSessionHandler — POST /api/billing/portal-session
func createPortalSessionHandler(w http.ResponseWriter, r *http.Request) {
	if !stripeEnabled() {
		writeError(w, http.StatusServiceUnavailable, "UNAVAILABLE", "Stripe not configured")
		return
	}
	u := userFromCtx(r)
	sub, err := getSubscriptionByUser(u.ID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeError(w, http.StatusNotFound, "NO_SUBSCRIPTION", "no subscription yet")
			return
		}
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	returnURL := getenv("PORTAL_SUCCESS_URL", "http://localhost:5173/billing")
	// Strip the /billing/success suffix so the portal returns to /billing.
	returnURL = strings.TrimSuffix(returnURL, "/success")

	ps, err := billingportalsession.New(&stripe.BillingPortalSessionParams{
		Customer:  stripe.String(sub.StripeCustomerID),
		ReturnURL: stripe.String(returnURL),
	})
	if err != nil {
		writeError(w, http.StatusBadGateway, "STRIPE_ERROR", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"portal_url": ps.URL})
}

// cancelSubscriptionHandler — POST /api/billing/cancel
func cancelSubscriptionHandler(w http.ResponseWriter, r *http.Request) {
	if !stripeEnabled() {
		writeError(w, http.StatusServiceUnavailable, "UNAVAILABLE", "Stripe not configured")
		return
	}
	u := userFromCtx(r)
	sub, err := getSubscriptionByUser(u.ID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeError(w, http.StatusNotFound, "NO_SUBSCRIPTION", "no subscription")
			return
		}
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if sub.StripeSubscriptionID == "" {
		writeError(w, http.StatusConflict, "NO_ACTIVE_SUB", "no active Stripe subscription to cancel")
		return
	}

	updated, err := stripesub.Update(sub.StripeSubscriptionID, &stripe.SubscriptionParams{
		CancelAtPeriodEnd: stripe.Bool(true),
	})
	if err != nil {
		writeError(w, http.StatusBadGateway, "STRIPE_ERROR", err.Error())
		return
	}

	_, _ = db.Exec(
		"UPDATE subscriptions SET cancel_at_period_end=TRUE, updated_at=? WHERE user_id=?",
		time.Now().UTC(), u.ID,
	)
	// Reflect Stripe's authoritative period_end in case it differs.
	if updated.CurrentPeriodEnd > 0 {
		_, _ = db.Exec(
			"UPDATE subscriptions SET current_period_end=? WHERE user_id=?",
			time.Unix(updated.CurrentPeriodEnd, 0).UTC(), u.ID,
		)
	}

	refreshed, err := getSubscriptionByUser(u.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"subscription": refreshed})
}

// -----------------------------------------------------------------------------
// Webhook
// -----------------------------------------------------------------------------

// MaxWebhookBodyBytes caps incoming webhook payloads.
const maxWebhookBodyBytes = 1 << 20 // 1 MB

// stripeWebhookHandler — POST /api/billing/webhook (raw body, signature
// verified against STRIPE_WEBHOOK_SECRET). Handler must read the body BEFORE
// any JSON parsing happens so the HMAC signature matches.
func stripeWebhookHandler(w http.ResponseWriter, r *http.Request) {
	secret := getenv("STRIPE_WEBHOOK_SECRET", "")
	if secret == "" {
		log.Printf("billing warn: STRIPE_WEBHOOK_SECRET empty — rejecting webhook")
		http.Error(w, "webhook secret not configured", http.StatusServiceUnavailable)
		return
	}

	payload, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxWebhookBodyBytes))
	if err != nil {
		http.Error(w, "read body: "+err.Error(), http.StatusBadRequest)
		return
	}

	sigHeader := r.Header.Get("Stripe-Signature")
	// IgnoreAPIVersionMismatch — Stripe may be configured for a different API
	// version than stripe-go pins. We rely on shape-of-fields we read, not
	// on exact version match.
	event, err := stripewebhook.ConstructEventWithOptions(
		payload, sigHeader, secret,
		stripewebhook.ConstructEventOptions{IgnoreAPIVersionMismatch: true},
	)
	if err != nil {
		log.Printf("billing webhook: signature verify failed: %v", err)
		http.Error(w, "invalid signature", http.StatusBadRequest)
		return
	}

	// Idempotency — ON CONFLICT DO NOTHING on stripe_events(stripe_event_id UNIQUE).
	res, err := db.Exec(
		`INSERT INTO stripe_events (stripe_event_id, event_type, payload_json)
		 VALUES (?, ?, ?)
		 ON CONFLICT (stripe_event_id) DO NOTHING`,
		event.ID, string(event.Type), string(payload),
	)
	if err != nil {
		log.Printf("billing webhook: insert event log: %v", err)
		http.Error(w, "log error", http.StatusInternalServerError)
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		// Already processed — return 200 so Stripe doesn't retry.
		log.Printf("billing webhook: event %s already processed (dedup)", event.ID)
		w.WriteHeader(http.StatusOK)
		return
	}

	if err := handleStripeEvent(event); err != nil {
		log.Printf("billing webhook: handle %s: %v", event.Type, err)
		// Delete the dedup row so Stripe's retry actually re-processes.
		_, _ = db.Exec("DELETE FROM stripe_events WHERE stripe_event_id=?", event.ID)
		http.Error(w, "handler error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusOK)
}

// handleStripeEvent dispatches based on event.Type.
func handleStripeEvent(event stripe.Event) error {
	switch event.Type {
	case "checkout.session.completed":
		var cs stripe.CheckoutSession
		if err := json.Unmarshal(event.Data.Raw, &cs); err != nil {
			return err
		}
		return onCheckoutCompleted(&cs)

	case "customer.subscription.updated", "customer.subscription.created":
		var sub stripe.Subscription
		if err := json.Unmarshal(event.Data.Raw, &sub); err != nil {
			return err
		}
		return onSubscriptionUpdated(&sub)

	case "customer.subscription.deleted":
		var sub stripe.Subscription
		if err := json.Unmarshal(event.Data.Raw, &sub); err != nil {
			return err
		}
		return onSubscriptionDeleted(&sub)

	case "invoice.payment_failed":
		var inv stripe.Invoice
		if err := json.Unmarshal(event.Data.Raw, &inv); err != nil {
			return err
		}
		return onInvoicePaymentFailed(&inv)

	case "invoice.paid":
		var inv stripe.Invoice
		if err := json.Unmarshal(event.Data.Raw, &inv); err != nil {
			return err
		}
		return onInvoicePaid(&inv)
	}
	// Unhandled types are fine — we acked & logged.
	return nil
}

func onCheckoutCompleted(cs *stripe.CheckoutSession) error {
	// client_reference_id holds our user_id (set on checkout creation).
	userID := parseUserID(cs.ClientReferenceID, cs.Metadata)
	if userID == 0 {
		return errors.New("checkout.session.completed: missing user_id")
	}
	customerID := ""
	if cs.Customer != nil {
		customerID = cs.Customer.ID
	}
	stripeSubID := ""
	if cs.Subscription != nil {
		stripeSubID = cs.Subscription.ID
	}

	// Fetch full Subscription object to get period_end + price_id.
	if stripeSubID != "" && stripeEnabled() {
		full, err := stripesub.Get(stripeSubID, nil)
		if err == nil {
			return upsertSubscriptionFromStripe(userID, customerID, full)
		}
		log.Printf("billing: fetch sub %s after checkout: %v", stripeSubID, err)
	}

	// Fallback — minimal update.
	now := time.Now().UTC()
	_, err := db.Exec(`
		UPDATE subscriptions
		SET stripe_customer_id=?, stripe_subscription_id=?, status='active', updated_at=?
		WHERE user_id=?`,
		customerID, stripeSubID, now, userID,
	)
	return err
}

func onSubscriptionUpdated(sub *stripe.Subscription) error {
	if sub.ID == "" {
		return nil
	}
	row, err := getSubscriptionByStripeSubID(sub.ID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			// Not our subscription (or arrived before checkout.completed) — ignore.
			return nil
		}
		return err
	}
	return upsertSubscriptionFromStripe(row.UserID, customerIDFromSub(sub), sub)
}

func onSubscriptionDeleted(sub *stripe.Subscription) error {
	if sub.ID == "" {
		return nil
	}
	_, err := db.Exec(
		"UPDATE subscriptions SET status='canceled', updated_at=? WHERE stripe_subscription_id=?",
		time.Now().UTC(), sub.ID,
	)
	return err
}

func onInvoicePaymentFailed(inv *stripe.Invoice) error {
	subID := invoiceSubscriptionID(inv)
	if subID == "" {
		return nil
	}
	_, err := db.Exec(
		"UPDATE subscriptions SET status='past_due', updated_at=? WHERE stripe_subscription_id=?",
		time.Now().UTC(), subID,
	)
	return err
}

func onInvoicePaid(inv *stripe.Invoice) error {
	subID := invoiceSubscriptionID(inv)
	if subID == "" {
		return nil
	}
	_, err := db.Exec(
		"UPDATE subscriptions SET status='active', updated_at=? WHERE stripe_subscription_id=?",
		time.Now().UTC(), subID,
	)
	return err
}

// upsertSubscriptionFromStripe writes the canonical fields from a Stripe
// Subscription object into our DB row.
func upsertSubscriptionFromStripe(userID int64, customerID string, sub *stripe.Subscription) error {
	tier := tierKeyFromSub(sub)
	priceID := priceIDFromSub(sub)
	quantity := quantityFromSub(sub)
	status := string(sub.Status)
	if status == "" {
		status = "active"
	}
	now := time.Now().UTC()

	var cpStart, cpEnd *time.Time
	if sub.CurrentPeriodStart > 0 {
		t := time.Unix(sub.CurrentPeriodStart, 0).UTC()
		cpStart = &t
	}
	if sub.CurrentPeriodEnd > 0 {
		t := time.Unix(sub.CurrentPeriodEnd, 0).UTC()
		cpEnd = &t
	}

	// Update if exists.
	res, err := db.Exec(`
		UPDATE subscriptions
		SET stripe_customer_id=?, stripe_subscription_id=?, stripe_price_id=?,
		    tier=COALESCE(NULLIF(?, ''), tier),
		    status=?, device_quota=?,
		    current_period_start=?, current_period_end=?,
		    cancel_at_period_end=?, updated_at=?
		WHERE user_id=?`,
		customerID, sub.ID, priceID,
		tier,
		status, quantity,
		cpStart, cpEnd,
		sub.CancelAtPeriodEnd, now,
		userID,
	)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n > 0 {
		// Auto-advance onboarding: payment completed → install_apk
		if status == "active" {
			advanceOnboardingIfAt(userID, "payment", "install_apk")
		}
		return nil
	}
	// Insert if absent.
	tierToInsert := tier
	if tierToInsert == "" {
		tierToInsert = FlatTierKey
	}
	_, err = db.Exec(`
		INSERT INTO subscriptions
		  (user_id, stripe_customer_id, stripe_subscription_id, stripe_price_id,
		   tier, status, device_quota,
		   current_period_start, current_period_end,
		   cancel_at_period_end, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		userID, customerID, sub.ID, priceID,
		tierToInsert, status, quantity,
		cpStart, cpEnd,
		sub.CancelAtPeriodEnd, now, now,
	)
	if err == nil && status == "active" {
		advanceOnboardingIfAt(userID, "payment", "install_apk")
	}
	return err
}

// quantityFromSub reads subscription.items[0].quantity — under flat
// per-device pricing this is the device quota the customer paid for.
func quantityFromSub(sub *stripe.Subscription) int {
	if sub == nil || sub.Items == nil || len(sub.Items.Data) == 0 {
		return 0
	}
	item := sub.Items.Data[0]
	if item == nil {
		return 0
	}
	return int(item.Quantity)
}

// -----------------------------------------------------------------------------
// Small helpers
// -----------------------------------------------------------------------------

func intToString(n int64) string {
	// avoid importing strconv twice with import alias contention
	return formatInt64(n)
}

func formatInt64(n int64) string {
	// 64-bit signed -> decimal
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	buf := [20]byte{}
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

func parseUserID(clientRef string, meta map[string]string) int64 {
	if clientRef != "" {
		if n := parseInt64(clientRef); n > 0 {
			return n
		}
	}
	if v := meta["user_id"]; v != "" {
		return parseInt64(v)
	}
	return 0
}

func parseInt64(s string) int64 {
	var out int64
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c < '0' || c > '9' {
			return 0
		}
		out = out*10 + int64(c-'0')
	}
	return out
}


func customerIDFromSub(sub *stripe.Subscription) string {
	if sub == nil || sub.Customer == nil {
		return ""
	}
	return sub.Customer.ID
}

func invoiceSubscriptionID(inv *stripe.Invoice) string {
	if inv == nil || inv.Subscription == nil {
		return ""
	}
	return inv.Subscription.ID
}

// tierKeyFromSub maps the Stripe Price ID back to one of our tier keys.
// Falls back to empty string when Price ID doesn't match (e.g., user
// changed tier in Stripe to one we don't know about).
func tierKeyFromSub(sub *stripe.Subscription) string {
	if sub == nil || sub.Items == nil {
		return ""
	}
	priceID := priceIDFromSub(sub)
	if priceID == "" {
		return ""
	}
	for _, t := range tierCatalog() {
		if t.StripePriceID == priceID {
			return t.Key
		}
	}
	return ""
}

func priceIDFromSub(sub *stripe.Subscription) string {
	if sub == nil || sub.Items == nil || len(sub.Items.Data) == 0 {
		return ""
	}
	item := sub.Items.Data[0]
	if item == nil || item.Price == nil {
		return ""
	}
	return item.Price.ID
}

// pendingStatusOrKeep — when a user clicks "subscribe" again after a prior
// `past_due` / `canceled` state, we want the row to read `pending` so the UI
// shows "กำลังรอชำระเงิน" until Stripe webhook flips it to `active`.
func pendingStatusOrKeep(existing string) string {
	switch existing {
	case "active":
		return existing
	default:
		return "pending"
	}
}
