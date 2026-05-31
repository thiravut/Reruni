package com.rerun.tiktokvcam

import android.app.AndroidAppHelper
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * Cross-process accessor for the MP4 staged by the TiktokRerun host app.
 *
 * TiktokRerun exposes the active video through its [VcamContentProvider] at
 * `content://com.rerun.tiktokrerun.vcam/video`. Inside the patched TikTok
 * process we fetch the host context via [AndroidAppHelper] (LSPosed runs in
 * the host's process so this returns TikTok's Application), then call
 * `ContentResolver.openFileDescriptor` to receive a read-only fd over
 * Binder. The decoder feeds that fd into [android.media.MediaExtractor]
 * directly — no shared-storage path or permission grant needed.
 *
 * Falls back to the legacy on-disk locations from [VcamConfig] when the
 * provider is unavailable (TiktokRerun not installed yet, or Android 11+
 * visibility blocked the resolution). That preserves the ad-hoc adb-push
 * workflow while we transition the writer side onto the provider.
 */
object VcamBridge {
    private const val TAG = "TiktokRerunVCam"

    private val VIDEO_URI: Uri = Uri.parse("content://com.rerun.tiktokrerun.vcam/video")

    /**
     * Result of resolving the staged video. Either an open [ParcelFileDescriptor]
     * (caller must close it), an absolute file path (legacy fallback), or null
     * when nothing is staged.
     */
    sealed class Source {
        class Pfd(val pfd: ParcelFileDescriptor) : Source()
        class Path(val absolutePath: String) : Source()
    }

    fun resolve(): Source? {
        val ctx = hostContext()
        if (ctx != null) {
            val pfd = try {
                ctx.contentResolver.openFileDescriptor(VIDEO_URI, "r")
            } catch (t: Throwable) {
                log("provider openFileDescriptor failed: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            if (pfd != null) {
                log("resolved via ContentProvider (size=${pfd.statSize})")
                return Source.Pfd(pfd)
            }
        } else {
            log("no host Context — falling back to file path")
        }
        val file = VcamConfig.videoFile
        if (file.exists() && file.length() > 0) {
            log("resolved via legacy path: ${file.absolutePath}")
            return Source.Path(file.absolutePath)
        }
        return null
    }

    private fun hostContext(): Context? = try {
        AndroidAppHelper.currentApplication()
    } catch (t: Throwable) {
        log("AndroidAppHelper.currentApplication threw: ${t.message}")
        null
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] VcamBridge: $msg")
        Log.i(TAG, "VcamBridge: $msg")
    }
}
