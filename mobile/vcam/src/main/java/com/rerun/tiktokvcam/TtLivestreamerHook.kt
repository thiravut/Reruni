package com.rerun.tiktokvcam

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier
import java.nio.ByteBuffer

/**
 * Hooks ByteDance's `ttlivestreamer` audio capture path.
 *
 * Reconning libvolcenginertc.so surfaced an internal Java→native bridge:
 *   - `com.ss.ttlivestreamer.core.audiorecord.AudioRecordProcessor`
 *     declares native methods `nativeCreate`, `nativeAudioRecordInit`,
 *     `nativeAudioRecordWritePcm`, `nativeStopAudioRecord`.
 *
 * Java AudioRecord.read() hooks never fired during a LIVE broadcast,
 * even though Java AudioRecord constructors did — TikTok captures the
 * broadcast mic via a different mechanism (likely the AudioRecord
 * callback API or a private feeding path that ends at nativeAudioRecord
 * WritePcm). If `nativeAudioRecordWritePcm` fires per audio buffer, its
 * `byte[]` argument is the substitution target: overwrite the bytes
 * with our [Mp4AudioProducer] PCM and let TikTok ship our audio instead
 * of the mic.
 *
 * This revision is reconnaissance-only — we log every method's first
 * few calls with arg signatures so the next iteration knows which one
 * to substitute, and at what cadence. Production buffer replacement
 * lands once we've confirmed the call rate and arg shape.
 */
object TtLivestreamerHook {

    private const val TAG = "TiktokRerunVCam"
    private const val PROCESSOR_CLASS =
        "com.ss.ttlivestreamer.core.audiorecord.AudioRecordProcessor"

    /** Number of calls per method we log before going silent — these can
     *  fire 50–100 times per second once a broadcast is active. */
    private const val LOG_BUDGET = 10
    private val callCounts = HashMap<String, Int>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        safe("AudioRecordProcessor native methods") {
            val klass = XposedHelpers.findClassIfExists(PROCESSOR_CLASS, cl) ?: run {
                log("class $PROCESSOR_CLASS not found (TikTok variant without ttlivestreamer?)")
                return@safe
            }
            val methods = klass.declaredMethods.filter {
                it.name.startsWith("native") && Modifier.isNative(it.modifiers)
            }
            if (methods.isEmpty()) {
                log("no native methods on $PROCESSOR_CLASS (variant without ttlivestreamer?)")
                return@safe
            }
            for (m in methods) {
                val sig = "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
                log("hooking $sig → ${m.returnType.simpleName}")
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        logCall(sig, m.name, param)
                    }
                })
            }
            log("hooked ${methods.size} AudioRecordProcessor native method(s)")
        }
    }

    /**
     * Log first [LOG_BUDGET] calls per method name with arg summary so we
     * can spot which signature carries the PCM buffer we want to substitute.
     */
    private fun logCall(sig: String, methodName: String, param: XC_MethodHook.MethodHookParam) {
        val n = callCounts[methodName] ?: 0
        if (n >= LOG_BUDGET) return
        callCounts[methodName] = n + 1
        val summary = param.args.mapIndexed { i, a ->
            when (a) {
                null -> "null"
                is ByteArray -> "byte[${a.size}]"
                is ShortArray -> "short[${a.size}]"
                is FloatArray -> "float[${a.size}]"
                is IntArray -> "int[${a.size}]"
                is ByteBuffer -> "ByteBuffer(pos=${a.position()},lim=${a.limit()},cap=${a.capacity()})"
                else -> a.toString().take(80)
            }
        }.joinToString(",")
        log("call#${n + 1} $sig args=[$summary]")
    }

    private inline fun safe(label: String, block: () -> Unit) {
        try {
            block()
            log("install ok: $label")
        } catch (t: Throwable) {
            log("install fail [$label]: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] TtLivestreamerHook: $msg")
        Log.i(TAG, "TtLivestreamerHook: $msg")
    }
}
