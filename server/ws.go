package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// -----------------------------------------------------------------------------
// Connection registries
// -----------------------------------------------------------------------------

type wsConn struct {
	conn     *websocket.Conn
	deviceID string
	writeMu  sync.Mutex
}

type portalConn struct {
	conn    *websocket.Conn
	userID  int64
	writeMu sync.Mutex
}

var (
	connections   = map[string]*wsConn{}
	connectionsMu sync.RWMutex

	// portalConns is keyed by user_id → list of open browser sockets.
	portalConns   = map[int64][]*portalConn{}
	portalConnsMu sync.RWMutex

	upgrader = websocket.Upgrader{
		CheckOrigin: func(r *http.Request) bool { return true },
	}
)

// -----------------------------------------------------------------------------
// /ws/device — mobile companion
// -----------------------------------------------------------------------------

// deviceWsHandler upgrades the connection and then enters a read loop. Auth
// for the first frame happens inside the loop via the "pair" or "hello"
// message types defined in api-contract §3.4. Query params (token, device_id)
// are accepted as fallback for the legacy POC client.
func deviceWsHandler(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("ws upgrade: %v", err)
		return
	}

	// Wait for first message (pair or hello) within 10s. Legacy query-string
	// auth lives in poc/ now — not on the production server.
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	_, raw, err := conn.ReadMessage()
	if err != nil {
		_ = conn.Close()
		return
	}
	var env wsEnvelope
	if err := json.Unmarshal(raw, &env); err != nil {
		writeWsError(conn, "INVALID_INPUT", "bad first frame")
		_ = conn.Close()
		return
	}

	var (
		deviceID    string
		ownerID     int64
		deviceToken string
	)

	log.Printf("ws first frame: type=%s remote=%s", env.Type, r.RemoteAddr)
	switch env.Type {
	case "pair":
		var p struct {
			Token      string         `json:"token"`
			DeviceInfo map[string]any `json:"device_info"`
		}
		_ = json.Unmarshal(env.Payload, &p)
		if p.Token == "" {
			log.Printf("ws pair: missing token in payload")
			writeWsError(conn, "INVALID_INPUT", "missing token")
			_ = conn.Close()
			return
		}
		var (
			owner int64
			exp   time.Time
			used  sql.NullTime
		)
		err := db.QueryRow(
			"SELECT owner_user_id, expires_at, used_at FROM pair_tokens WHERE token=?",
			p.Token,
		).Scan(&owner, &exp, &used)
		if err != nil {
			log.Printf("ws pair: token lookup failed (token=%q): %v", p.Token, err)
			writeWsError(conn, "UNAUTHORIZED", "invalid pair token")
			_ = conn.Close()
			return
		}
		if time.Now().After(exp) {
			log.Printf("ws pair: token expired at %v (now=%v)", exp, time.Now())
			writeWsError(conn, "UNAUTHORIZED", "pair token expired")
			_ = conn.Close()
			return
		}
		if used.Valid {
			log.Printf("ws pair: token already used at %v", used.Time)
			// Allow re-pair (existing behaviour preserves), but warn so we can
			// see in production if this is happening unexpectedly.
		}
		ownerID = owner
		// Mint device id + device token.
		deviceID = "dev-" + randomHex(6)
		deviceToken = generateSessionToken()
		name := ""
		if v, ok := p.DeviceInfo["name"].(string); ok {
			name = v
		}
		_, err = db.Exec(`
			INSERT INTO devices (id, name, owner_user_id, device_token, status, paired_at, last_seen)
			VALUES (?, ?, ?, ?, 'idle', ?, ?)`,
			deviceID, name, ownerID, deviceToken, time.Now(), time.Now(),
		)
		if err != nil {
			log.Printf("device insert: %v", err)
			writeWsError(conn, "INTERNAL_ERROR", err.Error())
			_ = conn.Close()
			return
		}
		_, _ = db.Exec("UPDATE pair_tokens SET used_at=? WHERE token=?", time.Now(), p.Token)
		// Look up the owner email so the mobile companion can show
		// "paired with <user>" on its home screen — operator confirmation
		// that the phone is talking to the right portal account.
		ownerEmail := lookupUserEmail(ownerID)
		_ = sendEnvelope(conn, "paired", map[string]any{
			"device_id":    deviceID,
			"device_token": deviceToken,
			"owner_email":  ownerEmail,
		})

	case "hello":
		var p struct {
			DeviceToken string `json:"device_token"`
			AppVersion  string `json:"app_version"`
		}
		_ = json.Unmarshal(env.Payload, &p)
		if p.DeviceToken == "" {
			log.Printf("ws hello: missing device_token")
			writeWsError(conn, "UNAUTHORIZED", "missing device_token")
			_ = conn.Close()
			return
		}
		err := db.QueryRow(
			"SELECT id, owner_user_id FROM devices WHERE device_token=?",
			p.DeviceToken,
		).Scan(&deviceID, &ownerID)
		if err != nil {
			log.Printf("ws hello: unknown device_token (len=%d, first8=%.8s): %v",
				len(p.DeviceToken), p.DeviceToken, err)
			writeWsError(conn, "UNAUTHORIZED", "unknown device_token")
			_ = conn.Close()
			return
		}
		ownerEmail := lookupUserEmail(ownerID)
		_ = sendEnvelope(conn, "welcome", map[string]any{
			"device_id":   deviceID,
			"owner_email": ownerEmail,
		})
	default:
		writeWsError(conn, "INVALID_INPUT", "expected pair or hello")
		_ = conn.Close()
		return
	}

	runDeviceLoop(conn, deviceID, ownerID)
}

// runDeviceLoop is the shared read/ping loop used by both auth flows.
func runDeviceLoop(conn *websocket.Conn, deviceID string, ownerID int64) {
	wc := &wsConn{conn: conn, deviceID: deviceID}
	connectionsMu.Lock()
	if old, ok := connections[deviceID]; ok && old != wc {
		_ = old.conn.Close()
	}
	connections[deviceID] = wc
	connectionsMu.Unlock()
	log.Printf("device connected: %s (owner=%d)", deviceID, ownerID)

	// Broadcast initial status to owner's portal sockets.
	broadcastToPortal(ownerID, "device_status_changed", map[string]any{
		"device_id":    deviceID,
		"status":       "idle",
		"last_seen_at": time.Now().UTC(),
	})

	defer func() {
		connectionsMu.Lock()
		if connections[deviceID] == wc {
			delete(connections, deviceID)
		}
		connectionsMu.Unlock()
		_ = conn.Close()
		_, _ = db.Exec("UPDATE devices SET last_seen=? WHERE id=?", time.Now(), deviceID)
		broadcastToPortal(ownerID, "device_status_changed", map[string]any{
			"device_id":    deviceID,
			"status":       "offline",
			"last_seen_at": time.Now().UTC(),
		})
		log.Printf("device disconnected: %s", deviceID)
	}()

	conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		_, _ = db.Exec("UPDATE devices SET last_seen=? WHERE id=?", time.Now(), deviceID)
		return nil
	})

	go pingLoop(wc)

	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		_, _ = db.Exec("UPDATE devices SET last_seen=? WHERE id=?", time.Now(), deviceID)
		handleDeviceMessage(deviceID, ownerID, msg)
	}
}

// handleDeviceMessage parses an envelope from the device and dispatches by type.
// Legacy frames that aren't valid envelopes are logged and dropped.
func handleDeviceMessage(deviceID string, ownerID int64, raw []byte) {
	var env wsEnvelope
	if err := json.Unmarshal(raw, &env); err != nil {
		log.Printf("[%s] non-envelope: %s", deviceID, string(raw))
		return
	}
	switch env.Type {
	case "status":
		var p struct {
			StreamStatus     string  `json:"stream_status"`
			CurrentVideoID   *int64  `json:"current_video_id"`
			CurrentPinnedSKU *string `json:"current_pinned_sku"`
			ViewerCount      *int    `json:"viewer_count"`
		}
		_ = json.Unmarshal(env.Payload, &p)
		status := "idle"
		if p.StreamStatus != "" {
			status = p.StreamStatus
		}
		_, _ = db.Exec(`
			UPDATE devices SET status=?, current_video_id=?, current_pinned_sku=?, last_seen=?
			WHERE id=?`, status, p.CurrentVideoID, p.CurrentPinnedSKU, time.Now(), deviceID)
		broadcastToPortal(ownerID, "device_status_changed", map[string]any{
			"device_id":    deviceID,
			"status":       status,
			"last_seen_at": time.Now().UTC(),
		})

	case "ack":
		var p struct {
			CommandID    string `json:"command_id"`
			Success      bool   `json:"success"`
			ErrorCode    string `json:"error_code"`
			ErrorMessage string `json:"error_message"`
		}
		_ = json.Unmarshal(env.Payload, &p)
		if p.CommandID != "" {
			markCommandAck(p.CommandID, p.Success, p.ErrorCode, p.ErrorMessage)
		}

	case "error":
		var p struct {
			CommandID string `json:"command_id"`
			Code      string `json:"code"`
			Message   string `json:"message"`
		}
		_ = json.Unmarshal(env.Payload, &p)
		broadcastToPortal(ownerID, "error_notice", map[string]any{
			"device_id": deviceID,
			"code":      p.Code,
			"message":   p.Message,
		})
		if p.CommandID != "" {
			markCommandAck(p.CommandID, false, p.Code, p.Message)
		}

	case "pong":
		// no-op; SetPongHandler covers control-frame pongs
	default:
		log.Printf("[%s] unhandled type: %s", deviceID, env.Type)
	}
}

// -----------------------------------------------------------------------------
// /ws/portal — browser
// -----------------------------------------------------------------------------

func portalWsHandler(w http.ResponseWriter, r *http.Request) {
	c, err := r.Cookie(portalCookieName)
	if err != nil || c.Value == "" {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	_, user, err := lookupSession(c.Value, sessionScopePortal)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("portal ws upgrade: %v", err)
		return
	}
	pc := &portalConn{conn: conn, userID: user.ID}
	portalConnsMu.Lock()
	portalConns[user.ID] = append(portalConns[user.ID], pc)
	portalConnsMu.Unlock()
	log.Printf("portal connected: user=%d", user.ID)

	defer func() {
		portalConnsMu.Lock()
		list := portalConns[user.ID]
		for i, x := range list {
			if x == pc {
				portalConns[user.ID] = append(list[:i], list[i+1:]...)
				break
			}
		}
		if len(portalConns[user.ID]) == 0 {
			delete(portalConns, user.ID)
		}
		portalConnsMu.Unlock()
		_ = conn.Close()
		log.Printf("portal disconnected: user=%d", user.ID)
	}()

	conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return nil
	})

	// Ping ticker.
	go func() {
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for range t.C {
			pc.writeMu.Lock()
			err := pc.conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second))
			pc.writeMu.Unlock()
			if err != nil {
				return
			}
		}
	}()

	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		// Currently the only browser→server messages are subscribe + pong (json).
		// We accept them but do nothing besides logging.
		_ = msg
	}
}

// -----------------------------------------------------------------------------
// Envelope helpers + ping
// -----------------------------------------------------------------------------

type wsEnvelope struct {
	ID        string          `json:"id"`
	Type      string          `json:"type"`
	Payload   json.RawMessage `json:"payload"`
	Timestamp time.Time       `json:"timestamp"`
}

// lookupUserEmail returns the email for a given user id, or empty string if
// the row is missing / lookup fails. Used to put a human-readable owner on
// the paired/welcome envelopes so the mobile companion can show "paired
// with <user>" without having to make a separate REST round-trip.
func lookupUserEmail(userID int64) string {
	if userID == 0 {
		return ""
	}
	var email string
	if err := db.QueryRow("SELECT email FROM users WHERE id=?", userID).Scan(&email); err != nil {
		return ""
	}
	return email
}

func sendEnvelope(conn *websocket.Conn, typ string, payload any) error {
	env := map[string]any{
		"id":        randomHex(8),
		"type":      typ,
		"payload":   payload,
		"timestamp": time.Now().UTC(),
	}
	data, err := json.Marshal(env)
	if err != nil {
		return err
	}
	return conn.WriteMessage(websocket.TextMessage, data)
}

func writeWsError(conn *websocket.Conn, code, msg string) {
	_ = sendEnvelope(conn, "error", map[string]string{
		"code":    code,
		"message": msg,
	})
}

func pingLoop(wc *wsConn) {
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for range t.C {
		wc.writeMu.Lock()
		err := wc.conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second))
		wc.writeMu.Unlock()
		if err != nil {
			return
		}
	}
}

func (wc *wsConn) send(v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	wc.writeMu.Lock()
	defer wc.writeMu.Unlock()
	return wc.conn.WriteMessage(websocket.TextMessage, data)
}

func (pc *portalConn) send(v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	pc.writeMu.Lock()
	defer pc.writeMu.Unlock()
	return pc.conn.WriteMessage(websocket.TextMessage, data)
}

// -----------------------------------------------------------------------------
// Public API used by handlers.go
// -----------------------------------------------------------------------------

func isDeviceOnline(deviceID string) bool {
	connectionsMu.RLock()
	_, ok := connections[deviceID]
	connectionsMu.RUnlock()
	return ok
}

func sendToDevice(deviceID string, payload any) error {
	connectionsMu.RLock()
	wc, ok := connections[deviceID]
	connectionsMu.RUnlock()
	if !ok {
		return errors.New("device not connected")
	}
	return wc.send(payload)
}

// disconnectDevice closes the open socket if present (used by DELETE /api/devices
// and admin force-disconnect).
func disconnectDevice(deviceID, reason string) {
	connectionsMu.Lock()
	wc, ok := connections[deviceID]
	if ok {
		_ = sendEnvelope(wc.conn, "force_disconnect", map[string]string{"reason": reason})
		_ = wc.conn.Close()
		delete(connections, deviceID)
	}
	connectionsMu.Unlock()
}

// broadcastToPortal fans an envelope out to every portal socket belonging to
// the given user. Errors are swallowed because the socket cleanup goroutine
// handles dead connections.
func broadcastToPortal(userID int64, typ string, payload any) {
	if userID == 0 {
		return
	}
	portalConnsMu.RLock()
	conns := append([]*portalConn(nil), portalConns[userID]...)
	portalConnsMu.RUnlock()
	if len(conns) == 0 {
		return
	}
	env := map[string]any{
		"id":        randomHex(8),
		"type":      typ,
		"payload":   payload,
		"timestamp": time.Now().UTC(),
	}
	for _, pc := range conns {
		_ = pc.send(env)
	}
}
