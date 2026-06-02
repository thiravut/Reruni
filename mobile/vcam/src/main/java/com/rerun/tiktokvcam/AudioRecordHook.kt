package com.rerun.tiktokvcam

import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Installs hooks on `android.media.AudioRecord` so the mic input TikTok
 * captures while broadcasting gets replaced with PCM from the same MP4
 * the vcam render path is showing.
 *
 * Constructor + Builder.build hooks: capture the requested sample rate
 * and channel count, hand them to [Mp4AudioProducer] so its decoder
 * resamples to the right format.
 *
 * read(...) hooks: every read variant gets an afterHookedMethod that
 * drains the producer's ring buffer into the caller's buffer and replaces
 * the int / long return value with the byte / sample count we delivered.
 * When the producer has no audio staged the hook is transparent — the
 * normal AudioRecord.read return value goes through unchanged, so mic
 * input still works for non-broadcast surfaces (voice messages, captcha
 * audio prompts, etc.).
 */
object AudioRecordHook {

    private const val TAG = "TiktokRerunVCam"

    /** Diagnostic: log the first N read() invocations per process so we can
     *  prove the hook fires (and which overload TikTok actually uses)
     *  without spamming logcat at 50–100 Hz once the broadcast is live. */
    private val readLogsRemaining = AtomicInteger(8)
    private val firstReadHit = AtomicBoolean(false)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader

        // Constructor — most TikTok calls use the int / int / int / int / int
        // ctor. We also hook the Builder so newer call sites are covered.
        safe("AudioRecord ctor(int,int,int,int,int)") {
            XposedHelpers.findAndHookConstructor(
                AudioRecord::class.java,
                Int::class.javaPrimitiveType, // audioSource
                Int::class.javaPrimitiveType, // sampleRateInHz
                Int::class.javaPrimitiveType, // channelConfig
                Int::class.javaPrimitiveType, // audioFormat
                Int::class.javaPrimitiveType, // bufferSizeInBytes
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val sampleRate = param.args[1] as Int
                        val channelConfig = param.args[2] as Int
                        val audioFormat = param.args[3] as Int
                        val bufferSize = param.args[4] as Int
                        val ch = Mp4AudioProducer.channelConfigToCount(channelConfig)
                        log("AudioRecord ctor: sr=$sampleRate ch=$ch fmt=$audioFormat buf=$bufferSize")
                        if (audioFormat != AudioFormat.ENCODING_PCM_16BIT) {
                            log("warning: AudioRecord requested non-PCM16 format ($audioFormat); " +
                                "producer always emits PCM16 so output will be misinterpreted")
                        }
                        Mp4AudioProducer.configureTarget(sampleRate, ch)
                        Mp4AudioProducer.start()
                        // RTC libs typically dlopen() lazily right around now.
                        // Trigger a native refresh so xhook re-applies pending
                        // PLT hooks against the libs that just landed.
                        NativeAudioHook.refresh()
                    }
                }
            )
        }
        // AudioRecord.Builder is the recommended modern API. Most TikTok builds
        // still use the legacy ctor above but hooking build() costs nothing.
        safe("AudioRecord.Builder.build") {
            val builderCls = XposedHelpers.findClassIfExists(
                "android.media.AudioRecord\$Builder", cl,
            ) ?: return@safe
            XposedHelpers.findAndHookMethod(
                builderCls, "build",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val record = param.result as? AudioRecord ?: return
                        try {
                            val sr = record.sampleRate
                            val ch = record.channelCount
                            log("AudioRecord.Builder.build: sr=$sr ch=$ch")
                            Mp4AudioProducer.configureTarget(sr, ch)
                            Mp4AudioProducer.start()
                        } catch (t: Throwable) {
                            log("Builder.build hook: read params failed: ${t.message}")
                        }
                    }
                }
            )
        }

        // Per-call read hooks. Multiple overloads exist; iterate the class so
        // we don't have to spell each signature out and stay forward-compatible
        // when new variants ship.
        safe("AudioRecord.read*") {
            val readMethods = AudioRecord::class.java.declaredMethods.filter {
                it.name == "read" && !Modifier.isStatic(it.modifiers)
            }
            for (m in readMethods) {
                val sig = m.parameterTypes.joinToString(",") { it.simpleName }
                log("  read overload: ($sig) returns ${m.returnType.simpleName}")
                XposedBridge.hookMethod(
                    m,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (firstReadHit.compareAndSet(false, true)) {
                                log("✓ first read() hit — overload ($sig)")
                            }
                            handleReadResult(m.parameterTypes, param, sig)
                        }
                    },
                )
            }
            log("hooked ${readMethods.size} AudioRecord.read overload(s)")
        }
    }

    /**
     * Bridge the producer's ring buffer into whichever read overload the
     * caller used. Each overload differs in destination type (byte[],
     * short[], float[], ByteBuffer) and what return value it reports
     * (bytes vs samples).
     */
    private fun handleReadResult(
        paramTypes: Array<Class<*>>,
        param: XC_MethodHook.MethodHookParam,
        sig: String,
    ) {
        if (!Mp4AudioProducer.hasTarget) return
        // First arg = destination buffer; layout differs per signature.
        val dest = param.args.getOrNull(0) ?: return
        when (dest) {
            is ByteArray -> {
                val offset = (param.args.getOrNull(1) as? Int) ?: 0
                val length = (param.args.getOrNull(2) as? Int) ?: dest.size
                val produced = Mp4AudioProducer.read(dest, offset, length)
                if (produced > 0) param.result = produced
                logFirstReads("byte[$length] sig=$sig produced=$produced result=${param.result}")
            }
            is ShortArray -> {
                val offset = (param.args.getOrNull(1) as? Int) ?: 0
                val length = (param.args.getOrNull(2) as? Int) ?: dest.size
                val bytesNeeded = length * 2
                val tmp = ByteArray(bytesNeeded)
                val producedBytes = Mp4AudioProducer.read(tmp, 0, bytesNeeded)
                var producedShorts = 0
                if (producedBytes > 0) {
                    producedShorts = producedBytes / 2
                    var i = 0
                    while (i < producedShorts) {
                        val lo = tmp[i * 2].toInt() and 0xFF
                        val hi = tmp[i * 2 + 1].toInt() shl 8
                        dest[offset + i] = (hi or lo).toShort()
                        i++
                    }
                    param.result = producedShorts
                }
                logFirstReads("short[$length] sig=$sig produced=$producedShorts result=${param.result}")
            }
            is FloatArray -> {
                val offset = (param.args.getOrNull(1) as? Int) ?: 0
                val length = (param.args.getOrNull(2) as? Int) ?: dest.size
                val bytesNeeded = length * 2
                val tmp = ByteArray(bytesNeeded)
                val producedBytes = Mp4AudioProducer.read(tmp, 0, bytesNeeded)
                var producedSamples = 0
                if (producedBytes > 0) {
                    producedSamples = producedBytes / 2
                    var i = 0
                    while (i < producedSamples) {
                        val lo = tmp[i * 2].toInt() and 0xFF
                        val hi = tmp[i * 2 + 1].toInt() shl 8
                        val s = (hi or lo).toShort().toInt()
                        dest[offset + i] = s / 32768f
                        i++
                    }
                    param.result = producedSamples
                }
                logFirstReads("float[$length] sig=$sig produced=$producedSamples result=${param.result}")
            }
            is ByteBuffer -> {
                val length = (param.args.getOrNull(1) as? Int) ?: dest.remaining()
                val tmp = ByteArray(length)
                val produced = Mp4AudioProducer.read(tmp, 0, length)
                if (produced > 0) {
                    val saved = dest.position()
                    dest.put(tmp, 0, produced)
                    param.result = produced
                    if (dest.position() == saved) dest.position(saved + produced)
                }
                logFirstReads("ByteBuffer[$length] sig=$sig produced=$produced result=${param.result}")
            }
        }
    }

    private fun logFirstReads(detail: String) {
        if (readLogsRemaining.get() > 0 && readLogsRemaining.decrementAndGet() >= 0) {
            log("read#${8 - readLogsRemaining.get()}: $detail")
        }
    }

    private inline fun safe(label: String, block: () -> Unit) {
        try {
            block()
            log("hooked: $label")
        } catch (t: Throwable) {
            log("hook failed [$label]: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] AudioRecordHook: $msg")
        Log.i(TAG, "AudioRecordHook: $msg")
    }
}
