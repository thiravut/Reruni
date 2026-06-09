package com.rerun.tiktokvcam

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Reconnaissance + override of TikTok's RTC audio encoder configuration.
 *
 * AudioDeviceModule's WebRTC processor switches turned out to already be
 * disabled by TikTok (see [TtRtcAudioHook]), so the broadcast distortion
 * Pond hears can't be coming from APM/ANS/AEC. The next suspect is the
 * AAC/voice encoder downstream — TikTok's RTC backend may be configured
 * for *voice* content (8–16 kHz bandwidth, low bitrate, mono downmix),
 * which mangles music regardless of how clean the raw PCM is.
 *
 * Targets found in `libvolcenginertc.so`:
 *   - LyraxPublisherImpl.setAudioContentConfig(...)  — content type (voice/music)
 *   - LyraxPublisherImpl.setAudioEncoderConfig(...)  — bitrate / sample rate / channels
 *   - NativeRTCVideoFunctions.setAudioEncodeConfigEx(...) — RTC-side config
 *
 * This first revision is *log-only*: for each Java entry point we hook,
 * we dump the argument list so we know exactly what TikTok is asking for
 * (content type enum, channel count, sample rate, bitrate). Once we see
 * the values we can decide how to override (force content=music, channels=2,
 * sample rate=48 kHz, bitrate=128 kbps) in a follow-up revision.
 */
object TtRtcEncoderHook {

    private const val TAG = "TiktokRerunVCam"

    /** Class names whose target methods we log. */
    private val TARGET_CLASSES = listOf(
        "com.ss.lyrax.publisher.LyraxPublisherImpl",
        "com.ss.bytertc.engine.NativeRTCVideoFunctions",
        "com.ss.pusher.core.engine.AudioEncoder",
        // Encoder input path — MediaEncodeStream.nativeOnFrame is the
        // entry point where the mixer hands frames to the AAC encoder.
        // Hooking here is downstream of the mic/MediaPlayer/SFX mixer
        // so we see exactly what TikTok will encode.
        "com.ss.pusher.core.engine.MediaEncodeStream",
        // Lyrax push session — the layer that streams encoded frames to
        // TikTok's CDN; may have audio frame data hooks too.
        "com.ss.lyrax.pushersession.LyraxPusherSessionImpl",
    )

    /** Substrings: any method whose name contains one of these gets logged. */
    private val METHOD_HITS = listOf(
        "setAudio",
        "setEncode",
        "setBitrate",
        "setSampleRate",
        "setChannel",
        "setContent",
        "AudioConfig",
        "onFrame",
        "onData",
        "pushAudio",
        "Encoded",
    )

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var hooked = 0
        for (className in TARGET_CLASSES) {
            val klass = XposedHelpers.findClassIfExists(className, cl)
            if (klass == null) {
                log("class not found: $className")
                continue
            }
            for (m in klass.declaredMethods) {
                val name = m.name
                if (!METHOD_HITS.any { name.contains(it, ignoreCase = false) }) continue
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // V1 ship config: only the encoder rewrite (LC
                            // + 128 kbps + plain encType/bitrateMode). The
                            // content-config rewrite is NOT applied here
                            // because disabling MediaPlayer/SFX mixing
                            // produced progressive A/V drift that worsens
                            // with LIVE duration. The recon log is kept
                            // so V2 work can pick up where we stopped.
                            if (name == "setAudioEncoderConfig" && param.args.isNotEmpty()) {
                                rewriteEncoderConfig(param.args[0])
                            }
                            val args = param.args.joinToString(",") { describe(it) }
                            log("${className.substringAfterLast('.')}.$name($args)")
                        }
                    })
                    hooked++
                } catch (t: Throwable) {
                    log("hook $className.$name failed: ${t.message}")
                }
            }
        }
        log("hooked $hooked encoder-config methods across ${TARGET_CLASSES.size} classes")
    }

    /**
     * Force the audio encoder out of voice-codec territory.
     *
     * TikTok's default for LIVE is:
     *   aacCodecProfile = AACHEv1   ← HE-AAC v1 with SBR. AAC encodes the
     *                                  0–8 kHz band; SBR fakes 8–24 kHz
     *                                  from low-band parameters. Works
     *                                  for speech (most energy < 8 kHz)
     *                                  but utterly destroys music — the
     *                                  SBR generator produces aliasing
     *                                  + tonal artefacts that the viewer
     *                                  hears as a blown speaker.
     *   bitrateKbps     = 64        ← 32 kbps per channel. Way below
     *                                  music's transparency point (~160
     *                                  kbps stereo for AAC-LC).
     *
     * Override:
     *   aacCodecProfile → LC (plain AAC-LC, full-band PCM-to-frequency
     *                         encoding, no SBR magic)
     *   bitrateKbps     → 128 (still light, but transparent enough for
     *                         streaming background music)
     *
     * Enum lookup is reflective so we don't pin to TikTok's internal
     * package layout (which they obfuscate per release).
     */
    private fun rewriteEncoderConfig(config: Any?) {
        if (config == null) return
        try {
            val clazz = config.javaClass

            // Find aacCodecProfile enum class via the field's declared type,
            // then locate the LC constant by name. Falls back gracefully if
            // the enum identifier was renamed.
            val profileField = runCatching { clazz.getDeclaredField("aacCodecProfile") }.getOrNull()
            if (profileField != null) {
                profileField.isAccessible = true
                val enumType = profileField.type
                val before = profileField.get(config)
                if (enumType.isEnum) {
                    val candidates = listOf("LC", "AACLC", "AAC_LC", "AAC", "MAIN")
                    val target = enumType.enumConstants?.firstOrNull { e ->
                        candidates.any { e.toString().equals(it, ignoreCase = true) }
                    }
                    if (target != null) {
                        profileField.set(config, target)
                        log("rewrote aacCodecProfile: $before → $target")
                    } else {
                        log("no LC-class enum constant found among ${enumType.enumConstants?.joinToString { it.toString() }}")
                    }
                }
            }

            // Bump bitrate. Field type may be int or Integer.
            // 256 kbps = TikTok Live Studio's documented max audio bitrate.
            // 128 was the previous compromise; bumping to 256 gives the
            // AAC-LC encoder enough budget to preserve full-band music
            // detail rather than degrading sibilants and high harmonics.
            val bitrateField = runCatching { clazz.getDeclaredField("bitrateKbps") }.getOrNull()
            if (bitrateField != null) {
                bitrateField.isAccessible = true
                val before = bitrateField.get(config)
                bitrateField.setInt(config, 256)
                log("rewrote bitrateKbps: $before → 256")
            }

            // aacEncType=4 was the HE-AAC-v1 encoder mode. 0 is the default
            // LC encoder type per ByteDance's enum tables.
            val encTypeField = runCatching { clazz.getDeclaredField("aacEncType") }.getOrNull()
            if (encTypeField != null) {
                encTypeField.isAccessible = true
                val before = encTypeField.get(config)
                encTypeField.setInt(config, 0)
                log("rewrote aacEncType: $before → 0")
            }

            // aacBitrateMode=10 likely a voice-tuned VBR mode; 0 = CBR.
            val rateModeField = runCatching { clazz.getDeclaredField("aacBitrateMode") }.getOrNull()
            if (rateModeField != null) {
                rateModeField.isAccessible = true
                val before = rateModeField.get(config)
                rateModeField.setInt(config, 0)
                log("rewrote aacBitrateMode: $before → 0")
            }
        } catch (t: Throwable) {
            log("rewriteEncoderConfig failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Force the broadcast audio mixer to only consume the mic path (which
     * carries our substituted MP4 PCM). The default LyraxAudioContentConfig
     * has `hasMediaPlayer=true` + `hasSpecialAudio=true`, so TikTok sums
     * mic + media-player-tone + SFX into a single mono-downmixed track. If
     * any of those extras contains non-zero samples while our substituted
     * mic also has signal, the encoder sees the sum and the result is the
     * broken-speaker mess Pond hears.
     */
    private fun rewriteContentConfig(config: Any?) {
        if (config == null) return
        try {
            val clazz = config.javaClass
            for ((name, value) in listOf(
                "hasMediaPlayer" to false,
                "hasSpecialAudio" to false,
                "hasScreenAudio"  to false,
            )) {
                val f = runCatching { clazz.getDeclaredField(name) }.getOrNull() ?: continue
                f.isAccessible = true
                val before = f.get(config)
                f.setBoolean(config, value)
                log("rewrote $name: $before → $value")
            }
        } catch (t: Throwable) {
            log("rewriteContentConfig failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun describe(arg: Any?): String = when (arg) {
        null -> "null"
        is IntArray -> "int[${arg.size}]=${arg.take(8).joinToString()}"
        is ByteArray -> "byte[${arg.size}]"
        is String -> "\"${arg.take(80)}\""
        is Number, is Boolean -> arg.toString()
        else -> {
            val cls = arg.javaClass.simpleName
            // For pojo configs, dump declared fields so we see what's inside.
            try {
                val fields = arg.javaClass.declaredFields.take(12).joinToString(",") { f ->
                    f.isAccessible = true
                    "${f.name}=${f.get(arg)}"
                }
                "$cls{$fields}"
            } catch (_: Throwable) {
                "$cls@${System.identityHashCode(arg).toString(16)}"
            }
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] TtRtcEncoderHook: $msg")
        Log.i(TAG, "TtRtcEncoderHook: $msg")
    }
}
