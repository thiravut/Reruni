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
     * Generation counter for decode-thread liveness. Incremented in [start]
     * after the CAS succeeds. The decode loop captures the value at thread
     * spawn and exits when it no longer matches.
     *
     * Why this exists: [Mp4FrameProducer.onSurfacesChanged] calls stop() →
     * start() back-to-back during camera surface transitions. Stop sets
     * `running=false`, but the decode thread only checks `running` between
     * runOnePass iterations (each ~10 s). The immediate next start() sets
     * `running=true` again — when the old decode thread eventually returns
     * from runOnePass, it sees running=true and continues, alongside the
     * new thread. The runId mismatch lets us evict the old thread
     * deterministically.
     */
    private val runId = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Set by [signalLoopBoundary] when video reaches EOS, cleared at the
     * start of each pass. The audio MP4 track and video MP4 track in the
     * same file usually differ in duration by tens of ms (AAC frame
     * alignment), so independent track-EOS loops drift apart by that delta
     * every iteration and accumulate. Pinning audio's rewind to video's
     * rewind keeps the per-loop offset bounded to the audio decoder's
     * restart cost (~50 ms) instead of growing without bound.
     */
    @Volatile private var abortCurrentPass: Boolean = false

    /**
     * Default target rate (Hz). The native AR ctor PLT hook attempts to
     * force the broadcast AR to 48000 Hz but TikTok 45.3.2 apparently
     * creates the broadcast AR via a different ctor variant that our PLT
     * hook misses — empirically the consumer still drains at ~72 kHz on
     * Samsung A15 (per Pond's "too fast" report after the PLT force).
     *
     * Use 72000 to match the actual consumer rate. Cubic resampling +
     * pre-LPF (introduced 2026-06-05) handle the 44.1 → 72 upsample
     * cleanly enough to keep aliasing out of the audible band.
     */
    private const val NATIVE_BROADCAST_RATE_HZ = 72000

    /**
     * Pre-resample anti-alias LPF state. We apply a 1-pole IIR low-pass
     * at ~16 kHz on source PCM samples *before* the cubic upsample.
     * Removes the 16-22 kHz source content that would otherwise fold
     * back into the audible band as aliased images at the 1.63× upsample
     * ratio (44.1 → 72). Symptom it targets: the "ลำโพงแตก" crackling
     * that survived cubic interp.
     *
     * α = 1 - exp(-2π × fc/fs) for a 1-pole IIR. For fc=16000,
     * fs=44100: α ≈ 0.898. Hardcoded — works close enough for the
     * 44.1/48 kHz source rates we'll see in practice.
     */
    private const val DEFAULT_LPF_CUTOFF_HZ = 16000
    @Volatile private var preLpfPrevL = 0.0
    @Volatile private var currentLpfAlpha = 0.0   // 0 = disabled (passthrough)

    /**
     * Optional noise-floor injection — adds low-level white noise to each
     * output sample. Hypothesis: TikTok's voice DSP downstream of the
     * mic buffer might be calibrated for "real mic" input which always
     * has some noise floor (~-50 dBFS from electronics + room). Our
     * synthesized PCM has *zero* noise floor; DSP detecting "perfectly
     * clean signal" could trigger weird processing (excessive denoising,
     * dynamic compression artefacts) that we hear as "ทุ้ม + แตก".
     *
     * File contents = int16 noise amplitude (peak), e.g. 100 ≈ -50 dBFS,
     * 327 ≈ -40 dBFS, 1024 ≈ -30 dBFS. 0 = disabled (default).
     */
    private const val NOISE_OVERRIDE_PATH =
        "/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_noise.txt"
    @Volatile private var currentNoiseAmp = 0

    private fun refreshNoiseAmp() {
        val n = try {
            val f = java.io.File(NOISE_OVERRIDE_PATH)
            if (!f.exists() || f.length() <= 0L || f.length() > 16L) 0
            else f.readText().trim().toIntOrNull()?.coerceIn(0, 8192) ?: 0
        } catch (_: Throwable) {
            0
        }
        if (n != currentNoiseAmp) {
            log("noise floor: ${currentNoiseAmp} → ${n} (int16 peak)")
            currentNoiseAmp = n
        }
    }

    // Carry the last 3 source mono samples across chunk boundaries so the
    // cubic interp at the START of the next chunk has real previous samples
    // (not boundary-clamped copies of s0). Without this carry, every chunk
    // boundary (~50/sec from MediaCodec's ~20 ms output chunks) introduces
    // a small discontinuity in the interpolated waveform — perceptible as
    // constant low-level crackling that matches the "ลำโพงแตก" symptom.
    @Volatile private var prevChunkTail0: Short = 0   // last source mono sample of prev chunk
    @Volatile private var prevChunkTail1: Short = 0   // second-to-last (for cubic s-2 if ever needed)
    @Volatile private var prevChunkValid: Boolean = false

    /** Override file: integer Hz cutoff. 0 = disable LPF. Per-chunk refresh. */
    private const val LPF_OVERRIDE_PATH =
        "/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_lpf_hz.txt"

    /** Override file: any contents → enable pure passthrough. When the
     *  source MP4's audio rate + channel count match our broadcast
     *  target, this bypasses pushDecodedChunk entirely and memcpys the
     *  decoder's PCM directly into the ring. Used as the last
     *  diagnostic for whether ANY of our processing contributes to the
     *  remaining crackling. */
    private const val PURE_BYPASS_PATH =
        "/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_pure_pass.txt"

    private fun pureBypassEnabled(): Boolean = try {
        java.io.File(PURE_BYPASS_PATH).exists()
    } catch (_: Throwable) {
        false
    }

    private fun readLpfCutoffOverride(): Int? = try {
        val f = java.io.File(LPF_OVERRIDE_PATH)
        if (!f.exists() || f.length() <= 0L || f.length() > 16L) null
        else f.readText().trim().toIntOrNull()?.takeIf { it in 0..24000 }
    } catch (_: Throwable) {
        null
    }

    /** α = 1 - exp(-2π·fc/fs) for a 1-pole IIR low-pass.
     *  cutoffHz=0 returns 1.0 (passthrough — y = x). */
    private fun computeLpfAlpha(cutoffHz: Int, fs: Int): Double {
        if (cutoffHz <= 0 || fs <= 0) return 1.0
        return 1.0 - kotlin.math.exp(-2.0 * Math.PI * cutoffHz.toDouble() / fs.toDouble())
    }

    private fun refreshLpfAlpha(srcSampleRate: Int) {
        val cutoff = readLpfCutoffOverride() ?: DEFAULT_LPF_CUTOFF_HZ
        val newAlpha = computeLpfAlpha(cutoff, srcSampleRate)
        if (kotlin.math.abs(newAlpha - currentLpfAlpha) > 0.001) {
            log("LPF cutoff: ${cutoff} Hz (α=${"%.4f".format(newAlpha)})" +
                (if (cutoff == 0) " — disabled" else ""))
            currentLpfAlpha = newAlpha
        }
    }

    /**
     * Optional override file: a single integer sample rate (Hz). Read on
     * every [configureTarget] call so a TikTok process restart picks it
     * up without an APK reinstall. Falls back to [NATIVE_BROADCAST_RATE_HZ]
     * when missing or unparseable.
     */
    private const val RATE_OVERRIDE_PATH =
        "/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_rate.txt"

    /**
     * Optional mode override:
     *  - "mp4" (default): decode MP4 audio → PCM → substitute into mic buffer
     *  - "tone": stream a 1000 Hz sine at the configured target rate;
     *     viewer perceived freq = 1000 × (native_rate / target_rate)
     *  - "speaker": play MP4 audio through the device speaker via
     *     MediaPlayer and stop substituting the mic buffer; the mic
     *     captures the speaker acoustically. This is the SamuraiLive
     *     architecture, kept as a diagnostic A/B baseline. If audio
     *     sounds clean in "speaker" mode but distorted in "mp4" mode,
     *     the issue is in our PCM injection pipeline (resampler, encoder
     *     rewrite, downstream resampling). Multi-device cross-
     *     contamination makes this unviable for production but it's a
     *     pure test of upstream-of-mic quality.
     */
    private const val MODE_OVERRIDE_PATH =
        "/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_mode.txt"

    /** Tone frequency for diagnostic mode. 1000 Hz is the canonical test
     *  tone and sits clear of voice fundamentals so DSP downstream is less
     *  likely to mangle it. */
    private const val TONE_FREQ_HZ = 1000.0

    private fun readRateOverride(): Int? = try {
        val f = java.io.File(RATE_OVERRIDE_PATH)
        if (!f.exists() || f.length() <= 0L || f.length() > 32L) null
        else f.readText().trim().toIntOrNull()?.takeIf { it in 8000..192000 }
    } catch (_: Throwable) {
        null
    }

    /**
     * Production default = "speaker" (acoustic loopback through device
     * speaker → mic → TikTok pipeline). After exhaustive PCM-injection
     * investigation on TikTok 45.3.2 + Samsung A15 (2026-06-08), every
     * post-decode processing knob we could touch failed to clean up the
     * "ลำโพงแตก" residue that downstream voice DSP imposes on any
     * non-silent PCM written into the AudioRecord buffer. Speaker mode
     * bypasses the issue by routing audio through the mic — TikTok's
     * pipeline treats it as natural mic input and processes cleanly.
     *
     * Trade-off: multi-device rooms get cross-contamination (one phone's
     * speaker leaks into another's mic). For single-device tests + demos
     * speaker mode is the right default; for true multi-device the path
     * is AAC injection at the RTMP transport layer (Option B).
     *
     * Mode options:
     *  - "speaker" (default)   — MediaPlayer → device speaker → mic
     *  - "mp4"                 — PCM substitution into AudioRecord buffer
     *  - "tone"                — 1 kHz sine into AudioRecord buffer
     *  - "lyrax"               — Inject staged MP4 through TikTok's own
     *                            LyraxAudioPlayer with mixingType=PUBLISH.
     *                            Routes audio through the broadcast Aux
     *                            Pipeline (TikTok's in-LIVE music feature
     *                            path), bypassing voice DSP entirely.
     *                            Multi-device safe (no acoustic loopback).
     *  - "rtmp_inject" / "rtmp_diag" — SUPERSEDED 2026-06-08 (Option B was
     *                            based on misread architecture; TikTok LIVE
     *                            uses native UDP RTC, not standard RTMP).
     *                            Hooks kept for diag log-only fallback.
     */
    private const val DEFAULT_MODE = "speaker"

    private fun readModeOverride(): String = try {
        val f = java.io.File(MODE_OVERRIDE_PATH)
        if (!f.exists() || f.length() <= 0L || f.length() > 32L) DEFAULT_MODE
        else f.readText().trim().lowercase()
    } catch (_: Throwable) {
        DEFAULT_MODE
    }

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
        // 2026-06-04 rate diagnostic. Pond's listening test reframed the
        // V1 "blown speaker" artefact: it isn't voice-DSP distortion, it's
        // PCM playing 1.5–2× too fast. obtainBuffer log analysis confirms
        // it — the encoder consumes ~1440 frames every ~20 ms steady-state
        // (pair of 960 + 480 calls per cycle) = **~72 000 Hz** effective
        // native rate on this Samsung A15 build.
        //
        // The phase3 doc baked in `forced 48000` from an older device's
        // AR_DUMP. On this device the native AR really is ~72 kHz, so our
        // 48 kHz output gets sucked through at 72/48 = 1.5× speed exactly
        // matching the perceptual symptom.
        //
        // Force our output to track the native consumption rate. 72 kHz
        // isn't a standard MP4 sample rate but our linear resampler in
        // pushDecodedChunk accepts any rational target — it'll upsample
        // 44.1/48 kHz MP4 sources to whatever we ask for.
        val overrideRate = readRateOverride()
        val effectiveRate = when {
            !NativeAudioHook.available -> sampleRate
            overrideRate != null -> overrideRate
            else -> NATIVE_BROADCAST_RATE_HZ
        }
        val effectiveCh   = if (NativeAudioHook.available) 2 else channelCount
        if (overrideRate != null) {
            log("rate override active: $overrideRate Hz (from $RATE_OVERRIDE_PATH)")
        }
        val changed = effectiveRate != targetSampleRate || effectiveCh != targetChannelCount
        targetSampleRate = effectiveRate
        targetChannelCount = effectiveCh
        hasTarget = true
        log("AudioRecord target: requested=${sampleRate}Hz/$channelCount → using $effectiveRate Hz x $effectiveCh ch (changed=$changed, nativePath=${NativeAudioHook.available})")
        if (changed) restart()
    }

    /**
     * Default output amplitude scale. 0.85 ≈ -1.4 dBTP headroom under
     * TikTok's documented -1 dBTP peak ceiling. Override at runtime via
     * [AMP_OVERRIDE_PATH] so Pond can iterate without an APK rebuild —
     * the "blown speaker" residue at 0.85 is likely from peaks still
     * clipping through the encoder; try 0.7 / 0.6 / 0.5 to dial in.
     */
    private const val DEFAULT_currentAmpScale = 0.85f
    private const val AMP_OVERRIDE_PATH =
        "/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_amp.txt"

    private fun readAmpOverride(): Float? = try {
        val f = java.io.File(AMP_OVERRIDE_PATH)
        if (!f.exists() || f.length() <= 0L || f.length() > 16L) null
        // Allow 0.0 explicitly so we can force silent output as a
        // diagnostic — "does the substitution mechanism itself produce
        // clean silence when given zero input?"
        else f.readText().trim().toFloatOrNull()?.takeIf { it in 0.0f..1.5f }
    } catch (_: Throwable) {
        null
    }

    @Volatile private var currentAmpScale: Float = DEFAULT_currentAmpScale

    private fun refreshAmpScale() {
        val override = readAmpOverride()
        val newScale = override ?: DEFAULT_currentAmpScale
        if (kotlin.math.abs(newScale - currentAmpScale) > 0.001f) {
            log("amp scale: ${currentAmpScale} → ${newScale}" +
                (if (override != null) " (from $AMP_OVERRIDE_PATH)" else " (default)"))
            currentAmpScale = newScale
        }
    }

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
        val mode = readModeOverride()
        if (mode == "tone") {
            // Tone synthesizes its own samples, doesn't need a staged MP4.
        } else if (!VcamConfig.videoReady() && VcamBridge.resolve() == null) {
            // MP4 / speaker / rtmp_inject all consume the staged MP4 file.
            log("no staged video; not starting audio producer")
            return
        }
        if (mode != "speaker" && mode != "rtmp_inject" && mode != "rtmp_diag" && mode != "ws_inject" && !hasTarget) {
            log("no AudioRecord target yet; deferring producer start")
            return
        }
        // ws_inject bootstrap (2026-06-09): TikTok's AudioRecord ctor hook
        // sometimes misses (different ctor path per session on A15 Android
        // 16) → hasTarget never becomes true → without this seed, WS
        // client never starts and viewer hears nothing. Read rate
        // override file directly so PCM resample matches encoder rate.
        // Also trigger xhook refresh — the ctor hook is the normal
        // refresh trigger (see AudioRecordHook); without it, the
        // aacEncEncode PLT slot in libvolcenginertc.so stays unhooked
        // and viewer hears TikTok's mic+DSP audio instead of our PC AAC.
        if (mode == "ws_inject" && !hasTarget) {
            val override = readRateOverride() ?: NATIVE_BROADCAST_RATE_HZ
            targetSampleRate = override
            targetChannelCount = 2
            hasTarget = true
            log("ws_inject bootstrap: seeded target = $override Hz / 2 ch")
            NativeAudioHook.refresh()
        }
        if (!running.compareAndSet(false, true)) return
        val myRunId = runId.incrementAndGet()
        synchronized(lock) {
            writePos = 0
            readPos = 0
            ringSize = 0
        }
        // Passthrough on the mic-substitution hook: ON for all modes that
        // don't drive the obtainBuffer ring. lyrax mode leaves the mic
        // untouched (TikTok captures ambient — discarded because lyrax's
        // PUBLISH-only player overrides the broadcast audio path).
        // ws_inject NOT in this list — see rationale at the videoReady
        // check above. PCM substitution provides audio activity that
        // makes TikTok's encoder fire so our AAC PLT hook gets hits.
        val leaveMicAlone = mode == "speaker" || mode == "rtmp_inject" ||
                            mode == "rtmp_diag"
        NativeAudioHook.setPassthrough(leaveMicAlone)

        // Default off — only `ws_inject` mode below turns substitution on.
        NativeAudioHook.setRtmpInjectEnabled(false)
        // ws_inject mode = Option G live: connect to PC encoder via
        // WebSocket and substitute AAC frames at the aacEncEncode hook.
        if (mode == "ws_inject") {
            Mp4GWsClient.start()
        }

        val thread = HandlerThread("Mp4AudioProducer").apply { start() }
        workerThread = thread
        when (mode) {
            "rtmp_inject" -> {
                log("⚠ MODE: rtmp_inject — pre-encoded AAC injection at RTMP " +
                    "layer. TikTok's encoder output is replaced per-frame; " +
                    "viewer hears the staged MP4's AAC track directly. " +
                    "Mic is left untouched (ambient sound encoded but " +
                    "discarded). Clear $MODE_OVERRIDE_PATH to return to default.")
                // No per-thread work here — Mp4AacProducer runs its own
                // dedicated demux thread. Just park.
                Thread {
                    while (running.get() && !Thread.interrupted()) {
                        try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    }
                }.apply {
                    name = "Mp4AudioProducer-rtmp-park"
                    isDaemon = true
                    start()
                }
            }
            "rtmp_diag" -> {
                log("⚠ MODE: rtmp_diag — PLT hook on rtmp_client_push_audio " +
                    "is ACTIVE in log-only mode. Check logcat for " +
                    "'rtmp_push_audio#N' lines to see what TikTok pushes. " +
                    "Switch to rtmp_inject to start AAC substitution.")
                Thread {
                    while (running.get() && !Thread.interrupted()) {
                        try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    }
                }.apply {
                    name = "Mp4AudioProducer-rtmp-diag-park"
                    isDaemon = true
                    start()
                }
            }
            "tone" -> {
                log("⚠ DIAGNOSTIC MODE: tone (${TONE_FREQ_HZ.toInt()} Hz sine at " +
                    "${targetSampleRate} Hz × ${targetChannelCount} ch) — viewer hears a tone, not MP4. " +
                    "Clear $MODE_OVERRIDE_PATH to return to MP4 playback.")
                Thread { runToneLoop() }.apply {
                    name = "Mp4AudioProducer-tone"
                    isDaemon = true
                    start()
                }
            }
            "speaker" -> {
                log("⚠ DIAGNOSTIC MODE: speaker (acoustic loopback) — MP4 plays through " +
                    "device speaker, mic captures it, our PCM substitution is DISABLED. " +
                    "Use this only as A/B baseline against mp4 mode; multi-device cross- " +
                    "contamination makes it unviable for production. " +
                    "Clear $MODE_OVERRIDE_PATH to return to MP4 PCM injection.")
                Thread { runSpeakerLoop() }.apply {
                    name = "Mp4AudioProducer-speaker"
                    isDaemon = true
                    start()
                }
            }
            else -> {
                Thread { runDecodeLoop(myRunId) }.apply {
                    name = "Mp4AudioProducer-decode"
                    isDaemon = true
                    start()
                }
            }
        }
    }

    /**
     * Synthesize a continuous [TONE_FREQ_HZ] sine into the producer ring at
     * the configured target rate. Used to measure exact native consumption
     * rate without an ear A/B: viewer hears the tone, screen-record the
     * LIVE, then ffmpeg/sox reads the peak frequency from the recording.
     *
     * Expected: if we configured target=R and consumer plays at R too,
     * viewer hears 1000 Hz. If consumer plays at C ≠ R, viewer hears
     * 1000 × (C/R) Hz. Solve for C, set our target to C, done.
     */
    private fun runToneLoop() {
        // Reset the loop-abort flag so a stray signalLoopBoundary call
        // (e.g. set before mode was switched) doesn't silently suppress
        // appendToRing writes the tone needs to make.
        abortCurrentPass = false
        val sampleRate = targetSampleRate.takeIf { it > 0 } ?: 48000
        val channels = targetChannelCount.takeIf { it > 0 } ?: 2
        val phaseIncr = 2.0 * Math.PI * TONE_FREQ_HZ / sampleRate
        // 100 ms chunks — small enough to pace cleanly against the
        // backpressure throttle, big enough that allocation overhead stays
        // below threading cost.
        val chunkFrames = (sampleRate / 10).coerceAtLeast(1)
        val chunkBytes = chunkFrames * channels * 2
        val chunk = ByteArray(chunkBytes)
        // Loud enough to dominate any residual mic input mixed alongside,
        // and well clear of the noise floor so aubiopitch latches on the
        // tone immediately. ~50 % of full scale = -6 dBFS, still under
        // the -1 dBTP clip ceiling.
        val amp = 16000
        var phase = 0.0
        var pushed = 0L
        log("tone loop entered (target=${sampleRate} Hz × ${channels} ch, chunkFrames=$chunkFrames)")
        while (running.get() && !Thread.interrupted()) {
            // Defensive: clear the abort flag every iteration in case
            // signalLoopBoundary fires from the video producer (we already
            // gate that on mode, but belt-and-braces).
            abortCurrentPass = false
            var i = 0
            while (i < chunkFrames) {
                val s = (kotlin.math.sin(phase) * amp).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                phase += phaseIncr
                if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                val frameOffset = i * channels * 2
                for (c in 0 until channels) {
                    val o = frameOffset + c * 2
                    chunk[o] = (s and 0xFF).toByte()
                    chunk[o + 1] = ((s shr 8) and 0xFF).toByte()
                }
                i++
            }
            appendToRing(chunk, chunkBytes)
            pushed++
            if (pushed == 1L || pushed % 50L == 0L) {
                log("tone: pushed ${pushed * chunkFrames} frames @ ${TONE_FREQ_HZ.toInt()} Hz")
            }
        }
        log("tone loop exited (pushed ${pushed * chunkFrames} frames total)")
        running.set(false)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        workerThread?.quitSafely()
        workerThread = null
        // Always re-arm substitution on stop so a stale speaker-mode
        // passthrough doesn't leak into the next session.
        NativeAudioHook.setPassthrough(false)
        Mp4GWsClient.stop()
        releaseSpeakerPlayer()
        synchronized(lock) {
            writePos = 0
            readPos = 0
            ringSize = 0
            lock.notifyAll()
        }
    }

    @Volatile private var speakerPlayer: android.media.MediaPlayer? = null

    private fun releaseSpeakerPlayer() {
        speakerPlayer?.let { p ->
            try { p.stop() } catch (_: Throwable) {}
            try { p.release() } catch (_: Throwable) {}
        }
        speakerPlayer = null
    }

    /**
     * Production speaker-loopback path. Spin up an Android MediaPlayer
     * pointed at the staged MP4 (audio track only — MediaPlayer ignores
     * video by default if it has no surface), set volume to max, let it
     * loop. The device speaker emits the audio; the broadcaster's mic
     * captures it as ambient sound; TikTok's pipeline handles the mic
     * input through its normal voice path. PCM substitution stays
     * disabled via the passthrough flag set by [start].
     *
     * Audio attributes: STREAM_MUSIC + USAGE_MEDIA for full-quality DAC
     * routing through the device's media speaker (not the voice
     * earpiece, not voice-call-tuned downsampling).
     */
    private fun runSpeakerLoop() {
        log("speaker loop entered")
        try {
            val source = VcamBridge.resolve() ?: run {
                log("speaker: no staged source; bailing")
                running.set(false)
                return
            }
            val mp = android.media.MediaPlayer()
            // Pin to media-stream audio attributes so the system routes
            // through the loud speaker (not the voice earpiece) and uses
            // the music-quality codec path, not voice-tuned downsampling.
            try {
                mp.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
            } catch (t: Throwable) {
                log("speaker: setAudioAttributes failed: ${t.message}")
            }
            when (source) {
                is VcamBridge.Source.Pfd -> {
                    mp.setDataSource(source.pfd.fileDescriptor)
                }
                is VcamBridge.Source.Path -> mp.setDataSource(source.absolutePath)
            }
            mp.isLooping = true
            // Full-volume L+R; SamuraiLive uses 1.0/1.0 too. The system
            // mixer / AGC will normalise on the encoder side.
            mp.setVolume(1.0f, 1.0f)
            mp.setOnErrorListener { _, what, extra ->
                log("speaker: MediaPlayer error what=$what extra=$extra")
                false
            }
            mp.prepare()
            mp.start()
            speakerPlayer = mp
            log("speaker: MediaPlayer started looping (source=${source.javaClass.simpleName})")
            // Park the thread; MediaPlayer runs on its own internal
            // threads. Wait until stop().
            while (running.get() && !Thread.interrupted()) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        } catch (t: Throwable) {
            log("speaker loop error: ${t.javaClass.simpleName}: ${t.message}")
            XposedBridge.log(Log.getStackTraceString(t))
        } finally {
            releaseSpeakerPlayer()
            log("speaker loop exited")
            running.set(false)
        }
    }

    private fun restart() {
        stop()
        start()
    }

    /**
     * Called from [Mp4FrameProducer] each time video reaches EOS. Aborts
     * whatever the audio decoder is doing so its next pass starts from
     * t=0 at the same loop boundary as video. Without this signal the
     * audio and video paths each loop on their own track-EOS — and the
     * tens-of-ms duration mismatch between the MP4's audio and video
     * tracks accumulates as A/V drift across loops.
     *
     * In tone mode this signal is a no-op — there's no MP4 pass to
     * abort, the tone is continuous. Letting it set the flag would
     * silence the tone after the first video loop (~22 s) because
     * appendToRing bails when abortCurrentPass is true.
     */
    fun signalLoopBoundary() {
        if (readModeOverride() == "tone") return
        abortCurrentPass = true
        // Wake the producer if it's parked in the backpressure sleep so
        // the abort takes effect immediately instead of after the next
        // 10 ms tick.
        synchronized(lock) { lock.notifyAll() }
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

    private fun runDecodeLoop(myRunId: Long) {
        while (running.get() && !Thread.interrupted() && runId.get() == myRunId) {
            val ok = runOnePass(myRunId)
            if (!ok) {
                log("audio pass failed; retry in 2s")
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
            if (!VcamConfig.LOOP) break
        }
        // Only the active runId should clear `running`. A stale thread
        // exiting because it was superseded must NOT reset running, because
        // the new thread is the one currently holding it true.
        val staleExit = runId.get() != myRunId
        log("audio decode loop ended (runId=$myRunId, stale=$staleExit)")
        if (!staleExit) running.set(false)
    }

    private fun runOnePass(myRunId: Long = runId.get()): Boolean {
        val source = VcamBridge.resolve() ?: run {
            log("no source; cannot decode audio"); return false
        }
        // Reset the abort flag on entry — any signal from a prior pass has
        // already been honoured by the time we get here, and a fresh signal
        // arriving mid-setup is fine: the inner while loop will pick it up
        // on the next iteration.
        abortCurrentPass = false
        // Invalidate the chunk-boundary cubic carry — the next chunk is
        // the first of a fresh MP4 pass, so prev-pass samples are stale.
        prevChunkValid = false
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
            while (!sawOutputEOS && running.get() && !Thread.interrupted() &&
                   !abortCurrentPass && runId.get() == myRunId) {
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
                            // PURE PASSTHROUGH diagnostic: if the source
                            // already matches our target rate + stereo and
                            // the override file exists, memcpy the bytes
                            // straight into the ring — bypass downmix /
                            // LPF / cubic / amp entirely. Used to prove
                            // whether ANY of our post-decode processing
                            // contributes to the crackling.
                            if (pureBypassEnabled() &&
                                srcSampleRate == targetSampleRate &&
                                srcChannelCount == targetChannelCount) {
                                val n = out.remaining()
                                if (n > 0) {
                                    val raw = ByteArray(n)
                                    out.get(raw)
                                    appendToRing(raw, n)
                                }
                                srcPosFrac = 0.0
                            } else {
                                srcPosFrac = pushDecodedChunk(out, srcSampleRate, srcChannelCount, srcPosFrac)
                            }
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
        // Pick up amp / noise override file changes per chunk so Pond
        // can iterate without forcing a TikTok restart.
        refreshAmpScale()
        refreshNoiseAmp()
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

        // ===== Stage 1: stereo-to-mono downmix =====
        // Adopt the expert-recommended architecture: collapse to mono
        // *first*, before LPF or resample, so the rest of the chain
        // operates on a single unified signal. TikTok's voice pipeline
        // downmixes to mono internally anyway; doing it ourselves keeps
        // L=R perfectly (no mid-side phase artefacts that survive their
        // downmixer) and halves the per-sample CPU for LPF/cubic.
        val srcMono: ShortArray
        val srcMonoCount: Int
        if (srcChannelCount == 2) {
            srcMonoCount = srcFrameCount
            srcMono = ShortArray(srcMonoCount)
            var i = 0
            while (i < srcMonoCount) {
                val l = srcShorts[i * 2].toInt()
                val r = srcShorts[i * 2 + 1].toInt()
                srcMono[i] = ((l + r) / 2).toShort()
                i++
            }
        } else {
            srcMonoCount = srcFrameCount
            srcMono = srcShorts
        }

        // ===== Stage 2: anti-alias pre-LPF =====
        // Configurable 1-pole IIR low-pass. Default 16 kHz cutoff;
        // override via [LPF_OVERRIDE_PATH] (0 = disabled).
        refreshLpfAlpha(srcSampleRate)
        val alpha = currentLpfAlpha
        if (alpha < 0.999) {
            val oneMinusAlpha = 1.0 - alpha
            var i = 0
            while (i < srcMonoCount) {
                val x = srcMono[i].toDouble()
                val y = alpha * x + oneMinusAlpha * preLpfPrevL
                preLpfPrevL = y
                srcMono[i] = y.toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                i++
            }
        }

        // ===== Stage 3: cubic resample (mono) =====
        val ratio = srcSampleRate.toDouble() / tSampleRate.toDouble()
        var pos = startFrac.coerceIn(0.0, (srcMonoCount - 1).toDouble())
        val outFramesEstimate =
            ((srcMonoCount - pos) / ratio).toInt().coerceAtLeast(0)
        if (outFramesEstimate == 0) {
            return 0.0
        }
        val outBytes = ByteArray(outFramesEstimate * tChannels * 2)
        var outIdx = 0

        var frameI = 0
        while (frameI < outFramesEstimate) {
            val s0 = pos.toInt()
            val s1 = s0 + 1
            if (s0 < 0 || s1 >= srcMonoCount) break
            val frac = (pos - s0)

            // Catmull-Rom cubic interpolation on the mono signal.
            // For sm1: when s0 == 0 (chunk start), use the previous
            // chunk's last sample (prevChunkTail0) instead of clamping
            // to srcMono[0]. This eliminates the chunk-boundary
            // discontinuity that produces audible crackling at ~50 Hz
            // (MediaCodec output rate).
            //
            // For s2: still clamp to srcMono[s1] at chunk end — we
            // don't have the next chunk's first sample yet without
            // deferring output. The one-sample edge artefact at chunk
            // end is much less perceptible than the chunk-start one.
            val ym1 = if (s0 > 0) {
                srcMono[s0 - 1].toInt()
            } else if (prevChunkValid) {
                prevChunkTail0.toInt()
            } else {
                srcMono[s0].toInt()
            }
            val y0  = srcMono[s0 ].toInt()
            val y1  = srcMono[s1 ].toInt()
            val y2  = if (s1 + 1 < srcMonoCount) srcMono[s1 + 1].toInt() else srcMono[s1].toInt()
            val raw = catmullRom(ym1, y0, y1, y2, frac)

            // ===== Stage 4: amp scale + optional noise floor =====
            var sample = (raw * currentAmpScale).toInt()
            if (currentNoiseAmp > 0) {
                // Cheap white-ish noise; not cryptographic, just enough
                // to fake a mic noise floor for TikTok's voice DSP.
                val n = ((Math.random() - 0.5) * 2.0 * currentNoiseAmp).toInt()
                sample += n
            }
            sample = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            // Stage 5 (writing) — duplicate mono to L and R for stereo
            // output. The native ring expects whatever channel count the
            // AR was created with; we keep tChannels-aware writing
            // below so a mono AR ctor (if one ever happens) just writes
            // a single sample per frame.
            val left = sample
            val right = sample

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

        // Save the last 2 source mono samples for the next chunk's cubic
        // interp boundary. Without this, every MediaCodec output chunk
        // start would have a 1-sample discontinuity at the cubic's sm1
        // point, producing audible crackling at the chunk emission rate.
        if (srcMonoCount >= 2) {
            prevChunkTail0 = srcMono[srcMonoCount - 1]
            prevChunkTail1 = srcMono[srcMonoCount - 2]
            prevChunkValid = true
        } else if (srcMonoCount == 1) {
            prevChunkTail0 = srcMono[0]
            prevChunkTail1 = 0
            prevChunkValid = true
        }

        // Carry only the sub-sample phase so we keep aligned with the source
        // grid; the integer part of pos resets to 0 at the start of the next
        // chunk. Always in [0, 1).
        return pos - pos.toInt()
    }

    /**
     * Catmull-Rom cubic spline interpolation. Returns the interpolated
     * sample at fractional position [frac] in [0, 1) between [y0] and [y1],
     * using [yMinus1] and [y2] as outer control points. Reduces aliased
     * images by ~12 dB vs linear interp without the runtime cost of a
     * polyphase FIR.
     */
    private fun catmullRom(yMinus1: Int, y0: Int, y1: Int, y2: Int, frac: Double): Double {
        val ym1 = yMinus1.toDouble()
        val y0d = y0.toDouble()
        val y1d = y1.toDouble()
        val y2d = y2.toDouble()
        val a = -0.5 * ym1 + 1.5 * y0d - 1.5 * y1d + 0.5 * y2d
        val b =        ym1 - 2.5 * y0d + 2.0 * y1d - 0.5 * y2d
        val c = -0.5 * ym1               + 0.5 * y1d
        val d =                  y0d
        // Horner form for fewer multiplications.
        return ((a * frac + b) * frac + c) * frac + d
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
            while (running.get() && !abortCurrentPass && NativeAudioHook.ringAvailable() > headroom) {
                try { Thread.sleep(10) } catch (_: InterruptedException) { return }
            }
            // Drop this chunk if the pass was just aborted — the next pass
            // will start from t=0 and push fresh PCM. Holding it back avoids
            // a stale-audio glitch at the loop boundary.
            if (abortCurrentPass) return
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
