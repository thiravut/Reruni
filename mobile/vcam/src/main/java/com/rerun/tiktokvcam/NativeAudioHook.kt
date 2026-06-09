package com.rerun.tiktokvcam

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * Bridges to the libvcam_native.so PLT hook scaffold (cpp/audio_hook.c).
 *
 * Phase 1 of native audio injection — installs a log-only hook on OpenSL ES'
 * `slCreateEngine` symbol inside libvolcenginertc.so so we can confirm the
 * native hook infrastructure is reaching TikTok's RTC audio path. Subsequent
 * phases extend the same lib with real buffer interception
 * (CreateAudioRecorder / RegisterCallback / Enqueue) and connect it to the
 * existing [Mp4AudioProducer] ring buffer.
 *
 * Loading is best-effort: if the .so fails to load (e.g., LSPatch process
 * blocks dlopen of bundled native libs on some devices) we just log and the
 * Java-side hooks keep working — the broadcast falls back to mic audio.
 */
object NativeAudioHook {

    private const val TAG = "TiktokRerunVCam"

    @Volatile
    var available: Boolean = false
        private set

    fun install() {
        try {
            System.loadLibrary("vcam_native")
            available = true
            log("vcam_native loaded — installing PLT hooks")
        } catch (t: UnsatisfiedLinkError) {
            log("System.loadLibrary(vcam_native) failed: ${t.message}")
            return
        } catch (t: Throwable) {
            log("loadLibrary unexpected failure: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        try {
            val ok = install0()
            log("PLT hooks registered (success=$ok)")
        } catch (t: Throwable) {
            log("native install0() threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Re-scan the process's loaded libs and re-apply pending PLT hooks.
     *  Some RTC libs are dlopen()'d lazily — calling this once the operator
     *  taps Go LIVE catches mappings that didn't exist at install time. */
    fun refresh() {
        if (!available) return
        try {
            refresh0()
        } catch (t: Throwable) {
            log("native refresh0() threw: ${t.message}")
        }
    }

    /** Push a chunk of PCM (16-bit LE, target rate/channels) into the native
     *  ring buffer that AudioRecord::obtainBuffer drains from on the audio
     *  thread. Called by [Mp4AudioProducer] per decoded chunk. No-op until
     *  the native lib is loaded — Java-side Mp4AudioProducer continues to
     *  function for the legacy Java AudioRecord.read path. */
    fun writePcm(data: ByteArray, length: Int) {
        if (!available) return
        try {
            writePcm0(data, length)
        } catch (t: Throwable) {
            // Don't kill the producer on a transient native failure.
        }
    }

    /** Bytes currently sitting in the native ring. Lets the decode loop
     *  pace itself so it doesn't run too far ahead of the broadcast. */
    fun ringAvailable(): Int =
        if (available) try { ringAvailable0() } catch (_: Throwable) { 0 } else 0

    /** Toggle the native obtainBuffer hook's substitution. When `true`,
     *  the hook leaves whatever the mic hardware captured in the buffer
     *  alone (no PCM substitution). Used for the "speaker" loopback
     *  diagnostic where MP4 audio is played through the device speaker
     *  and captured acoustically by the mic. No-op if the native lib
     *  isn't loaded. */
    fun setPassthrough(passthrough: Boolean) {
        if (!available) return
        try {
            setPassthrough0(passthrough)
        } catch (t: Throwable) {
            log("setPassthrough0() threw: ${t.message}")
        }
    }

    // ---- Option B: RTMP-layer AAC injection -------------------------------

    /** Enable substitution of TikTok's outgoing AAC payload at the
     *  rtmp_client_push_audio call site. When `false` (default), the PLT
     *  hook is diagnostic-only — it logs sizes / first bytes / pts for
     *  the first ~64 calls so we can inspect the AAC payload format
     *  TikTok pushes without touching the live stream. */
    fun setRtmpInjectEnabled(enabled: Boolean) {
        if (!available) return
        try {
            setRtmpInjectEnabled0(enabled)
        } catch (t: Throwable) {
            log("setRtmpInjectEnabled0() threw: ${t.message}")
        }
    }

    /** Push one pre-encoded AAC access unit into the native ring. The
     *  RTMP push-audio hook pops one per outgoing packet and substitutes
     *  it for TikTok's encoder output. Underrun (empty ring) passes the
     *  original AAC frame through untouched. */
    fun pushAacFrame(data: ByteArray, length: Int) {
        if (!available) return
        try {
            pushAacFrame0(data, length)
        } catch (_: Throwable) { /* drop silently */ }
    }

    /** Reset the AAC ring (drop all queued frames). Called at the start
     *  of each MP4 loop pass so a stale tail from the previous pass
     *  doesn't get injected mid-stream. */
    fun clearAacRing() {
        if (!available) return
        try {
            clearAacRing0()
        } catch (_: Throwable) { /* idempotent */ }
    }

    /** Number of AAC frames queued in the ring. Lets the producer pace
     *  itself against TikTok's actual RTMP push rate so we don't overflow. */
    fun aacRingFrames(): Int =
        if (available) try { aacRingFrames0() } catch (_: Throwable) { 0 } else 0

    /** Request an A/V resync — the next encoder fire will drain the ring
     *  down to its newest 1 frame so audio re-aligns with PC's real-time
     *  stream position. Used at video loop boundary so accumulated drift
     *  doesn't carry across loops. */
    fun aacRingResync() {
        if (!available) return
        try {
            aacRingResync0()
        } catch (_: Throwable) { /* idempotent */ }
    }

    /**
     * Called from native audio_hook.c the FIRST time the AAC encoder fires
     * after substitution is enabled. Triggers the cross-component reset
     * that aligns audio + video to MP4 t=0 at this instant:
     *
     *  - PC AAC streamer: send "reset" via WS so it restarts from MP4
     *    audio frame 0
     *  - Video producer: abort current pass and rewind to MP4 video
     *    frame 0
     *
     * Without this, audio plays whatever PC was streaming at first-encoder-
     * fire moment (= an arbitrary offset baked in from WS-connect-to-
     * broadcast-start latency), while video continues from wherever the
     * decoder was at first encoder fire — perceived as "they start at
     * different times".
     *
     * Re-entry safety: native side gates this on `g_should_notify_first_fire`
     * which is cleared atomically before the call, so even racy double-
     * dispatches result in only one Kotlin-side invocation.
     */
    @JvmStatic
    fun onFirstEncoderFire() {
        log("onFirstEncoderFire — sending PC reset + video rewind")
        try {
            Mp4GWsClient.sendReset()
        } catch (t: Throwable) {
            log("Mp4GWsClient.sendReset failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        try {
            Mp4FrameProducer.requestRestart()
        } catch (t: Throwable) {
            log("Mp4FrameProducer.requestRestart failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @JvmStatic
    private external fun install0(): Boolean

    @JvmStatic
    private external fun refresh0()

    @JvmStatic
    private external fun writePcm0(data: ByteArray, length: Int)

    @JvmStatic
    private external fun ringAvailable0(): Int

    @JvmStatic
    private external fun setPassthrough0(passthrough: Boolean)

    @JvmStatic
    private external fun setRtmpInjectEnabled0(enabled: Boolean)

    @JvmStatic
    private external fun pushAacFrame0(data: ByteArray, length: Int)

    @JvmStatic
    private external fun clearAacRing0()

    @JvmStatic
    private external fun aacRingFrames0(): Int

    @JvmStatic
    private external fun aacRingResync0()

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] NativeAudioHook: $msg")
        Log.i(TAG, "NativeAudioHook: $msg")
    }
}
