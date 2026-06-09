#!/usr/bin/env bash
# tools/test-audio-rate.sh — drive the audio injection's runtime overrides
# without rebuilding the APK. Avoids the reinstall+relogin cycle that puts
# Pond's TikTok account at CAPTCHA risk
# (memory/feedback_tiktok_account_protection).
#
# Usage:
#   ./tools/test-audio-rate.sh 60000        # override target sample rate
#   ./tools/test-audio-rate.sh clear        # remove ALL overrides
#   ./tools/test-audio-rate.sh tone         # switch to 1000 Hz sine mode
#                                              (viewer hears tone, not MP4)
#   ./tools/test-audio-rate.sh mp4          # switch back to MP4 playback
#   ./tools/test-audio-rate.sh tail         # follow audio-related logs
#   ./tools/test-audio-rate.sh record N     # screen-record N seconds + pull
#                                              + extract audio + dump peak
#                                              frequency for tone analysis
#
# After setting a value, manually open TikTok, start LIVE, listen.
#
# Objective rate measurement workflow (no ear A/B needed):
#   1. ./tools/test-audio-rate.sh tone      # play 1000 Hz sine
#   2. ./tools/test-audio-rate.sh 60000     # set candidate target rate
#   3. open TikTok manually, start LIVE
#   4. ./tools/test-audio-rate.sh record 10 # capture 10s of broadcast
#   5. read the printed "peak frequency = X Hz" line
#      if X = 1000: rate is correct
#      if X = 1500: actual native rate = 60000 × (1500/1000) = 90000 Hz
#      if X = 666:  actual native rate = 60000 × (666/1000)  = 40000 Hz
#   6. set candidate to that and repeat — converges in 2-3 iterations

set -euo pipefail
DEVICE="${ADB_SERIAL:-R5CX51F83ZR}"
ADB="${ADB:-/opt/homebrew/bin/adb} -s $DEVICE"
RATE_FILE="/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_rate.txt"
MODE_FILE="/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_mode.txt"
AMP_FILE="/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_amp.txt"
LPF_FILE="/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_lpf_hz.txt"
ENC_OFF_FILE="/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_encoder_rewrite_off.txt"
NOISE_FILE="/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_noise.txt"
SCENE_FILE="/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_scene.txt"
PURE_FILE="/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_pure_pass.txt"
PKG="com.zhiliaoapp.musically"

restart_pkg() {
    echo "==> force-stopping $PKG so next launch re-reads the override files"
    $ADB shell am force-stop "$PKG"
}

case "${1:-}" in
    tail)
        $ADB logcat -c
        echo "==> tailing TiktokRerunVCam audio logs (Ctrl-C to stop)"
        exec $ADB logcat -v time \
            | grep --line-buffered -E "rate override|configureTarget|AudioRecord ctor|obtainBuffer|tone|DIAGNOSTIC|Mp4AudioProducer"
        ;;
    clear|"")
        echo "==> removing all overrides"
        $ADB shell rm -f "$RATE_FILE" "$MODE_FILE" "$AMP_FILE" "$LPF_FILE" "$ENC_OFF_FILE" "$NOISE_FILE" "$SCENE_FILE" "$PURE_FILE"
        restart_pkg
        ;;
    pure-on)
        echo "==> enabling pure passthrough (memcpy source PCM to ring, bypass all processing)"
        echo "    requires source rate + channels match broadcast target"
        $ADB shell "touch $PURE_FILE"
        restart_pkg
        ;;
    pure-off)
        echo "==> disabling pure passthrough"
        $ADB shell "rm -f $PURE_FILE"
        restart_pkg
        ;;
    scene)
        VAL="${2:-}"
        if [[ -z "$VAL" ]]; then
            echo "usage: $0 scene <0..4>" >&2
            echo "  0=DEFAULT  1=CHATROOM(voice)  2=HIGH_QUALITY_CHATROOM" >&2
            echo "  3=LOW_LATENCY  4=KARAOKE(music — default)" >&2
            exit 1
        fi
        echo "==> setting audio scene to $VAL"
        $ADB shell "echo $VAL > $SCENE_FILE"
        $ADB shell "cat $SCENE_FILE"
        restart_pkg
        ;;
    noise)
        VAL="${2:-}"
        if [[ -z "$VAL" ]]; then
            echo "usage: $0 noise <0..8192>   int16 peak amplitude of injected noise" >&2
            echo "                            0 = off, 100 ≈ -50 dBFS, 327 ≈ -40 dBFS, 1024 ≈ -30 dBFS" >&2
            exit 1
        fi
        echo "==> setting noise injection to $VAL (int16 peak)"
        $ADB shell "echo $VAL > $NOISE_FILE"
        $ADB shell "cat $NOISE_FILE"
        echo "==> noise picked up live — no restart needed"
        ;;
    amp)
        VAL="${2:-}"
        if [[ -z "$VAL" ]]; then
            echo "usage: $0 amp <0..1.5>   e.g. $0 amp 0.7 (0 = silence diagnostic)" >&2
            exit 1
        fi
        echo "==> setting amp override to $VAL"
        $ADB shell "echo $VAL > $AMP_FILE"
        $ADB shell "cat $AMP_FILE"
        echo "==> amp picked up live — no restart needed (refresh per chunk)"
        ;;
    lpf)
        VAL="${2:-}"
        if [[ -z "$VAL" ]]; then
            echo "usage: $0 lpf <Hz>     0 = disable, 16000 = default, 20000 = gentler" >&2
            exit 1
        fi
        echo "==> setting LPF cutoff to $VAL Hz (0 = disabled)"
        $ADB shell "echo $VAL > $LPF_FILE"
        $ADB shell "cat $LPF_FILE"
        echo "==> LPF cutoff picked up live — no restart needed"
        ;;
    encoder-off)
        echo "==> disabling AAC-LC encoder rewrite (TikTok default HE-AACv1 stays in effect)"
        $ADB shell "touch $ENC_OFF_FILE"
        restart_pkg
        ;;
    encoder-on)
        echo "==> enabling AAC-LC encoder rewrite (default)"
        $ADB shell "rm -f $ENC_OFF_FILE"
        restart_pkg
        ;;
    tone)
        echo "==> enabling tone mode (1000 Hz sine in place of MP4)"
        $ADB shell "echo tone > $MODE_FILE"
        restart_pkg
        ;;
    mp4)
        echo "==> switching back to MP4 mode (PCM substitution into mic buffer)"
        $ADB shell "echo mp4 > $MODE_FILE"
        restart_pkg
        ;;
    speaker)
        echo "==> enabling SPEAKER (acoustic loopback) mode"
        echo "    MP4 audio plays through device speaker; mic captures it acoustically."
        echo "    PCM substitution DISABLED. Use only as A/B diagnostic vs mp4 mode."
        $ADB shell "echo speaker > $MODE_FILE"
        restart_pkg
        ;;
    dlite-poc)
        echo "==> enabling D-lite POC mode (parallel AudioTrack with 1 kHz sine)"
        echo "    Tests whether voice DSP applies per-track or to every track."
        echo "    Viewer should hear a clean 1 kHz tone in addition to mic input."
        echo "    If tone is clean → voice DSP is mic-only → can ship full D-lite."
        echo "    If tone is distorted → DSP applies to all tracks → D-lite dead."
        echo "    Watch logs with: $0 dlite-tail"
        $ADB shell "echo dlite_poc > $MODE_FILE"
        restart_pkg
        ;;
    dlite-tail)
        $ADB logcat -c
        echo "==> tailing Mp4DLitePoc logs (Ctrl-C to stop)"
        exec $ADB logcat -v time \
            | grep --line-buffered -E "Mp4DLitePoc|MediaEncodeStream|MediaEngineFactory|addTrack"
        ;;
    ws-inject)
        echo "==> enabling ws_inject mode (Option G — PC-encoded AAC injection)"
        echo "    1. Start PC server: ./tools/aac-server.py <input.mp4>"
        echo "    2. Set PC IP via: $0 ws-endpoint ws://<PC-IP>:8765"
        echo "    3. (Re)start TikTok → Go LIVE"
        echo "    Viewers should hear PC-encoded AAC (bypasses voice DSP)."
        $ADB shell "echo ws_inject > $MODE_FILE"
        restart_pkg
        ;;
    ws-endpoint)
        VAL="${2:-}"
        if [[ -z "$VAL" ]]; then
            echo "usage: $0 ws-endpoint ws://192.168.1.100:8765" >&2
            exit 1
        fi
        WS_FILE="/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_ws_endpoint.txt"
        echo "==> setting WebSocket endpoint to $VAL"
        $ADB shell "echo $VAL > $WS_FILE"
        $ADB shell "cat $WS_FILE"
        echo "==> endpoint picked up at next Mp4GWsClient.start()"
        ;;
    ws-tail)
        $ADB logcat -c
        echo "==> tailing Mp4GWsClient + aacEncEncode logs (Ctrl-C to stop)"
        exec $ADB logcat -v time \
            | grep --line-buffered -E "Mp4GWsClient|aacEncEncode|AAC SUBSTITUTE|underrun|RTMP AAC injection"
        ;;
    lyrax)
        echo "==> enabling lyrax mode (Option C — TikTok's own broadcast music path)"
        echo "    MP4 plays through TikTok's LyraxAudioPlayer with mixingType=PUBLISH."
        echo "    Routes through ByteDance's broadcast Aux Pipeline, bypassing voice DSP."
        echo "    Broadcaster does NOT hear audio locally (PUBLISH-only); viewers do."
        echo "    Watch logs with: $0 lyrax-tail"
        $ADB shell "echo lyrax > $MODE_FILE"
        restart_pkg
        ;;
    lyrax-tail)
        $ADB logcat -c
        echo "==> tailing Mp4LyraxProducer + RTCVideoImpl logs (Ctrl-C to stop)"
        exec $ADB logcat -v time \
            | grep --line-buffered -E "Mp4LyraxProducer|RTCVideoImpl|getLyraxAudioPlayer|LyraxAudioPlayer|TiktokRerunVCam.*lyrax"
        ;;
    rtmp-diag)
        echo "==> enabling rtmp_diag mode (Option B reconnaissance — log-only)"
        echo "    PLT hook on rtmp_client_push_audio fires; first ~64 calls log size + first bytes + pts."
        echo "    No payload substitution. Use this to inspect TikTok's outgoing AAC format."
        echo "    Watch logs with: $0 rtmp-tail"
        $ADB shell "echo rtmp_diag > $MODE_FILE"
        restart_pkg
        ;;
    rtmp-inject)
        echo "==> enabling rtmp_inject mode (Option B — production candidate)"
        echo "    Staged MP4's AAC track is demuxed and injected at rtmp_client_push_audio."
        echo "    TikTok's encoder output is replaced per-frame; viewer hears the MP4's audio directly."
        echo "    Mic captures ambient sound but it's discarded at the RTMP layer."
        $ADB shell "echo rtmp_inject > $MODE_FILE"
        restart_pkg
        ;;
    rtmp-tail)
        $ADB logcat -c
        echo "==> tailing Option B RTMP hook logs (Ctrl-C to stop)"
        exec $ADB logcat -v time \
            | grep --line-buffered -E "rtmp_push_|INJECT#|underrun#|Mp4AacProducer|RTMP AAC injection"
        ;;
    record)
        SECS="${2:-10}"
        OUT_DIR="/tmp/vcam-audio-test"
        mkdir -p "$OUT_DIR"
        STAMP="$(date +%H%M%S)"
        DEVICE_MP4="/sdcard/vcam-test-$STAMP.mp4"
        HOST_MP4="$OUT_DIR/recording-$STAMP.mp4"
        HOST_WAV="$OUT_DIR/recording-$STAMP.wav"

        echo "==> screen-recording $SECS s on device (start LIVE BEFORE you run this)"
        # --audio-source internal captures the device's speaker output mix
        # on Android 10+. NOTE: this records what the BROADCASTER hears,
        # not what viewers hear. For true broadcast-side measurement, watch
        # the LIVE from a second device and capture audio there.
        $ADB shell "screenrecord --audio-source internal --time-limit $SECS $DEVICE_MP4" 2>/dev/null \
            || $ADB shell "screenrecord --time-limit $SECS $DEVICE_MP4"
        echo "==> pulling recording"
        $ADB pull "$DEVICE_MP4" "$HOST_MP4"
        $ADB shell rm -f "$DEVICE_MP4"

        if ! command -v ffmpeg >/dev/null 2>&1; then
            echo "ffmpeg not installed (brew install ffmpeg). Recording saved to: $HOST_MP4"
            exit 0
        fi

        echo "==> extracting audio to WAV"
        ffmpeg -y -i "$HOST_MP4" -vn -ac 1 -ar 44100 "$HOST_WAV" 2>/dev/null

        echo "==> measuring peak frequency (looking for our 1000 Hz tone)"
        # Use astats on a 1-second window from the middle of the recording
        # to skip startup transients. Dominant freq surfaces as the bin
        # with peak magnitude.
        ffmpeg -i "$HOST_WAV" -af "showspectrumpic=s=2048x1024:legend=1:scale=lin" \
            -f null - 2>&1 | tail -3 || true

        # Quick peak-frequency estimate via aubiopitch if available, else
        # fall back to a sox stat call.
        if command -v aubiopitch >/dev/null 2>&1; then
            echo "==> aubiopitch trace (median of frames):"
            aubiopitch "$HOST_WAV" 2>/dev/null | awk '{print $2}' \
                | grep -vE '^0|^-' | sort -n \
                | awk '{a[NR]=$1} END{print "  median peak freq =", a[int(NR/2)], "Hz"}'
        elif command -v sox >/dev/null 2>&1; then
            echo "==> sox dominant freq:"
            sox "$HOST_WAV" -n stat 2>&1 | grep -E "Maximum|Minimum|Mean"
        else
            echo "Install aubio (brew install aubio) or sox for peak-freq analysis."
            echo "Recording saved to: $HOST_WAV"
        fi
        echo "==> recording at: $HOST_MP4"
        exit 0
        ;;
    *[!0-9]*|[0-9]|[0-9][0-9]|[0-9][0-9][0-9])
        echo "first arg must be an integer >= 1000, or one of:" >&2
        echo "  tone | mp4 | speaker | lyrax | lyrax-tail" >&2
        echo "  rtmp-diag | rtmp-inject | rtmp-tail" >&2
        echo "  clear | tail | record | amp | lpf | noise | scene | encoder-on | encoder-off" >&2
        echo "  pure-on | pure-off" >&2
        exit 1
        ;;
    *)
        RATE="$1"
        echo "==> setting rate override to $RATE Hz"
        $ADB shell "echo $RATE > $RATE_FILE"
        $ADB shell "cat $RATE_FILE"
        restart_pkg
        ;;
esac

echo
echo "Now open TikTok manually, start LIVE, listen / record."
echo "Iterate: ./tools/test-audio-rate.sh <rate|tone|mp4|record N|clear>"
