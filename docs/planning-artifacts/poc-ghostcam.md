---
title: GhostCam POC — base layer of V3 broadcast stack
status: planning
created: 2026-05-28
---

# GhostCam POC

V3 (the sole active SKU) depends on three layers: **GhostCam** (camera2 video injection) → **autoCaptcha** (auto-solve TikTok captcha) → **our agent** (orchestration). If GhostCam doesn't work on a 2026 Android + Magisk + TikTok stack, the other two layers are meaningless. This POC is the cheapest answer to "is V3 feasible at all?" — ~1-2 days on a real device.

## Goal

Verify that on a rooted Android device with Magisk + Zygisk + LSPosed + GhostCam installed, opening stock TikTok → LIVE → Device camera → Go LIVE produces a broadcast where the **prerecorded MP4** plays as the live stream (not the real camera sensor), visible to a 2nd watching account in native portrait 9:16.

Pass = V3 is real. Fail = path D is dead and we're back to the drawing board.

## Prerequisites

- 1 Android device, willing to wipe + root (recommended: old Pixel 6/7 or a cheap Samsung). NOT a daily driver.
- 1 second Android device or laptop browser logged into a different TikTok account (to verify what viewers actually see).
- 1 prerecorded MP4 — short (30-60s), portrait 9:16, H264 baseline if possible. Anything will do for POC; production-grade encoding is a separate concern.
- Magisk + Zygisk + LSPosed Manager APK files staged on a USB stick or sideloaded.
- GhostCam APK from [github.com/benzitools/GhostCam](https://github.com/benzitools/GhostCam) (latest release: V13.24).

## Install order

1. **Unlock bootloader** on the test device (vendor-specific — Pixel: `fastboot flashing unlock`; Samsung: developer options).
2. **Patch boot.img with Magisk** → flash → device boots with su shell.
3. **Enable Zygisk** in Magisk settings (Zygisk = required for LSPosed hooks).
4. **Install LSPosed** via Magisk module zip → reboot → LSPosed Manager visible in launcher.
5. **Install GhostCam APK** (sideload via `adb install`).
6. **Enable GhostCam in LSPosed Manager** → scope = TikTok (com.zhiliaoapp.musically OR com.ss.android.ugc.trill, whichever is installed). Reboot.
7. **Install TikTok** (stock from Play Store, fresh account recommended — burner).
8. Open GhostCam app → select the test MP4 from internal storage → toggle ON.

## Test procedure

### T1 — sanity: camera replacement works at all
Open the system Camera app (or a Camera Test app). Confirm the preview shows the MP4, not the real sensor. If T1 fails, GhostCam isn't loading — debug LSPosed scope / Zygisk state before continuing.

### T2 — TikTok recognizes injected feed
1. Open TikTok → grant camera permission → swipe to LIVE.
2. Tap Device camera tab.
3. Confirm the preview area (the small camera preview inside TikTok's setup screen) shows the MP4.

### T3 — broadcast actually carries the injected feed to viewers
1. From the setup screen, tap Go LIVE.
2. From the **second account** (other phone / laptop browser), find the live and watch.
3. Confirm:
   - Video shown is the MP4, not real camera input
   - Aspect ratio is **portrait 9:16** (no letterbox)
   - Audio? — leave for follow-up; GhostCam's audio handling is a separate question

### T4 — duration / stability
Let the broadcast run **2-4 hours** unattended (loop the MP4 in GhostCam if it has the option, else use a longer source video). Watch for:
- TikTok force-ends the broadcast ("we detected unusual activity" or similar)
- Account suspension ("บัญชีถูกระงับ" / banner messages)
- Black frames / freezes
- Frame drops visible to viewers

## Pass / Fail criteria

| Outcome | Meaning | Next |
|---|---|---|
| T1 ✓ T2 ✓ T3 ✓ T4 ✓ | V3 is real. Move forward. | Build agent + Mobile Gaming integration |
| T1 ✓ T2 ✓ T3 ✓ T4 ✗ (kicked < 30 min) | TikTok detection exists but only after some signal. May still be viable if we identify + work around the signal. | Investigate kick trigger (frame analysis? audio missing? sensor sanity checks?) |
| T1 ✓ T2 ✓ T3 ✗ | GhostCam works system-wide but TikTok specifically filters injected camera. Path D nearly dead — would need deeper hook. | Try fork with different hook strategy; if no win in 1-2 days, escalate decision |
| T1 ✓ T2 ✗ | TikTok has its own camera path independent of camera2 (uses NDK / low-level access). Path D dead. | Pivot to V1 with letterbox or abandon |
| T1 ✗ | GhostCam broken on test device (Android version / Magisk version mismatch). | Try alternative module (jhangyu/magisk-Camera2-API or fork GhostCam) before declaring failure |

## Open questions to answer DURING the POC

These are things we can't determine from docs — only from running the test:

1. **Programmatic video swap.** Can we change the MP4 GhostCam plays *without* opening the GhostCam UI? (We need this to swap videos per broadcast.)
   - Inspect: `/data/data/<ghostcam-package>/shared_prefs/*.xml` after picking a video — find which key holds the path
   - Test: write that file directly with `su` then trigger TikTok to re-grab camera; does GhostCam pick up the new path?
   - If yes: agent's job is trivial — `echo` + restart TikTok camera
   - If no: need to fork GhostCam and add an Intent receiver or broadcast a `KILL` + relaunch
2. **Audio source during broadcast.** TikTok normally pairs camera + mic. Does it use real mic or does GhostCam intercept audio too? If real mic → broadcast is silent unless we route audio separately.
3. **Account ban window.** Does a fresh account get away with 4h+ broadcasts, or does TikTok flag the IP / device fingerprint quickly? This bleeds into the autoCaptcha + account_pool work.
4. **Compatibility surface.** What Android versions does GhostCam V13.24 actually work on? README doesn't say. Test on 1-2 devices spanning Android 13/14/15.

## What to record during the POC

Make a short note per test (T1-T4) with:
- Device model + Android version + Magisk version + GhostCam version
- Pass/fail per checkpoint
- Any error messages from TikTok / system
- Screenshots of broadcaster phone and viewer phone (side-by-side proves "what we showed = what viewer saw")
- Findings on the open questions above

A 1-page write-up appended to this doc is enough — don't over-engineer the report.

## Not in scope of this POC

- autoCaptcha integration — separate POC after this passes
- our agent — same
- account rotation / data-dir swap — separate
- Production-grade video encoding pipeline
- Server changes — current `start_live` envelope contract is enough; agent talks to GhostCam, our existing mobile app drives TikTok UI
