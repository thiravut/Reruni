package com.rerun.tiktokvcam

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier

/**
 * Disables TikTok's WebRTC audio processing chain so MP4 PCM we substitute
 * into the broadcast AudioRecord buffer reaches the encoder un-mangled.
 *
 * Recon of [libvolcenginertc.so] surfaced this JNI control surface on
 * `com.ss.ttlivestreamer.core.engine.AudioDeviceModule`:
 *   - nativeEnableANS       — Adaptive Noise Suppression (the worst
 *     offender: it learns our music as "stationary noise" within a few
 *     seconds and then aggressively subtracts it, producing the
 *     "broken-speaker" sound that takes hold after a clean intro)
 *   - nativeEnableApmProcess — entire APM (Audio Processing Module)
 *   - nativeEnableAudioNoiseDetection
 *   - nativeEnableEchoMode / nativeEnableHardwareEchoMode /
 *     nativeEnableSoftwareEchoMode (AEC)
 *
 * Strategy: intercept each enable-*(boolean) method, log the requested
 * state for diagnostics, and force the boolean arg to `false` so the
 * native side initialises with that processor disabled. We keep the call
 * itself going through (don't skip the native invocation) so TikTok's
 * downstream state machine still sees the side-effects it expects.
 *
 * Int-based enables (echo mode takes a mode integer) get coerced to 0
 * ("off"). If a getter (`isEchoMode`, `isXxx`) is later queried, the
 * native side returns the actually-applied state, so the rest of TikTok
 * agrees with reality.
 */
object TtRtcAudioHook {

    private const val TAG = "TiktokRerunVCam"
    private const val ADM_CLASS =
        "com.ss.ttlivestreamer.core.engine.AudioDeviceModule"

    /**
     * Method names whose first boolean arg we force to false. Add new ones
     * here as we discover more WebRTC processors that need silencing.
     */
    private val BOOLEAN_DISABLE_TARGETS = setOf(
        "enableANS",
        "enableApmProcess",
        "enableAudioNoiseDetection",
        "enableHardwareEchoMode",
        "enableSoftwareEchoMode",
        "enableAudioStrangeVoice",
        "enableNew3ARmsStatistics",
        "enablePlayOutEcho",
        // Native counterparts (same name with `native` prefix) — hooking
        // both belt-and-braces in case TikTok bypasses the Java wrapper.
        "nativeEnableANS",
        "nativeEnableApmProcess",
        "nativeEnableAudioNoiseDetection",
        "nativeEnableHardwareEchoMode",
        "nativeEnableSoftwareEchoMode",
        "nativeEnableAudioStrangeVoice",
        "nativeEnableNew3ARmsStatistics",
        "nativeEnablePlayOutEcho",
    )

    /** Int-valued enables — force arg to 0 (= off / default). */
    private val INT_DISABLE_TARGETS = setOf(
        "enableEchoMode",
        "nativeEnableEchoMode",
    )

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val klass = XposedHelpers.findClassIfExists(ADM_CLASS, cl) ?: run {
            log("class $ADM_CLASS not found — TikTok build without ttlivestreamer?")
            return
        }

        var hooked = 0
        for (m in klass.declaredMethods) {
            val name = m.name
            val params = m.parameterTypes
            val isBoolTarget = name in BOOLEAN_DISABLE_TARGETS &&
                    params.isNotEmpty() && params[0].name == "boolean"
            val isIntTarget = name in INT_DISABLE_TARGETS &&
                    params.isNotEmpty() && params[0].name == "int"
            if (!isBoolTarget && !isIntTarget) continue

            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val requested = param.args[0]
                        if (isBoolTarget) {
                            param.args[0] = false
                        } else {
                            param.args[0] = 0
                        }
                        log("$name(requested=$requested) → forced=${param.args[0]}" +
                                (if (Modifier.isNative(m.modifiers)) " [native]" else ""))
                    }
                })
                hooked++
            } catch (t: Throwable) {
                log("hook $name failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        log("hooked $hooked WebRTC processor switches on $ADM_CLASS")
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] TtRtcAudioHook: $msg")
        Log.i(TAG, "TtRtcAudioHook: $msg")
    }
}
