#!/usr/bin/env bash
# tools/release.sh — build, patch, and (optionally) upload the customer
# distribution artifacts to Cloudflare R2.
#
# Usage:
#   ./tools/release.sh <version> [--upload]
#
# Example:
#   ./tools/release.sh 0.1.0           # build only — artifacts land in releases/v0.1.0/
#   ./tools/release.sh 0.1.0 --upload  # build + upload to R2 + update latest/
#
# Required env (only when --upload is passed):
#   R2_BUCKET            e.g. reruni-releases
#   R2_ACCOUNT_ID        Cloudflare account id (for R2 endpoint)
#   R2_ACCESS_KEY_ID     R2 token access key
#   R2_SECRET_ACCESS_KEY R2 token secret
#   R2_PUBLIC_BASE_URL   public URL prefix, e.g. https://releases.reruni.com
#                        (custom domain or pub-XXX.r2.dev)
#
# Customer download names (what ships):
#   tiktok-reruni-v<version>.apk   — TikTok+vcam bundle for BYOD (Lite tier)
#   reruni-v<version>.apk           — Reruni control app
#
# Internal artifacts (not uploaded):
#   vcam-debug.apk — intermediate; embedded into the patched TikTok by LSPatch.

set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

# ---- args ------------------------------------------------------------------
VERSION="${1:-}"
UPLOAD=0
if [[ "$VERSION" == "" || "$VERSION" == "-h" || "$VERSION" == "--help" ]]; then
    sed -n '2,25p' "$0" >&2
    exit 1
fi
shift || true
for arg in "$@"; do
    case "$arg" in
        --upload) UPLOAD=1 ;;
        *) echo "unknown flag: $arg" >&2; exit 1 ;;
    esac
done

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "version must look like 0.1.0 (got: $VERSION)" >&2
    exit 1
fi

OUT="$REPO/releases/v${VERSION}"
mkdir -p "$OUT"

# ---- prerequisites ---------------------------------------------------------
if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
        export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    else
        echo "JAVA_HOME not set and Android Studio JBR not at default path" >&2
        exit 1
    fi
fi
export PATH="$JAVA_HOME/bin:$PATH"

LSPATCH_JAR="${LSPATCH_JAR:-/Users/pond/Tools/lspatch-0.8/lspatch.jar}"
if [[ ! -f "$LSPATCH_JAR" ]]; then
    echo "lspatch.jar missing at $LSPATCH_JAR (override via LSPATCH_JAR=...)" >&2
    exit 1
fi

TIKTOK_SRC="${TIKTOK_SRC:-$REPO/apk/45/com.zhiliaoapp.musically_45.3.2-2024503020_minAPI23(arm64-v8a,armeabi-v7a)(nodpi)_apkmirror.com.apk}"
if [[ ! -f "$TIKTOK_SRC" ]]; then
    echo "source TikTok APK missing at $TIKTOK_SRC" >&2
    exit 1
fi

# ---- build vcam module + reruni control app -------------------------------
echo "==> building vcam + reruni"
cd "$REPO/mobile"
./gradlew --console=plain :vcam:assembleDebug :app:assembleDebug

VCAM_APK="$REPO/mobile/vcam/build/outputs/apk/debug/vcam-debug.apk"
RERUNI_APK_SRC="$REPO/mobile/app/build/outputs/apk/debug/app-debug.apk"

# ---- patch TikTok with vcam (LSPatch) -------------------------------------
echo "==> patching TikTok with vcam (LSPatch)"
PATCH_TMP="$(mktemp -d)"
trap 'rm -rf "$PATCH_TMP"' EXIT

# Force Gregorian calendar — LSPatch's apkzlib verifies the MS-DOS date
# which Thai locale (Buddhist era, year=2569) trips on.
java -Duser.language=en -Duser.country=US -Duser.variant= \
    -jar "$LSPATCH_JAR" \
    "$TIKTOK_SRC" \
    -m "$VCAM_APK" \
    -l 0 \
    -o "$PATCH_TMP" > "$PATCH_TMP/lspatch.log" 2>&1

PATCHED_RAW="$(ls "$PATCH_TMP"/*.apk | head -n 1)"
if [[ -z "$PATCHED_RAW" ]]; then
    echo "LSPatch failed — log:" >&2
    cat "$PATCH_TMP/lspatch.log" >&2
    exit 1
fi

# ---- rename to customer-facing names --------------------------------------
TIKTOK_RERUNI="$OUT/tiktok-reruni-v${VERSION}.apk"
RERUNI_APK="$OUT/reruni-v${VERSION}.apk"
cp "$PATCHED_RAW" "$TIKTOK_RERUNI"
cp "$RERUNI_APK_SRC" "$RERUNI_APK"

# ---- manifest --------------------------------------------------------------
echo "==> writing manifest"
TIKTOK_RERUNI_SIZE=$(stat -f%z "$TIKTOK_RERUNI" 2>/dev/null || stat -c%s "$TIKTOK_RERUNI")
RERUNI_SIZE=$(stat -f%z "$RERUNI_APK" 2>/dev/null || stat -c%s "$RERUNI_APK")
TIKTOK_RERUNI_SHA=$(shasum -a 256 "$TIKTOK_RERUNI" | awk '{print $1}')
RERUNI_SHA=$(shasum -a 256 "$RERUNI_APK" | awk '{print $1}')
RELEASED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

PUBLIC_BASE="${R2_PUBLIC_BASE_URL:-https://example.invalid}"
cat > "$OUT/manifest.json" <<EOF
{
  "version": "${VERSION}",
  "released_at": "${RELEASED_AT}",
  "tiktok_base": {
    "package": "com.zhiliaoapp.musically",
    "version_name": "45.3.2",
    "version_code": "2024503020"
  },
  "artifacts": {
    "tiktok_reruni": {
      "filename": "tiktok-reruni-v${VERSION}.apk",
      "size_bytes": ${TIKTOK_RERUNI_SIZE},
      "sha256": "${TIKTOK_RERUNI_SHA}",
      "url": "${PUBLIC_BASE}/v${VERSION}/tiktok-reruni-v${VERSION}.apk",
      "audience": "lite",
      "label": "TikTok (Reruni bundle)"
    },
    "reruni": {
      "filename": "reruni-v${VERSION}.apk",
      "size_bytes": ${RERUNI_SIZE},
      "sha256": "${RERUNI_SHA}",
      "url": "${PUBLIC_BASE}/v${VERSION}/reruni-v${VERSION}.apk",
      "audience": "all",
      "label": "Reruni Controller"
    }
  }
}
EOF

echo "==> built:"
ls -lh "$OUT"

# ---- upload to R2 (optional) ----------------------------------------------
if (( UPLOAD )); then
    : "${R2_BUCKET:?missing R2_BUCKET}"
    : "${R2_ACCOUNT_ID:?missing R2_ACCOUNT_ID}"
    : "${R2_ACCESS_KEY_ID:?missing R2_ACCESS_KEY_ID}"
    : "${R2_SECRET_ACCESS_KEY:?missing R2_SECRET_ACCESS_KEY}"
    : "${R2_PUBLIC_BASE_URL:?missing R2_PUBLIC_BASE_URL}"

    if ! command -v aws >/dev/null 2>&1; then
        echo "aws CLI required for --upload (brew install awscli)" >&2
        exit 1
    fi

    ENDPOINT="https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com"
    export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID"
    export AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY"
    export AWS_DEFAULT_REGION="auto"

    echo "==> uploading to s3://${R2_BUCKET}/v${VERSION}/"
    aws s3 cp "$TIKTOK_RERUNI"   "s3://${R2_BUCKET}/v${VERSION}/" --endpoint-url "$ENDPOINT"
    aws s3 cp "$RERUNI_APK"      "s3://${R2_BUCKET}/v${VERSION}/" --endpoint-url "$ENDPOINT"
    aws s3 cp "$OUT/manifest.json" "s3://${R2_BUCKET}/v${VERSION}/" --endpoint-url "$ENDPOINT" \
        --content-type application/json

    # Update latest/manifest.json so guide page picks up the new release.
    aws s3 cp "$OUT/manifest.json" "s3://${R2_BUCKET}/latest/manifest.json" --endpoint-url "$ENDPOINT" \
        --content-type application/json

    echo "==> uploaded. public URLs:"
    echo "    ${R2_PUBLIC_BASE_URL}/v${VERSION}/tiktok-reruni-v${VERSION}.apk"
    echo "    ${R2_PUBLIC_BASE_URL}/v${VERSION}/reruni-v${VERSION}.apk"
    echo "    ${R2_PUBLIC_BASE_URL}/latest/manifest.json"
fi

echo "==> done. manifest at $OUT/manifest.json"
