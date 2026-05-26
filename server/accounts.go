package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// -----------------------------------------------------------------------------
// TikTok account pool — burner accounts a device rotates through.
//
// Routes: see main.go (`/api/devices/{id}/accounts*`).
// Ownership: every endpoint verifies the device belongs to the caller's user.
// -----------------------------------------------------------------------------

// deviceOwnedBy returns nil if the device exists AND is owned by userID.
// Returns sql.ErrNoRows when missing; a generic error otherwise.
func deviceOwnedBy(deviceID string, userID int64) (string, error) {
	var ownerID int64
	var skuTier string
	err := db.QueryRow(
		"SELECT owner_user_id, sku_tier FROM devices WHERE id=?", deviceID,
	).Scan(&ownerID, &skuTier)
	if err != nil {
		return "", err
	}
	if ownerID != userID {
		return "", errors.New("DEVICE_NOT_OWNED")
	}
	return skuTier, nil
}

func writeDeviceLookupError(w http.ResponseWriter, err error) {
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, "DEVICE_NOT_FOUND", "device not found")
		return
	}
	if err.Error() == "DEVICE_NOT_OWNED" {
		writeError(w, http.StatusForbidden, "DEVICE_NOT_OWNED", "device not owned")
		return
	}
	writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
}

// -----------------------------------------------------------------------------
// GET /api/devices/{id}/accounts
// -----------------------------------------------------------------------------

func listDeviceAccountsHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	deviceID := r.PathValue("id")
	if _, err := deviceOwnedBy(deviceID, user.ID); err != nil {
		writeDeviceLookupError(w, err)
		return
	}

	rows, err := db.Query(`
		SELECT id, device_id, owner_user_id, label, username, status, snapshot_path,
		       last_used_at, banned_at, captcha_count, notes, created_at
		FROM tiktok_accounts
		WHERE device_id=?
		ORDER BY id ASC`, deviceID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	defer rows.Close()

	out := []TikTokAccount{}
	for rows.Next() {
		var a TikTokAccount
		var username, snapshot, notes sql.NullString
		var lastUsed, bannedAt sql.NullTime
		if err := rows.Scan(
			&a.ID, &a.DeviceID, &a.OwnerUserID, &a.Label, &username, &a.Status,
			&snapshot, &lastUsed, &bannedAt, &a.CaptchaCount, &notes, &a.CreatedAt,
		); err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		if username.Valid {
			s := username.String
			a.Username = &s
		}
		if snapshot.Valid {
			s := snapshot.String
			a.SnapshotPath = &s
		}
		if notes.Valid {
			s := notes.String
			a.Notes = &s
		}
		if lastUsed.Valid {
			t := lastUsed.Time
			a.LastUsedAt = &t
		}
		if bannedAt.Valid {
			t := bannedAt.Time
			a.BannedAt = &t
		}
		out = append(out, a)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

// -----------------------------------------------------------------------------
// POST /api/devices/{id}/accounts
// -----------------------------------------------------------------------------

type addAccountReq struct {
	Label    string  `json:"label"`
	Username *string `json:"username,omitempty"`
	Notes    *string `json:"notes,omitempty"`
}

func addDeviceAccountHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	deviceID := r.PathValue("id")
	if _, err := deviceOwnedBy(deviceID, user.ID); err != nil {
		writeDeviceLookupError(w, err)
		return
	}

	var body addAccountReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
		return
	}
	body.Label = strings.TrimSpace(body.Label)
	if body.Label == "" {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "label required")
		return
	}

	var username, notes sql.NullString
	if body.Username != nil {
		username = sql.NullString{String: strings.TrimSpace(*body.Username), Valid: true}
	}
	if body.Notes != nil {
		notes = sql.NullString{String: *body.Notes, Valid: true}
	}

	res, err := db.Exec(`
		INSERT INTO tiktok_accounts (device_id, owner_user_id, label, username, notes)
		VALUES (?, ?, ?, ?, ?)`,
		deviceID, user.ID, body.Label, username, notes,
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	id, _ := res.LastInsertId()
	writeJSON(w, http.StatusCreated, map[string]any{"id": id})
}

// -----------------------------------------------------------------------------
// PATCH /api/devices/{id}/accounts/{aid}
// -----------------------------------------------------------------------------

type patchAccountReq struct {
	Label        *string `json:"label,omitempty"`
	Username     *string `json:"username,omitempty"`
	Status       *string `json:"status,omitempty"`
	SnapshotPath *string `json:"snapshot_path,omitempty"`
	Notes        *string `json:"notes,omitempty"`
	CaptchaCount *int    `json:"captcha_count,omitempty"`
}

func patchDeviceAccountHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	deviceID := r.PathValue("id")
	if _, err := deviceOwnedBy(deviceID, user.ID); err != nil {
		writeDeviceLookupError(w, err)
		return
	}
	accountID, err := strconv.ParseInt(r.PathValue("aid"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "bad account id")
		return
	}

	var body patchAccountReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
		return
	}

	sets := []string{}
	args := []any{}
	if body.Label != nil {
		sets = append(sets, "label=?")
		args = append(args, *body.Label)
	}
	if body.Username != nil {
		sets = append(sets, "username=?")
		args = append(args, *body.Username)
	}
	if body.Status != nil {
		switch *body.Status {
		case "active", "cooldown", "banned", "disabled":
		default:
			writeError(w, http.StatusBadRequest, "INVALID_INPUT", "bad status")
			return
		}
		sets = append(sets, "status=?")
		args = append(args, *body.Status)
		if *body.Status == "banned" {
			sets = append(sets, "banned_at=?")
			args = append(args, time.Now())
		}
	}
	if body.SnapshotPath != nil {
		sets = append(sets, "snapshot_path=?")
		args = append(args, *body.SnapshotPath)
	}
	if body.Notes != nil {
		sets = append(sets, "notes=?")
		args = append(args, *body.Notes)
	}
	if body.CaptchaCount != nil {
		sets = append(sets, "captcha_count=?")
		args = append(args, *body.CaptchaCount)
	}
	if len(sets) == 0 {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "nothing to update")
		return
	}

	args = append(args, accountID, deviceID)
	q := "UPDATE tiktok_accounts SET " + strings.Join(sets, ", ") + " WHERE id=? AND device_id=?"
	res, err := db.Exec(q, args...)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "account not found")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// -----------------------------------------------------------------------------
// DELETE /api/devices/{id}/accounts/{aid}
// -----------------------------------------------------------------------------

func deleteDeviceAccountHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	deviceID := r.PathValue("id")
	if _, err := deviceOwnedBy(deviceID, user.ID); err != nil {
		writeDeviceLookupError(w, err)
		return
	}
	accountID, err := strconv.ParseInt(r.PathValue("aid"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "bad account id")
		return
	}
	res, err := db.Exec("DELETE FROM tiktok_accounts WHERE id=? AND device_id=?",
		accountID, deviceID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "account not found")
		return
	}
	// Clear current_account_id on device if it pointed here.
	_, _ = db.Exec(
		"UPDATE devices SET current_account_id=NULL WHERE id=? AND current_account_id=?",
		deviceID, accountID,
	)
	w.WriteHeader(http.StatusNoContent)
}

// -----------------------------------------------------------------------------
// POST /api/devices/{id}/accounts/rotate
//
// Picks the next eligible account from the device's pool (round-robin by
// last_used_at, skipping banned/disabled) and sends a switch_account command
// over the device WebSocket. The device performs the actual data-dir swap
// (V3) or in-app account switch (V1) and acks via status envelope.
// -----------------------------------------------------------------------------

type rotateAccountReq struct {
	// If set, switch to this specific account instead of round-robin. Useful
	// for the portal "use account X for next live" override.
	AccountID *int64 `json:"account_id,omitempty"`
}

func rotateDeviceAccountHandler(w http.ResponseWriter, r *http.Request) {
	user := userFromCtx(r)
	deviceID := r.PathValue("id")
	skuTier, err := deviceOwnedBy(deviceID, user.ID)
	if err != nil {
		writeDeviceLookupError(w, err)
		return
	}

	var body rotateAccountReq
	_ = json.NewDecoder(r.Body).Decode(&body)

	var (
		accountID    int64
		label        string
		snapshot     sql.NullString
	)
	if body.AccountID != nil {
		err = db.QueryRow(`
			SELECT id, label, snapshot_path FROM tiktok_accounts
			WHERE id=? AND device_id=? AND status='active'`,
			*body.AccountID, deviceID,
		).Scan(&accountID, &label, &snapshot)
	} else {
		// Round-robin: pick the active account with the oldest last_used_at
		// (NULLs first so newly added accounts get warmed up first).
		err = db.QueryRow(`
			SELECT id, label, snapshot_path FROM tiktok_accounts
			WHERE device_id=? AND status='active'
			ORDER BY last_used_at IS NULL DESC, last_used_at ASC
			LIMIT 1`, deviceID,
		).Scan(&accountID, &label, &snapshot)
	}
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusBadRequest, "NO_ACCOUNTS_AVAILABLE",
			"device has no active accounts in pool")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	// V3 tier MUST have a snapshot path for the rotator to work without UI
	// interaction. V1 tier doesn't need one — the device will use the
	// in-TikTok account switcher.
	if skuTier == "v3_pro" && !snapshot.Valid {
		writeError(w, http.StatusConflict, "NO_SNAPSHOT",
			"V3 device requires snapshot_path on the chosen account")
		return
	}

	payload := map[string]any{
		"account_id":    accountID,
		"label":         label,
		"sku_tier":      skuTier,
		"snapshot_path": snapshot.String, // empty for V1
	}
	cid, err := issueCommand(user.ID, deviceID, "switch_account", payload)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	// Optimistic: mark as in-use now. If the device fails the swap it can
	// emit an ack with success=false and the portal can correct.
	_, _ = db.Exec(`
		UPDATE tiktok_accounts SET last_used_at=? WHERE id=?`,
		time.Now(), accountID,
	)
	_, _ = db.Exec(`
		UPDATE devices SET current_account_id=? WHERE id=?`,
		accountID, deviceID,
	)

	writeJSON(w, http.StatusOK, map[string]any{
		"command_id": cid,
		"account_id": accountID,
		"label":      label,
	})
}
