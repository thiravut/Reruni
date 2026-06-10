# Reruni — repo guide for Claude

TikTok LIVE commerce ops platform. Operator controls broadcasts remotely via web portal → Go server → Reruni controller (Android app) → patched TikTok (LSPosed vcam module).

## What Reruni does (feature map)

### Highlight features (จุดเด่น)

- **Clean Option G audio** — bypasses TikTok's voice DSP that mangles music. PC/backend pre-encodes AAC and substitutes at TikTok's encoder OUTPUT buffer via PLT hook on `aacEncEncode`. Viewers hear studio-quality audio; competitors stuck with mic-pickup distortion.
- **Backend-driven multi-tenant streaming** — `wss://api.reruni.com/ws/aac` serves AAC per-LIVE-session. Customers don't need a PC. Server can route different sessions to different edge boxes (URL changes per session, vcam reads from broadcast endpoint).
- **Cross-process kill switch** — Reruni force-kills TikTok before each LIVE via in-process `BroadcastReceiver` → `Process.killProcess(myPid)`. Solves Android 16's swipe-suspend partial-state-corruption that ranges encoder firing from 50 Hz down to ~2 Hz.
- **Video MP4 hijack via Camera2 + GL/OES** — TikTok's broadcast captures from the camera surface; we replace the surface content with a decoded MP4 feed, preserving native frame rate / encoder quality without re-encoding overhead.
- **Captcha auto-solve** — YOLO TFLite vision model solves TikTok's slider captcha locally on-device, no operator needed.
- **JSON automation DSL** — entire LIVE-setup / end-LIVE flow lives in JSON scripts (`script_shoppable_vcam_v1.json`, `script_end_live_v1.json`, etc.). Conditional branches (`skip_if_no_keywords`), region-bounded taps (`bounds`), text+contentDescription matching with retries — no Kotlin recompile to tweak a tap.
- **Per-LIVE-session WS endpoint via Intent broadcast** — works around Android 11+ scoped storage. Reruni broadcasts `com.rerun.vcam.SET_WS_ENDPOINT` → receiver inside TikTok's UID writes the override file → Mp4GWsClient reads it on connect.
- **Say-Hi keepalive bot** — every 20s the autopilot taps TikTok's "Say hi" greet button (best-effort, ignore_failure). Defeats TikTok's non-interactive-host visibility throttle.
- **A/V resync at Go-LIVE tap** — broadcasts `AV_RESYNC` right after the autopilot taps Go LIVE so audio (PC stream reset to MP4 frame 0) and video (Mp4FrameProducer rewind) both start their LIVE-visible playback from MP4 t=0 simultaneously.
- **Auto-end LIVE on loop count** — operator picks "play N times", autopilot tracks Mp4FrameProducer loops, fires endLive when target reached.

### Module-by-module

**vcam LSPosed module** (`mobile/vcam/`) — loaded into TikTok's process by LSPatch:

| File | What it does |
|---|---|
| `HookEntry.kt` | Module entry; installs every hook below |
| `Camera2Hook.kt` + `Mp4FrameProducer.kt` + `GlFrameRenderer.kt` | Camera2 surface hijack → MP4 video feed |
| `audio_hook.c` + `NativeAudioHook.kt` | xhook PLT hooks for `aacEncEncode`, `AudioRecord::read`, etc. AAC ring + drift correction + A/V resync |
| `Mp4GWsClient.kt` | WebSocket client → backend `/ws/aac`. Concurrency via `sessionId` token to prevent leaked listeners during rapid stop/start |
| `Mp4AudioProducer.kt` | PCM injection thread (required even in Option G mode — encoder needs "audio activity" to fire). `runId` token for thread liveness |
| `TtRtcAudioHook.kt` | Forces WebRTC APM / strange-voice / noise-suppress switches OFF on the AudioDeviceModule |
| `TtRtcEncoderHook.kt` | Rewrites Lyrax encoder config to AAC-LC 256kbps 48kHz stereo, aacEncType=0 (libfdk-aac path) |
| `TtAudioSceneHook.kt` | Pins audio scene to KARAOKE (music-friendly) instead of CHATROOM (voice DSP heavy) |
| `VcamKillSwitch.kt` | Receives `com.rerun.vcam.KILL_SELF` → `Process.killProcess(myPid)` |
| `VcamEndpointReceiver.kt` | Receives `com.rerun.vcam.SET_WS_ENDPOINT` → writes endpoint file inside TikTok's UID |
| `VcamAvResyncReceiver.kt` | Receives `com.rerun.vcam.AV_RESYNC` → triggers PC reset + video rewind |
| `VcamBridge.kt` | Resolves staged MP4 via Reruni's ContentProvider |

**Reruni controller app** (`mobile/app/`):

| File | What it does |
|---|---|
| `Autopilot.kt` | Orchestrates start_live / end_live flows; manages keepalive + coachmark dismisser jobs |
| `script/ScriptRunner.kt` + `script/ScriptContext.kt` | JSON DSL interpreter; ops: tap_by_text (with bounds filter), wait_for_any, swipe, swipe_to_find_tab, press_back, launch_tiktok (preserve_task), broadcast_av_resync, skip_if_no_keywords/label, auto_pin_products, remove_pre_selected_products, search_in_picker_first_keyword, set_live_title_if_provided |
| `WsClient.kt` + `WsBus.kt` | `/ws/device` connection → `PlayCommand` flow bus → MainActivity → Autopilot |
| `TikTokAutopilotService.kt` | Android AccessibilityService — gives Autopilot access to TikTok's UI tree |
| `CaptchaSolver.kt` | YOLO TFLite slider-captcha solver (auto-solves blocking modals) |
| `VcamContentProvider.kt` | Serves the staged MP4 file to the vcam module via Binder |
| `VideoDownloader.kt` | Fetches server-stitched MP4 from `/uploads/`, stages locally |
| `OverlayService.kt` + `PlayerActivity.kt` | Smart overlay (V1 path) — player on top of TikTok |
| `ConnectionService.kt` | Foreground service keeping WS alive |
| `OnboardingActivity.kt` + `SettingsActivity.kt` | Pairing wizard + per-user prefs (product keywords, server URL, mode overrides) |
| `AppPrefs.kt` | SharedPreferences wrapper |

**Backend Go server** (`server/`):

| File | What it does |
|---|---|
| `main.go` | HTTP router + bootstrap |
| `ws.go` | `/ws/device` (mobile) + `/ws/portal` (browser) + envelope routing |
| `aac_ws.go` | `/ws/aac` — Option G AAC streamer. ffmpeg → ADTS parser → real-time WS pacing. Handles client "reset" message |
| `handlers.go` | REST: video upload, live start/stop, device pairing, account flows |
| `ffmpeg.go` | Concat multiple source videos into a single broadcast MP4; synthesize silent audio for video-only inputs |
| `auth.go` + `accounts.go` | Signup, login, password reset, user CRUD |
| `billing.go` | Stripe integration — subscriptions, checkout, invoices, webhooks |
| `onboarding.go` | First-time wizard state machine (device quota, plan picker) |
| `downloads.go` + `downloads_admin.go` | Gated APK downloads + admin-picked active version per release channel |
| `scripts.go` | Serves automation script JSON files (hot-update without APK rebuild) |
| `reminders.go` | Email reminders for trial expiry, payment issues |
| `db.go` + `migrations/` | Postgres 16 / SQLite; goose migrations with timestamp prefixes |

**Portal SPA** (`portal/`) — React/Vite + bun:

- Live dashboard, video library (with thumbnails + click-to-play)
- Device management (pair, rename, quota)
- Billing (subscription summary, payment history, plan changes)
- Onboarding wizard
- Setup guide (`คู่มือการติดตั้ง`) gated behind active subscription
- Downloads (APK manifest, version picker)
- Auth flows

**Backoffice** (`backoffice/`) — admin SPA:

- User management, subscription audit, manual ops
- Release channel management (pick "active" version per channel)

**Build & ops** (`tools/`, `deploy/`):

- `tools/dev-install.sh` — A15 dev iteration (no version bump)
- `tools/release.sh` — versioned customer APK build + optional R2 upload
- `tools/aac-server.py` — PC-side AAC streamer fallback (mirrors `aac_ws.go` for offline dev)
- `tools/test-audio-rate.sh` — operator helpers (set ws-inject mode, tail logs)
- `deploy/deploy.sh` + `deploy/Caddyfile` + `deploy/systemd/` — Contabo prod deploy
- `.github/workflows/deploy.yml` — push-to-main auto-deploy

### Subscription model

Single tier — **flat per-device pricing (฿299/device/month)**. Onboarding wizard enforces quota. Stripe handles all payments. Setup guide gated behind active subscription. See `project_tier_model_collapsed.md` memory for the pivot history.

### MP4 video pipeline

Operators upload MP4(s) to portal → backend stitches via ffmpeg concat → mobile downloads from `/uploads/<filename>` → stages into `VcamContentProvider` → vcam module reads via Binder → `Mp4FrameProducer` decodes + renders to TikTok's camera Surface via EGL/OES → TikTok's H.264 encoder captures the surface and broadcasts. Pure video stays in TikTok's hands; we just feed it different pixels.

### Audio pipeline (Option G)

```
Server-side (Go):
  uploaded MP4 → ffmpeg → ADTS-framed AAC AU → /ws/aac WebSocket → mobile

Mobile (vcam):
  Mp4GWsClient receives AAC frames →
  push to native AAC ring (256 slots, drop-oldest) →
  hooked_aacEncEncode (xhook PLT hook on libvolcenginertc.so) fires →
    real_aacEncEncode runs (encodes TikTok's mic-captured PCM, discarded) →
    pop next frame from AAC ring →
    memcpy(outBuf, our_aac, size) + update numOutBytes →
  TikTok's transport sends OUR bytes downstream

Concurrent paths:
  - PCM injection into AudioRecord (Mp4AudioProducer + obtainBuffer hook):
    "wakes" TikTok's encoder so aacEncEncode fires (REQUIRED, not redundant)
  - amp=0 silences the PCM so viewer only hears Option G substituted audio
```

### Override files (operator-tunable, no rebuild needed)

Located at `/sdcard/Android/data/com.zhiliaoapp.musically/files/`:

| File | Value | Purpose |
|---|---|---|
| `vcam_audio_mode.txt` | `ws_inject` / `speaker` / `mp4` / `tone` / `rtmp_inject` | Audio path selector. Production = `ws_inject` |
| `vcam_ws_endpoint.txt` | `wss://...` | Per-session AAC streamer URL. Auto-written by `VcamEndpointReceiver` on Reruni broadcast |
| `vcam_audio_rate.txt` | `48000` | Force PCM resample to match encoder rate (required for Option G) |
| `vcam_audio_amp.txt` | `0` | PCM amp scale (required = 0 in Option G so PCM is silent, AAC substitution is the deliverable) |
| `vcam_encoder_rewrite_off.txt` | (exists / not) | Opt-out of TtRtcEncoderHook for A/B testing |

## Build & install

| Want | Command | Output |
|---|---|---|
| Iterate on device (A15) | `./tools/dev-install.sh` | `/tmp/vcam-dev/` (overwritten each run) |
| Module only | `./tools/dev-install.sh --module-only` | same, faster |
| Controller only | `./tools/dev-install.sh --skip-tiktok` | same |
| **Customer-facing release** | `./tools/release.sh <version>` | `releases/v<version>/` |

**Don't run `release.sh` unprompted.** Sales/customer APKs ship from there — version bumps and uploads are operator decisions. Pond will ask explicitly when a release is needed.

Test device: Samsung A15 5G, ADB serial `R5CX51F83ZR`. Always pass `-s R5CX51F83ZR` to adb (Pond often has multiple devices).

## Deploy

Push to `main` triggers `.github/workflows/deploy.yml` → Go server rebuild + restart on Contabo. Server URL: `api.reruni.com`. APK changes don't auto-deploy — they install via dev-install or ship via release.

## Architecture quick map

```
[Web portal] → [Go server /ws/portal] → [Reruni /ws/device] → [Autopilot + ScriptRunner]
                                                                    ↓
                                                       [VcamBridge ContentProvider]
                                                                    ↓
                                              [Patched TikTok (LSPatch)]
                                                                    ↓
                                              [LSPosed vcam module hooks]
                                                  - Camera2Hook → Mp4FrameProducer (video)
                                                  - audio_hook.c → aacEncEncode PLT hook (audio)
                                                  - Mp4GWsClient → wss://api.reruni.com/ws/aac (Option G)
```

**Option G** is the production audio path: PC/backend pre-encodes AAC → mobile substitutes at TikTok's encoder output buffer via PLT hook. Bypasses TikTok's voice DSP that mangles music. See `docs/vcam-findings/option-g-aac-inject.md`.

## Cross-process IPC patterns

Reruni (controller, normal Android UID) and patched TikTok (different UID, hosts vcam LSPosed module) talk via **dynamic BroadcastReceiver pattern**. Scoped storage blocks cross-app file writes since Android 11. Three receivers live inside TikTok's process:

| Action | Sent by | Receiver does |
|---|---|---|
| `com.rerun.vcam.KILL_SELF` | Autopilot.start before each LIVE | `Process.killProcess(myPid)` — force-fresh process |
| `com.rerun.vcam.SET_WS_ENDPOINT` | Autopilot pre-kill + post-launch retries | Writes `vcam_ws_endpoint.txt` (path inside TikTok's external dir, only TikTok's UID can write) |
| `com.rerun.vcam.AV_RESYNC` | ScriptRunner right after Go LIVE tap | `Mp4GWsClient.sendReset()` + `Mp4FrameProducer.requestRestart()` — both audio & video rewind to MP4 t=0 |

All registered with `Context.RECEIVER_EXPORTED` (Android 13+ requirement for cross-UID). All `setPackage(tiktokPkg)` to scope delivery.

## DO NOT do

1. **Java-hook codec-x / pusher / ttlivestreamer encoder classes** — `nativeOnEncodedFrame`, `nativeEncoded`, etc. Even diagnostic-only hooks kill TikTok's whole codec init → aacEncEncode PLT hook stops firing → no audio. Burned twice (EncoderOutputRecon, VideoEncoderRecon) — both kept in tree as DISABLED references.
2. **"Optimise" by disabling PCM injection in ws_inject mode.** PCM-into-AudioRecord is the trigger that wakes TikTok's encoder; AAC substitution is the deliverable. Removing PCM = encoder never fires = silent broadcast. The dual injection looks redundant but isn't.
3. **Write to `/sdcard/Android/data/com.zhiliaoapp.musically/files/` from Reruni's UID.** Scoped storage returns ENOENT. Always go via the SET_WS_ENDPOINT broadcast pattern.
4. **Use `FLAG_ACTIVITY_CLEAR_TASK` in launchIntent for end-live flows.** It forces TikTok back to Home and drops the broadcast. Use `preserve_task: true` on `launch_tiktok` op.

## File layout

- `server/` — Go HTTP + WS server (gorilla/websocket). Key files: `main.go` (routes), `handlers.go` (REST), `ws.go` (device/portal WS), `aac_ws.go` (Option G AAC streamer).
- `mobile/app/` — Reruni controller Android app.
  - `Autopilot.kt` — orchestrates LIVE flow (start_live / end_live), invokes ScriptRunner.
  - `WsClient.kt` — talks to `/ws/device`, dispatches start_live → PlayCommand → Autopilot.
  - `script/ScriptRunner.kt` + `script/ScriptContext.kt` — JSON automation DSL.
  - `res/raw/script_*.json` — flow scripts (shoppable_vcam, end_live, etc.).
- `mobile/vcam/` — LSPosed module that hooks TikTok.
  - `HookEntry.kt` — entry, installs all hooks.
  - `cpp/audio_hook.c` — PLT hook on `aacEncEncode` + AAC ring + A/V sync logic.
  - `Mp4FrameProducer.kt` — feeds MP4 video frames to Camera2 surface.
  - `Mp4GWsClient.kt` — connects to `/ws/aac`, drains AAC frames into native ring.
- `tools/` — release.sh, dev-install.sh, aac-server.py (PC dev fallback for Option G).
- `releases/` — versioned customer APKs. Append-only; don't edit existing versions.

## Debug log filters

```bash
# Endpoint flow
adb -s R5CX51F83ZR logcat -d | grep -E "VcamEndpointReceiver|broadcastVcamEndpoint|Mp4GWsClient: connecting"

# A/V sync
adb -s R5CX51F83ZR logcat -d | grep -E "AAC ring resync|first-fire|onFirstEncoderFire|sent reset|restart requested|AV_RESYNC"

# Steady-state encoder
adb -s R5CX51F83ZR logcat -d | grep -E "AAC SUBSTITUTE|AAC underrun|received=.*ring="

# Process kill flow
adb -s R5CX51F83ZR logcat -d | grep -E "KILL_SELF|VcamKillSwitch"

# Autopilot script execution
adb -s R5CX51F83ZR logcat -d | grep -E "ScriptRunner|Autopilot:"
```

## Active known issues (operator feedback, in priority order)

1. **A/V sync at LIVE start** — video plays before audio. Mitigation in progress via Go-LIVE-tap broadcast_av_resync. Still tuning.
2. **Manual end-live not synced to portal** — when operator taps End in TikTok directly (not via portal), live_sessions row on server stays open. Needs accessibility-driven detection of "Live ended" screen → fire `live_ended` envelope.
3. **No state check before start_live** — Autopilot runs script blindly from any TikTok state. Should detect current screen + navigate appropriately (or skip if already LIVE).

## Conventions

- Commits: lowercase prefix (`vcam:`, `mobile:`, `server:`), em-dash separator, terse subject. Bodies are welcome and useful for context.
- Push to `main` directly is gated by `Git Push to Default Branch` policy — Pond may need to approve or push from their terminal.
- Thai + English mixed in user-facing strings is fine. Logs should be English.
- No `kill -9` style shortcuts; investigate root causes (e.g. don't just drain ring to mask drift — find why drift exists).
