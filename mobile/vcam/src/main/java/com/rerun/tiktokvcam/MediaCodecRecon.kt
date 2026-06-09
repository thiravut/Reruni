package com.rerun.tiktokvcam

import android.media.MediaCodec
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Diagnostic-only hook on `android.media.MediaCodec.createEncoderByType`
 * and `createByCodecName`. Logs which encoders TikTok actually instantiates
 * during a LIVE session.
 *
 * Why this is safe: we're hooking a *framework* class (android.media), NOT
 * codec-x / pusher / ttlivestreamer / lyrax. The framework class's static
 * methods don't run during TikTok's codec-x init, so this hook doesn't
 * disrupt the encoder registration that EncoderOutputRecon /
 * VideoEncoderRecon broke.
 *
 * Goal: confirm whether TikTok 45.3.2 on Samsung A15 Android 16 uses Java
 * MediaCodec for its AAC encoder (which we'd then substitute output for).
 * If we see `audio/mp4a-latm` in the log, the path exists and we can
 * implement the substitution layer next. If not, the encoder lives
 * somewhere else entirely.
 */
object MediaCodecRecon {
    private const val TAG = "TiktokRerunVCam"
    private var logCount = 0
    private const val LOG_BUDGET = 32

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        runCatching {
            val mediaCodecClass = XposedHelpers.findClass("android.media.MediaCodec", cl)
            XposedBridge.hookAllMethods(
                mediaCodecClass,
                "createEncoderByType",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (logCount >= LOG_BUDGET) return
                        logCount++
                        val mime = param.args.getOrNull(0) as? String
                        val codec = param.result as? MediaCodec
                        log("createEncoderByType(\"$mime\") → ${codec?.javaClass?.simpleName}@${
                            codec?.let { System.identityHashCode(it).toString(16) }
                        }")
                    }
                },
            )
            XposedBridge.hookAllMethods(
                mediaCodecClass,
                "createByCodecName",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (logCount >= LOG_BUDGET) return
                        logCount++
                        val name = param.args.getOrNull(0) as? String
                        val codec = param.result as? MediaCodec
                        log("createByCodecName(\"$name\") → ${codec?.javaClass?.simpleName}@${
                            codec?.let { System.identityHashCode(it).toString(16) }
                        }")
                    }
                },
            )
            log("install OK — hooked createEncoderByType + createByCodecName on android.media.MediaCodec")
        }.onFailure { log("install failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] MediaCodecRecon: $msg")
        Log.i(TAG, "MediaCodecRecon: $msg")
    }
}
