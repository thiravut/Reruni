package main

import (
	"net/http"
	"os"
	"path/filepath"
)

// downloadsDir is where operator-managed binaries live (Reruni APK, mirrored
// GhostCam .zip, etc). Set alongside uploadsDir via the same flag scheme so
// systemd can point at /home/reruni/rerun-data/downloads.
var downloadsDir string

// downloadItem describes one curated artifact in the setup guide. URL is
// either a same-origin path served by downloadsFileHandler or an upstream
// GitHub release link.
type downloadItem struct {
	Key         string `json:"key"`
	Label       string `json:"label"`
	Version     string `json:"version,omitempty"`
	SizeBytes   int64  `json:"size_bytes,omitempty"`
	URL         string `json:"url"`
	UpstreamURL string `json:"upstream_url,omitempty"`
	Hosted      bool   `json:"hosted"`
	Required    bool   `json:"required"`
}

// downloadsManifestHandler returns the curated list of setup files. Public
// endpoint — operators don't need to sign in to read the setup guide.
//
// File metadata (size/version) is filled in on demand by os.Stat so we don't
// drift when a new APK is uploaded. Missing local files surface as Hosted=false
// with the URL still set; the portal renders them as "ยังไม่พร้อม / coming soon".
func downloadsManifestHandler(w http.ResponseWriter, _ *http.Request) {
	items := []downloadItem{
		{
			Key:      "reruni_apk",
			Label:    "Reruni Companion APK",
			URL:      "/downloads/reruni-companion.apk",
			Hosted:   true,
			Required: true,
		},
		{
			Key:      "ghostcam",
			Label:    "GhostCam Magisk Module",
			URL:      "/downloads/ghostcam-magisk.zip",
			Hosted:   true,
			Required: true,
		},
		{
			Key:         "magisk",
			Label:       "Magisk (latest stable)",
			URL:         "https://github.com/topjohnwu/Magisk/releases/latest",
			UpstreamURL: "https://github.com/topjohnwu/Magisk/releases/latest",
			Hosted:      false,
			Required:    true,
		},
		{
			Key:         "lsposed",
			Label:       "LSPosed (Zygisk)",
			URL:         "https://github.com/JingMatrix/LSPosed/releases/latest",
			UpstreamURL: "https://github.com/JingMatrix/LSPosed/releases/latest",
			Hosted:      false,
			Required:    false,
		},
		{
			Key:      "autocaptcha",
			Label:    "autoCaptcha Xposed Module",
			URL:      "/downloads/autocaptcha-xposed.apk",
			Hosted:   true,
			Required: false,
		},
	}

	// Fill in size + Hosted=false when the file isn't actually on disk yet
	// so operators can publish the page even before APKs are uploaded.
	for i := range items {
		if !items[i].Hosted {
			continue
		}
		base := filepath.Base(items[i].URL)
		path := filepath.Join(downloadsDir, base)
		st, err := os.Stat(path)
		if err != nil {
			items[i].Hosted = false
			continue
		}
		items[i].SizeBytes = st.Size()
	}

	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}
