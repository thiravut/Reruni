package main

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Minimal device WS hub for the POC. Mirrors the legacy POC protocol the
// mobile client expects:
//
//   ws://host/ws/device?token=<pair_token>&device_id=<existing_id>&name=<label>
//
// First frame from server: {"type":"welcome","device_id":"..."}.
// Server can push: {"type":"play",...} or {"type":"start_live",...}.
// Client may send free-form JSON (status updates); the POC just logs them.

type wsConn struct {
	conn     *websocket.Conn
	deviceID string
	writeMu  sync.Mutex
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

var (
	connections   = map[string]*wsConn{}
	connectionsMu sync.RWMutex

	upgrader = websocket.Upgrader{
		CheckOrigin: func(r *http.Request) bool { return true },
	}
)

func deviceWsHandler(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("ws upgrade: %v", err)
		return
	}

	token := r.URL.Query().Get("token")
	deviceID := r.URL.Query().Get("device_id")
	name := r.URL.Query().Get("name")

	// Auth: valid pair token OR already-known device id (allows reconnect
	// after the original pair token has expired).
	tokenValid := false
	if token != "" {
		var exp time.Time
		if err := db.QueryRow("SELECT expires_at FROM pair_tokens WHERE token=?", token).Scan(&exp); err == nil {
			tokenValid = time.Now().Before(exp)
		}
	}
	deviceKnown := false
	if deviceID != "" {
		var n int
		_ = db.QueryRow("SELECT COUNT(*) FROM devices WHERE id=?", deviceID).Scan(&n)
		deviceKnown = n > 0
	}
	if !tokenValid && !deviceKnown {
		log.Printf("ws auth rejected: token=%q device_id=%q", token, deviceID)
		_ = conn.Close()
		return
	}

	if deviceID == "" {
		deviceID = "dev-" + randomHex(6)
	}
	_, _ = db.Exec(`
		INSERT INTO devices (id, name, last_seen)
		VALUES (?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET name=excluded.name, last_seen=excluded.last_seen`,
		deviceID, name, time.Now(),
	)
	_ = conn.WriteJSON(map[string]string{
		"type":      "welcome",
		"device_id": deviceID,
	})

	runDeviceLoop(conn, deviceID)
}

func runDeviceLoop(conn *websocket.Conn, deviceID string) {
	wc := &wsConn{conn: conn, deviceID: deviceID}
	connectionsMu.Lock()
	if old, ok := connections[deviceID]; ok && old != wc {
		_ = old.conn.Close()
	}
	connections[deviceID] = wc
	connectionsMu.Unlock()
	log.Printf("device connected: %s", deviceID)

	defer func() {
		connectionsMu.Lock()
		if connections[deviceID] == wc {
			delete(connections, deviceID)
		}
		connectionsMu.Unlock()
		_ = conn.Close()
		_, _ = db.Exec("UPDATE devices SET last_seen=? WHERE id=?", time.Now(), deviceID)
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
		log.Printf("[%s] msg: %s", deviceID, string(msg))
	}
}

func pingLoop(wc *wsConn) {
	t := time.NewTicker(20 * time.Second)
	defer t.Stop()
	for range t.C {
		wc.writeMu.Lock()
		err := wc.conn.WriteControl(
			websocket.PingMessage,
			[]byte("ping"),
			time.Now().Add(5*time.Second),
		)
		wc.writeMu.Unlock()
		if err != nil {
			return
		}
	}
}

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
