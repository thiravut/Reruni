package com.rerun.tiktokrerun

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Plays the staged MP4's audio track out of the phone speaker on a loop so
 * TikTok's microphone — sitting a few centimetres away on the same device —
 * captures it as a normal mic signal.
 *
 * Why this exists: direct PCM injection into AudioRecord's buffer was tried
 * across ~16 iterations (Phase 3 + Plan B in the vcam module). Every fix
 * exposed another layer of TikTok's voice-tuned audio chain (HE-AACv1
 * encoder, multi-source mixer, downstream WebRTC processors) mangling the
 * substituted PCM. Acoustic loopback bypasses all of it: TikTok receives
 * what it expects (mic-captured ambient signal), so its DSP chain works as
 * designed.
 *
 * Trade-offs vs direct injection:
 *   + Works on every TikTok version (no native ABI dependencies)
 *   + No A/V drift; TikTok timestamps audio + video naturally
 *   + No distortion; mic path is the DSP-tuned path
 *   – Lower SNR (speaker → air → mic loses ~10-20 dB)
 *   – Operator must wear earphones to avoid hearing themselves on broadcast
 *   – Quiet room required; ambient noise also enters the mic
 *
 * Lifecycle is process-global because callers may arrive from a broadcast
 * receiver (no Activity/Service to scope to). The player releases its
 * MediaPlayer on stop so we don't leak file descriptors between sessions.
 */
object AudioLoopbackPlayer {

    private const val TAG = "AudioLoopbackPlayer"

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    var playingFile: File? = null
        private set

    val isPlaying: Boolean get() = player?.isPlaying == true

    /**
     * Begins looping playback of [source]'s audio out the speaker. Idempotent
     * for the same file — calling repeatedly with the currently-playing file
     * is a no-op. Calling with a different file replaces the running player.
     */
    @Synchronized
    fun start(context: Context, source: File) {
        if (!source.exists() || source.length() == 0L) {
            Log.w(TAG, "source not readable: $source")
            return
        }
        val existing = player
        if (existing != null && existing.isPlaying && playingFile?.absolutePath == source.absolutePath) {
            Log.i(TAG, "already playing $source")
            return
        }
        stopLocked()
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                @Suppress("DEPRECATION")
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(source.absolutePath)
                isLooping = true
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    false
                }
                prepare()
                start()
            }
            player = mp
            playingFile = source
            forceSpeakerOutput(context)
            Log.i(TAG, "started loopback for $source")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start loopback", t)
            stopLocked()
        }
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        player?.let {
            try { it.stop() } catch (_: Throwable) {}
            try { it.release() } catch (_: Throwable) {}
        }
        player = null
        playingFile = null
    }

    /**
     * Force audio out the phone speaker even when the operator's Bluetooth
     * earphones are connected — the whole point of loopback is that the mic
     * picks the sound up acoustically, so the speaker must stay active.
     * Operator monitors the LIVE via headphones; this just keeps Android's
     * routing manager from "helpfully" redirecting media playback off the
     * speaker.
     */
    private fun forceSpeakerOutput(context: Context) {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        try {
            // No-op on devices without speakerphone; we ask politely and move on.
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        } catch (t: Throwable) {
            Log.w(TAG, "forceSpeakerOutput: ${t.message}")
        }
    }
}
