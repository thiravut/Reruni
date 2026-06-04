package main

import (
	"database/sql"
	"errors"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

// downloadsDir is where operator-managed binaries live (Reruni APK, mirrored
// GhostCam .zip, etc). Set alongside uploadsDir via the same flag scheme so
// systemd can point at /home/reruni/rerun-data/downloads.
var downloadsDir string

// Versioned APK filenames follow the pattern `<prefix><semver>.apk` — e.g.
// "reruni-v0.1.1.apk". The active filename per artifact is stored in
// app_settings so admins can pin a specific version from the backoffice
// without redeploying the server. When the pin is missing or points at a
// file that's no longer on disk, we fall back to the highest semver-sorted
// file matching the prefix.
const (
	RerunAPKPrefix    = "reruni-v"
	TikTokRerunPrefix = "tiktok-reruni-v"
	APKSuffix         = ".apk"

	GhostCamFilename    = "ghostcam-magisk.zip"
	AutoCaptchaFilename = "autocaptcha-xposed.apk"

	SettingRerunAPK     = "release.reruni_apk.filename"
	SettingTikTokReruni = "release.tiktok_reruni.filename"
)

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

// releaseFile is one candidate APK for a versioned artifact. Returned by
// scanReleases and used by both the public manifest and the admin picker UI.
type releaseFile struct {
	Filename  string    `json:"filename"`
	Version   string    `json:"version"`
	SizeBytes int64     `json:"size_bytes"`
	ModTime   time.Time `json:"mod_time"`
}

// scanReleases lists every file in dir whose name is "<prefix><version>.apk",
// sorted with the highest semver version first.
func scanReleases(dir, prefix string) []releaseFile {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	out := []releaseFile{}
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		if !strings.HasPrefix(name, prefix) || !strings.HasSuffix(name, APKSuffix) {
			continue
		}
		version := strings.TrimSuffix(strings.TrimPrefix(name, prefix), APKSuffix)
		info, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, releaseFile{
			Filename:  name,
			Version:   version,
			SizeBytes: info.Size(),
			ModTime:   info.ModTime(),
		})
	}
	sort.Slice(out, func(i, j int) bool {
		return compareSemver(out[i].Version, out[j].Version) > 0
	})
	return out
}

// compareSemver returns >0 if a > b, 0 if equal, <0 if a < b. Numeric chunks
// sort by integer value; non-numeric chunks ("rc1", "beta") fall back to
// lexicographic comparison so we never panic on a weird release tag.
func compareSemver(a, b string) int {
	pa := strings.Split(a, ".")
	pb := strings.Split(b, ".")
	n := len(pa)
	if len(pb) > n {
		n = len(pb)
	}
	for i := 0; i < n; i++ {
		var x, y string
		if i < len(pa) {
			x = pa[i]
		}
		if i < len(pb) {
			y = pb[i]
		}
		xi, xErr := strconv.Atoi(x)
		yi, yErr := strconv.Atoi(y)
		if xErr == nil && yErr == nil {
			if xi != yi {
				if xi > yi {
					return 1
				}
				return -1
			}
			continue
		}
		if x != y {
			if x > y {
				return 1
			}
			return -1
		}
	}
	return 0
}

// resolveActiveRelease picks the file currently exposed by /downloads/<file>:
// the pinned setting if present and on disk, otherwise the highest-version
// scan result. Returns (zero, false) when no file matches the prefix at all.
func resolveActiveRelease(dir, prefix, settingKey string) (releaseFile, bool) {
	avail := scanReleases(dir, prefix)
	if len(avail) == 0 {
		return releaseFile{}, false
	}
	if pinned := getSetting(settingKey, ""); pinned != "" {
		for _, r := range avail {
			if r.Filename == pinned {
				return r, true
			}
		}
	}
	return avail[0], true
}

// downloadsManifestHandler returns the curated list of setup files. Gated
// behind requireActiveSubscription at the route level — only paying
// customers see the list (and only they can fetch the underlying files
// from /downloads/*).
//
// The two versioned APKs (Reruni Controller + TikTok bundle) are resolved
// dynamically via resolveActiveRelease so ops can ship a new version by
// uploading a file and flipping a setting in the backoffice. Other artifacts
// (GhostCam zip, autoCaptcha module) still use fixed filenames.
func downloadsManifestHandler(w http.ResponseWriter, _ *http.Request) {
	items := []downloadItem{}

	if rerun, ok := resolveActiveRelease(downloadsDir, RerunAPKPrefix, SettingRerunAPK); ok {
		items = append(items, downloadItem{
			Key:       "reruni_apk",
			Label:     "Reruni Controller",
			Version:   rerun.Version,
			SizeBytes: rerun.SizeBytes,
			URL:       "/downloads/" + rerun.Filename,
			Hosted:    true,
			Required:  true,
		})
	} else {
		items = append(items, downloadItem{
			Key:      "reruni_apk",
			Label:    "Reruni Controller",
			URL:      "/downloads/",
			Hosted:   false,
			Required: true,
		})
	}

	if tiktok, ok := resolveActiveRelease(downloadsDir, TikTokRerunPrefix, SettingTikTokReruni); ok {
		items = append(items, downloadItem{
			Key:       "tiktok_reruni",
			Label:     "TikTok (Reruni bundle)",
			Version:   tiktok.Version,
			SizeBytes: tiktok.SizeBytes,
			URL:       "/downloads/" + tiktok.Filename,
			Hosted:    true,
			Required:  true,
		})
	} else {
		items = append(items, downloadItem{
			Key:      "tiktok_reruni",
			Label:    "TikTok (Reruni bundle)",
			URL:      "/downloads/",
			Hosted:   false,
			Required: true,
		})
	}

	items = append(items,
		downloadItem{
			Key:      "ghostcam",
			Label:    "GhostCam Magisk Module",
			URL:      "/downloads/" + GhostCamFilename,
			Hosted:   true,
			Required: true,
		},
		downloadItem{
			Key:         "magisk",
			Label:       "Magisk (latest stable)",
			URL:         "https://github.com/topjohnwu/Magisk/releases/latest",
			UpstreamURL: "https://github.com/topjohnwu/Magisk/releases/latest",
			Hosted:      false,
			Required:    true,
		},
		downloadItem{
			Key:         "lsposed",
			Label:       "LSPosed (Zygisk)",
			URL:         "https://github.com/JingMatrix/LSPosed/releases/latest",
			UpstreamURL: "https://github.com/JingMatrix/LSPosed/releases/latest",
			Hosted:      false,
			Required:    false,
		},
		downloadItem{
			Key:      "autocaptcha",
			Label:    "autoCaptcha Xposed Module",
			URL:      "/downloads/" + AutoCaptchaFilename,
			Hosted:   true,
			Required: false,
		},
	)

	// Fill size for hosted non-versioned items; flip Hosted=false when missing
	// so the portal can render "ยังไม่พร้อม / coming soon".
	for i := range items {
		if !items[i].Hosted || items[i].SizeBytes > 0 {
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
// Resolves the same active reruni_apk file the manifest advertises so there's
// a single source of truth on disk. Logs each successful download for ops.
func gatedCompanionAPKHandler(w http.ResponseWriter, r *http.Request) {
	u := userFromCtx(r)

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

	rerun, ok := resolveActiveRelease(downloadsDir, RerunAPKPrefix, SettingRerunAPK)
	if !ok {
		writeError(w, http.StatusNotFound, "APK_NOT_AVAILABLE",
			"APK ยังไม่ได้อัพโหลด — กรุณาติดต่อทีมงาน")
		return
	}
	apkPath := filepath.Join(downloadsDir, rerun.Filename)

	advanceOnboardingIfAt(u.ID, StepPayment, StepInstallAPK)

	log.Printf("downloads: %s → user=%d size=%d", rerun.Filename, u.ID, rerun.SizeBytes)

	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="`+rerun.Filename+`"`)
	http.ServeFile(w, r, apkPath)
}
