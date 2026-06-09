package com.rerun.tiktokvcam

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer

/**
 * Option G — Phase 1 recon.
 *
 * Hooks the Java-side `nativeEncoded` callbacks on TikTok's ttlivestreamer
 * AudioEncoder + VideoEncoder. These methods are how a Java-side encoder
 * (e.g. MediaCodec wrapper) hands an encoded frame back to native code
 * for downstream transport. If they fire during a real LIVE session, we
 * have our Java-side substitution point — no inline native hook needed.
 *
 * If they DON'T fire, TikTok is using a pure-native encoder
 * (NativeAudioEncoder / NativeVideoEncoder) and we have to drop a level
 * and inline-hook the equivalent C++ function inside libvolcenginertc.so
 * — bytehook (already bundled with TikTok as libbytehook.so) or
 * shadowhook handles that.
 *
 * Either way, this recon answers the binary "Java hook viable?" question
 * with one LIVE test.
 *
 * Logs:
 *   - first 16 bytes of the ByteBuffer (hex)
 *   - length + flags + pts
 *   - first ~64 calls per encoder, then silences to avoid flooding
 *
 * Signatures recon'd from /tmp/tt-decompile/sources/com/ss/ttlivestreamer/
 * core/engine/AudioEncoder.java + VideoEncoder.java:
 *   AudioEncoder.nativeEncoded(ByteBuffer, int flags, int offset, long pts)
 *   VideoEncoder.nativeEncoded(ByteBuffer, int flags, int offset, int kind, long pts, long dts)
 */
object EncoderOutputRecon {
    private const val TAG = "TiktokRerunVCam"
    private const val LOG_BUDGET = 64

    private var audioLogCount = 0
    private var videoLogCount = 0

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader

        hookEncoder(cl, "com.ss.ttlivestreamer.core.engine.AudioEncoder", isAudio = true)
        hookEncoder(cl, "com.ss.ttlivestreamer.core.engine.VideoEncoder", isAudio = false)
    }

    private fun hookEncoder(cl: ClassLoader, fqn: String, isAudio: Boolean) {
        val klass = XposedHelpers.findClassIfExists(fqn, cl)
        if (klass == null) {
            log("class $fqn not found")
            return
        }
        val methods = klass.declaredMethods.filter { it.name == "nativeEncoded" }
        if (methods.isEmpty()) {
            log("no nativeEncoded on $fqn")
            return
        }
        methods.forEach { method ->
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            logCall(isAudio, param)
                        } catch (t: Throwable) {
                            log("logCall threw: ${t.message}")
                        }
                    }
                })
            } catch (t: Throwable) {
                log("hook $fqn.nativeEncoded failed: ${t.message}")
            }
        }
        log("hooked $fqn.nativeEncoded (${methods.size} overload(s))")
    }

    private fun logCall(isAudio: Boolean, param: XC_MethodHook.MethodHookParam) {
        if (isAudio) {
            if (audioLogCount >= LOG_BUDGET) return
            audioLogCount++
        } else {
            if (videoLogCount >= LOG_BUDGET) return
            videoLogCount++
        }
        val tag = if (isAudio) "AUDIO" else "VIDEO"
        val n = if (isAudio) audioLogCount else videoLogCount
        val args = param.args
        // arg 0 = ByteBuffer, others = ints + longs (interpreted from
        // decompile signatures).
        val buf = args.getOrNull(0) as? ByteBuffer
        val hex = if (buf != null && buf.remaining() > 0) {
            val save = buf.position()
            try {
                val n = minOf(16, buf.remaining())
                val bytes = ByteArray(n)
                buf.duplicate().get(bytes)
                bytes.joinToString(" ") { "%02x".format(it) }
            } finally {
                buf.position(save)
            }
        } else "<empty>"

        val argDescription = args.mapIndexed { i, a ->
            when (a) {
                is ByteBuffer -> "bbuf[remaining=${a.remaining()}]"
                is Number, is Boolean -> "$a"
                null -> "null"
                else -> a.javaClass.simpleName
            }
        }.joinToString(", ")

        log("nativeEncoded[$tag #$n] args=($argDescription) firstBytes=$hex" +
            (if (n == LOG_BUDGET) " (silencing)" else ""))
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] EncoderOutputRecon: $msg")
        Log.i(TAG, "EncoderOutputRecon: $msg")
    }
}
