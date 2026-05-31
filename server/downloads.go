package main

import (
	"errors"
	"database/sql"
	"log"
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

// gatedCompanionAPKHandler — GET /api/downloads/companion-apk
//
// Token-gated APK distribution for V1 launch onboarding (PRD §3.12 step 6):
// only customers with an active subscription can download the Companion APK.
//
// Resolves the APK at the same path the public manifest advertises
// (reruni-companion.apk under downloadsDir) so there's a single source of
// truth on disk. Logs each successful download for ops visibility.
func gatedCompanionAPKHandler(w http.ResponseWriter, r *http.Request) {
	u := userFromCtx(r)

	// Subscription gate — must be active. requireActiveSubscription does
	// the same check at middleware level for most routes, but we keep the
	// guard here so the response can carry a richer JSON error body.
	sub, err := getSubscriptionByUser(u.ID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeError(w, http.StatusPaymentRequired, "NO_SUBSCRIPTION",
				"ต้องสมัครสมาชิกก่อนถึงดาวน์โหลด APK ได้")
			return
		}
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}
	if sub.Status != "active" {
		writeError(w, http.StatusPaymentRequired, "SUBSCRIPTION_INACTIVE",
			"subscription ยังไม่ active — ตรวจสอบสถานะการชำระเงิน")
		return
	}

	apkPath := filepath.Join(downloadsDir, "reruni-companion.apk")
	st, err := os.Stat(apkPath)
	if err != nil {
		writeError(w, http.StatusNotFound, "APK_NOT_AVAILABLE",
			"APK ยังไม่ได้อัพโหลด — กรุณาติดต่อทีมงาน")
		return
	}

	// Onboarding hook — issuing the APK download is a strong signal the
	// user has reached the install step.
	advanceOnboardingIfAt(u.ID, StepPayment, StepInstallAPK)

	log.Printf("downloads: companion-apk → user=%d size=%d", u.ID, st.Size())

	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="reruni-companion.apk"`)
	http.ServeFile(w, r, apkPath)
}
