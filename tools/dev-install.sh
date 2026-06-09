#!/usr/bin/env bash
# tools/dev-install.sh — build vcam + reruni, patch TikTok, install to A15.
#
# Unlike tools/release.sh, this does NOT create files in releases/. Patched
# APKs go to /tmp/vcam-dev/ and are overwritten on every run, so the
# releases/ dir stays clean for actual customer releases.
#
# Usage:
#   ./tools/dev-install.sh                # build + patch + install all
#   ./tools/dev-install.sh --module-only  # rebuild + install JUST the vcam
#                                            module (skip controller app)
#   ./tools/dev-install.sh --skip-tiktok  # rebuild + install JUST the
#                                            controller app (no TikTok touch)
#
# Targets the A15 by default (ADB serial R5CX51F83ZR). Override with
#   ADB_SERIAL=<serial> ./tools/dev-install.sh

set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

DEVICE="${ADB_SERIAL:-R5CX51F83ZR}"
ADB="${ADB:-adb} -s $DEVICE"
DEV_OUT="/tmp/vcam-dev"
mkdir -p "$DEV_OUT"

MODE="all"
for arg in "$@"; do
    case "$arg" in
        --module-only) MODE="module-only" ;;
        --skip-tiktok) MODE="skip-tiktok" ;;
        *) echo "unknown flag: $arg" >&2; exit 1 ;;
    esac
done

# ---- JDK + LSPatch + TikTok APK ------------------------------------------
if [[ -z "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
export PATH="$JAVA_HOME/bin:$PATH"

LSPATCH_JAR="${LSPATCH_JAR:-/Users/pond/Tools/lspatch-0.8/lspatch.jar}"
TIKTOK_SRC="${TIKTOK_SRC:-$REPO/apk/45/com.zhiliaoapp.musically_45.3.2-2024503020_minAPI23(arm64-v8a,armeabi-v7a)(nodpi)_apkmirror.com.apk}"

# ---- gradle build ---------------------------------------------------------
echo "==> building vcam$([ "$MODE" != "module-only" ] && echo " + reruni")"
cd "$REPO/mobile"
if [[ "$MODE" == "module-only" ]]; then
    ./gradlew --console=plain :vcam:assembleDebug
else
    ./gradlew --console=plain :vcam:assembleDebug :app:assembleDebug
fi

VCAM_APK="$REPO/mobile/vcam/build/outputs/apk/debug/vcam-debug.apk"
RERUNI_APK="$REPO/mobile/app/build/outputs/apk/debug/app-debug.apk"

# ---- patch TikTok with the rebuilt vcam module ----------------------------
if [[ "$MODE" != "skip-tiktok" ]]; then
    echo "==> patching TikTok with new vcam module"
    rm -rf "$DEV_OUT/patch"
    mkdir -p "$DEV_OUT/patch"
    java -Duser.language=en -Duser.country=US -Duser.variant= \
        -jar "$LSPATCH_JAR" \
        "$TIKTOK_SRC" \
        -m "$VCAM_APK" \
        -l 0 \
        -o "$DEV_OUT/patch" > "$DEV_OUT/lspatch.log" 2>&1

    PATCHED="$(ls "$DEV_OUT/patch"/*.apk | head -n 1)"
    if [[ -z "$PATCHED" ]]; then
        echo "LSPatch failed — log:" >&2
        tail -30 "$DEV_OUT/lspatch.log" >&2
        exit 1
    fi
    cp "$PATCHED" "$DEV_OUT/tiktok-dev.apk"
    echo "==> installing patched TikTok to $DEVICE"
    $ADB install -r "$DEV_OUT/tiktok-dev.apk"
fi

# ---- install controller app -----------------------------------------------
if [[ "$MODE" == "all" ]]; then
    echo "==> installing reruni controller to $DEVICE"
    $ADB install -r "$RERUNI_APK"
fi

echo "==> done. dev artifacts in $DEV_OUT/"
