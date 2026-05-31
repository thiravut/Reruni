package com.rerun.tiktokvcam

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes the staged MP4 with [MediaCodec] (output to a [GlFrameRenderer]-owned
 * intermediate SurfaceTexture) and renders each frame onto TikTok's hijacked
 * [Surface] via an EGL window surface.
 *
 * Why EGL and not ImageWriter: TikTok's Surface backs a SurfaceTexture
 * consumer that wants HARDWARE_BUFFER (GPU) frames. ImageWriter with
 * YUV_420_888 destination Images returned single-plane buffers that didn't
 * match the decoder's 3-plane YUV output (length=1; index=1 errors).
 * EGL emits the right buffer format and the GPU samples YUV→RGB via
 * samplerExternalOES.
 *
 * Limitations:
 *  - No audio.
 *  - Coarse loop point (rewind at EOS).
 *  - Single target surface (first valid one wins).
 */
object Mp4FrameProducer {
    private const val TAG = "TiktokRerunVCam"
    private const val TIMEOUT_US = 10_000L

    private val running = AtomicBoolean(false)
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    @Volatile
    private var currentTargetSurfaces: List<Surface> = emptyList()

    fun onSurfacesChanged(newSurfaces: List<Surface>) {
        currentTargetSurfaces = newSurfaces.filter { it.isValid }
        if (currentTargetSurfaces.isEmpty()) {
            log("no valid surfaces; stop")
            stop()
            return
        }
        // Defer the videoReady check to runOnePass — the producer retry loop
        // will keep polling VcamBridge so the user can stage the video after
        // navigating into the camera preview.
        stop()
        start()
    }

    private fun start() {
        if (!running.compareAndSet(false, true)) return
        val thread = HandlerThread("Mp4FrameProducer").apply { start() }
        workerThread = thread
        worker = Handler(thread.looper)
        worker?.post { runDecodeLoop() }
    }

    private fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        workerThread = null
        worker = null
    }

    private fun runDecodeLoop() {
        val targetSurface = currentTargetSurfaces.firstOrNull { it.isValid }
        if (targetSurface == null) {
            log("no valid surface; bail")
            running.set(false)
            return
        }
        log("start: target=$targetSurface")

        while (running.get() && !Thread.interrupted()) {
            val ok = runOnePass(targetSurface)
            if (!ok) {
                log("pass failed; retry in 2s")
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
            if (!VcamConfig.LOOP) break
        }
        log("decode loop ended")
        running.set(false)
    }

    /** One full pass through the MP4 file. Returns true on clean EOS. */
    private fun runOnePass(targetSurface: Surface): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var renderer: GlFrameRenderer? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var framesRendered = 0

        try {
            val source = VcamBridge.resolve() ?: run {
                log("no staged video; idle"); return false
            }
            when (source) {
                is VcamBridge.Source.Pfd -> {
                    pfd = source.pfd
                    extractor.setDataSource(pfd.fileDescriptor)
                }
                is VcamBridge.Source.Path -> {
                    extractor.setDataSource(source.absolutePath)
                }
            }
            val (track, format) = pickVideoTrack(extractor) ?: run {
                log("no video track"); return false
            }
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                log("missing MIME"); return false
            }
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)

            // EGL/GL renderer: window-surface bound to TikTok's Surface, with
            // its own intermediate SurfaceTexture for the decoder to draw into.
            renderer = try {
                GlFrameRenderer(targetSurface, w, h).apply { setup() }
            } catch (t: Throwable) {
                log("GlFrameRenderer.setup failed: ${t.javaClass.simpleName}: ${t.message}")
                XposedBridge.log(Log.getStackTraceString(t))
                return false
            }
            log("renderer ready ${w}x${h}")

            // Decoder renders into the renderer's intermediate SurfaceTexture.
            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(format, renderer.decoderInputSurface, null, 0)
                start()
            }
            log("decoder started: $mime ${w}x${h} (Surface mode → SurfaceTexture)")

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            val startNanos = System.nanoTime()

            while (!sawOutputEOS && running.get() && !Thread.interrupted()) {
                if (!sawInputEOS) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx) ?: continue
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEOS = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIdx, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        val ptsUs = bufferInfo.presentationTimeUs
                        val elapsedUs = (System.nanoTime() - startNanos) / 1000L
                        val sleepUs = ptsUs - elapsedUs
                        if (sleepUs > 1000) {
                            try { Thread.sleep(sleepUs / 1000) }
                            catch (_: InterruptedException) { break }
                        }

                        val shouldRender = bufferInfo.size > 0
                        decoder.releaseOutputBuffer(outIdx, shouldRender)

                        if (shouldRender) {
                            // releaseOutputBuffer(idx, true) posts the frame
                            // into our SurfaceTexture's BufferQueue. Wait for
                            // the listener, pull it into the OES texture,
                            // and render to TikTok's Surface.
                            if (renderer.awaitNewFrame()) {
                                renderer.drawFrame()
                                renderer.setPresentationTime(ptsUs * 1000L)
                                renderer.swapBuffers()
                                framesRendered++
                                if (framesRendered == 1 || framesRendered % 30 == 0) {
                                    log("rendered frame #$framesRendered (pts=${ptsUs}us)")
                                }
                            } else {
                                log("awaitNewFrame timed out at pts=${ptsUs}us")
                            }
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        log("decoder output format: ${decoder.outputFormat}")
                    }
                }
            }
            log("pass completed; rendered $framesRendered frames")
            return true
        } catch (t: Throwable) {
            log("pass error: ${t.javaClass.simpleName}: ${t.message}")
            XposedBridge.log(Log.getStackTraceString(t))
            return false
        } finally {
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
            try { renderer?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
    }

    private fun pickVideoTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i to format
        }
        return null
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] Mp4FrameProducer: $msg")
        Log.i(TAG, "Mp4FrameProducer: $msg")
    }
}
