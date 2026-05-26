package com.rerun.tiktokrerun

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.rerun.tiktokrerun.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private val selectedVideos = mutableListOf<Uri>()

    private val pickVideosLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            if (!selectedVideos.contains(uri)) {
                selectedVideos.add(uri)
            }
        }
        refreshList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)

        binding.pickVideoButton.setOnClickListener {
            pickVideosLauncher.launch(arrayOf("video/*"))
        }

        binding.clearButton.setOnClickListener {
            selectedVideos.clear()
            refreshList()
        }

        binding.startButton.setOnClickListener {
            if (selectedVideos.isEmpty()) return@setOnClickListener
            startActivity(
                Intent(this, PlayerActivity::class.java).apply {
                    putParcelableArrayListExtra(
                        PlayerActivity.EXTRA_VIDEO_URIS,
                        ArrayList(selectedVideos)
                    )
                }
            )
        }

        binding.openSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.autopilotButton.setOnClickListener {
            launchAutopilot(AutopilotMode.Personal)
        }
        binding.autopilotShoppableButton.setOnClickListener {
            launchAutopilot(shoppableModeForTier())
        }

        binding.overlayToggleButton.setOnClickListener { toggleOverlay() }

        binding.dumpUiButton.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        binding.dumpUiButton.setOnClickListener {
            // Delay-then-dump so user has time to switch back to the target app (TikTok).
            // While our app is in foreground, TikTok's window may be filtered out of
            // service.windows; pulling focus back to TikTok ensures it's captured.
            Toast.makeText(
                this,
                "⏳ Dump in 6s — สลับกลับไป TikTok ทันที (ไม่ต้องกดอะไรเพิ่ม)",
                Toast.LENGTH_LONG
            ).show()
            lifecycleScope.launch {
                kotlinx.coroutines.delay(6000)
                val dump = Autopilot.dumpVisibleNodes()
                val file = java.io.File(externalCacheDir ?: cacheDir, "manual_dump.txt")
                file.writeText(dump)
                android.util.Log.i("UIDump", "─── BEGIN dump (${dump.length} chars, ${dump.lineSequence().count()} lines) ───")
                val chunkSize = 3000
                var start = 0
                var idx = 0
                while (start < dump.length) {
                    val end = (start + chunkSize).coerceAtMost(dump.length)
                    android.util.Log.i("UIDump", "[chunk $idx] " + dump.substring(start, end))
                    start = end
                    idx++
                }
                android.util.Log.i("UIDump", "─── END dump (file=${file.absolutePath}) ───")
            }
        }

        observeConnection()
        observeRemotePlayCommands()
        observeRemoteStartLiveCommands()
        observeAutopilot()
        observeOverlay()
        refreshList()
    }

    private fun toggleOverlay() {
        if (OverlayService.isRunning.value) {
            OverlayService.stop(this)
            return
        }
        if (!canDrawOverlays()) {
            Toast.makeText(this, getString(R.string.overlay_needs_permission), Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }
        // First selected video → real broadcast slice; otherwise → test pattern.
        val video = selectedVideos.firstOrNull()
        if (video != null) OverlayService.startVideo(this, video)
        else OverlayService.startTest(this)
    }

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

    private fun observeOverlay() {
        lifecycleScope.launch {
            OverlayService.isRunning.collect { renderOverlayState() }
        }
        lifecycleScope.launch {
            OverlayService.activeMode.collect { renderOverlayState() }
        }
    }

    private fun renderOverlayState() {
        val running = OverlayService.isRunning.value
        val mode = OverlayService.activeMode.value
        binding.overlayToggleButton.text = getString(
            if (running) R.string.overlay_stop else R.string.overlay_start
        )
        binding.overlayStatusText.text = when {
            !running -> {
                val hint = if (selectedVideos.isEmpty())
                    getString(R.string.overlay_hint_test_pattern)
                else
                    getString(R.string.overlay_hint_first_video, displayName(selectedVideos.first()))
                getString(R.string.overlay_status_idle) + "\n" + hint
            }
            mode == OverlayService.Companion.Mode.Video ->
                getString(R.string.overlay_status_running_video)
            else ->
                getString(R.string.overlay_status_running)
        }
    }

    private fun observeRemoteStartLiveCommands() {
        lifecycleScope.launch {
            WsBus.startLiveCommands.collect { cmd ->
                // Web-triggered start_live = affiliate use case → Shoppable flow.
                // If use_overlay is set we use the operator's first locally-selected video
                // (since start_live carries no video URL). No video → fall back to legacy
                // foreground-switch path and inform the user.
                val overlayUri = if (cmd.useOverlay) {
                    val first = selectedVideos.firstOrNull()
                    if (first == null) {
                        Toast.makeText(
                            this@MainActivity,
                            "use_overlay ขอ video แต่ playlist ว่าง → กลับไปใช้ legacy path",
                            Toast.LENGTH_LONG,
                        ).show()
                        null
                    } else if (!Settings.canDrawOverlays(this@MainActivity)) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.overlay_needs_permission),
                            Toast.LENGTH_LONG,
                        ).show()
                        return@collect
                    } else first
                } else null

                // V3 path doesn't use the SAW overlay (VCam feeds the camera input
                // directly); only V1 needs the overlay URI.
                val mode = shoppableModeForTier()
                Autopilot.start(
                    this@MainActivity,
                    mode,
                    productKeywords = cmd.productKeywords,
                    liveTitle = cmd.liveTitle,
                    overlayVideoUri = if (mode == AutopilotMode.ShoppableVCam) null else overlayUri,
                )
            }
        }
    }

    /** Maps the user's selected SKU tier to the right Shoppable autopilot variant.
     *  V2 (partner's modded TikTok) and V3 (Magisk VCam) both end in Device camera
     *  Go LIVE — the broadcast medium differs but the on-device autopilot does not. */
    private fun shoppableModeForTier(): AutopilotMode = when (prefs.skuTier) {
        SkuTier.V3Pro,
        SkuTier.V2Standard -> AutopilotMode.ShoppableVCam
        SkuTier.V1Lite     -> AutopilotMode.Shoppable
    }

    private fun launchAutopilot(mode: AutopilotMode) {
        if (Autopilot.state.value == AutopilotState.Running) {
            Autopilot.cancel()
            return
        }
        val followup = if (selectedVideos.isNotEmpty()) {
            Intent(this, PlayerActivity::class.java).apply {
                putParcelableArrayListExtra(
                    PlayerActivity.EXTRA_VIDEO_URIS,
                    ArrayList(selectedVideos)
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else null
        Autopilot.start(this, mode, followup)
    }

    private fun observeAutopilot() {
        lifecycleScope.launch {
            Autopilot.activeMode.collect { active ->
                when (active) {
                    null -> {
                        // Idle — show both choices.
                        binding.autopilotShoppableButton.visibility = View.VISIBLE
                        binding.autopilotShoppableButton.text = getString(R.string.autopilot_start_shoppable)
                        binding.autopilotButton.visibility = View.VISIBLE
                        binding.autopilotButton.text = getString(R.string.autopilot_start)
                    }
                    AutopilotMode.Shoppable,
                    AutopilotMode.ShoppableVCam -> {
                        binding.autopilotShoppableButton.visibility = View.VISIBLE
                        binding.autopilotShoppableButton.text = getString(
                            R.string.autopilot_cancel_mode,
                            getString(R.string.autopilot_mode_shoppable)
                        )
                        binding.autopilotButton.visibility = View.GONE
                    }
                    AutopilotMode.Personal -> {
                        binding.autopilotButton.visibility = View.VISIBLE
                        binding.autopilotButton.text = getString(
                            R.string.autopilot_cancel_mode,
                            getString(R.string.autopilot_mode_personal)
                        )
                        binding.autopilotShoppableButton.visibility = View.GONE
                    }
                }
            }
        }
        lifecycleScope.launch {
            Autopilot.lastStep.collect { step ->
                if (step.isNotEmpty() && Autopilot.state.value == AutopilotState.Running) {
                    Toast.makeText(this@MainActivity, getString(R.string.autopilot_running, step), Toast.LENGTH_SHORT).show()
                } else if (Autopilot.state.value == AutopilotState.Failed) {
                    Toast.makeText(this@MainActivity, step, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Ensure the connection service is running whenever there are saved creds.
        // Idempotent: re-issuing startForegroundService on an already-running service is a no-op.
        if (prefs.serverUrl.isNotEmpty() && prefs.pairToken.isNotEmpty()) {
            ConnectionService.start(this)
        }
    }

    private fun observeConnection() {
        lifecycleScope.launch {
            WsBus.state.collect { renderConnection(it, WsBus.statusLine.value) }
        }
        lifecycleScope.launch {
            WsBus.statusLine.collect { renderConnection(WsBus.state.value, it) }
        }
    }

    private fun renderConnection(st: ConnState, line: String) {
        binding.connectionStatus.text = when (st) {
            ConnState.Disconnected -> getString(R.string.conn_disconnected)
            ConnState.Connecting   -> getString(R.string.conn_connecting)
            ConnState.Connected    -> getString(R.string.conn_connected, line.ifEmpty { "online" })
            ConnState.Error        -> getString(R.string.conn_error, line)
        }
        val chipBg = when (st) {
            ConnState.Disconnected -> R.drawable.bg_status_chip_offline
            ConnState.Connecting   -> R.drawable.bg_status_chip_connecting
            ConnState.Connected    -> R.drawable.bg_status_chip_online
            ConnState.Error        -> R.drawable.bg_status_chip_error
        }
        binding.connectionChipContainer.setBackgroundResource(chipBg)
    }

    private fun observeRemotePlayCommands() {
        lifecycleScope.launch {
            WsBus.playCommands.collect { cmd ->
                Toast.makeText(this@MainActivity, getString(R.string.receiving_video, cmd.name), Toast.LENGTH_SHORT).show()
                val localFile = try {
                    withContext(Dispatchers.IO) {
                        VideoDownloader.fetch(this@MainActivity, cmd.url)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    return@collect
                }

                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "$packageName.fileprovider",
                    localFile
                )
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                val playIntent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                    putParcelableArrayListExtra(
                        PlayerActivity.EXTRA_VIDEO_URIS,
                        arrayListOf<Uri>(uri)
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                if (cmd.autoStartLive) {
                    // Tier picks the autopilot mode. V3 Pro = VCam Device camera Go LIVE
                    // (no overlay, no foreground switch — video comes from system camera2
                    // hook). V1 Lite = Mobile Gaming + Screen Share, video reaches the
                    // capture via SAW overlay (if use_overlay) or PlayerActivity foreground.
                    val mode = shoppableModeForTier()
                    if (mode == AutopilotMode.ShoppableVCam) {
                        Autopilot.start(
                            this@MainActivity,
                            mode,
                            productKeywords = cmd.productKeywords,
                            liveTitle = cmd.liveTitle,
                        )
                    } else if (cmd.useOverlay) {
                        if (!Settings.canDrawOverlays(this@MainActivity)) {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.overlay_needs_permission),
                                Toast.LENGTH_LONG,
                            ).show()
                            return@collect
                        }
                        Autopilot.start(
                            this@MainActivity,
                            AutopilotMode.Shoppable,
                            productKeywords = cmd.productKeywords,
                            liveTitle = cmd.liveTitle,
                            overlayVideoUri = uri,
                        )
                    } else {
                        Autopilot.start(
                            this@MainActivity,
                            AutopilotMode.Shoppable,
                            playIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                            productKeywords = cmd.productKeywords,
                            liveTitle = cmd.liveTitle,
                        )
                    }
                } else {
                    startActivity(playIntent)
                }
            }
        }
    }

    private fun refreshList() {
        binding.startButton.isEnabled = selectedVideos.isNotEmpty()
        binding.clearButton.isEnabled = selectedVideos.isNotEmpty()
        binding.selectedVideoText.text = if (selectedVideos.isEmpty()) {
            getString(R.string.no_video_selected)
        } else {
            val names = selectedVideos.joinToString("\n") { uri -> "• " + displayName(uri) }
            getString(R.string.selected_videos_count, selectedVideos.size) + "\n" + names
        }
        renderOverlayState()
    }

    private fun displayName(uri: Uri): String {
        // Prefer the human-readable DISPLAY_NAME (e.g. "Live_clip_03.mp4") over
        // the raw URI tail, which on SAF is opaque ("msf:1000000123").
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }
}
