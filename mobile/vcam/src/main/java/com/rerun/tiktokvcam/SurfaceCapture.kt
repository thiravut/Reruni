package com.rerun.tiktokvcam

import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.util.Log
import android.view.Surface
import de.robv.android.xposed.XposedBridge

/**
 * Captures the output [Surface]s that TikTok hands to `createCaptureSession`.
 * The captured surfaces become the targets we write our MP4-decoded frames
 * into via [Mp4FrameProducer].
 *
 * Captured surfaces are accumulated; the producer picks the first one that
 * looks like a usable preview/video target (largest dimensions wins —
 * stills usually outrank thumbnails).
 */
object SurfaceCapture {
    private const val TAG = "TiktokRerunVCam"

    private val surfacesRef = mutableListOf<Surface>()

    /** Surfaces currently considered "live" (latest createCaptureSession call). */
    @Synchronized
    fun current(): List<Surface> = surfacesRef.toList()

    /** Called from [Camera2Hook] when a session is created. */
    @Synchronized
    fun update(newSurfaces: List<Surface>) {
        val previous = surfacesRef.toList()
        surfacesRef.clear()
        surfacesRef.addAll(newSurfaces.filter { it.isValid })
        log(
            "captured ${surfacesRef.size} surface(s); " +
                surfacesRef.joinToString { s -> describe(s) },
        )
        // When the active surface set changes (camera reopen, flip etc.),
        // notify producer so it re-targets.
        if (previous != surfacesRef) {
            Mp4FrameProducer.onSurfacesChanged(surfacesRef.toList())
        }
    }

    /** Extract a usable Surface list from whatever `createCaptureSession*` was called with. */
    fun fromAny(arg: Any?): List<Surface> = when (arg) {
        is SessionConfiguration -> arg.outputConfigurations.flatMap { extractFromOutputConfig(it) }
        is List<*> -> arg.flatMap { item ->
            when (item) {
                is Surface -> listOf(item)
                is OutputConfiguration -> extractFromOutputConfig(item)
                else -> emptyList()
            }
        }
        else -> emptyList()
    }

    private fun extractFromOutputConfig(oc: OutputConfiguration): List<Surface> {
        val list = mutableListOf<Surface>()
        // OutputConfiguration#getSurface() exists since API 24; getSurfaces() since 28
        runCatching { oc.surface?.let { list.add(it) } }
        runCatching {
            // multi-surface configs (added in P)
            val multi = OutputConfiguration::class.java.getMethod("getSurfaces").invoke(oc)
            if (multi is Collection<*>) multi.filterIsInstance<Surface>().forEach { list.add(it) }
        }
        return list
    }

    private fun describe(s: Surface): String {
        val sb = StringBuilder()
        sb.append("Surface(valid=${s.isValid}")
        sb.append(",hash=0x${"%08x".format(System.identityHashCode(s))}")
        runCatching {
            val s2 = s.toString()
            sb.append(",toString=${s2.take(120)}")
        }
        sb.append(")")
        return sb.toString()
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] SurfaceCapture: $msg")
        Log.i(TAG, "SurfaceCapture: $msg")
    }
}
