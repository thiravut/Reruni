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
