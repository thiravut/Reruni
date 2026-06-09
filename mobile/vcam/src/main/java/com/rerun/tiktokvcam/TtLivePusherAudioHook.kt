package com.rerun.tiktokvcam

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MITM the encoder's audio input so the AAC frames TikTok ships to viewers
 * carry our MP4 PCM instead of the mic.
 *
 * Discovery trail (don't repeat the dead ends):
 *  - V1 substituted `AudioRecord.read` → mangled by the voice-DSP chain
 *    downstream (HAL AGC + WebRTC ANS). Distorted on viewer side.
 *  - Attempt 2 (`ILyraxAudioPlayer.open` via `RTCVideoImplV2`) — V2 engine
 *    class is never instantiated in TikTok 45.3.2's LIVE flow.
 *  - Attempt 3 (`LivePusherImpl.pushExternalAudioFrame` via ByteRTC engine) —
 *    LivePusherImpl is also never instantiated.
 *  - Attempt 4 (push via `X.0ftm.pushExternalAudioFrame` on the concrete
 *    `VeLivePusherImpV3`) — the call arrives, but `VeLivePusherImpV3.
 *    doPushExternalAudioFrame` is `return 0` (no-op). Frames are
 *    silently dropped.
 *
 * What actually works:
 *    Hook `com.ss.pusher.core.engine.MediaEncodeStream.LIZLLL(ByteBuffer,
 *    int samples, int sampleRate, int channels, long ts_ms)`. This is the
 *    obfuscated `onData` that hands PCM to the native AAC encoder
 *    (`nativeOnData`). TikTok's mic capture path lands here just before
 *    encoding, AFTER all voice DSP. If we overwrite the buffer contents
 *    in `beforeHookedMethod`, the encoder eats our MP4 PCM instead of the
 *    mic's — same format, same cadence, no DSP to mangle music.
 *
 * Two MediaEncodeStream classes exist (`com.ss.pusher.core.engine.*` and
 * `com.ss.ttlivestreamer.core.engine.*`). We hook both; whichever the
 * build actually uses will fire and feed our PCM.
 */
object TtLivePusherAudioHook {

    private const val TAG = "TiktokRerunVCam"

    // Real LIVE audio capture in TikTok 45.3.2 lives on `X.0ffu`
    // (LiveStreamAudioCapture in jadx-renamed source). It extends
    // `com.ss.ttlivestreamer.core.capture.audio.AudioCapturer` and exposes
    // `onData(Buffer, samples, sampleRate, channels, ts)` which calls
    // `nativeOnData` straight into the native encoder. MITM-ing the buffer
    // here puts our PCM into the broadcast at the same insertion point
    // mic input would normally use, downstream of any Java-side DSP.
    //
    // We also keep MediaEncodeStream hooks as a fallback for the C0fuN /
    // external-audio path that exists in the dex but isn't reached by the
    // mic flow on this build.
    private val AUDIO_CAPTURE_CLASSES = listOf(
        "X.0ffu",  // LiveStreamAudioCapture (concrete in 45.3.2)
    )
    private val MEDIA_ENCODE_STREAM_CLASSES = listOf(
        "com.ss.pusher.core.engine.MediaEncodeStream",
        "com.ss.ttlivestreamer.core.engine.MediaEncodeStream",
    )

    /** Method name on MediaEncodeStream / capture that pushes audio. */
    private const val AUDIO_ONDATA_METHOD = "LIZLLL"
    private const val CAPTURE_ONDATA_METHOD = "onData"

    private val producerStarted = AtomicBoolean(false)
    private val mitmHits = AtomicLong(0)
    private val firstMitmLogged = AtomicBoolean(false)
    // Track the encoder's last seen format so we only re-configure the
    // decoder on actual change. The MITM callback fires ~100 Hz on the
    // encoder thread; calling configureTarget on every hit would log-spam
    // and risk stalling the encoder.
    @Volatile private var lastSampleRate = 0
    @Volatile private var lastChannels = 0

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var classesFound = 0
        var methodsHooked = 0

        // PRIMARY path: hook the live-stream audio capturer's onData. This is
        // where mic-captured PCM goes from Java into native code on this
        // TikTok build. Method signature: onData(Buffer, int samples,
        // int sampleRate, int channels, long timestamp).
        for (className in AUDIO_CAPTURE_CLASSES) {
            val klass = XposedHelpers.findClassIfExists(className, cl)
            if (klass == null) {
                log("$className not found")
                continue
            }
            classesFound++
            for (m in klass.declaredMethods) {
                if (m.name != CAPTURE_ONDATA_METHOD) continue
                val pt = m.parameterTypes
                if (pt.size != 5) continue
                // Buffer is java.nio.Buffer (superclass of ByteBuffer); the
                // capturer accepts the more general type.
                if (!java.nio.Buffer::class.java.isAssignableFrom(pt[0])) continue
                if (pt[1] != java.lang.Integer.TYPE) continue
                if (pt[4] != java.lang.Long.TYPE) continue
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            mitm(param, className)
                        }
                    })
                    methodsHooked++
                    log("hooked $className.$CAPTURE_ONDATA_METHOD(${pt.joinToString { it.simpleName }})")
                } catch (t: Throwable) {
                    log("hook $className.$CAPTURE_ONDATA_METHOD failed: ${t.message}")
                }
            }
        }

        // SECONDARY fallback: MediaEncodeStream.LIZLLL (the external-audio
        // path used by C0fuN.doPushExternalAudioFrame). Kept in case some
        // build variant routes audio through there.
        for (className in MEDIA_ENCODE_STREAM_CLASSES) {
            val klass = XposedHelpers.findClassIfExists(className, cl)
            if (klass == null) continue
            classesFound++
            for (m in klass.declaredMethods) {
                if (m.name != AUDIO_ONDATA_METHOD) continue
                val pt = m.parameterTypes
                if (pt.size != 5) continue
                if (pt[0] != ByteBuffer::class.java) continue
                if (pt[1] != java.lang.Integer.TYPE) continue
                if (pt[4] != java.lang.Long.TYPE) continue
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            mitm(param, className)
                        }
                    })
                    methodsHooked++
                    log("hooked $className.$AUDIO_ONDATA_METHOD(${pt.joinToString { it.simpleName }})")
                } catch (t: Throwable) {
                    log("hook $className.$AUDIO_ONDATA_METHOD failed: ${t.message}")
                }
            }
        }

        log("install: classes_found=$classesFound methods_hooked=$methodsHooked")
    }

    private fun mitm(param: XC_MethodHook.MethodHookParam, sourceClass: String) {
        // Capturer.onData uses java.nio.Buffer (superclass); the encoder
        // path uses ByteBuffer. In practice both are direct ByteBuffers
        // carrying PCM16 LE — cast accordingly.
        val rawBuf = param.args[0] ?: return
        val buf: ByteBuffer = when (rawBuf) {
            is ByteBuffer -> rawBuf
            is java.nio.Buffer -> {
                // Some capture paths pass a Buffer view backing a ByteBuffer;
                // fall back to ByteBuffer.wrap() of the underlying memory is
                // unsafe (no array on direct), so we skip if it isn't already
                // a ByteBuffer instance.
                return
            }
            else -> return
        }
        val samples = param.args[1] as Int
        val sampleRate = param.args[2] as Int
        val channels = param.args[3] as Int
        // Bytes-per-frame for PCM16: samples * channels * 2
        val needed = samples * channels * 2
        if (needed <= 0 || buf.remaining() < needed) return

        // Start the decoder lazily once we know what format the encoder
        // expects. Only re-configure on actual format change so we don't
        // log-spam or restart the decoder thread at encoder cadence.
        if (sampleRate != lastSampleRate || channels != lastChannels) {
            lastSampleRate = sampleRate
            lastChannels = channels
            Mp4AudioProducer.configureTarget(sampleRate, channels)
            if (producerStarted.compareAndSet(false, true)) {
                Mp4AudioProducer.start()
            }
        }

        val pcm = scratchBufferFor(needed)
        val got = Mp4AudioProducer.read(pcm, 0, needed)
        if (got <= 0) {
            // Underflow — let the mic frame through so we don't introduce
            // an audible gap. Once the producer ring fills (within ~50 ms
            // of LIVE start) every subsequent frame is ours.
            return
        }
        // Pad any shortfall with silence so the encoder sees a complete
        // frame instead of stale mic data we'd be partially leaving in.
        if (got < needed) {
            java.util.Arrays.fill(pcm, got, needed, 0.toByte())
        }

        // Overwrite the buffer's payload range in-place. Save position so
        // the native call still reads from where it expects.
        val pos = buf.position()
        try {
            // Direct buffers don't support array(); use bulk put() at the
            // current position then rewind back.
            buf.put(pcm, 0, needed)
            buf.position(pos)
        } catch (t: Throwable) {
            // Fallback for read-only or otherwise immutable buffers: skip
            // substitution this frame.
            buf.position(pos)
            return
        }

        val hits = mitmHits.incrementAndGet()
        if (firstMitmLogged.compareAndSet(false, true)) {
            log("✓ first MITM: src=$sourceClass samples=$samples sr=$sampleRate ch=$channels needed=$needed got=$got")
        } else if (hits % 500L == 0L) {
            log("MITM hit #$hits (sr=$sampleRate ch=$channels samples=$samples)")
        }
    }

    /** Reuse one byte[] per thread to avoid per-call allocation churn — the
     *  encoder thread fires this hook ~100 Hz. */
    private val scratch = ThreadLocal<ByteArray>()
    private fun scratchBufferFor(size: Int): ByteArray {
        val cur = scratch.get()
        if (cur != null && cur.size >= size) return cur
        val fresh = ByteArray(size)
        scratch.set(fresh)
        return fresh
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] TtLivePusherAudioHook: $msg")
        Log.i(TAG, "TtLivePusherAudioHook: $msg")
    }
}
