package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

const pairTokenTTL = 24 * time.Hour

// -----------------------------------------------------------------------------
// helpers
// -----------------------------------------------------------------------------

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(b)
}

func generatePairToken() string {
	b := make([]byte, 10)
	_, _ = rand.Read(b)
	const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	out := make([]byte, len(b))
	for i, c := range b {
		out[i] = alphabet[int(c)%len(alphabet)]
	}
	return string(out)
}

// -----------------------------------------------------------------------------
// /api/legacy/pair
// -----------------------------------------------------------------------------

func createPairHandler(w http.ResponseWriter, r *http.Request) {
	token := generatePairToken()
	if _, err := db.Exec(
		"INSERT INTO pair_tokens (token, expires_at) VALUES (?, ?)",
		token, time.Now().Add(pairTokenTTL),
	); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	baseURL := scheme + "://" + r.Host
	qrPayload, _ := json.Marshal(map[string]string{"url": baseURL, "token": token})
	png, err := qrcode.Encode(string(qrPayload), qrcode.Medium, 320)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{
		"token":       token,
		"url":         baseURL,
		"qr_data_url": "data:image/png;base64," + base64.StdEncoding.EncodeToString(png),
	})
}

// -----------------------------------------------------------------------------
// /api/legacy/videos
// -----------------------------------------------------------------------------

func listVideosHandler(w http.ResponseWriter, r *http.Request) {
	rows, err := db.Query("SELECT id, name, filename, size_bytes, created_at FROM videos ORDER BY id DESC")
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	out := []Video{}
	for rows.Next() {
		var v Video
		if err := rows.Scan(&v.ID, &v.Name, &v.Filename, &v.SizeBytes, &v.CreatedAt); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		v.URL = "/uploads/" + v.Filename
		out = append(out, v)
	}
	writeJSON(w, http.StatusOK, out)
}

func uploadVideoHandler(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(2 << 30); err != nil {
		writeErr(w, http.StatusBadRequest, "parse multipart: "+err.Error())
		return
	}
	file, header, err := r.FormFile("video")
	if err != nil {
		writeErr(w, http.StatusBadRequest, "missing 'video' field")
		return
	}
	defer file.Close()

	ext := filepath.Ext(header.Filename)
	if ext == "" {
		ext = ".mp4"
	}
	storedName := time.Now().Format("20060102-150405") + "-" + randomHex(4) + ext
	storedPath := filepath.Join(uploadsDir, storedName)

	out, err := os.Create(storedPath)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	n, err := io.Copy(out, file)
	out.Close()
	if err != nil {
		_ = os.Remove(storedPath)
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}

	res, err := db.Exec(
		"INSERT INTO videos (name, filename, size_bytes) VALUES (?, ?, ?)",
		header.Filename, storedName, n,
	)
	if err != nil {
		_ = os.Remove(storedPath)
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	id, _ := res.LastInsertId()
	writeJSON(w, http.StatusCreated, Video{
		ID:        id,
		Name:      header.Filename,
		Filename:  storedName,
		SizeBytes: n,
		CreatedAt: time.Now(),
		URL:       "/uploads/" + storedName,
	})
}

func deleteVideoHandler(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad id")
		return
	}
	var filename string
	if err := db.QueryRow("SELECT filename FROM videos WHERE id=?", id).Scan(&filename); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeErr(w, http.StatusNotFound, "not found")
			return
		}
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if _, err := db.Exec("DELETE FROM videos WHERE id=?", id); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = os.Remove(filepath.Join(uploadsDir, filename))
	w.WriteHeader(http.StatusNoContent)
}

// -----------------------------------------------------------------------------
// /api/legacy/devices
// -----------------------------------------------------------------------------

func listDevicesHandler(w http.ResponseWriter, r *http.Request) {
	rows, err := db.Query("SELECT id, name, paired_at, last_seen FROM devices ORDER BY paired_at DESC")
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	out := []Device{}
	for rows.Next() {
		var d Device
		var name sql.NullString
		var ls sql.NullTime
		if err := rows.Scan(&d.ID, &name, &d.PairedAt, &ls); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		if name.Valid {
			d.Name = name.String
		}
		if ls.Valid {
			t := ls.Time
			d.LastSeen = &t
		}
		d.Online = isDeviceOnline(d.ID)
		out = append(out, d)
	}
	writeJSON(w, http.StatusOK, out)
}

// -----------------------------------------------------------------------------
// /api/legacy/devices/{id}/play  and  /start-live
// -----------------------------------------------------------------------------

type playPayload struct {
	VideoID         int64    `json:"video_id"`
	AutoStartLive   bool     `json:"auto_start_live,omitempty"`
	UseOverlay      bool     `json:"use_overlay,omitempty"`
	ProductKeywords []string `json:"product_keywords,omitempty"`
	LiveTitle       string   `json:"live_title,omitempty"`
}

func playOnDeviceHandler(w http.ResponseWriter, r *http.Request) {
	deviceID := r.PathValue("id")
	var body playPayload
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	var filename, name string
	if err := db.QueryRow("SELECT filename, name FROM videos WHERE id=?", body.VideoID).Scan(&filename, &name); err != nil {
		writeErr(w, http.StatusNotFound, "video not found")
		return
	}
	cmd := map[string]any{
		"type":             "play",
		"video_id":         body.VideoID,
		"name":             name,
		"url":              "/uploads/" + filename,
		"auto_start_live":  body.AutoStartLive,
		"use_overlay":      body.UseOverlay,
		"product_keywords": body.ProductKeywords,
		"live_title":       body.LiveTitle,
	}
	if err := sendToDevice(deviceID, cmd); err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "sent"})
}

type startLivePayload struct {
	ProductKeywords []string `json:"product_keywords,omitempty"`
	LiveTitle       string   `json:"live_title,omitempty"`
	UseOverlay      bool     `json:"use_overlay,omitempty"`
}

func startLiveOnDeviceHandler(w http.ResponseWriter, r *http.Request) {
	deviceID := r.PathValue("id")
	var body startLivePayload
	_ = json.NewDecoder(r.Body).Decode(&body)
	cmd := map[string]any{
		"type":             "start_live",
		"product_keywords": body.ProductKeywords,
		"live_title":       body.LiveTitle,
		"use_overlay":      body.UseOverlay,
	}
	if err := sendToDevice(deviceID, cmd); err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "sent"})
}
