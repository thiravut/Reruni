package com.rerun.tiktokvcam

import android.os.Environment
import java.io.File

/**
 * Runtime configuration for the virtual camera.
 *
 * The MP4 is read from TikTok's external app-specific dir (no special
 * permission needed for the hooked process to read its own dir). The
 * host TiktokRerun app (or our installer) writes the video there before
 * the broadcast starts.
 *
 * Locations checked in order of preference:
 *   1. /sdcard/Android/data/<tiktok-pkg>/files/vcam_video.mp4    (per-app, no perm needed)
 *   2. /sdcard/Download/vcam_video.mp4                            (legacy, requires storage perm)
 */
object VcamConfig {

    /** TikTok variants we may run inside. */
    private val TIKTOK_PACKAGES = listOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
    )

    /** Where the host TiktokRerun app drops the video for this broadcast. */
    val videoFile: File
        get() {
            // 1. App-specific external — no storage permission needed for hosted process.
            for (pkg in TIKTOK_PACKAGES) {
                val appExternal = File(
                    Environment.getExternalStorageDirectory(),
                    "Android/data/$pkg/files/vcam_video.mp4",
                )
                if (appExternal.exists()) return appExternal
            }
            // 2. Fallback: legacy Download dir.
            return File(Environment.getExternalStorageDirectory(), "Download/vcam_video.mp4")
        }

    /** Whether the host has actually staged a video to play. */
    fun videoReady(): Boolean = videoFile.exists() && videoFile.length() > 0

    /** Loop the file by default — broker live runs are long. */
    const val LOOP = true
}
