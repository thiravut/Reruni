package com.rerun.tiktokvcam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * Tracks which lens (front/back) TikTok last opened. The render pipeline
 * uses this to mirror the output when the front camera is active — TikTok's
 * preview transform composes a selfie mirror on top of our content, so we
 * pre-mirror to cancel it out.
 *
 * Updated from [Camera2Hook]'s openCamera hook. Read from [GlFrameRenderer].
 */
object CameraIdentity {
    private const val TAG = "TiktokRerunVCam"

    @Volatile
    var lensFacing: Int = -1
        private set

    /** True if the most recent openCamera was for a front-facing lens. */
    val isFront: Boolean
        get() = lensFacing == CameraCharacteristics.LENS_FACING_FRONT

    fun update(manager: CameraManager, cameraId: String) {
        try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: -1
            lensFacing = facing
            log("openCamera id=$cameraId lensFacing=${describeFacing(facing)}")
        } catch (t: Throwable) {
            log("getCameraCharacteristics($cameraId) failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun describeFacing(facing: Int) = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($facing)"
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] CameraIdentity: $msg")
        Log.i(TAG, "CameraIdentity: $msg")
    }
}
