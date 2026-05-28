package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
	stripe "github.com/stripe/stripe-go/v76"
	stripesub "github.com/stripe/stripe-go/v76/subscription"
)

// -----------------------------------------------------------------------------
// /api/admin/users
// -----------------------------------------------------------------------------

func adminListUsersHandler(w http.ResponseWriter, r *http.Request) {
	limit, offset := parsePagination(r)
	q := strings.TrimSpace(r.URL.Query().Get("q"))

	where := ""
	args := []any{}
	if q != "" {
		where = " WHERE email ILIKE ?"
		args = append(args, "%"+strings.ToLower(q)+"%")
	}

	var total int
	_ = db.QueryRow("SELECT COUNT(*) FROM users"+where, args...).Scan(&total)

	listArgs := append([]any{}, args...)
	listArgs = append(listArgs, limit, offset)
	rows, err := db.Query(`
		SELECT id, email, role, created_at,
		       (SELECT COUNT(*) FROM devices WHERE owner_user_id=u.id) AS device_count,
		       (SELECT COUNT(*) FROM videos  WHERE owner_user_id=u.id) AS video_count,
		       (SELECT MAX(last_seen) FROM devices WHERE owner_user_id=u.id) AS last_active
		FROM users u`+where+`
		ORDER BY id ASC LIMIT ? OFFSET ?`, listArgs...)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	defer rows.Close()

	items := []map[string]any{}
	for rows.Next() {
		var id int64
		var email, role string
		var created time.Time
		var devCount, vidCount int
		var lastActive sql.NullTime
		if err := rows.Scan(&id, &email, &role, &created, &devCount, &vidCount, &lastActive); err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		item := map[string]any{
			"id":           id,
			"email":        email,
			"role":         role,
			"created_at":   created.UTC(),
			"device_count": devCount,
			"video_count":  vidCount,
		}
		if lastActive.Valid {
			item["last_active_at"] = lastActive.Time.UTC()
		}
		items = append(items, item)
	}
	writeJSON(w, http.StatusOK, paginatedResponse(items, total, limit, offset))
}

type roleReq struct {
	Role string `json:"role"`
}

func adminPatchUserRoleHandler(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "bad id")
		return
	}
	var body roleReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
		return
	}
	if body.Role != "user" && body.Role != "admin" {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "role must be user|admin")
		return
	}
	res, err := db.Exec("UPDATE users SET role=? WHERE id=?", body.Role, id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "user not found")
		return
	}
	var u User
	if err := db.QueryRow(
		"SELECT id, email, role, must_change_password, created_at FROM users WHERE id=?", id,
	).Scan(&u.ID, &u.Email, &u.Role, &u.MustChangePassword, &u.CreatedAt); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, &u)
}

func adminResetPasswordHandler(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "bad id")
		return
	}
	temp := generateTempPassword()
	hash, err := bcrypt.GenerateFromPassword([]byte(temp), bcryptCost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	res, err := db.Exec(
		"UPDATE users SET password_hash=?, must_change_password=TRUE WHERE id=?",
		string(hash), id,
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "user not found")
		return
	}
	// Invalidate existing sessions — user must re-login with temp password.
	_, _ = db.Exec("DELETE FROM sessions WHERE user_id=?", id)
	writeJSON(w, http.StatusOK, map[string]string{"temp_password": temp})
}

func adminDeleteUserHandler(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "bad id")
		return
	}
	// Cascade in app code (FK ON DELETE CASCADE may not be enforced in SQLite without pragma).
	_, _ = db.Exec("DELETE FROM sessions WHERE user_id=?", id)
	_, _ = db.Exec("DELETE FROM banners WHERE owner_user_id=?", id)
	_, _ = db.Exec("DELETE FROM commands WHERE owner_user_id=?", id)
	_, _ = db.Exec("DELETE FROM live_sessions WHERE owner_user_id=?", id)
	_, _ = db.Exec("DELETE FROM pair_tokens WHERE owner_user_id=?", id)
	_, _ = db.Exec("DELETE FROM videos WHERE owner_user_id=?", id)
	_, _ = db.Exec("DELETE FROM devices WHERE owner_user_id=?", id)
	res, err := db.Exec("DELETE FROM users WHERE id=?", id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "user not found")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// -----------------------------------------------------------------------------
// /api/admin/devices
// -----------------------------------------------------------------------------

func adminListDevicesHandler(w http.ResponseWriter, r *http.Request) {
	limit, offset := parsePagination(r)
	statusFilter := r.URL.Query().Get("status")

	where := ""
	args := []any{}
	if statusFilter != "" {
		where = " WHERE d.status = ?"
		args = append(args, statusFilter)
	}

	var total int
	_ = db.QueryRow("SELECT COUNT(*) FROM devices d"+where, args...).Scan(&total)

	listArgs := append([]any{}, args...)
	listArgs = append(listArgs, limit, offset)
	rows, err := db.Query(`
		SELECT d.id, d.name, d.owner_user_id, COALESCE(d.status,'idle'), d.last_seen,
		       COALESCE(u.email,'')
		FROM devices d
		LEFT JOIN users u ON u.id = d.owner_user_id`+where+`
		ORDER BY d.paired_at DESC LIMIT ? OFFSET ?`, listArgs...)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	defer rows.Close()
	items := []map[string]any{}
	for rows.Next() {
		var id, owner string
		var ownerID int64
		var name sql.NullString
		var status, email string
		var lastSeen sql.NullTime
		if err := rows.Scan(&id, &name, &ownerID, &status, &lastSeen, &email); err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		_ = owner
		item := map[string]any{
			"id":            id,
			"name":          name.String,
			"owner_user_id": ownerID,
			"owner_email":   email,
			"status":        status,
		}
		if lastSeen.Valid {
			item["last_seen_at"] = lastSeen.Time.UTC()
		}
		items = append(items, item)
	}
	writeJSON(w, http.StatusOK, paginatedResponse(items, total, limit, offset))
}

func adminDisconnectDeviceHandler(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	disconnectDevice(id, "admin_disconnect")
	w.WriteHeader(http.StatusNoContent)
}

// -----------------------------------------------------------------------------
// /api/admin/videos
// -----------------------------------------------------------------------------

func adminListVideosHandler(w http.ResponseWriter, r *http.Request) {
	limit, offset := parsePagination(r)

	var total int
	var totalDisk sql.NullInt64
	_ = db.QueryRow("SELECT COUNT(*), SUM(size_bytes) FROM videos").Scan(&total, &totalDisk)

	rows, err := db.Query(`
		SELECT v.id, v.name, v.filename, v.size_bytes, v.created_at,
		       COALESCE(u.email,'')
		FROM videos v
		LEFT JOIN users u ON u.id = v.owner_user_id
		ORDER BY v.id DESC LIMIT ? OFFSET ?`, limit, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	defer rows.Close()
	items := []map[string]any{}
	for rows.Next() {
		var id, size int64
		var name, filename, email string
		var created time.Time
		if err := rows.Scan(&id, &name, &filename, &size, &created, &email); err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		items = append(items, map[string]any{
			"id":          id,
			"name":        name,
			"filename":    filename,
			"size_bytes":  size,
			"uploaded_at": created.UTC(),
			"owner_email": email,
		})
	}
	resp := paginatedResponse(items, total, limit, offset)
	if totalDisk.Valid {
		resp["total_disk_bytes"] = totalDisk.Int64
	} else {
		resp["total_disk_bytes"] = 0
	}
	writeJSON(w, http.StatusOK, resp)
}

// -----------------------------------------------------------------------------
// /api/admin/metrics
// -----------------------------------------------------------------------------

func adminMetricsHandler(w http.ResponseWriter, r *http.Request) {
	var usersTotal, devicesTotal int
	var disk sql.NullInt64
	_ = db.QueryRow("SELECT COUNT(*) FROM users").Scan(&usersTotal)
	_ = db.QueryRow("SELECT COUNT(*) FROM devices").Scan(&devicesTotal)
	_ = db.QueryRow("SELECT SUM(size_bytes) FROM videos").Scan(&disk)

	// Online = current ws conn count.
	connectionsMu.RLock()
	devicesOnline := len(connections)
	connectionsMu.RUnlock()

	var devicesLive int
	_ = db.QueryRow("SELECT COUNT(*) FROM devices WHERE status='live'").Scan(&devicesLive)

	var lives24h int
	since := time.Now().Add(-24 * time.Hour)
	_ = db.QueryRow("SELECT COUNT(*) FROM live_sessions WHERE started_at >= ?", since).Scan(&lives24h)

	var usersActive7d int
	since7 := time.Now().Add(-7 * 24 * time.Hour)
	_ = db.QueryRow(`
		SELECT COUNT(DISTINCT owner_user_id) FROM live_sessions WHERE started_at >= ?
	`, since7).Scan(&usersActive7d)

	// broadcast_hours_24h — sum of (ended_at - started_at) for lives in last 24h.
	var broadcastSecs sql.NullFloat64
	_ = db.QueryRow(`
		SELECT COALESCE(SUM(
			EXTRACT(EPOCH FROM (COALESCE(ended_at, NOW()) - started_at))
		), 0)
		FROM live_sessions WHERE started_at >= ?`, since).Scan(&broadcastSecs)

	resp := map[string]any{
		"users_total":         usersTotal,
		"users_active_7d":     usersActive7d,
		"devices_total":       devicesTotal,
		"devices_online":      devicesOnline,
		"devices_live":        devicesLive,
		"lives_24h":           lives24h,
		"broadcast_hours_24h": broadcastSecs.Float64 / 3600.0,
		"disk_used_bytes":     disk.Int64,
		"uptime_pct_30d":      100.0, // placeholder; uptime tracking is out of scope for v1
	}
	writeJSON(w, http.StatusOK, resp)
}

// -----------------------------------------------------------------------------
// /api/admin/lives
// -----------------------------------------------------------------------------

func adminListLivesHandler(w http.ResponseWriter, r *http.Request) {
	limit, offset := parsePagination(r)
	status := r.URL.Query().Get("status") // "active" | "ended" | ""

	where := ""
	switch status {
	case "active":
		where = " WHERE l.ended_at IS NULL"
	case "ended":
		where = " WHERE l.ended_at IS NOT NULL"
	}

	rows, err := db.Query(`
		SELECT l.id, l.device_id, l.owner_user_id, l.video_id, l.title, l.started_at, l.ended_at, l.end_reason,
		       COALESCE(u.email,'')
		FROM live_sessions l
		LEFT JOIN users u ON u.id = l.owner_user_id`+where+`
		ORDER BY l.started_at DESC LIMIT ? OFFSET ?`, limit, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	defer rows.Close()
	items := []map[string]any{}
	for rows.Next() {
		var id, ownerID int64
		var vid sql.NullInt64
		var did, email string
		var title, reason sql.NullString
		var started time.Time
		var ended sql.NullTime
		if err := rows.Scan(&id, &did, &ownerID, &vid, &title, &started, &ended, &reason, &email); err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		item := map[string]any{
			"id":            id,
			"device_id":     did,
			"owner_user_id": ownerID,
			"owner_email":   email,
			"started_at":    started.UTC(),
		}
		if vid.Valid {
			item["video_id"] = vid.Int64
		}
		if title.Valid {
			item["title"] = title.String
		}
		if ended.Valid {
			item["ended_at"] = ended.Time.UTC()
		}
		if reason.Valid {
			item["end_reason"] = reason.String
		}
		items = append(items, item)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"items":  items,
		"limit":  limit,
		"offset": offset,
	})
}

// -----------------------------------------------------------------------------
// /api/admin/subscriptions
// -----------------------------------------------------------------------------

// adminListSubscriptionsHandler — GET /api/admin/subscriptions?status=active&limit=50&offset=0
// Returns paginated list across ALL users with owner email + tier + status.
func adminListSubscriptionsHandler(w http.ResponseWriter, r *http.Request) {
	limit, offset := parsePagination(r)
	statusFilter := strings.TrimSpace(r.URL.Query().Get("status"))

	where := ""
	args := []any{}
	if statusFilter != "" {
		where = " WHERE s.status = ?"
		args = append(args, statusFilter)
	}

	var total int
	countArgs := append([]any{}, args...)
	_ = db.QueryRow("SELECT COUNT(*) FROM subscriptions s"+where, countArgs...).Scan(&total)

	listArgs := append([]any{}, args...)
	listArgs = append(listArgs, limit, offset)
	rows, err := db.Query(`
		SELECT s.id, s.user_id, COALESCE(u.email,''), s.tier, s.status,
		       s.stripe_subscription_id, s.current_period_start, s.current_period_end,
		       s.cancel_at_period_end, s.created_at, s.updated_at
		FROM subscriptions s
		LEFT JOIN users u ON u.id = s.user_id`+where+`
		ORDER BY s.created_at DESC LIMIT ? OFFSET ?`, listArgs...)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	defer rows.Close()

	items := []map[string]any{}
	for rows.Next() {
		var id, uid int64
		var email, tier, status string
		var subID sql.NullString
		var cpStart, cpEnd sql.NullTime
		var cancel bool
		var created, updated time.Time
		if err := rows.Scan(&id, &uid, &email, &tier, &status, &subID,
			&cpStart, &cpEnd, &cancel, &created, &updated); err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		item := map[string]any{
			"id":                   id,
			"user_id":              uid,
			"owner_email":          email,
			"tier":                 tier,
			"status":               status,
			"cancel_at_period_end": cancel,
			"created_at":           created.UTC(),
			"updated_at":           updated.UTC(),
		}
		if subID.Valid {
			item["stripe_subscription_id"] = subID.String
		}
		if cpStart.Valid {
			item["current_period_start"] = cpStart.Time.UTC()
		}
		if cpEnd.Valid {
			item["current_period_end"] = cpEnd.Time.UTC()
		}
		items = append(items, item)
	}
	writeJSON(w, http.StatusOK, paginatedResponse(items, total, limit, offset))
}

func adminForceStopLiveHandler(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "bad id")
		return
	}
	var did string
	var ownerID int64
	var vidNull sql.NullInt64
	if err := db.QueryRow(
		"SELECT device_id, owner_user_id, video_id FROM live_sessions WHERE id=? AND ended_at IS NULL",
		id,
	).Scan(&did, &ownerID, &vidNull); err != nil {
		writeError(w, http.StatusNotFound, "LIVE_NOT_FOUND", "active live not found")
		return
	}
	_, _ = issueCommand(ownerID, did, "stop_live", map[string]any{})
	_, _ = db.Exec(
		"UPDATE live_sessions SET ended_at=?, end_reason='admin_force' WHERE id=?",
		time.Now(), id,
	)
	_, _ = db.Exec(
		"UPDATE devices SET status='idle', current_video_id=NULL, current_pinned_sku=NULL WHERE id=?", did,
	)
	broadcastToPortal(ownerID, "live_ended", map[string]any{
		"live_session_id": id,
		"device_id":       did,
		"end_reason":      "admin_force",
	})
	if vidNull.Valid {
		cleanupEphemeralVideoIfUnreferenced(vidNull.Int64)
	}
	w.WriteHeader(http.StatusNoContent)
}

// adminRecheckSubscriptionHandler — POST /api/admin/subscriptions/{user_id}/recheck
//
// Admin-callable: pulls latest subscription state from Stripe API for the
// specified user and upserts to local DB. Used to reconcile pending
// subscriptions when webhook isn't configured or missed an event.
func adminRecheckSubscriptionHandler(w http.ResponseWriter, r *http.Request) {
	userID, err := strconv.ParseInt(r.PathValue("user_id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "bad user_id")
		return
	}
	sub, err := getSubscriptionByUser(userID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeError(w, http.StatusNotFound, "NO_SUBSCRIPTION", "user has no subscription record")
			return
		}
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if sub.StripeCustomerID == "" {
		writeError(w, http.StatusNotFound, "NO_CUSTOMER", "no Stripe customer record")
		return
	}

	params := &stripe.SubscriptionListParams{
		Customer: stripe.String(sub.StripeCustomerID),
	}
	params.Filters.AddFilter("status", "", "all")
	iter := stripesub.List(params)

	var latest *stripe.Subscription
	for iter.Next() {
		s := iter.Subscription()
		if latest == nil || s.Created > latest.Created {
			latest = s
		}
	}
	if err := iter.Err(); err != nil {
		writeError(w, http.StatusBadGateway, "STRIPE_ERROR", err.Error())
		return
	}

	if latest == nil {
		writeJSON(w, http.StatusOK, map[string]any{"subscription": sub, "synced": false})
		return
	}

	if err := upsertSubscriptionFromStripe(userID, sub.StripeCustomerID, latest); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	updated, _ := getSubscriptionByUser(userID)
	writeJSON(w, http.StatusOK, map[string]any{"subscription": updated, "synced": true})
}
