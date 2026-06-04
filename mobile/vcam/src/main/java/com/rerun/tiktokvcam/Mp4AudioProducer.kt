package com.rerun.tiktokvcam

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.HandlerThread
import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.io.FileInputStream
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes the audio track of the MP4 that [Mp4FrameProducer] is already
 * showing, resamples / re-channels it to whatever AudioRecord asked for,
 * and exposes a [read] entry point that [AudioRecordHook] calls from the
 * hooked `AudioRecord.read(byte[], int, int)` to splice the PCM into
 * TikTok's recording buffer in place of the mic input.
 *
 * Why a separate decoder from [Mp4FrameProducer]:
 *  - Two MediaCodec instances, two threads. AudioRecord's read loop and
 *    the camera Surface render loop run independently in TikTok; keeping
 *    the producers separate avoids cross-blocking either path.
 *  - Audio format negotiation needs to honour AudioRecord's requested
 *    rate / channel count; video uses the buffer the camera HAL bound.
 *
 * Resampling is intentionally cheap: linear interpolation between source
 * samples, mono → stereo just duplicates, stereo → mono averages. Quality
 * is acceptable for live shopping VO; if a tester flags artifacts we can
 * swap in a real polyphase filter later.
 *
 * Loop boundary: when the decoder hits EOS we rewind the extractor.
 * Output stalls briefly across the rewind (tens of ms); TikTok's audio
 * pipeline just sees silence padding and recovers without complaint.
 */
object Mp4AudioProducer {

    private const val TAG = "TiktokRerunVCam"
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** Target format AudioRecord asked for. Configured by [AudioRecordHook]
     *  on every constructor; the decode loop reconfigures itself on change. */
    @Volatile private var targetSampleRate: Int = 0
    @Volatile private var targetChannelCount: Int = 1
    /** True when we've received non-zero target params at least once. */
    @Volatile var hasTarget: Boolean = false
        private set

    private val running = AtomicBoolean(false)
    private var workerThread: HandlerThread? = null

    /**
     * Lock-protected byte ring buffer. PCM 16-bit, little-endian, target
     * format. Producer (decode loop) appends; consumer (AudioRecord hook)
     * drains.
     *
     * 512 KB ≈ 5.9 s at 44.1 kHz stereo; enough to ride out a stalled
     * read on either side without dropping or silencing audio.
     */
    private val ringCapacity = 512 * 1024
    private val ring = ByteArray(ringCapacity)
    private var writePos = 0
    private var readPos = 0
    private var ringSize = 0
    private val lock = Object()

    /** Called from AudioRecord's constructor hook. Reconfigures the decode
     *  loop if the requested format changed. No-op when called with the
     *  current values.
     *
     *  Override: when the native hook is in play, the broadcast path uses
     *  its own native AudioRecord (different instance from the one the Java
     *  ctor created) at 48 kHz stereo PCM16. The Java ctor we see reports
     *  44.1 kHz, but that AudioRecord isn't the one feeding TikTok LIVE.
     *  Resampling to 44.1 kHz and pushing into the native ring produces
     *  pitch/speed-shifted audio on the viewer side. Force 48 kHz / 2 ch
     *  whenever native is available so producer output matches what the
     *  obtainBuffer hook hands back to TikTok. */
    fun configureTarget(sampleRate: Int, channelCount: Int) {
        if (sampleRate <= 0 || channelCount <= 0) return
        // Native broadcast AudioRecord = 48 kHz stereo PCM16 (confirmed via
        // AR_DUMP scan of the AudioRecord object's mSampleRate field at
        // offset +0x140). Both 48 kHz and 44.1 kHz produced "blown-speaker"
        // artifacts on the viewer side at full-scale amplitude — likely
        // TikTok's WebRTC AGC overshooting on music transients. Output
        // amplitude is scaled below in pushDecodedChunk to keep peaks
        // within the AGC's expected voice range.
        val effectiveRate = if (NativeAudioHook.available) 48000 else sampleRate
        val effectiveCh   = if (NativeAudioHook.available) 2     else channelCount
        val changed = effectiveRate != targetSampleRate || effectiveCh != targetChannelCount
        targetSampleRate = effectiveRate
        targetChannelCount = effectiveCh
        hasTarget = true
        log("AudioRecord target: requested=${sampleRate}Hz/$channelCount → using $effectiveRate Hz x $effectiveCh ch (changed=$changed, nativePath=${NativeAudioHook.available})")
        if (changed) restart()
    }

    /**
     * Output amplitude scale applied to MP4 PCM before it lands in the
     * broadcast ring. Initially used to dodge an assumed AGC overshoot;
     * later diagnostics showed even near-silence sine bursts distorted,
     * pointing at the HAL DSP chain (AGC/ANS/AEC enabled by TikTok's
     * VOICE_COMMUNICATION audio source) rather than level. Restored to
     * 1.0 now that the AudioRecord ctor PLT hook forces the source to
     * UNPROCESSED, bypassing that chain entirely.
     */
    private const val OUTPUT_AMP_SCALE = 1.0f

    // ----------------------------------------------------------------------
    // Voice-like pre-processing — Phase 3j workaround.
    //
    // TikTok's broadcast pipeline runs a WebRTC voice-processing chain
    // (AGC + NS + voice band-pass + mono downmix) *after* AudioRecord at
    // the app level, so HAL-side knobs (audio_source) can't disable it.
    // The chain destroys music: AGC pumps wildly on dynamic transients,
    // NS detects "non-voice harmonics" and aggressively attenuates,
    // band-pass strips high-frequency content. Result: "blown speaker"
    // on the viewer side regardless of what we substitute.
    //
    // Workaround: make MP4 audio *look like voice* before it enters the
    // pipeline. Two simple DSP stages, applied per sample in the resample
    // loop so we don't add a separate pass:
    //   1. Single-pole HPF at ~80 Hz to strip rumble that confuses NS.
    //   2. Soft peak limiter + AGC: maintain near-constant amplitude
    //      around -10 dBFS so TikTok's AGC sits idle instead of chasing
    //      transients.
    //
    // Mono downmix is unavoidable (handled by their voice encoder); the
    // viewer hears the mono content duplicated to L=R. We feed identical
    // L/R from a pre-summed mono mix so we don't lose intelligibility to
    // mid-side cancellation in the downmix.
    // ----------------------------------------------------------------------

    // HPF state per channel — 1-pole. y[n] = α * (y[n-1] + x[n] - x[n-1]).
    // α = exp(-2π·fc/fs) → 0.989 at fc=80Hz / fs=48000.
    private const val HPF_ALPHA = 0.989
    @Volatile private var hpfPrevInL = 0.0
    @Volatile private var hpfPrevOutL = 0.0
    @Volatile private var hpfPrevInR = 0.0
    @Volatile private var hpfPrevOutR = 0.0

    // Peak limiter / soft AGC: keep envelope near LIMITER_TARGET.
    // Attack fast (~5 ms), release slow (~300 ms) so envelope doesn't
    // chase short peaks — exactly the behaviour TikTok's AGC is missing.
    private const val LIMITER_TARGET = 8000.0       // ~ -12 dBFS
    private const val LIMITER_ATTACK = 0.05         // 1 - exp(-1/(0.005*48000))
    private const val LIMITER_RELEASE = 0.0001
    private const val LIMITER_MAX_GAIN = 3.0        // +9 dB makeup ceiling
    @Volatile private var limiterEnvelope = LIMITER_TARGET

    private fun voiceShape(sampleL: Int, sampleR: Int): IntArray {
        // 1. Downmix to mono first — TikTok will do this anyway; doing it
        //    here keeps the limiter envelope unified across channels and
        //    avoids stereo width that gets cancelled in their mid-side.
        val mono = (sampleL + sampleR) / 2

        // 2. HPF (operating on the mono signal to halve the work).
        val xn = mono.toDouble()
        val yn = HPF_ALPHA * (hpfPrevOutL + xn - hpfPrevInL)
        hpfPrevInL = xn
        hpfPrevOutL = yn

        // 3. Limiter / soft AGC. Envelope tracks peak; gain = target/env
        //    clamped to MAX_GAIN. Fast attack ensures peaks never poke
        //    above target; slow release keeps gain stable through quiet
        //    sections so the AGC downstream doesn't have to do anything.
        val absY = kotlin.math.abs(yn)
        limiterEnvelope =
            if (absY > limiterEnvelope) {
                limiterEnvelope + (absY - limiterEnvelope) * LIMITER_ATTACK
            } else {
                limiterEnvelope + (absY - limiterEnvelope) * LIMITER_RELEASE
            }
        val gain = if (limiterEnvelope > 1.0) {
            (LIMITER_TARGET / limiterEnvelope).coerceAtMost(LIMITER_MAX_GAIN)
        } else {
            LIMITER_MAX_GAIN
        }

        val out = (yn * gain).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

        // 4. Duplicate to both channels — voice downstream is mono anyway.
        return intArrayOf(out, out)
    }

    fun start() {
        if (running.get()) return
        if (!VcamConfig.videoReady() && VcamBridge.resolve() == null) {
            log("no staged video; not starting audio producer")
            return
        }
        if (!hasTarget) {
            log("no AudioRecord target yet; deferring producer start")
            return
        }
        if (!running.compareAndSet(false, true)) return
        synchronized(lock) {
            writePos = 0
            readPos = 0
            ringSize = 0
        }
        val thread = HandlerThread("Mp4AudioProducer").apply { start() }
        workerThread = thread
        Thread { runDecodeLoop() }.apply {
            name = "Mp4AudioProducer-decode"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        workerThread?.quitSafely()
        workerThread = null
        synchronized(lock) {
            writePos = 0
            readPos = 0
            ringSize = 0
            lock.notifyAll()
        }
    }

    private fun restart() {
        stop()
        start()
    }

    /**
     * Called from the hooked `AudioRecord.read(byte[], int, int)`. Drains
     * at most [length] PCM bytes from the ring into [out] starting at
     * [offset]. Returns the number of bytes actually written; the hook
     * substitutes this as the read() return value.
     *
     * Silence (0 bytes) is returned if the producer isn't running or the
     * ring is empty — the AudioRecord caller treats that as "no audio yet",
     * which is the same as a quiet microphone.
     */
    fun read(out: ByteArray, offset: Int, length: Int): Int {
        if (!running.get() || length <= 0) return 0
        synchronized(lock) {
            if (ringSize <= 0) return 0
            val n = min(length, ringSize)
            var copied = 0
            while (copied < n) {
                val chunk = min(n - copied, ringCapacity - readPos)
                System.arraycopy(ring, readPos, out, offset + copied, chunk)
                readPos = (readPos + chunk) % ringCapacity
                copied += chunk
            }
            ringSize -= n
            lock.notifyAll()
            return n
        }
    }

    private fun runDecodeLoop() {
        while (running.get() && !Thread.interrupted()) {
            val ok = runOnePass()
            if (!ok) {
                log("audio pass failed; retry in 2s")
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
            if (!VcamConfig.LOOP) break
        }
        log("audio decode loop ended")
        running.set(false)
    }

    private fun runOnePass(): Boolean {
        val source = VcamBridge.resolve() ?: run {
            log("no source; cannot decode audio"); return false
        }
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var inputStream: FileInputStream? = null

        try {
            when (source) {
                is VcamBridge.Source.Pfd -> {
                    pfd = source.pfd
                    extractor.setDataSource(pfd.fileDescriptor)
                }
                is VcamBridge.Source.Path -> extractor.setDataSource(source.absolutePath)
            }
            val track = pickAudioTrack(extractor) ?: run {
                log("no audio track in source; will silence mic"); return false
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                log("audio track missing MIME"); return false
            }
            val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            log("audio src: $mime ${srcSampleRate} Hz x $srcChannelCount → " +
                "target ${targetSampleRate} Hz x $targetChannelCount")

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            // Resampling carry — fractional source position between samples.
            var srcPosFrac = 0.0
            // We pull from the decoder's output ByteBuffer into a transient
            // float buffer to convert + resample, then write PCM16 to the ring.
            while (!sawOutputEOS && running.get() && !Thread.interrupted()) {
                if (!sawInputEOS) {
                    val inIdx = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx) ?: continue
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEOS = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIdx, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val out = decoder.getOutputBuffer(outIdx)
                        if (out != null) {
                            out.order(ByteOrder.LITTLE_ENDIAN)
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            srcPosFrac = pushDecodedChunk(out, srcSampleRate, srcChannelCount, srcPosFrac)
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
            }
            return true
        } catch (t: Throwable) {
            log("audio pass error: ${t.javaClass.simpleName}: ${t.message}")
            XposedBridge.log(Log.getStackTraceString(t))
            return false
        } finally {
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
            try { inputStream?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Convert a chunk of decoder-produced PCM16 into the target rate /
     * channel layout and push it into the ring buffer.
     *
     * Linear interpolation in time; mono→stereo duplicates, stereo→mono
     * averages. Returns the new fractional source position that the next
     * chunk should pick up from (so we don't restart interpolation between
     * MediaCodec output buffers).
     */
    private fun pushDecodedChunk(
        srcBuf: java.nio.ByteBuffer,
        srcSampleRate: Int,
        srcChannelCount: Int,
        startFrac: Double,
    ): Double {
        val tSampleRate = targetSampleRate.takeIf { it > 0 } ?: srcSampleRate
        val tChannels = targetChannelCount.takeIf { it > 0 } ?: srcChannelCount
        val srcShortCount = srcBuf.remaining() / 2
        val srcFrameCount = srcShortCount / srcChannelCount
        if (srcFrameCount < 2) return 0.0

        // Stage the chunk as shorts to keep math simple. ~bounded by
        // MediaCodec's output buffer size (a few KB to ~64 KB).
        val srcShorts = ShortArray(srcShortCount)
        val sb = srcBuf.asShortBuffer()
        sb.get(srcShorts)

        val ratio = srcSampleRate.toDouble() / tSampleRate.toDouble()
        // We don't bridge interpolation across chunks (would require keeping
        // the last source frame of the previous chunk in a tail buffer); the
        // resulting one-sample discontinuity is inaudible in practice.
        // Clamp any negative residual carried in from the prior chunk so we
        // never index srcShorts with a negative s0 — that crashed the
        // producer on the very first run.
        var pos = startFrac.coerceIn(0.0, (srcFrameCount - 1).toDouble())
        val outFramesEstimate =
            ((srcFrameCount - pos) / ratio).toInt().coerceAtLeast(0)
        if (outFramesEstimate == 0) {
            return 0.0
        }
        val outBytes = ByteArray(outFramesEstimate * tChannels * 2)
        var outIdx = 0

        var frameI = 0
        while (frameI < outFramesEstimate) {
            val s0 = pos.toInt()
            val s1 = s0 + 1
            if (s0 < 0 || s1 >= srcFrameCount) break
            val frac = (pos - s0)

            // Read interpolated source frame as up-to-2 channels.
            val srcCh0L = srcShorts[s0 * srcChannelCount].toInt()
            val srcCh0R = srcShorts[s1 * srcChannelCount].toInt()
            val leftRaw = (srcCh0L + (srcCh0R - srcCh0L) * frac)
            val left = (leftRaw * OUTPUT_AMP_SCALE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val right = if (srcChannelCount >= 2) {
                val srcCh1L = srcShorts[s0 * srcChannelCount + 1].toInt()
                val srcCh1R = srcShorts[s1 * srcChannelCount + 1].toInt()
                val rightRaw = (srcCh1L + (srcCh1R - srcCh1L) * frac)
                (rightRaw * OUTPUT_AMP_SCALE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            } else {
                left
            }

            // Write target frame.
            if (tChannels == 1) {
                val mono = if (srcChannelCount >= 2) (left + right) / 2 else left
                outBytes[outIdx++] = (mono and 0xFF).toByte()
                outBytes[outIdx++] = ((mono shr 8) and 0xFF).toByte()
            } else {
                outBytes[outIdx++] = (left and 0xFF).toByte()
                outBytes[outIdx++] = ((left shr 8) and 0xFF).toByte()
                outBytes[outIdx++] = (right and 0xFF).toByte()
                outBytes[outIdx++] = ((right shr 8) and 0xFF).toByte()
                if (tChannels > 2) {
                    // Pad extra channels with the mono mix.
                    val mono = if (srcChannelCount >= 2) (left + right) / 2 else left
                    for (c in 2 until tChannels) {
                        outBytes[outIdx++] = (mono and 0xFF).toByte()
                        outBytes[outIdx++] = ((mono shr 8) and 0xFF).toByte()
                    }
                }
            }
            pos += ratio
            frameI++
        }

        if (outIdx > 0) appendToRing(outBytes, outIdx)
        // Carry only the sub-sample phase so we keep aligned with the source
        // grid; the integer part of pos resets to 0 at the start of the next
        // chunk. Always in [0, 1).
        return pos - pos.toInt()
    }

    private fun appendToRing(buf: ByteArray, length: Int) {
        if (length <= 0) return
        // Native ring feeds the C++ AudioRecord::obtainBuffer hook (the
        // path TikTok LIVE actually pulls PCM from). Java ring below still
        // serves Java-side AudioRecord.read hooks for non-broadcast surfaces.
        //
        // Backpressure: MediaCodec decodes much faster than realtime, so
        // without pacing here the producer floods the 256 KB native ring
        // ~10× faster than TikTok drains it. The ring's drop-oldest
        // overflow handler then chops the audio into a stuttering mess.
        // Sleep until the ring has room for another chunk so decode rate
        // tracks the broadcast's pull rate.
        //
        // Target headroom: keep <= 32 KB queued ≈ 170 ms of 48 kHz stereo.
        // Anything larger adds A/V latency the operator can hear lagging
        // behind the video; anything smaller risks underflow → silence
        // padding bleeding into the audio.
        if (NativeAudioHook.available) {
            val headroom = 32 * 1024
            while (running.get() && NativeAudioHook.ringAvailable() > headroom) {
                try { Thread.sleep(10) } catch (_: InterruptedException) { return }
            }
            NativeAudioHook.writePcm(buf, length)
        }
        synchronized(lock) {
            var written = 0
            while (written < length) {
                // Bounded wait if ring is full — drop oldest by advancing readPos.
                if (ringSize >= ringCapacity) {
                    val drop = max(1, ringCapacity / 8)
                    readPos = (readPos + drop) % ringCapacity
                    ringSize -= drop
                }
                val chunk = min(length - written, ringCapacity - writePos)
                val free = ringCapacity - ringSize
                val take = min(chunk, free)
                if (take <= 0) continue
                System.arraycopy(buf, written, ring, writePos, take)
                writePos = (writePos + take) % ringCapacity
                ringSize += take
                written += take
            }
            lock.notifyAll()
        }
    }

    private fun pickAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /** Helper to map AudioRecord's CHANNEL_IN_MONO / CHANNEL_IN_STEREO. */
    fun channelConfigToCount(channelConfig: Int): Int = when (channelConfig) {
        AudioFormat.CHANNEL_IN_MONO   -> 1
        AudioFormat.CHANNEL_IN_STEREO -> 2
        else -> 1
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] Mp4AudioProducer: $msg")
        Log.i(TAG, "Mp4AudioProducer: $msg")
    }
}
