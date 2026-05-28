package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/skip2/go-qrcode"
)

// -----------------------------------------------------------------------------
// Constants
// -----------------------------------------------------------------------------

const (
	maxVideoBytes      int64 = 500 * 1024 * 1024
	defaultPageLimit         = 50
	maxPageLimit             = 200
	// 5 min was too tight for real-world flow (open QR on laptop → walk to phone
	// → grant camera/scan → first WS attempt). Bumped to 30 min so a single
	// setup session always fits inside one token.
	pairTokenTTL             = 30 * time.Minute
	commandHistoryDays       = 90
)

// -----------------------------------------------------------------------------
// Legacy helpers used in other files
// -----------------------------------------------------------------------------

// randomHex returns 2n hex chars.
func randomHex(n int) string {
	buf := make([]byte, n)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}

func parsePagination(r *http.Request) (limit, offset int) {
	limit = defaultPageLimit
	offset = 0
	if s := r.URL.Query().Get("limit"); s != "" {
		if n, err := strconv.Atoi(s); err == nil && n > 0 {
			limit = n
		}
	}
	if limit > maxPageLimit {
		limit = maxPageLimit
	}
	if s := r.URL.Query().Get("offset"); s != "" {
		if n, err := strconv.Atoi(s); err == nil && n >= 0 {
			offset = n
		}
	}
	return
}

func paginatedResponse(items any, total, limit, offset int) map[string]any {
	return map[string]any{
		"items":  items,
		"total":  total,
		"limit":  limit,
		"offset": offset,
	}
}

// -----------------------------------------------------------------------------
// Videos
// -----------------------------------------------------------------------------

func listVideosHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	limit, offset := parsePagination(r)

	var total int
	_ = db.QueryRow("SELECT COUNT(*) FROM videos WHERE owner_user_id = ?", user.ID).Scan(&total)

	rows, err := db.Query(`
		SELECT id, name, filename, size_bytes, duration_sec, created_at
		FROM videos WHERE owner_user_id = ?
		ORDER BY id DESC LIMIT ? OFFSET ?`, user.ID, limit, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	defer rows.Close()

	items := []Video{}
	for rows.Next() {
		var v Video
		var dur sql.NullInt64
		if err := rows.Scan(&v.ID, &v.Name, &v.Filename, &v.SizeBytes, &dur, &v.CreatedAt); err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		if dur.Valid {
			d := dur.Int64
			v.DurationSec = &d
		}
		v.URL = "/uploads/" + v.Filename
		items = append(items, v)
	}
	writeJSON(w, http.StatusOK, paginatedResponse(items, total, limit, offset))
}

func uploadVideoHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)

	// Cap raw body size before parsing.
	r.Body = http.MaxBytesReader(w, r.Body, maxVideoBytes+10*1024*1024)
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		writeError(w, http.StatusRequestEntityTooLarge, "FILE_TOO_LARGE", "upload exceeds limit")
		return
	}
	// Accept either "file" (per api-contract) or legacy "video" form field.
	file, header, err := r.FormFile("file")
	if err != nil {
		file, header, err = r.FormFile("video")
		if err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_INPUT", "missing file field")
			return
		}
	}
	defer file.Close()

	ext := strings.ToLower(filepath.Ext(header.Filename))
	if ext != ".mp4" && ext != ".mov" {
		writeError(w, http.StatusBadRequest, "INVALID_FORMAT", "only .mp4 or .mov allowed")
		return
	}
	if header.Size > maxVideoBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "FILE_TOO_LARGE", "max 500 MB")
		return
	}

	displayName := r.FormValue("name")
	if displayName == "" {
		displayName = header.Filename
	}
	if len(displayName) > 100 {
		displayName = displayName[:100]
	}

	token := randomHex(8)
	storedName := time.Now().Format("20060102-150405") + "-" + token + ext
	storedPath := filepath.Join(uploadsDir, storedName)

	out, err := os.Create(storedPath)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	n, err := io.Copy(out, file)
	out.Close()
	if err != nil {
		_ = os.Remove(storedPath)
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	var id int64
	err = db.QueryRow(
		"INSERT INTO videos (name, filename, size_bytes, owner_user_id) VALUES (?, ?, ?, ?) RETURNING id",
		displayName, storedName, n, user.ID,
	).Scan(&id)
	if err != nil {
		_ = os.Remove(storedPath)
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	writeJSON(w, http.StatusCreated, Video{
		ID:        id,
		Name:      displayName,
		Filename:  storedName,
		SizeBytes: n,
		CreatedAt: time.Now().UTC(),
		URL:       "/uploads/" + storedName,
	})
}

type patchVideoReq struct {
	Name string `json:"name"`
}

func patchVideoHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "bad id")
		return
	}

	var body patchVideoReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
		return
	}
	name := strings.TrimSpace(body.Name)
	if len(name) == 0 || len(name) > 100 {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "name must be 1-100 chars")
		return
	}

	res, err := db.Exec(
		"UPDATE videos SET name=? WHERE id=? AND owner_user_id=?",
		name, id, user.ID,
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeError(w, http.StatusNotFound, "VIDEO_NOT_FOUND", "video not found")
		return
	}

	var v Video
	var dur sql.NullInt64
	err = db.QueryRow(`
		SELECT id, name, filename, size_bytes, duration_sec, created_at
		FROM videos WHERE id=? AND owner_user_id=?`, id, user.ID,
	).Scan(&v.ID, &v.Name, &v.Filename, &v.SizeBytes, &dur, &v.CreatedAt)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if dur.Valid {
		d := dur.Int64
		v.DurationSec = &d
	}
	v.URL = "/uploads/" + v.Filename
	writeJSON(w, http.StatusOK, v)
}

func deleteVideoHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "bad id")
		return
	}

	var filename string
	err = db.QueryRow(
		"SELECT filename FROM videos WHERE id=? AND owner_user_id=?",
		id, user.ID,
	).Scan(&filename)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeError(w, http.StatusNotFound, "VIDEO_NOT_FOUND", "video not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	// Prevent deletion when broadcasting on any device.
	var inUse int
	_ = db.QueryRow(`
		SELECT COUNT(*) FROM live_sessions
		WHERE video_id = ? AND ended_at IS NULL`, id).Scan(&inUse)
	if inUse > 0 {
		writeError(w, http.StatusConflict, "VIDEO_IN_USE", "video is broadcasting")
		return
	}

	if _, err := db.Exec("DELETE FROM videos WHERE id=?", id); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	_ = os.Remove(filepath.Join(uploadsDir, filename))
	w.WriteHeader(http.StatusNoContent)
}

// -----------------------------------------------------------------------------
// Devices
// -----------------------------------------------------------------------------

func listDevicesHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	limit, offset := parsePagination(r)

	var total int
	_ = db.QueryRow("SELECT COUNT(*) FROM devices WHERE owner_user_id = ?", user.ID).Scan(&total)

	rows, err := db.Query(`
		SELECT id, name, paired_at, last_seen, COALESCE(status,'idle'),
		       current_video_id, current_pinned_sku, caps_json, caps_reported_at
		FROM devices WHERE owner_user_id = ?
		ORDER BY paired_at DESC LIMIT ? OFFSET ?`, user.ID, limit, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	defer rows.Close()

	items := []Device{}
	for rows.Next() {
		var d Device
		var name sql.NullString
		var ls sql.NullTime
		var vid sql.NullInt64
		var sku sql.NullString
		var caps sql.NullString
		var capsAt sql.NullTime
		if err := rows.Scan(&d.ID, &name, &d.PairedAt, &ls, &d.Status, &vid, &sku, &caps, &capsAt); err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		if name.Valid {
			d.Name = name.String
		}
		if ls.Valid {
			t := ls.Time
			d.LastSeen = &t
		}
		if vid.Valid {
			v := vid.Int64
			d.CurrentVideoID = &v
		}
		if sku.Valid {
			s := sku.String
			d.CurrentPinnedSKU = &s
		}
		if caps.Valid && caps.String != "" {
			d.Caps = json.RawMessage(caps.String)
		}
		if capsAt.Valid {
			t := capsAt.Time
			d.CapsReportedAt = &t
		}
		d.Online = isDeviceOnline(d.ID)
		// Coalesce online/offline based on websocket presence
		if !d.Online && d.Status == "idle" {
			d.Status = "offline"
		}
		items = append(items, d)
	}
	writeJSON(w, http.StatusOK, paginatedResponse(items, total, limit, offset))
}

type patchDeviceReq struct {
	Name string `json:"name"`
}

func patchDeviceHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	id := r.PathValue("id")

	var body patchDeviceReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
		return
	}
	name := strings.TrimSpace(body.Name)
	if len(name) == 0 || len(name) > 50 {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "name must be 1-50 chars")
		return
	}

	res, err := db.Exec(
		"UPDATE devices SET name=? WHERE id=? AND owner_user_id=?",
		name, id, user.ID,
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeError(w, http.StatusNotFound, "DEVICE_NOT_FOUND", "device not found")
		return
	}

	d, err := getDeviceForUser(id, user.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, d)
}

func deleteDeviceHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	id := r.PathValue("id")

	res, err := db.Exec(
		"DELETE FROM devices WHERE id=? AND owner_user_id=?",
		id, user.ID,
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeError(w, http.StatusNotFound, "DEVICE_NOT_FOUND", "device not found")
		return
	}
	// Force-disconnect websocket if connected.
	disconnectDevice(id, "unpaired")
	w.WriteHeader(http.StatusNoContent)
}

func getDeviceForUser(id string, userID int64) (*Device, error) {
	var d Device
	var name sql.NullString
	var ls sql.NullTime
	var vid sql.NullInt64
	var sku sql.NullString
	err := db.QueryRow(`
		SELECT id, name, paired_at, last_seen, COALESCE(status,'idle'),
		       current_video_id, current_pinned_sku
		FROM devices WHERE id=? AND owner_user_id=?`,
		id, userID,
	).Scan(&d.ID, &name, &d.PairedAt, &ls, &d.Status, &vid, &sku)
	if err != nil {
		return nil, err
	}
	if name.Valid {
		d.Name = name.String
	}
	if ls.Valid {
		t := ls.Time
		d.LastSeen = &t
	}
	if vid.Valid {
		v := vid.Int64
		d.CurrentVideoID = &v
	}
	if sku.Valid {
		s := sku.String
		d.CurrentPinnedSKU = &s
	}
	d.Online = isDeviceOnline(d.ID)
	return &d, nil
}

// -----------------------------------------------------------------------------
// Pairing
// -----------------------------------------------------------------------------

func createPairTokenHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	token := generatePairToken()
	expires := time.Now().Add(pairTokenTTL)

	_, err := db.Exec(
		"INSERT INTO pair_tokens (token, owner_user_id, expires_at) VALUES (?, ?, ?)",
		token, user.ID, expires,
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	writeJSON(w, http.StatusCreated, map[string]any{
		"token":      token,
		"expires_at": expires.UTC(),
		"qr_url":     "/api/pair/qr?token=" + token,
	})
}

// pairQRHandler is public — devices fetch the QR image without auth so it
// can render in the mobile app preview / printed pairing card.
func pairQRHandler(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("token")
	if token == "" {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "missing token")
		return
	}
	// We sit behind nginx (CWP) which terminates TLS, so r.TLS is always nil
	// here. Prefer the explicit PUBLIC_BASE_URL env (set in /etc/rerun/env),
	// then X-Forwarded-Proto, and only fall back to r.TLS as a last resort.
	baseURL := strings.TrimRight(os.Getenv("PUBLIC_BASE_URL"), "/")
	if baseURL == "" {
		scheme := "http"
		if proto := r.Header.Get("X-Forwarded-Proto"); proto != "" {
			scheme = proto
		} else if r.TLS != nil {
			scheme = "https"
		}
		baseURL = scheme + "://" + r.Host
	}
	payload, _ := json.Marshal(map[string]string{
		"url":   baseURL,
		"token": token,
	})
	png, err := qrcode.Encode(string(payload), qrcode.Medium, 320)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	w.Header().Set("Content-Type", "image/png")
	_, _ = w.Write(png)
}


// -----------------------------------------------------------------------------
// Live Sessions
// -----------------------------------------------------------------------------

type startLiveReq struct {
	DeviceIDs []string `json:"device_ids"`
	// VideoIDs is the canonical field — devices are assigned a video by
	// round-robin index (dev[i] → video_ids[i % len(video_ids)]). VideoID is
	// the legacy single-video field kept for backward compat; if VideoIDs is
	// empty we fall back to [VideoID].
	VideoIDs []int64 `json:"video_ids"`
	VideoID  int64   `json:"video_id"`
	// LoopCount controls how many full passes through the playlist the
	// device should play. nil → loop forever; non-nil → play that many
	// times. Omitted entirely defaults to 1 (play once) — matches the
	// pre-feature behaviour.
	LoopCount *int `json:"loop_count,omitempty"`
	// LoopForever is a convenience flag from the portal UI; when true,
	// LoopCount is forced to nil regardless of what was sent.
	LoopForever bool     `json:"loop_forever,omitempty"`
	Title       string   `json:"title"`
	Caption     string   `json:"caption"`
	Hashtags    []string `json:"hashtags"`
	PinnedSKU   string   `json:"pinned_sku"`
}

func startLiveHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)

	var body startLiveReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
		return
	}
	// device_ids may arrive as ints or strings; normalize.
	if len(body.DeviceIDs) == 0 {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "device_ids required")
		return
	}
	if len(body.Title) > 100 {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "title too long")
		return
	}
	if len(body.Caption) > 500 {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "caption too long")
		return
	}

	// Resolve video list: prefer VideoIDs[], fall back to legacy single VideoID.
	videoIDs := body.VideoIDs
	if len(videoIDs) == 0 && body.VideoID != 0 {
		videoIDs = []int64{body.VideoID}
	}

	if len(videoIDs) == 0 {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "video_ids required")
		return
	}

	// Resolve loop count. LoopForever wins; otherwise default to 1 when the
	// field is missing, validate range when supplied.
	var loopCount *int
	switch {
	case body.LoopForever:
		loopCount = nil
	case body.LoopCount == nil:
		one := 1
		loopCount = &one
	case *body.LoopCount <= 0:
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "loop_count must be >= 1")
		return
	case *body.LoopCount > 1000:
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "loop_count too large (max 1000)")
		return
	default:
		loopCount = body.LoopCount
	}

	// Validate every video — bail on first miss with the specific id so the
	// portal can surface it accurately.
	type videoMeta struct {
		filename string
		name     string
	}
	videos := make(map[int64]videoMeta, len(videoIDs))
	for _, vid := range videoIDs {
		var fn, nm string
		if err := db.QueryRow(
			"SELECT filename, name FROM videos WHERE id=? AND owner_user_id=?",
			vid, user.ID,
		).Scan(&fn, &nm); err != nil {
			writeError(w, http.StatusNotFound, "VIDEO_NOT_FOUND",
				fmt.Sprintf("video %d not found", vid))
			return
		}
		videos[vid] = videoMeta{filename: fn, name: nm}
	}

	// Verify ownership of every requested device, build the online subset.
	onlineDevices := []string{}
	for _, did := range body.DeviceIDs {
		var owner int64
		if err := db.QueryRow("SELECT owner_user_id FROM devices WHERE id=?", did).Scan(&owner); err != nil {
			writeError(w, http.StatusNotFound, "DEVICE_NOT_FOUND", "device "+did+" not found")
			return
		}
		if owner != user.ID {
			writeError(w, http.StatusForbidden, "DEVICE_NOT_OWNED", "device "+did+" not owned")
			return
		}
		if isDeviceOnline(did) {
			onlineDevices = append(onlineDevices, did)
		}
	}
	if len(onlineDevices) == 0 {
		writeError(w, http.StatusBadRequest, "NO_DEVICES_ONLINE", "no devices online")
		return
	}

	type cmdResp struct {
		CommandID string `json:"command_id"`
		DeviceID  string `json:"device_id"`
	}
	out := []cmdResp{}

	for i, did := range onlineDevices {
		// Round-robin: device i gets video_ids[i % len(video_ids)]. With one
		// video this collapses to the legacy single-video behaviour; with
		// many it diversifies broadcasts across the fleet so TikTok's
		// dedupe heuristics see distinct streams per phone.
		vid := videoIDs[i%len(videoIDs)]
		meta := videos[vid]

		var liveID int64
		err := db.QueryRow(`
			INSERT INTO live_sessions (device_id, owner_user_id, video_id, title, caption, hashtags, pinned_sku, loop_count)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			RETURNING id`,
			did, user.ID, vid, body.Title, body.Caption,
			strings.Join(body.Hashtags, ","), body.PinnedSKU, loopCount,
		).Scan(&liveID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}

		payload := map[string]any{
			"video_url":    "/uploads/" + meta.filename,
			"video_id":     vid,
			"video_name":   meta.name,
			"title":        body.Title,
			"caption":      body.Caption,
			"hashtags":     body.Hashtags,
			"pinned_sku":   body.PinnedSKU,
			"live_session": liveID,
			"banners":      fetchBannersForLiveStart(user.ID, vid),
			// loop_count: nil → loop forever, int → finite passes. Mobile
			// today ignores this; reserved for the playlist-aware update.
			"loop_count": loopCount,
		}
		cid, err := issueCommand(user.ID, did, "start_live", payload)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		_, _ = db.Exec("UPDATE devices SET status='live', current_video_id=? WHERE id=?", vid, did)
		broadcastToPortal(user.ID, "live_started", map[string]any{
			"live_session_id": liveID,
			"device_id":       did,
			"video_id":        body.VideoID,
		})
		out = append(out, cmdResp{CommandID: cid, DeviceID: did})
	}

	writeJSON(w, http.StatusAccepted, map[string]any{"commands": out})
}

func stopLiveHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	did := r.PathValue("device_id")
	if err := assertDeviceOwned(did, user.ID, w); err != nil {
		return
	}
	cid, err := issueCommand(user.ID, did, "stop_live", map[string]any{})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	// Close any active live_session for this device.
	now := time.Now()
	_, _ = db.Exec(
		"UPDATE live_sessions SET ended_at=?, end_reason='user_stop' WHERE device_id=? AND ended_at IS NULL",
		now, did,
	)
	_, _ = db.Exec("UPDATE devices SET status='idle', current_video_id=NULL, current_pinned_sku=NULL WHERE id=?", did)
	broadcastToPortal(user.ID, "live_ended", map[string]any{
		"device_id":  did,
		"end_reason": "user_stop",
	})
	writeJSON(w, http.StatusAccepted, map[string]string{"command_id": cid})
}

type switchVideoReq struct {
	VideoID int64 `json:"video_id"`
}

func switchVideoHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	did := r.PathValue("device_id")
	if err := assertDeviceOwned(did, user.ID, w); err != nil {
		return
	}
	var body switchVideoReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
		return
	}
	var filename, videoName string
	if err := db.QueryRow(
		"SELECT filename, name FROM videos WHERE id=? AND owner_user_id=?",
		body.VideoID, user.ID,
	).Scan(&filename, &videoName); err != nil {
		writeError(w, http.StatusNotFound, "VIDEO_NOT_FOUND", "video not found")
		return
	}
	cid, err := issueCommand(user.ID, did, "switch_video", map[string]any{
		"video_url": "/uploads/" + filename,
		"video_id":  body.VideoID,
		"name":      videoName,
	})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	_, _ = db.Exec("UPDATE devices SET current_video_id=? WHERE id=?", body.VideoID, did)
	writeJSON(w, http.StatusAccepted, map[string]string{"command_id": cid})
}

func restartLiveHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	did := r.PathValue("device_id")
	if err := assertDeviceOwned(did, user.ID, w); err != nil {
		return
	}
	cid, err := issueCommand(user.ID, did, "restart_live", map[string]any{})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]string{"command_id": cid})
}

type volumeReq struct {
	Volume int `json:"volume"`
}

func volumeHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	did := r.PathValue("device_id")
	if err := assertDeviceOwned(did, user.ID, w); err != nil {
		return
	}
	var body volumeReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
		return
	}
	if body.Volume < 0 || body.Volume > 100 {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "volume must be 0-100")
		return
	}
	cid, err := issueCommand(user.ID, did, "set_volume", map[string]any{"volume": body.Volume})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]string{"command_id": cid})
}

func activeLivesHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	limit, _ := parsePagination(r)
	rows, err := db.Query(`
		SELECT id, device_id, video_id, title, pinned_sku, started_at
		FROM live_sessions
		WHERE owner_user_id=? AND ended_at IS NULL
		ORDER BY started_at DESC LIMIT ?`, user.ID, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	defer rows.Close()
	items := []map[string]any{}
	for rows.Next() {
		var id int64
		var did string
		var vid sql.NullInt64
		var title, sku sql.NullString
		var started time.Time
		if err := rows.Scan(&id, &did, &vid, &title, &sku, &started); err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		item := map[string]any{
			"id":         id,
			"device_id":  did,
			"started_at": started.UTC(),
		}
		if vid.Valid {
			item["video_id"] = vid.Int64
		}
		if title.Valid {
			item["title"] = title.String
		}
		if sku.Valid {
			item["pinned_sku"] = sku.String
		}
		items = append(items, item)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func liveHistoryHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	limit, offset := parsePagination(r)
	deviceFilter := r.URL.Query().Get("device_id")

	q := `
		SELECT id, device_id, video_id, title, caption, hashtags, pinned_sku,
		       started_at, ended_at, end_reason
		FROM live_sessions
		WHERE owner_user_id=? AND ended_at IS NOT NULL`
	args := []any{user.ID}
	if deviceFilter != "" {
		q += " AND device_id=?"
		args = append(args, deviceFilter)
	}
	q += " ORDER BY ended_at DESC LIMIT ? OFFSET ?"
	args = append(args, limit, offset)

	rows, err := db.Query(q, args...)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	defer rows.Close()
	items := []map[string]any{}
	for rows.Next() {
		var id int64
		var did string
		var vid sql.NullInt64
		var title, caption, hashtags, sku, reason sql.NullString
		var started time.Time
		var ended sql.NullTime
		if err := rows.Scan(&id, &did, &vid, &title, &caption, &hashtags, &sku, &started, &ended, &reason); err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		item := map[string]any{
			"id":         id,
			"device_id":  did,
			"started_at": started.UTC(),
		}
		if vid.Valid {
			item["video_id"] = vid.Int64
		}
		if title.Valid {
			item["title"] = title.String
		}
		if caption.Valid {
			item["caption"] = caption.String
		}
		if hashtags.Valid && hashtags.String != "" {
			item["hashtags"] = strings.Split(hashtags.String, ",")
		}
		if sku.Valid {
			item["pinned_sku"] = sku.String
		}
		if ended.Valid {
			item["ended_at"] = ended.Time.UTC()
		}
		if reason.Valid {
			item["end_reason"] = reason.String
		}
		items = append(items, item)
	}
	writeJSON(w, http.StatusOK, paginatedResponse(items, len(items), limit, offset))
}

// -----------------------------------------------------------------------------
// Products
// -----------------------------------------------------------------------------

type pinProductReq struct {
	SKU string `json:"sku"`
}

func pinProductHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	did := r.PathValue("device_id")
	if err := assertDeviceOwned(did, user.ID, w); err != nil {
		return
	}
	var body pinProductReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
		return
	}
	cid, err := issueCommand(user.ID, did, "pin_product", map[string]any{"sku": body.SKU})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	_, _ = db.Exec("UPDATE devices SET current_pinned_sku=? WHERE id=?", body.SKU, did)
	writeJSON(w, http.StatusAccepted, map[string]string{"command_id": cid})
}

func unpinProductHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	did := r.PathValue("device_id")
	if err := assertDeviceOwned(did, user.ID, w); err != nil {
		return
	}
	cid, err := issueCommand(user.ID, did, "unpin_product", map[string]any{})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	_, _ = db.Exec("UPDATE devices SET current_pinned_sku=NULL WHERE id=?", did)
	writeJSON(w, http.StatusAccepted, map[string]string{"command_id": cid})
}

// -----------------------------------------------------------------------------
// Banners
// -----------------------------------------------------------------------------

func listBannersHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	vidStr := r.URL.Query().Get("video_id")
	liveStr := r.URL.Query().Get("live_session_id")

	q := `SELECT id, video_id, live_session_id, slot, text, bg_color, text_color, font_size, deadline, created_at
	      FROM banners WHERE owner_user_id=?`
	args := []any{user.ID}
	if vidStr != "" {
		if v, err := strconv.ParseInt(vidStr, 10, 64); err == nil {
			q += " AND video_id=?"
			args = append(args, v)
		}
	}
	if liveStr != "" {
		if v, err := strconv.ParseInt(liveStr, 10, 64); err == nil {
			q += " AND live_session_id=?"
			args = append(args, v)
		}
	}
	q += " ORDER BY id DESC"
	rows, err := db.Query(q, args...)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	defer rows.Close()

	items := []Banner{}
	for rows.Next() {
		b, err := scanBanner(rows)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		items = append(items, *b)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

type bannerReq struct {
	VideoID       *int64     `json:"video_id"`
	LiveSessionID *int64     `json:"live_session_id"`
	Slot          string     `json:"slot"`
	Text          string     `json:"text"`
	BgColor       string     `json:"bg_color"`
	TextColor     string     `json:"text_color"`
	FontSize      string     `json:"font_size"`
	Deadline      *time.Time `json:"deadline"`
}

func createBannerHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	var body bannerReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
		return
	}
	if body.Slot == "" || body.Text == "" {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "slot and text required")
		return
	}
	if len(body.Text) > 80 {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "text max 80 chars")
		return
	}
	if body.VideoID == nil && body.LiveSessionID == nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "video_id or live_session_id required")
		return
	}
	if body.BgColor == "" {
		body.BgColor = "#000000"
	}
	if body.TextColor == "" {
		body.TextColor = "#FFFFFF"
	}
	if body.FontSize == "" {
		body.FontSize = "M"
	}
	// Ownership check: video must belong to user; live session must belong to user.
	if body.VideoID != nil {
		var owner int64
		if err := db.QueryRow("SELECT owner_user_id FROM videos WHERE id=?", *body.VideoID).Scan(&owner); err != nil || owner != user.ID {
			writeError(w, http.StatusNotFound, "VIDEO_NOT_FOUND", "video not found")
			return
		}
	}
	if body.LiveSessionID != nil {
		var owner int64
		if err := db.QueryRow("SELECT owner_user_id FROM live_sessions WHERE id=?", *body.LiveSessionID).Scan(&owner); err != nil || owner != user.ID {
			writeError(w, http.StatusNotFound, "LIVE_NOT_FOUND", "live session not found")
			return
		}
	}

	var id int64
	err := db.QueryRow(`
		INSERT INTO banners (owner_user_id, video_id, live_session_id, slot, text, bg_color, text_color, font_size, deadline)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		RETURNING id`,
		user.ID, body.VideoID, body.LiveSessionID, body.Slot, body.Text,
		body.BgColor, body.TextColor, body.FontSize, body.Deadline,
	).Scan(&id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	b, _ := getBanner(id, user.ID)

	// Dynamic banner attached to a live session: push to device + portals.
	if body.LiveSessionID != nil {
		dispatchBannerUpdate(user.ID, *body.LiveSessionID, b, "add")
	}

	writeJSON(w, http.StatusCreated, b)
}

func patchBannerHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "bad id")
		return
	}
	existing, err := getBanner(id, user.ID)
	if err != nil {
		writeError(w, http.StatusNotFound, "BANNER_NOT_FOUND", "banner not found")
		return
	}
	var body bannerReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
		return
	}
	// Apply patches.
	if body.Slot != "" {
		existing.Slot = body.Slot
	}
	if body.Text != "" {
		if len(body.Text) > 80 {
			writeError(w, http.StatusBadRequest, "INVALID_INPUT", "text max 80 chars")
			return
		}
		existing.Text = body.Text
	}
	if body.BgColor != "" {
		existing.BgColor = body.BgColor
	}
	if body.TextColor != "" {
		existing.TextColor = body.TextColor
	}
	if body.FontSize != "" {
		existing.FontSize = body.FontSize
	}
	if body.Deadline != nil {
		existing.Deadline = body.Deadline
	}
	_, err = db.Exec(`
		UPDATE banners SET slot=?, text=?, bg_color=?, text_color=?, font_size=?, deadline=?
		WHERE id=? AND owner_user_id=?`,
		existing.Slot, existing.Text, existing.BgColor, existing.TextColor,
		existing.FontSize, existing.Deadline, id, user.ID,
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if existing.LiveSessionID != nil {
		dispatchBannerUpdate(user.ID, *existing.LiveSessionID, existing, "update")
	}
	writeJSON(w, http.StatusOK, existing)
}

func deleteBannerHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "bad id")
		return
	}
	existing, err := getBanner(id, user.ID)
	if err != nil {
		writeError(w, http.StatusNotFound, "BANNER_NOT_FOUND", "banner not found")
		return
	}
	if _, err := db.Exec("DELETE FROM banners WHERE id=? AND owner_user_id=?", id, user.ID); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if existing.LiveSessionID != nil {
		dispatchBannerUpdate(user.ID, *existing.LiveSessionID, existing, "remove")
	}
	w.WriteHeader(http.StatusNoContent)
}

func scanBanner(rows *sql.Rows) (*Banner, error) {
	var b Banner
	var vid, live sql.NullInt64
	var deadline sql.NullTime
	err := rows.Scan(&b.ID, &vid, &live, &b.Slot, &b.Text, &b.BgColor, &b.TextColor, &b.FontSize, &deadline, &b.CreatedAt)
	if err != nil {
		return nil, err
	}
	if vid.Valid {
		v := vid.Int64
		b.VideoID = &v
	}
	if live.Valid {
		v := live.Int64
		b.LiveSessionID = &v
	}
	if deadline.Valid {
		t := deadline.Time
		b.Deadline = &t
	}
	return &b, nil
}

func getBanner(id, userID int64) (*Banner, error) {
	row := db.QueryRow(`
		SELECT id, video_id, live_session_id, slot, text, bg_color, text_color, font_size, deadline, created_at
		FROM banners WHERE id=? AND owner_user_id=?`, id, userID)
	var b Banner
	var vid, live sql.NullInt64
	var deadline sql.NullTime
	err := row.Scan(&b.ID, &vid, &live, &b.Slot, &b.Text, &b.BgColor, &b.TextColor, &b.FontSize, &deadline, &b.CreatedAt)
	if err != nil {
		return nil, err
	}
	if vid.Valid {
		v := vid.Int64
		b.VideoID = &v
	}
	if live.Valid {
		v := live.Int64
		b.LiveSessionID = &v
	}
	if deadline.Valid {
		t := deadline.Time
		b.Deadline = &t
	}
	return &b, nil
}

// fetchBannersForLiveStart pulls all video-level banners so they can be
// shipped along with the start_live command.
func fetchBannersForLiveStart(userID, videoID int64) []map[string]any {
	rows, err := db.Query(`
		SELECT id, slot, text, bg_color, text_color, font_size, deadline
		FROM banners WHERE owner_user_id=? AND video_id=?`, userID, videoID)
	if err != nil {
		return nil
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id int64
		var slot, text, bg, tc, fs string
		var deadline sql.NullTime
		if err := rows.Scan(&id, &slot, &text, &bg, &tc, &fs, &deadline); err != nil {
			continue
		}
		m := map[string]any{
			"id": id, "slot": slot, "text": text,
			"bg_color": bg, "text_color": tc, "font_size": fs,
		}
		if deadline.Valid {
			m["deadline"] = deadline.Time.UTC()
		}
		out = append(out, m)
	}
	return out
}

// dispatchBannerUpdate sends update_banner to the device tied to the live
// session and notifies all portal sockets owned by the user.
func dispatchBannerUpdate(userID, liveSessionID int64, b *Banner, action string) {
	var did string
	if err := db.QueryRow(
		"SELECT device_id FROM live_sessions WHERE id=? AND owner_user_id=?",
		liveSessionID, userID,
	).Scan(&did); err == nil && did != "" {
		_, _ = issueCommand(userID, did, "update_banner", map[string]any{
			"slot":       b.Slot,
			"text":       b.Text,
			"bg_color":   b.BgColor,
			"text_color": b.TextColor,
			"font_size":  b.FontSize,
			"deadline":   b.Deadline,
			"action":     action,
		})
	}
	broadcastToPortal(userID, "banner_updated", map[string]any{
		"banner_id":       b.ID,
		"live_session_id": liveSessionID,
	})
}

// -----------------------------------------------------------------------------
// Commands
// -----------------------------------------------------------------------------

func issueCommand(userID int64, deviceID, cmdType string, payload map[string]any) (string, error) {
	id := uuid.NewString()
	payloadJSON, _ := json.Marshal(payload)
	_, err := db.Exec(
		"INSERT INTO commands (id, owner_user_id, device_id, type, payload_json) VALUES (?, ?, ?, ?, ?)",
		id, userID, deviceID, cmdType, string(payloadJSON),
	)
	if err != nil {
		return "", err
	}
	// Try delivery (best-effort).
	envelope := map[string]any{
		"id":        id,
		"type":      cmdType,
		"payload":   payload,
		"timestamp": time.Now().UTC(),
	}
	_ = sendToDevice(deviceID, envelope)
	return id, nil
}

func getCommandHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	id := r.PathValue("id")

	var c Command
	var ack sql.NullTime
	var ecode, emsg sql.NullString
	err := db.QueryRow(`
		SELECT id, device_id, type, issued_at, ack_at, status, error_code, error_message
		FROM commands WHERE id=? AND owner_user_id=?`,
		id, user.ID,
	).Scan(&c.ID, &c.DeviceID, &c.Type, &c.IssuedAt, &ack, &c.Status, &ecode, &emsg)
	if err != nil {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "command not found")
		return
	}
	if ack.Valid {
		t := ack.Time
		c.AckAt = &t
	}
	resp := map[string]any{
		"id":        c.ID,
		"type":      c.Type,
		"device_id": c.DeviceID,
		"issued_at": c.IssuedAt.UTC(),
		"status":    c.Status,
	}
	if c.AckAt != nil {
		resp["ack_at"] = c.AckAt.UTC()
	}
	if ecode.Valid {
		resp["error"] = map[string]string{"code": ecode.String, "message": emsg.String}
	} else {
		resp["error"] = nil
	}
	writeJSON(w, http.StatusOK, resp)
}

// markCommandAck is called from ws.go when a device acks.
func markCommandAck(id string, success bool, code, msg string) {
	now := time.Now().UTC()
	status := "ack"
	if !success {
		status = "error"
	}
	if !success {
		_, _ = db.Exec(`
			UPDATE commands SET ack_at=?, status=?, error_code=?, error_message=?
			WHERE id=?`, now, status, code, msg, id)
	} else {
		_, _ = db.Exec(`
			UPDATE commands SET ack_at=?, status=? WHERE id=?`, now, status, id)
	}
	// Notify portal sockets owned by the issuing user.
	var owner int64
	if err := db.QueryRow("SELECT owner_user_id FROM commands WHERE id=?", id).Scan(&owner); err == nil {
		payload := map[string]any{"command_id": id, "status": status}
		if !success {
			payload["error"] = map[string]string{"code": code, "message": msg}
		}
		broadcastToPortal(owner, "command_completed", payload)
	}
}

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

// assertDeviceOwned writes a 404 or 403 and returns an error if the device
// does not exist or is owned by another user.
func assertDeviceOwned(deviceID string, userID int64, w http.ResponseWriter) error {
	var owner int64
	err := db.QueryRow("SELECT owner_user_id FROM devices WHERE id=?", deviceID).Scan(&owner)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeError(w, http.StatusNotFound, "DEVICE_NOT_FOUND", "device not found")
			return err
		}
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return err
	}
	if owner != userID {
		writeError(w, http.StatusForbidden, "DEVICE_NOT_OWNED", "device not owned")
		return errors.New("not owned")
	}
	return nil
}

// stripTrailingSlash kept as a tiny utility (used by no current code, but
// other agents/tests may reference it).
func stripTrailingSlash(p string) string {
	return strings.TrimSuffix(p, "/")
}
