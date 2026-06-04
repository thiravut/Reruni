# Reruni release notes

Customer downloads are hosted on Cloudflare R2. The build pipeline is
`tools/release.sh` — it builds, patches TikTok with the vcam LSPosed module,
renames artifacts to the customer-facing names, writes a manifest, and
optionally uploads to R2. Run `./tools/release.sh <version> --upload` to
publish.

Versioned snapshots live at `s3://<bucket>/v<version>/`. The guide page reads
`latest/manifest.json` for the "current stable" pointer.

Build-only (no upload) writes the same set into `releases/v<version>/` for
local verification; that directory is gitignored.

## v0.1.1 — 2026-06-04

Audio injection lands in V1 with known distortion. Operators can now run
LIVE with the staged MP4's soundtrack instead of mic input, but the
viewer-side audio has a "blown-speaker" colouration that we couldn't
remove without breaking A/V sync.

**What's new**

- `obtainBuffer[ts]` PLT substitution drains MP4 PCM into TikTok's
  broadcast AudioRecord buffer (Camera2 + Audio in one bundle).
- `AudioLoopbackPlayer` + `AudioLoopbackReceiver` in the Reruni controller
  for single-device acoustic loopback fallback (multi-device rooms have
  cross-contamination and should stick to the direct injection path).
- VcamStageReceiver auto-starts loopback when staging; pass `--ez loopback
  false` from the adb broadcast to opt out.

**Known limits**

- Audio sounds distorted on the viewer side ("blown speaker"). Phase 3
  reverse-engineering ran out of attack surface on `libvolcenginertc.so`
  exported symbols; root cause sits in either Samsung's HAL DSP or a
  non-PLT TikTok core processor. Full diagnosis trail and V2 roadmap in
  `docs/vcam-findings/phase3-audio-injection-deferred.md`.
- A/V sync is preserved: the encoder + content-config rewrites that
  produced cleaner audio also caused progressive drift, so V1 keeps
  TikTok's default encoder settings.
- Same Samsung A15 / TikTok musically 45.3.2 verification scope as v0.1.0.

## v0.1.0 — TBD

First customer-installable build.

**Artifacts**

- `tiktok-reruni-v0.1.0.apk` — TikTok 45.3.2 patched with vcam (Lite/BYOD
  tier — no root, no Magisk). Installs over a clean device after the
  upstream TikTok is removed.
- `reruni-v0.1.0.apk` — Reruni controller app. Pairs with the patched
  TikTok via the `com.rerun.tiktokrerun.vcam` ContentProvider.

**What works**

- Camera2 preview is replaced with a looping MP4 staged through the
  controller's ContentProvider; both rear and selfie cameras render
  upright (auto-mirror for selfie).
- Verified on Samsung A15 5G running Android 16, TikTok musically 45.3.2.

**Known scope limits**

- Single TikTok base version (45.3.2). Other versions need re-test.
- No `com.ss.android.ugc.trill` (SEA variant) verification yet.
- Aspect-correct edge fit not done — minor crop on portrait video.
- Audio is not injected (preview path only; LIVE audio is a separate hook).
