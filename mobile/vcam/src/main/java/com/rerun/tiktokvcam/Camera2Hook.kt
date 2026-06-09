package com.rerun.tiktokvcam

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Camera2 hook installer.
 *
 * MVP scope this revision: install logging hooks on the camera-open
 * lifecycle so we can verify which path TikTok actually uses for Live
 * broadcast (Camera2 directly, ByteDance RealX RTC, or WebRTC).
 * Subsequent revision swaps the [XC_MethodHook] callbacks for frame
 * replacement (ImageReader fed by [Mp4FrameSource]).
 *
 * Targets mirror SamuraiLive's smali findings: standard Camera2 +
 * ByteDance/WebRTC capturers + ttvecamera wrapper.
 */
object Camera2Hook {
    private const val TAG = "TiktokRerunVCam"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader

        hookCameraManager(cl)
        hookCameraDevice(cl)
        hookCameraClose(cl)
        hookTtveCamera(cl)
        hookBytedanceRealxRtc(cl)
        hookWebRtc(cl)
    }

    /**
     * When the camera device or its capture session closes, ask the
     * frame producer to schedule an audio stop. The producer waits a
     * short grace period before actually stopping so a brief
     * close-reopen (camera flip, session reconfigure) doesn't cut the
     * audio. A user navigating *away* from the LIVE preview entirely
     * leaves no replacement surface, so the deferred stop fires.
     */
    private fun hookCameraClose(cl: ClassLoader) {
        safe("CameraDeviceImpl.close") {
            val klass = XposedHelpers.findClassIfExists(
                "android.hardware.camera2.impl.CameraDeviceImpl", cl,
            ) ?: return@safe
            klass.declaredMethods.filter { it.name == "close" }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        log("→ CameraDevice.close (thread=${Thread.currentThread().name})")
                        Mp4FrameProducer.onCameraCloseDetected()
                    }
                })
            }
        }
        safe("CameraCaptureSessionImpl.close") {
            val klass = XposedHelpers.findClassIfExists(
                "android.hardware.camera2.impl.CameraCaptureSessionImpl", cl,
            ) ?: return@safe
            klass.declaredMethods.filter { it.name == "close" }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        log("→ CameraCaptureSession.close (thread=${Thread.currentThread().name})")
                        Mp4FrameProducer.onCameraCloseDetected()
                    }
                })
            }
        }
    }

    // ── Layer 1: standard Camera2 ─────────────────────────────────

    private fun hookCameraManager(cl: ClassLoader) = safe("CameraManager.openCamera") {
        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CameraManager", cl,
            "openCamera",
            String::class.java,
            "android.hardware.camera2.CameraDevice\$StateCallback",
            android.os.Handler::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val label = "CameraManager.openCamera(cameraId,callback,handler)"
                    log("→ $label (thread=${Thread.currentThread().name})")
                    val mgr = param.thisObject as? android.hardware.camera2.CameraManager
                    val cameraId = param.args.getOrNull(0) as? String
                    if (mgr != null && cameraId != null) {
                        CameraIdentity.update(mgr, cameraId)
                    }
                }
            },
        )
    }

    private fun hookCameraDevice(cl: ClassLoader) {
        safe("CameraDeviceImpl.createCaptureSession*") {
            val klass = XposedHelpers.findClassIfExists(
                "android.hardware.camera2.impl.CameraDeviceImpl", cl,
            ) ?: return@safe
            klass.declaredMethods
                .filter {
                    it.name.startsWith("createCaptureSession") || it.name == "createCaptureRequest"
                }
                .forEach { method ->
                    XposedBridge.hookMethod(
                        method,
                        captureSessionHook("CameraDeviceImpl.${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"),
                    )
                }
        }
        safe("CameraCaptureSessionImpl.setRepeatingRequest") {
            val klass = XposedHelpers.findClassIfExists(
                "android.hardware.camera2.impl.CameraCaptureSessionImpl", cl,
            ) ?: return@safe
            klass.declaredMethods
                .filter { it.name == "setRepeatingRequest" || it.name == "setRepeatingBurst" }
                .forEach { method ->
                    XposedBridge.hookMethod(
                        method,
                        logCall("CameraCaptureSessionImpl.${method.name}"),
                    )
                }
        }
    }

    /**
     * Hook variant that:
     *  1. Logs the call.
     *  2. Substitutes TikTok's Surfaces with dummies via [SurfaceSubstitution],
     *     freeing the originals from the camera HAL's producer slot.
     *  3. Hands the freed originals to [SurfaceCapture] so the producer can
     *     connect an [ImageWriter] without EINVAL.
     *
     * Substitution only happens for the *Internal* createCaptureSession variant
     * (the one all overloads route through). Outer wrappers see the swapped
     * args via param.args mutation.
     */
    private fun captureSessionHook(label: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            log("→ $label (thread=${Thread.currentThread().name})")
            // Only substitute for the Internal variant — wrappers like
            // createCaptureSession(SessionConfiguration) delegate to Internal,
            // and substituting twice would double-replace.
            val isInternal = (param.method as? java.lang.reflect.Method)?.name == "createCaptureSessionInternal"
            val freed = if (isInternal) {
                SurfaceSubstitution.substitute(param.args)
            } else {
                emptyList()
            }
            if (freed.isNotEmpty()) {
                SurfaceCapture.update(freed)
            } else {
                // Fallback: just observe surfaces without substitution (createCaptureRequest etc.)
                val observed = param.args
                    .filterNotNull()
                    .flatMap { SurfaceCapture.fromAny(it) }
                if (observed.isNotEmpty() && !isInternal) {
                    // Don't reset the captured list with non-substituted surfaces.
                    log("observed ${observed.size} surface(s) on $label (no substitution)")
                }
            }
        }
    }

    // ── Layer 2: TikTok's ttvecamera wrapper ──────────────────────

    private fun hookTtveCamera(cl: ClassLoader) {
        // TECameraCapture.connect() — TikTok-internal entry point that
        // ultimately calls Camera2 APIs. We log here so we can confirm
        // TikTok routes through ttvecamera (vs WebRTC directly).
        safe("TECameraCapture.connect") {
            val klass = XposedHelpers.findClassIfExists(
                "com.ss.android.ttvecamera.TECameraCapture", cl,
            ) ?: return@safe
            // Hook all `connect(...)` overloads — there are 2 per the
            // origin smali (with/without Cert param).
            klass.declaredMethods
                .filter { it.name == "connect" }
                .forEach { method ->
                    XposedBridge.hookMethod(method, logCall("TECameraCapture.${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"))
                }
        }
    }

    // ── Layer 3: ByteDance RealX RTC (TikTok Live's video engine) ─

    private fun hookBytedanceRealxRtc(cl: ClassLoader) {
        listOf(
            "com.bytedance.realx.video.Camera1Capturer",
            "com.bytedance.realx.video.Camera2Capturer",
            "com.bytedance.realx.video.CameraCapturer",
        ).forEach { className ->
            safe("$className.startCapture") {
                val klass = XposedHelpers.findClassIfExists(className, cl) ?: return@safe
                klass.declaredMethods
                    .filter { it.name == "startCapture" }
                    .forEach { method ->
                        XposedBridge.hookMethod(method, logCall("$className.startCapture"))
                    }
            }
        }
    }

    // ── Layer 4: WebRTC (used inside RealX RTC) ───────────────────

    private fun hookWebRtc(cl: ClassLoader) {
        listOf(
            "org.webrtc.Camera2Enumerator",
            "org.webrtc.Camera1Enumerator",
        ).forEach { className ->
            safe("$className.createCapturer") {
                val klass = XposedHelpers.findClassIfExists(className, cl) ?: return@safe
                klass.declaredMethods
                    .filter { it.name == "createCapturer" }
                    .forEach { method ->
                        XposedBridge.hookMethod(method, logCall("$className.createCapturer"))
                    }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private inline fun safe(label: String, block: () -> Unit) {
        try {
            block()
            log("hooked: $label")
        } catch (t: Throwable) {
            log("hook failed [$label]: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logCall(label: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            log("→ $label (thread=${Thread.currentThread().name})")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] $msg")
        Log.i(TAG, msg)
    }
}
