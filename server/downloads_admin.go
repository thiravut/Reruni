package main

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// releaseSpec maps each user-visible artifact to the prefix used on disk and
// the app_settings key that pins which file is "active". Kept in one place so
// the admin endpoints and the public manifest stay in sync.
type releaseSpec struct {
	Label      string
	Prefix     string
	SettingKey string
}

var releaseRegistry = map[string]releaseSpec{
	"reruni_apk":    {"Reruni Controller", RerunAPKPrefix, SettingRerunAPK},
	"tiktok_reruni": {"TikTok (Reruni bundle)", TikTokRerunPrefix, SettingTikTokReruni},
}

// releaseRegistryOrder controls the order returned to the backoffice UI. Map
// iteration isn't stable in Go, so we render through this slice.
var releaseRegistryOrder = []string{"reruni_apk", "tiktok_reruni"}

type releaseInfo struct {
	Key        string        `json:"key"`
	Label      string        `json:"label"`
	Prefix     string        `json:"prefix"`
	SettingKey string        `json:"setting_key"`
	Active     string        `json:"active"`           // filename currently served (resolved)
	Pinned     string        `json:"pinned,omitempty"` // raw setting value; empty means auto-latest
	Files      []releaseFile `json:"files"`
}

// adminListReleasesHandler — GET /api/admin/downloads/releases
//
// Returns every versioned artifact with all candidate files on disk plus
// the currently-active filename. The backoffice uses this to render the
// version picker.
func adminListReleasesHandler(w http.ResponseWriter, _ *http.Request) {
	out := []releaseInfo{}
	for _, key := range releaseRegistryOrder {
		spec := releaseRegistry[key]
		files := scanReleases(downloadsDir, spec.Prefix)
		active, _ := resolveActiveRelease(downloadsDir, spec.Prefix, spec.SettingKey)
		out = append(out, releaseInfo{
			Key:        key,
			Label:      spec.Label,
			Prefix:     spec.Prefix,
			SettingKey: spec.SettingKey,
			Active:     active.Filename,
			Pinned:     getSetting(spec.SettingKey, ""),
			Files:      files,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

// adminSetActiveReleaseHandler — POST /api/admin/downloads/releases/{key}
//
// Pins which filename is active for an artifact. Empty filename clears the
// pin and falls back to auto-latest. Validates that the file matches the
// expected prefix and actually exists on disk so a typo can't break the
// public manifest.
func adminSetActiveReleaseHandler(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	spec, ok := releaseRegistry[key]
	if !ok {
		writeError(w, http.StatusNotFound, "UNKNOWN_RELEASE", "release key not found")
		return
	}

	var body struct {
		Filename string `json:"filename"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", err.Error())
		return
	}
	filename := strings.TrimSpace(body.Filename)

	if filename != "" {
		if !strings.HasPrefix(filename, spec.Prefix) || !strings.HasSuffix(filename, APKSuffix) {
			writeError(w, http.StatusBadRequest, "INVALID_INPUT",
				"filename does not match expected pattern")
			return
		}
		// Block path-traversal — filename must be a single segment.
		if strings.ContainsAny(filename, `/\`) {
			writeError(w, http.StatusBadRequest, "INVALID_INPUT", "filename must not contain path separators")
			return
		}
		if _, err := os.Stat(filepath.Join(downloadsDir, filename)); err != nil {
			writeError(w, http.StatusNotFound, "FILE_NOT_FOUND", "file not on server")
			return
		}
	}

	if err := setSetting(spec.SettingKey, filename); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}
