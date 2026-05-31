package com.rerun.tiktokvcam

import android.graphics.SurfaceTexture
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.util.Log
import android.view.Surface
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Rewrites the `createCaptureSession*` arguments so the camera HAL writes
 * into throwaway Surfaces we control while the *original* Surfaces (TikTok's
 * SurfaceTextures that the app reads frames from) become free for an
 * [ImageWriter] producer that [Mp4FrameProducer] owns.
 *
 * Strategy:
 *  1. In the beforeHook of `createCaptureSessionInternal`, walk the
 *     `List<OutputConfiguration>` argument.
 *  2. For each entry, allocate a dummy `SurfaceTexture` + `Surface`
 *     (a black hole — camera writes here, nobody reads).
 *  3. Replace the OutputConfiguration's surface field via reflection.
 *  4. Forward the *original* Surface to [SurfaceCapture] so the producer
 *     can connect an ImageWriter to it.
 *
 * Limitations:
 *  - Multi-surface OutputConfiguration variants (API 28+) only handle the
 *    primary surface; secondary ones (sharedSurfaces) are left untouched.
 *  - We retain a hard reference to the dummy SurfaceTexture per session so
 *    GC doesn't reclaim it mid-stream.
 */
object SurfaceSubstitution {
    private const val TAG = "TiktokRerunVCam"

    // Keep dummies alive — one per active surface so we don't collide.
    private val dummies = mutableListOf<DummySurface>()

    private data class DummySurface(val texture: SurfaceTexture, val surface: Surface)

    /**
     * Walk createCaptureSession args and substitute Surfaces.
     * Returns the list of original Surfaces extracted (now free for our producer).
     */
    @Synchronized
    fun substitute(args: Array<Any?>): List<Surface> {
        recycleDummies()
        val originals = mutableListOf<Surface>()

        for (i in args.indices) {
            val a = args[i] ?: continue
            when (a) {
                is SessionConfiguration -> {
                    val configs = a.outputConfigurations
                    for (config in configs) {
                        substituteInOutputConfiguration(config)?.let { originals.add(it) }
                    }
                }
                is List<*> -> {
                    for (item in a) {
                        when (item) {
                            is OutputConfiguration -> substituteInOutputConfiguration(item)?.let { originals.add(it) }
                            is Surface -> log("warning: raw Surface in List arg — cannot substitute without OutputConfiguration wrapper")
                        }
                    }
                }
            }
        }
        log("substituted ${originals.size} surface(s); freed for ImageWriter")
        return originals
    }

    /**
     * Replace the OutputConfiguration's surface with a dummy. Returns the
     * original surface (now caller's to feed via ImageWriter), or null on
     * failure.
     */
    private fun substituteInOutputConfiguration(config: OutputConfiguration): Surface? {
        val original = config.surface ?: return null
        val dummy = newDummySurface()

        // OutputConfiguration#mSurfaces is the internal list of Surfaces;
        // OutputConfiguration#mSurfacesList in some SDK levels. Try both.
        val candidateFields = listOf("mSurfaces", "mSurfacesList", "mSurface")
        for (fieldName in candidateFields) {
            try {
                val field = OutputConfiguration::class.java.getDeclaredField(fieldName)
                field.isAccessible = true
                val value = field.get(config)
                when (value) {
                    is MutableList<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val list = value as MutableList<Surface>
                        if (list.isNotEmpty()) {
                            list[0] = dummy.surface
                            log("swapped surface in OutputConfiguration.$fieldName (list of ${list.size})")
                            return original
                        }
                    }
                    is Surface -> {
                        field.set(config, dummy.surface)
                        log("swapped surface in OutputConfiguration.$fieldName (single)")
                        return original
                    }
                }
            } catch (t: NoSuchFieldException) {
                // try next candidate
            } catch (t: Throwable) {
                log("reflect $fieldName failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        log("could not locate Surface field on OutputConfiguration — substitution skipped")
        try { dummy.surface.release() } catch (_: Throwable) {}
        try { dummy.texture.release() } catch (_: Throwable) {}
        return null
    }

    private fun newDummySurface(): DummySurface {
        val tex = SurfaceTexture(/*texName=*/0).apply {
            setDefaultBufferSize(720, 1280)
        }
        // Detach from current GL context so SurfaceTexture isn't tied to any thread.
        runCatching { tex.detachFromGLContext() }
        val sf = Surface(tex)
        val d = DummySurface(tex, sf)
        dummies.add(d)
        return d
    }

    private fun recycleDummies() {
        for (d in dummies) {
            try { d.surface.release() } catch (_: Throwable) {}
            try { d.texture.release() } catch (_: Throwable) {}
        }
        dummies.clear()
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] SurfaceSubstitution: $msg")
        Log.i(TAG, "SurfaceSubstitution: $msg")
    }
}
