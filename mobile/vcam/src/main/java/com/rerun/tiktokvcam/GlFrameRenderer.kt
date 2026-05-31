package com.rerun.tiktokvcam

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import de.robv.android.xposed.XposedBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * EGL window surface bound to TikTok's hijacked [Surface], plus an
 * intermediate [SurfaceTexture] that [android.media.MediaCodec] renders into.
 *
 * Pipeline per frame:
 *   1. Decoder releases output buffer with render=true → frame lands in
 *      [decoderInputSurface]'s BufferQueue.
 *   2. [SurfaceTexture.OnFrameAvailableListener] fires (any thread) →
 *      sets [frameAvailable], wakes the render thread.
 *   3. Render thread calls [awaitNewFrame] → updateTexImage() pulls the
 *      latest YUV frame into our external-OES texture.
 *   4. [drawFrame] samples the OES texture with the SurfaceTexture's
 *      transform matrix and writes RGBA into the EGL window surface.
 *   5. [swapBuffers] posts the buffer into TikTok's Surface (where its
 *      SurfaceTexture consumer is reading from).
 *
 * Why this exists: the previous ImageWriter path failed because TikTok's
 * Surface backs a SurfaceTexture consumer that expects GPU
 * HARDWARE_BUFFER frames (single-plane RGBA), not 3-plane YUV_420_888.
 * EGL gives us a producer that emits the right buffer format and lets
 * the GPU do YUV→RGB conversion via samplerExternalOES.
 *
 * Threading: all GL operations (setup, drawFrame, swapBuffers, release)
 * must occur on the thread that called [setup]. The frame-available
 * listener can fire on any thread — it only flips a flag.
 */
class GlFrameRenderer(
    private val targetSurface: Surface,
    private val width: Int,
    private val height: Int,
) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTextureId: Int = 0
    private var program: Int = 0
    private var aPosLoc: Int = 0
    private var aTexCoordLoc: Int = 0
    private var uTexMatrixLoc: Int = 0
    private var sTextureLoc: Int = 0
    private lateinit var vertexBuffer: FloatBuffer
    // Actual EGL window dimensions (TikTok's preview surface size) — queried
    // post-bind because TikTok's Surface size may differ from the decoded
    // video size. We use these for glViewport so we cover the full preview.
    private var surfaceW: Int = 0
    private var surfaceH: Int = 0

    lateinit var decoderSurfaceTexture: SurfaceTexture
        private set
    lateinit var decoderInputSurface: Surface
        private set

    private val frameLock = Object()
    @Volatile private var frameAvailable = false
    private val texMatrix = FloatArray(16)
    // SurfaceTexture's OnFrameAvailableListener is dispatched via the Looper
    // of whichever thread registered it. The GL/decode thread runs in a tight
    // dequeueOutputBuffer loop and never pumps its Looper, so we register the
    // listener with a dedicated Handler thread that exists only to flip the
    // frameAvailable flag and wake the GL thread.
    private var listenerThread: HandlerThread? = null
    private var listenerHandler: Handler? = null

    fun setup() {
        log("setup begin: targetSurface=$targetSurface valid=${targetSurface.isValid}")
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            "eglInitialize failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        // No EGL_RECORDABLE_ANDROID — TikTok's SurfaceTexture-backed Surface
        // does not advertise that capability and rejects the bind otherwise.
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0),
        ) { "eglChooseConfig failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        val config = configs[0] ?: error("no EGL config")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(eglContext !== EGL14.EGL_NO_CONTEXT) {
            "eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        log("about to bind EGL window: surfValid=${targetSurface.isValid}")
        eglSurface = try {
            EGL14.eglCreateWindowSurface(eglDisplay, config, targetSurface, surfAttribs, 0)
        } catch (iae: IllegalArgumentException) {
            log("eglCreateWindowSurface IAE: ${iae.message} (surfValid=${targetSurface.isValid})")
            throw iae
        }
        check(eglSurface !== EGL14.EGL_NO_SURFACE) {
            "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        val dim = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, dim, 0)
        surfaceW = dim[0]
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, dim, 0)
        surfaceH = dim[0]
        log("EGL window dimensions: ${surfaceW}x${surfaceH} (video=${width}x${height})")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTextureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        sTextureLoc = GLES20.glGetUniformLocation(program, "sTexture")

        vertexBuffer = ByteBuffer
            .allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(VERTICES); position(0) }

        val lt = HandlerThread("GlFrameListener").apply { start() }
        listenerThread = lt
        val lh = Handler(lt.looper)
        listenerHandler = lh

        decoderSurfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener(
                {
                    synchronized(frameLock) {
                        frameAvailable = true
                        frameLock.notifyAll()
                    }
                },
                lh,
            )
        }
        decoderInputSurface = Surface(decoderSurfaceTexture)
        log("EGL setup ok: ${width}x${height}, oesTex=$oesTextureId, program=$program")
    }

    /** Block until decoder posts a new frame to our SurfaceTexture. */
    fun awaitNewFrame(timeoutMs: Long = 2000): Boolean {
        synchronized(frameLock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!frameAvailable) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                try {
                    frameLock.wait(left)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            frameAvailable = false
        }
        decoderSurfaceTexture.updateTexImage()
        decoderSurfaceTexture.getTransformMatrix(texMatrix)
        return true
    }

    fun drawFrame() {
        // Draw to the full EGL window. The fullscreen quad in NDC (-1..1)
        // takes care of covering whatever resolution TikTok's Surface is at.
        val vw = if (surfaceW > 0) surfaceW else width
        val vh = if (surfaceH > 0) surfaceH else height
        GLES20.glViewport(0, 0, vw, vh)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(sTextureLoc, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun swapBuffers(): Boolean {
        val ok = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!ok) log("eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        return ok
    }

    fun release() {
        try {
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (eglSurface !== EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext !== EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (_: Throwable) {}
        try { decoderInputSurface.release() } catch (_: Throwable) {}
        try { decoderSurfaceTexture.release() } catch (_: Throwable) {}
        try { listenerThread?.quitSafely() } catch (_: Throwable) {}
        listenerThread = null
        listenerHandler = null
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            error("program link failed: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            error("shader compile failed (type=$type): $log")
        }
        return s
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] GlFrameRenderer: $msg")
        Log.i(TAG, "GlFrameRenderer: $msg")
    }

    companion object {
        private const val TAG = "TiktokRerunVCam"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        // TikTok's preview Surface is sensor-oriented (e.g. 1280x720
        // landscape) and the preview pipeline rotates 90° CW for portrait
        // display. To make the portrait video come out upright after that
        // rotation, we lay it into the landscape buffer rotated 90° CCW.
        //
        // Mapping: output (u_o, v_o) → texture (u = v_o, v = 1 - u_o).
        //
        //   screen (NDC)  →  output (u,v)  →  texture (u,v)
        //   (-1,-1)          (0, 0)            (0, 1)
        //   ( 1,-1)          (1, 0)            (0, 0)
        //   (-1, 1)          (0, 1)            (1, 1)
        //   ( 1, 1)          (1, 1)            (1, 0)
        private val VERTICES = floatArrayOf(
            // x, y, u, v
            -1f, -1f, 0f, 1f,
            1f, -1f, 0f, 0f,
            -1f, 1f, 1f, 1f,
            1f, 1f, 1f, 0f,
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
