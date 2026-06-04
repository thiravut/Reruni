package com.rerun.tiktokrerun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Stages a video into [VcamContentProvider] from an external trigger.
 *
 * Trigger via adb:
 * ```
 * adb shell am broadcast \
 *   -n com.rerun.tiktokrerun/.VcamStageReceiver \
 *   -a com.rerun.tiktokrerun.VCAM_STAGE \
 *   --es path /sdcard/Download/vcam_video.mp4
 * ```
 *
 * Lives here (not behind a hidden activity) so the test loop is exactly one
 * adb call — no UI navigation needed while we iterate on the vcam side.
 * The autopilot will call [VcamContentProvider.stage] directly once the
 * download / playlist flow is wired up.
 */
class VcamStageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path")
        if (path.isNullOrBlank()) {
            Log.w(TAG, "missing 'path' extra")
            return
        }
        val source = File(path)
        if (!source.exists() || source.length() == 0L) {
            Log.w(TAG, "source not readable: $path")
            return
        }
        try {
            VcamContentProvider.stage(context, source)
        } catch (t: Throwable) {
            Log.e(TAG, "stage failed", t)
            return
        }
        // Acoustic loopback: speaker plays the staged MP4's audio so TikTok's
        // mic picks it up naturally — bypasses the broken native injection
        // path entirely. Operator must wear earphones to avoid feedback.
        // Disable by passing --ez loopback false from the staging broadcast.
        val enableLoopback = intent.getBooleanExtra("loopback", true)
        if (enableLoopback) {
            try {
                AudioLoopbackPlayer.start(context, VcamContentProvider.activeFile(context))
            } catch (t: Throwable) {
                Log.e(TAG, "loopback start failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "VcamStageReceiver"
    }
}
