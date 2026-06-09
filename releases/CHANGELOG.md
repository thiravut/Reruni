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

## v0.1.2 — 2026-06-08

Audio path pivots to speaker-loopback after v0.1.1's PCM injection turned
out to be unfixable on this device class. Viewer-side audio is now clean
for single-device LIVE — the cost is multi-device acoustic cross-talk,
which v0.1.x ships with documented and accepted.

**What's new**

- `Mp4AudioProducer` defaults to `speaker` mode: `MediaPlayer` plays the
  staged MP4 through the device speaker, the broadcast mic captures it
  acoustically, TikTok's pipeline handles the mic input normally. No PCM
  substitution in the default flow.
- `MediaPlayer` pinned to `USAGE_MEDIA + CONTENT_TYPE_MUSIC` audio
  attributes so the system routes through the loud media speaker (not
  the voice earpiece) and avoids voice-tuned downsampling.
- Audio + video start aligned: `Mp4FrameProducer.onSurfacesChanged` now
  also kicks off `Mp4AudioProducer.start()`, so MP4 playback begins
  the moment the broadcaster enters the LIVE preview screen — both
  tracks are mid-loop at the same offset when broadcast actually starts.

**Why PCM injection was abandoned**

Two days of A/B ruling-out (rate, amp, LPF cutoff, AAC encoder rewrite,
audio scene, noise floor, source bitrate, mono downmix, cubic vs linear
resampler, pure-passthrough memcpy with zero processing) all left the
same "ลำโพงแตก" distortion on viewer side. `amp=0` (silence) was
verifiably clean, proving the substitution mechanism itself is fine —
the issue is a deep voice DSP in TikTok 45.3.2 (likely
`libkryptonaudio` or similar ByteDance custom DSP) that mangles *any*
non-silent injected PCM but processes acoustically-captured mic input
cleanly. Full trail in `memory/project_audio_speaker_production.md`.

**Known limits**

- Multi-device rooms get acoustic cross-talk: one phone's speaker leaks
  into another phone's mic. Single-device tests + demos are unaffected.
- Diagnostic modes (`mp4`, `tone`) preserved behind override files
  (`/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_*`)
  for any future TikTok build where PCM injection might become viable.

**What's next**

- Option B (R&D, not in this release): AAC injection at TikTok's RTMP
  transport layer — PC pre-encodes AAC to TikTok's spec, mobile injects
  the AAC frames directly after the encoder, bypassing the entire voice
  DSP chain. Estimated ~500 lines of native hook code.

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
