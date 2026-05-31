package main

// Onboarding wizard — state machine + HTTP handlers for the first-time
// signup flow under flat per-device pricing.
//
// See docs/planning-artifacts/prd-v1-launch.md §3.12 for step semantics
// and skippability rules.

import (
	"database/sql"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"
)

// Step constants — keep in sync with the prd-v1-launch.md §3.12 table and
// the users.onboarding_step column default in migrations/0006_onboarding.sql.
const (
	StepWelcome      = "welcome"
	StepPickQuantity = "pick_quantity"
	StepPayment      = "payment"
	StepInstallAPK   = "install_apk"
	StepPairDevice   = "pair_device"
	StepFirstVideo   = "first_video"
	StepComplete     = "complete"
)

// validSteps lists every step the API will accept. Order is meaningful — used
// by stepIndex for forward-only auto-advance.
var validSteps = []string{
	StepWelcome,
	StepPickQuantity,
	StepPayment,
	StepInstallAPK,
	StepPairDevice,
	StepFirstVideo,
	StepComplete,
}

// skippableSteps — set of steps the user can `POST /api/onboarding/skip`
// past while still receiving the dashboard. Aligns with PRD §3.12.
var skippableSteps = map[string]bool{
	StepInstallAPK: true,
	StepPairDevice: true,
	StepFirstVideo: true,
}

func stepIndex(step string) int {
	for i, s := range validSteps {
		if s == step {
			return i
		}
	}
	return -1
}

// advanceOnboardingIfAt moves the user from `from` → `to` exactly when
// their current step is `from`. No-op otherwise so concurrent triggers
// (webhook + pair event + manual click) stay idempotent.
func advanceOnboardingIfAt(userID int64, from, to string) {
	if stepIndex(to) < 0 {
		log.Printf("onboarding: refusing advance to unknown step %q", to)
		return
	}
	_, err := db.Exec(
		"UPDATE users SET onboarding_step=? WHERE id=? AND onboarding_step=?",
		to, userID, from,
	)
	if err != nil {
		log.Printf("onboarding: advance user %d %s→%s: %v", userID, from, to, err)
	}
}

// forceAdvanceOnboarding sets the step regardless of current value — used
// when the user explicitly skips a skippable step or clicks "Next" from a
// non-auto-advance step.
func forceAdvanceOnboarding(userID int64, to string) error {
	if stepIndex(to) < 0 {
		return errors.New("unknown onboarding step")
	}
	_, err := db.Exec(
		"UPDATE users SET onboarding_step=? WHERE id=?", to, userID,
	)
	return err
}

// getOnboardingStep reads the current step. Returns "welcome" if the row
// is missing the column (defensive — migration sets default).
func getOnboardingStep(userID int64) string {
	var step string
	err := db.QueryRow("SELECT onboarding_step FROM users WHERE id=?", userID).Scan(&step)
	if err != nil || step == "" {
		return StepWelcome
	}
	return step
}

// -----------------------------------------------------------------------------
// HTTP handlers
// -----------------------------------------------------------------------------

// getOnboardingHandler — GET /api/onboarding
// Returns the user's current step + denormalized state useful to the wizard
// UI (subscription quota, paired count) in one round-trip.
func getOnboardingHandler(w http.ResponseWriter, r *http.Request) {
	u := userFromCtx(r)
	step := getOnboardingStep(u.ID)

	quota := 0
	subStatus := "none"
	if sub, err := getSubscriptionByUser(u.ID); err == nil {
		quota = sub.DeviceQuota
		subStatus = sub.Status
	} else if !errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	var paired int
	_ = db.QueryRow("SELECT COUNT(*) FROM devices WHERE owner_user_id=?", u.ID).Scan(&paired)

	var firstVideoExists int
	_ = db.QueryRow("SELECT COUNT(*) FROM videos WHERE owner_user_id=?", u.ID).Scan(&firstVideoExists)

	writeJSON(w, http.StatusOK, map[string]any{
		"step":                step,
		"skippable":           skippableSteps[step],
		"subscription_status": subStatus,
		"device_quota":        quota,
		"devices_paired":      paired,
		"has_first_video":     firstVideoExists > 0,
	})
}

// advanceOnboardingHandler — POST /api/onboarding/advance {"to": "<step>"}
// Lets the wizard UI confirm a non-auto-advanced step (welcome → pick_quantity,
// payment_success click, etc.). Rejects backward moves.
func advanceOnboardingHandler(w http.ResponseWriter, r *http.Request) {
	u := userFromCtx(r)
	var body struct {
		To string `json:"to"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "invalid JSON")
		return
	}
	to := strings.TrimSpace(body.To)
	if stepIndex(to) < 0 {
		writeError(w, http.StatusBadRequest, "INVALID_STEP", "unknown step")
		return
	}
	current := getOnboardingStep(u.ID)
	if stepIndex(to) < stepIndex(current) {
		writeError(w, http.StatusConflict, "BACKWARD_TRANSITION", "cannot move backward")
		return
	}
	if err := forceAdvanceOnboarding(u.ID, to); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"step": to})
}

// skipOnboardingHandler — POST /api/onboarding/skip
// Skips the current step (only allowed for steps in skippableSteps).
// Lands on the *next* step in validSteps. Effectively a forward jump.
func skipOnboardingHandler(w http.ResponseWriter, r *http.Request) {
	u := userFromCtx(r)
	current := getOnboardingStep(u.ID)
	if !skippableSteps[current] {
		writeError(w, http.StatusConflict, "NOT_SKIPPABLE",
			"current step cannot be skipped")
		return
	}
	idx := stepIndex(current)
	if idx < 0 || idx+1 >= len(validSteps) {
		// Already at the last step — treat as complete.
		_ = forceAdvanceOnboarding(u.ID, StepComplete)
		writeJSON(w, http.StatusOK, map[string]any{"step": StepComplete})
		return
	}
	next := validSteps[idx+1]
	if err := forceAdvanceOnboarding(u.ID, next); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"step": next})
}

// -----------------------------------------------------------------------------
// Auto-advance hooks fired from other subsystems
// -----------------------------------------------------------------------------

// onDeviceFirstOnline is called by ws.go when a device first reports online.
// Moves install_apk → pair_device → first_video as appropriate.
func onDeviceFirstOnline(userID int64) {
	advanceOnboardingIfAt(userID, StepInstallAPK, StepPairDevice)
	advanceOnboardingIfAt(userID, StepPairDevice, StepFirstVideo)
}

// onFirstVideoUploaded is called by the video upload handler the first time
// a user uploads to /api/videos.
func onFirstVideoUploaded(userID int64) {
	advanceOnboardingIfAt(userID, StepFirstVideo, StepComplete)
}

// -----------------------------------------------------------------------------
// Quota helpers
// -----------------------------------------------------------------------------

// checkDeviceQuota returns (currentPaired, quota, ok). ok=false when paired
// would exceed quota — caller should return 402/403 to the client.
func checkDeviceQuota(userID int64) (paired, quota int, ok bool) {
	_ = db.QueryRow("SELECT COUNT(*) FROM devices WHERE owner_user_id=?", userID).Scan(&paired)
	_ = db.QueryRow("SELECT device_quota FROM subscriptions WHERE user_id=?", userID).Scan(&quota)
	return paired, quota, paired < quota
}

// timeNowUTC is a tiny indirection that makes the cron tests deterministic
// later. Today it's just a thin wrapper.
func timeNowUTC() time.Time { return time.Now().UTC() }
