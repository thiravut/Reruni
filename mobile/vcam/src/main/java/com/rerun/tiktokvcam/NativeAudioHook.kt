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

    @JvmStatic
    private external fun install0(): Boolean

    @JvmStatic
    private external fun refresh0()

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] NativeAudioHook: $msg")
        Log.i(TAG, "NativeAudioHook: $msg")
    }
}
