package com.rerun.tiktokrerun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Adb-triggerable control for the audio loopback player.
 *
 * Start playback of the currently staged video's audio (most common usage):
 * ```
 * adb shell am broadcast \
 *   -n com.rerun.tiktokrerun/.AudioLoopbackReceiver \
 *   -a com.rerun.tiktokrerun.AUDIO_LOOPBACK \
 *   --es action start
 * ```
 *
 * Start playback of an arbitrary file (test / different content):
 * ```
 * adb shell am broadcast \
 *   -n com.rerun.tiktokrerun/.AudioLoopbackReceiver \
 *   -a com.rerun.tiktokrerun.AUDIO_LOOPBACK \
 *   --es action start \
 *   --es path /sdcard/Download/other.mp4
 * ```
 *
 * Stop playback:
 * ```
 * adb shell am broadcast \
 *   -n com.rerun.tiktokrerun/.AudioLoopbackReceiver \
 *   -a com.rerun.tiktokrerun.AUDIO_LOOPBACK \
 *   --es action stop
 * ```
 *
 * The "start with no path" form reads the same staged file that vcam serves
 * to TikTok, so audio matches video by construction — the operator stages
 * the MP4 once, and the speaker plays its soundtrack while TikTok captures
 * the picture from vcam and the sound from the mic.
 */
class AudioLoopbackReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("action")?.lowercase()) {
            "start" -> handleStart(context, intent.getStringExtra("path"))
            "stop"  -> AudioLoopbackPlayer.stop()
            else    -> Log.w(TAG, "missing or unknown 'action' extra; use start|stop")
        }
    }

    private fun handleStart(context: Context, explicitPath: String?) {
        val file = if (explicitPath.isNullOrBlank()) {
            VcamContentProvider.activeFile(context)
        } else {
            File(explicitPath)
        }
        AudioLoopbackPlayer.start(context, file)
    }

    companion object {
        private const val TAG = "AudioLoopbackReceiver"
    }
}
