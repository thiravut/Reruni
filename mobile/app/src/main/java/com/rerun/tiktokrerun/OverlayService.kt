package com.rerun.tiktokrerun

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Smart Overlay Broadcast Mode — Layer 1 (video) + Layer 2 (tick indicator).
 *
 * G1 (capture compatibility) passed 2026-05-26 — TikTok screen-share captures our
 * `TYPE_APPLICATION_OVERLAY` window. This slice swaps the magenta test content for
 * real ExoPlayer playback so we can validate G2 (no block detection, long-run)
 * and G3 (accessibility taps still reach TikTok underneath).
 *
 * Touch passthrough: window is `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` so the
 * AccessibilityService can drive TikTok UI underneath without our overlay
 * swallowing taps.
 *
 * Start modes:
 * - `ACTION_START_TEST` — magenta band + counter (the G1 test pattern).
 * - `ACTION_START_VIDEO` + EXTRA_VIDEO_URI — loops the given video in the overlay.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIF_ID = 4242
        private const val CHANNEL_ID = "overlay_companion"
        const val ACTION_START_TEST = "com.rerun.tiktokrerun.OVERLAY_START_TEST"
        const val ACTION_START_VIDEO = "com.rerun.tiktokrerun.OVERLAY_START_VIDEO"
        const val ACTION_STOP = "com.rerun.tiktokrerun.OVERLAY_STOP"
        const val EXTRA_VIDEO_URI = "video_uri"

        val isRunning = MutableStateFlow(false)
        val activeMode = MutableStateFlow<Mode?>(null)

        enum class Mode { Test, Video }

        fun startTest(ctx: Context) {
            launch(ctx, Intent(ctx, OverlayService::class.java).setAction(ACTION_START_TEST))
        }

        fun startVideo(ctx: Context, videoUri: Uri) {
            launch(
                ctx,
                Intent(ctx, OverlayService::class.java)
                    .setAction(ACTION_START_VIDEO)
                    .putExtra(EXTRA_VIDEO_URI, videoUri.toString()),
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_STOP))
        }

        private fun launch(ctx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayRoot: FrameLayout? = null
    private var tickJob: Job? = null
    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val startUptime = SystemClock.elapsedRealtime()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_VIDEO -> {
                val uriStr = intent.getStringExtra(EXTRA_VIDEO_URI)
                if (uriStr.isNullOrBlank()) {
                    Log.w(TAG, "video start missing uri — falling back to test pattern")
                    showOverlay(Mode.Test, null)
                } else {
                    showOverlay(Mode.Video, Uri.parse(uriStr))
                }
            }
            ACTION_START_TEST -> showOverlay(Mode.Test, null)
            else -> showOverlay(Mode.Test, null)
        }
        return START_STICKY
    }

    private fun showOverlay(mode: Mode, videoUri: Uri?) {
        if (overlayRoot != null) {
            Log.i(TAG, "overlay already showing — ignoring re-show in mode=$mode")
            return
        }
        startForegroundNotif(mode)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val root = FrameLayout(this)
        when (mode) {
            Mode.Test -> fillTestPattern(root)
            Mode.Video -> fillVideoLayer(root, videoUri!!)
        }
        // Tiny corner indicator proves the overlay is *live* (not a freeze frame).
        // Small + bottom-right so it doesn't dominate the broadcast.
        root.addView(
            cornerTickView(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { setMargins(0, 0, 24, 80) },
        )

        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed — SYSTEM_ALERT_WINDOW permission missing?", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        overlayRoot = root
        isRunning.value = true
        activeMode.value = mode
        Log.i(TAG, "overlay shown (mode=$mode, type=$type)")
    }

    private fun fillTestPattern(root: FrameLayout) {
        root.setBackgroundColor(Color.argb(220, 0xFF, 0x2D, 0x55))
        val centerView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            text = "OVERLAY TEST PATTERN"
        }
        root.addView(
            centerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
    }

    private fun fillVideoLayer(root: FrameLayout, videoUri: Uri) {
        // Black backdrop in case the video's aspect doesn't fill the screen.
        root.setBackgroundColor(Color.BLACK)

        // SurfaceView (not TextureView) — SurfaceView's dedicated compositor surface
        // is what MediaProjection captures correctly through SAW overlays on most
        // Android versions. TextureView can be filtered out of some capture paths.
        val surface = SurfaceView(this)
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        val p = ExoPlayer.Builder(this).build().apply {
            setVideoSurfaceView(surface)
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f  // muted — broadcast audio path is TikTok's, not ours
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
        player = p
    }

    private fun cornerTickView(): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(140, 0, 0, 0))
        setPadding(12, 6, 12, 6)
        textSize = 11f
        typeface = Typeface.MONOSPACE
        text = stampText()
        scope.launch {
            while (true) {
                text = stampText()
                delay(1000)
            }
        }.also { tickJob = it }
    }

    private fun stampText(): String {
        val elapsedMs = SystemClock.elapsedRealtime() - startUptime
        val wallClock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        return "$wallClock · t+${elapsedMs / 1000}s"
    }

    private fun stopOverlay() {
        tickJob?.cancel()
        tickJob = null
        player?.release()
        player = null
        overlayRoot?.let { v ->
            runCatching { windowManager?.removeView(v) }
                .onFailure { Log.w(TAG, "removeView", it) }
        }
        overlayRoot = null
        windowManager = null
        isRunning.value = false
        activeMode.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        scope.cancel()
        stopOverlay()
        super.onDestroy()
    }

    private fun startForegroundNotif(mode: Mode) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Smart Overlay",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Broadcast overlay companion" }
            )
        }

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = when (mode) {
            Mode.Test -> "Smart Overlay (Test Pattern)"
            Mode.Video -> "Smart Overlay กำลังเล่นวิดีโอ"
        }

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(title)
            .setContentText("กดเพื่อปิด overlay")
            .setContentIntent(stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
