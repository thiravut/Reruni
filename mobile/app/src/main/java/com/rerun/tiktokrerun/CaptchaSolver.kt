package com.rerun.tiktokrerun

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * YOLOv5 jigsaw/arrow captcha solver. Loads a fine-tuned YOLOv5s model from
 * assets and drives the slider via Accessibility gesture dispatch.
 *
 * Model contract (from decompile of competitor AutoCaptchaTT — see
 * tools/captcha-poc/solve.py for the Python reference):
 *   Input:  [1, 640, 640, 3] float32 in [0,1]
 *   Output: [1, 25200, 7] = (cx, cy, w, h, obj, p_jigsaw, p_arrow)
 *   Labels: 0=jigsaw, 1=arrow
 *
 * Solve algorithm (mirrors competitor's MyAccessibilityService.a()):
 *   1. takeScreenshot via Accessibility (API 30+)
 *   2. resize to 640x640, normalize to float32 [0,1]
 *   3. run interpreter → 25200 candidate boxes
 *   4. per-class NMS @ IoU 0.45, then global NMS @ IoU 0.70
 *   5. filter: jigsaw conf>=0.80, arrow conf>=0.20, centerY in CAPTCHA band
 *   6. swipe arrow.center → jigsaw.center over 800ms (+ jitter)
 *
 * Threading: load + infer on Dispatchers.Default; gesture dispatch is async
 * via Accessibility's executor.
 */
object CaptchaSolver {

    private const val TAG = "CaptchaSolver"

    private const val MODEL_ASSET = "captcha/mobile_tiktok4.tflite"
    private const val LABEL_ASSET = "captcha/labels.txt"

    private const val INPUT_SIZE = 640
    private const val OBJECTNESS_THRESHOLD = 0.20f
    private const val NMS_IOU_PER_CLASS = 0.45f
    private const val NMS_IOU_GLOBAL = 0.70f

    // Trigger thresholds from competitor decompile.
    private const val JIGSAW_FIRE_THRESHOLD = 0.80f
    private const val ARROW_FIRE_THRESHOLD = 0.20f

    // Y band where the slider track lives on a ~1920px-tall portrait screen,
    // from competitor's `if (iCenterY > 550 && iCenterY < 1500)`. Scaled to
    // device height at runtime.
    private const val REF_HEIGHT = 1920
    private const val REF_BAND_TOP = 550
    private const val REF_BAND_BOTTOM = 1500

    private const val SWIPE_DURATION_MS = 800L
    private const val JITTER_PIXELS = 10

    private const val LABEL_JIGSAW = "jigsaw"
    private const val LABEL_ARROW = "arrow"

    // Anti-loop: if the same jigsaw X has been seen this many times in a row
    // we assume the model is stuck on a misdetection and back off.
    private const val STUCK_RETRY_LIMIT = 5

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var labels: List<String> = emptyList()
    @Volatile private var loadFailedReason: String? = null

    private var lastJigsawX: Int = -1
    private var stuckCount: Int = 0

    /** Lazy load — call once before first inference. */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (interpreter != null) return true
        if (loadFailedReason != null) return false
        try {
            val model = loadModelFile(context, MODEL_ASSET)
            val opts = Interpreter.Options().apply { setNumThreads(4) }
            interpreter = Interpreter(model, opts)
            labels = context.assets.open(LABEL_ASSET).bufferedReader().use { it.readLines() }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            Log.i(TAG, "model loaded — labels=$labels")
            return true
        } catch (e: Throwable) {
            loadFailedReason = e.message
            Log.e(TAG, "model load failed", e)
            return false
        }
    }

    /**
     * Try to solve one CAPTCHA frame. Returns true if a swipe was dispatched
     * (i.e. the model thinks it found jigsaw+arrow); the caller still needs
     * to verify by re-checking the accessibility tree on the next tick.
     *
     * [onCollectedSample] receives the raw screenshot bitmap before any
     * processing — caller uses this to persist training data. Bitmap is
     * recycled after the callback returns, so caller must copy/save synchronously.
     */
    suspend fun trySolve(
        service: AccessibilityService,
        onCollectedSample: ((Bitmap) -> Unit)? = null,
    ): SolveResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return SolveResult.Unsupported("takeScreenshot requires Android 11+ (API 30)")
        }
        if (!ensureLoaded(service)) {
            return SolveResult.LoadFailed(loadFailedReason ?: "unknown")
        }
        val bitmap = takeScreenshotSuspending(service)
            ?: return SolveResult.NoScreenshot

        // Hand the raw bitmap to the collector before we run inference, so
        // even loads that fail still contribute training data. Use a copy
        // because the caller might keep the reference past recycle.
        if (onCollectedSample != null) {
            try {
                onCollectedSample(bitmap)
            } catch (t: Throwable) {
                Log.w(TAG, "sample collector threw — ignoring", t)
            }
        }

        val srcW = bitmap.width
        val srcH = bitmap.height
        val detections = withContext(Dispatchers.Default) {
            val input = preprocess(bitmap)
            bitmap.recycle()
            val raw = Array(1) { Array(25200) { FloatArray(7) } }
            interpreter!!.run(input, raw)
            decode(raw, srcW, srcH)
        }
        Log.i(TAG, "detections=${detections.size}")

        val verdict = pickActionable(detections, srcH)
            ?: return SolveResult.NoTarget(detections)

        // Stuck detection — if we keep landing on the same jigsaw X, the
        // model is probably misdetecting a stationary background element.
        if (verdict.jigsawX == lastJigsawX) {
            stuckCount++
            if (stuckCount >= STUCK_RETRY_LIMIT) {
                Log.w(TAG, "stuck on jigsawX=${verdict.jigsawX} for $stuckCount tries — backing off")
                stuckCount = 0
                lastJigsawX = -1
                return SolveResult.Stuck
            }
        } else {
            stuckCount = 0
        }
        lastJigsawX = verdict.jigsawX

        val ok = dispatchSwipe(service, verdict.arrowX, verdict.arrowY, verdict.jigsawX, verdict.arrowY)
        return if (ok) SolveResult.SwipeDispatched(verdict) else SolveResult.SwipeFailed(verdict)
    }

    /** Reset stuck-tracking state — call when a new captcha session starts. */
    fun resetSession() {
        lastJigsawX = -1
        stuckCount = 0
    }

    private fun loadModelFile(context: Context, asset: String): MappedByteBuffer {
        val afd = context.assets.openFd(asset)
        val fis = afd.createInputStream()
        val channel = fis.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buf = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f)
            buf.putFloat(((p shr 8) and 0xFF) / 255f)
            buf.putFloat((p and 0xFF) / 255f)
        }
        buf.rewind()
        if (scaled !== bitmap) scaled.recycle()
        return buf
    }

    /** Decode YOLOv5 head. Mirrors q.java's c() — boxes normalized to [0,1],
     *  cx/cy/w/h scaled back to source resolution, score = objectness only. */
    private fun decode(raw: Array<Array<FloatArray>>, srcW: Int, srcH: Int): List<Detection> {
        val candidates = ArrayList<Detection>()
        for (row in raw[0]) {
            val obj = row[4]
            if (obj < OBJECTNESS_THRESHOLD) continue
            val pJ = row[5]
            val pA = row[6]
            val cls = if (pJ >= pA) 0 else 1
            val cx = row[0] * srcW
            val cy = row[1] * srcH
            val w = row[2] * srcW
            val h = row[3] * srcH
            val x1 = max(0f, cx - w / 2)
            val y1 = max(0f, cy - h / 2)
            val x2 = min(srcW.toFloat(), cx + w / 2)
            val y2 = min(srcH.toFloat(), cy + h / 2)
            candidates.add(Detection(x1, y1, x2, y2, obj, cls))
        }
        // Per-class NMS first.
        val perClass = candidates.groupBy { it.cls }
            .flatMap { (_, dets) -> nms(dets, NMS_IOU_PER_CLASS) }
        // Then global NMS across classes.
        return nms(perClass, NMS_IOU_GLOBAL)
    }

    private fun nms(dets: List<Detection>, iouThr: Float): List<Detection> {
        val sorted = dets.sortedByDescending { it.conf }.toMutableList()
        val kept = ArrayList<Detection>()
        while (sorted.isNotEmpty()) {
            val head = sorted.removeAt(0)
            kept.add(head)
            val it = sorted.iterator()
            while (it.hasNext()) {
                if (iou(head, it.next()) >= iouThr) it.remove()
            }
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val iw = max(0f, ix2 - ix1)
        val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val union = areaA + areaB - inter
        return if (union <= 0f) 1f else inter / union
    }

    /** Pick the best (jigsaw, arrow) pair inside the CAPTCHA band. */
    private fun pickActionable(detections: List<Detection>, srcH: Int): Verdict? {
        val scale = srcH.toFloat() / REF_HEIGHT
        val bandTop = REF_BAND_TOP * scale
        val bandBottom = REF_BAND_BOTTOM * scale
        var jigsaw: Detection? = null
        var arrow: Detection? = null
        for (d in detections) {
            val cy = (d.y1 + d.y2) / 2f
            if (cy < bandTop || cy > bandBottom) continue
            val name = labels.getOrNull(d.cls) ?: continue
            when (name) {
                LABEL_JIGSAW -> if (d.conf >= JIGSAW_FIRE_THRESHOLD && (jigsaw == null || d.conf > jigsaw.conf)) jigsaw = d
                LABEL_ARROW -> if (d.conf >= ARROW_FIRE_THRESHOLD && (arrow == null || d.conf > arrow.conf)) arrow = d
            }
        }
        val j = jigsaw ?: return null
        val a = arrow ?: return null
        return Verdict(
            jigsawX = ((j.x1 + j.x2) / 2f).toInt(),
            arrowX = ((a.x1 + a.x2) / 2f).toInt(),
            arrowY = ((a.y1 + a.y2) / 2f).toInt(),
            jigsawConf = j.conf,
            arrowConf = a.conf,
        )
    }

    private suspend fun dispatchSwipe(
        service: AccessibilityService,
        startX: Int, startY: Int, endX: Int, endY: Int,
    ): Boolean {
        // Add jitter on both endpoints so the trace doesn't look mechanical.
        val jitterStart = Random.nextInt(-JITTER_PIXELS, JITTER_PIXELS + 1)
        val jitterEnd = Random.nextInt(-JITTER_PIXELS, JITTER_PIXELS + 1)
        val sx = (startX + jitterStart).coerceAtLeast(0).toFloat()
        val ex = (endX + jitterEnd).coerceAtLeast(0).toFloat()
        val sy = startY.toFloat()
        val ey = endY.toFloat()
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, 10L, SWIPE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    Log.i(TAG, "captcha swipe completed ($sx,$sy→$ex,$ey)")
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(g: GestureDescription) {
                    Log.w(TAG, "captcha swipe cancelled")
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = service.dispatchGesture(gesture, cb, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    private suspend fun takeScreenshotSuspending(service: AccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        // Copy to software config (ARGB_8888) so we can read pixels for inference.
                        val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        result.hardwareBuffer.close()
                        if (cont.isActive) cont.resume(sw)
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed code=$errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }
    }

    private data class Detection(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val conf: Float, val cls: Int,
    )

    data class Verdict(
        val jigsawX: Int, val arrowX: Int, val arrowY: Int,
        val jigsawConf: Float, val arrowConf: Float,
    )

    sealed class SolveResult {
        data class SwipeDispatched(val verdict: Verdict) : SolveResult()
        data class SwipeFailed(val verdict: Verdict) : SolveResult()
        data class NoTarget(val detections: List<Any>) : SolveResult()
        object NoScreenshot : SolveResult()
        data class LoadFailed(val reason: String) : SolveResult()
        data class Unsupported(val reason: String) : SolveResult()
        object Stuck : SolveResult()
    }
}

/**
 * Saves CAPTCHA screenshots to app-private storage for future training of
 * our own model (path B in the A→B strategy). Files land in:
 *   /sdcard/Android/data/com.rerun.tiktokrerun/files/captcha_samples/
 * which is auto-cleaned when the user uninstalls.
 *
 * Capped at [maxSamples] to bound disk usage; oldest evicted FIFO.
 */
object CaptchaSampleCollector {
    private const val TAG = "CaptchaCollect"
    private const val DIR = "captcha_samples"
    private const val MAX_SAMPLES_DEFAULT = 200

    fun save(context: Context, bitmap: Bitmap, tag: String = "raw", maxSamples: Int = MAX_SAMPLES_DEFAULT) {
        try {
            val dir = File(context.getExternalFilesDir(null), DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "couldn't create $dir")
                return
            }
            val ts = System.currentTimeMillis()
            val file = File(dir, "${ts}_${tag}.png")
            FileOutputStream(file).use { os ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            Log.i(TAG, "saved ${file.name} (${file.length()} bytes)")
            evictOldSamples(dir, maxSamples)
        } catch (t: Throwable) {
            Log.w(TAG, "save failed", t)
        }
    }

    private fun evictOldSamples(dir: File, max: Int) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= max) return
        val toDelete = files.size - max
        for (i in 0 until toDelete) {
            if (!files[i].delete()) Log.w(TAG, "evict failed ${files[i].name}")
        }
    }
}
