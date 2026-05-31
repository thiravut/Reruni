package com.rerun.tiktokrerun

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.rerun.tiktokrerun.databinding.ActivityMainBinding
import com.rerun.tiktokrerun.script.ScriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs

    /** Captured from the most recent start_live PlayCommand so the loop-goal
     *  observer can echo the right ids into the `live_ended` + `ack`
     *  envelopes when overlay playback finishes. */
    private var currentLiveSessionId: Long = 0L
    private var currentCommandId: String = ""

    /**
     * QR pair scanner — reused from SettingsActivity's flow so the user can
     * pair without leaving the home screen. Result is parsed inline:
     * { "url": "...", "token": "..." }. On a clean parse we persist + start
     * the service immediately; the existing onResume + observers redraw the
     * paired state.
     */
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        try {
            val obj = JSONObject(contents)
            val url = obj.optString("url")
            val token = obj.optString("token")
            if (url.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, getString(R.string.onboarding_qr_parse_error), Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            prefs.serverUrl = url
            prefs.pairToken = token
            prefs.connectionPaused = false
            ConnectionService.start(this)
            Toast.makeText(this, getString(R.string.onboarding_paired_toast, url), Toast.LENGTH_SHORT).show()
            refreshUiState()
        } catch (e: Exception) {
            Toast.makeText(this, "QR parse failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result drives refresh via onResume */ refreshUiState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)

        binding.openSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.onboardingScanButton.setOnClickListener { launchPairScan() }
        binding.onboardingManualEntryButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.permAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.permNotificationButton.setOnClickListener { requestNotificationPermission() }

        binding.connectionToggleButton.setOnClickListener {
            if (prefs.connectionPaused) {
                prefs.connectionPaused = false
                ConnectionService.start(this)
            } else {
                prefs.connectionPaused = true
                ConnectionService.stop(this)
            }
            refreshUiState()
        }

        observeConnection()
        observeIdentity()
        observeRemotePlayCommands()
        observeRemoteStartLiveCommands()
        observeAutopilot()
        observeOverlayLoopGoal()
        observeCaptcha()
        refreshUiState()
        refreshAutomationScripts()
    }

    /**
     * Background-fetch automation scripts so Autopilot picks up any
     * server-side updates the operator published since last launch. Quiet
     * on failure — [ScriptStore.getScript] falls back to the disk cache or
     * the bundled baseline, so the next live still works offline.
     */
    private fun refreshAutomationScripts() {
        if (prefs.serverUrl.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val store = ScriptStore(applicationContext)
            store.refresh(ScriptStore.PERSONAL_LIVE)
        }
    }

    override fun onResume() {
        super.onResume()
        // Accessibility may have been toggled in system Settings — refresh checklist.
        refreshUiState()
    }

    /**
     * Recompute onboarding / permissions / identity visibility based on current
     * prefs + permission state. Cheap and idempotent — safe to call from any
     * flow-state observer.
     */
    private fun refreshUiState() {
        // SKU badge
        binding.tierBadge.text = when (prefs.skuTier) {
            SkuTier.V1Lite     -> getString(R.string.tier_badge_v1)
            SkuTier.V2Standard -> getString(R.string.tier_badge_v2)
            SkuTier.V3Pro      -> getString(R.string.tier_badge_v3)
        }

        val paired = prefs.deviceToken.isNotEmpty() || prefs.deviceId.isNotEmpty()
        val accessibilityOn = TikTokAutopilotService.isEnabled(this)
        val notifGranted = notificationPermissionGranted()
        val anyPermMissing = !accessibilityOn || !notifGranted

        // Onboarding card: only when unpaired — the pair flow lives here so the
        // user doesn't need to bounce to Settings the first time.
        binding.onboardingCard.visibility = if (paired) View.GONE else View.VISIBLE

        // Permissions card: only when paired AND something is still missing.
        // Once everything is granted, the panel disappears — there's nothing to
        // act on, so showing a green "all set" panel would just be visual noise.
        binding.permissionsCard.visibility =
            if (paired && anyPermMissing) View.VISIBLE else View.GONE
        renderPermissionRow(
            statusView = binding.permAccessibilityStatus,
            button = binding.permAccessibilityButton,
            granted = accessibilityOn,
            descRes = R.string.permission_accessibility_desc,
        )
        // Notification permission is only a runtime concept on Android 13+.
        // Older devices have it implicitly — hide the row entirely.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.permNotificationRow.visibility = View.VISIBLE
            renderPermissionRow(
                statusView = binding.permNotificationStatus,
                button = binding.permNotificationButton,
                granted = notifGranted,
                descRes = R.string.permission_notification_desc,
            )
        } else {
            binding.permNotificationRow.visibility = View.GONE
        }

        // Identity card
        binding.identityOwnerText.text = if (prefs.ownerEmail.isNotEmpty()) {
            prefs.ownerEmail
        } else if (!paired) {
            getString(R.string.identity_unpaired)
        } else {
            getString(R.string.identity_owner_unknown)
        }
        binding.identityDeviceText.text = prefs.deviceName.ifEmpty { android.os.Build.MODEL ?: "—" }
        binding.identityDeviceIdText.text = if (prefs.deviceId.isNotEmpty()) {
            "${getString(R.string.identity_device_id_label)}: ${prefs.deviceId}"
        } else {
            ""
        }
        binding.identityDeviceIdText.visibility =
            if (prefs.deviceId.isEmpty()) View.GONE else View.VISIBLE

        // Connection toggle only meaningful when paired.
        binding.connectionToggleButton.visibility = if (paired) View.VISIBLE else View.GONE
        binding.connectionToggleButton.setText(
            if (prefs.connectionPaused) R.string.conn_resume else R.string.conn_pause
        )
    }

    private fun renderPermissionRow(
        statusView: android.widget.TextView,
        button: com.google.android.material.button.MaterialButton,
        granted: Boolean,
        descRes: Int,
    ) {
        if (granted) {
            statusView.text = getString(R.string.permission_status_granted)
            button.visibility = View.GONE
        } else {
            statusView.text = getString(descRes)
            button.visibility = View.VISIBLE
        }
    }

    private fun notificationPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionGranted()) return
        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun launchPairScan() {
        scanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.onboarding_title))
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
        )
    }

    private fun observeRemoteStartLiveCommands() {
        lifecycleScope.launch {
            WsBus.startLiveCommands.collect { cmd ->
                Autopilot.start(
                    this@MainActivity,
                    shoppableModeForTier(),
                    productKeywords = cmd.productKeywords,
                    liveTitle = cmd.liveTitle,
                )
            }
        }
    }

    /** Maps the user's selected SKU tier to the right Shoppable autopilot variant. */
    private fun shoppableModeForTier(): AutopilotMode = when (prefs.skuTier) {
        SkuTier.V3Pro,
        SkuTier.V2Standard -> AutopilotMode.ShoppableVCam
        SkuTier.V1Lite     -> AutopilotMode.Shoppable
    }

    /**
     * When the Smart Overlay (V1) finishes its server-configured loop_count,
     * drive end-of-broadcast: have Autopilot navigate TikTok's End live UI,
     * then notify the server via `live_ended` + `ack` envelopes so the
     * live_sessions row gets closed (api-contract §3.4).
     */
    private fun observeOverlayLoopGoal() {
        lifecycleScope.launch {
            OverlayService.isRunning.collect { /* observe to keep flow alive — actual loop goal not tracked in restored OverlayService */ }
        }
    }

    /**
     * Autopilot has no on-device trigger anymore — operation is portal-driven.
     * We only surface the loud signal: a Toast when a run fails, so the
     * operator near the phone notices and can intervene. Step-by-step progress
     * is intentionally silent; the connection chip + identity card carry the
     * persistent status.
     */
    private fun observeAutopilot() {
        lifecycleScope.launch {
            Autopilot.lastStep.collect { step ->
                if (Autopilot.state.value == AutopilotState.Failed && step.isNotEmpty()) {
                    Toast.makeText(this@MainActivity, step, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Reflect server-supplied owner email into the identity card as soon as it lands. */
    private fun observeIdentity() {
        lifecycleScope.launch {
            WsBus.ownerEmail.collect { refreshUiState() }
        }
    }

    /**
     * Surface a louder Toast the moment autopilot detects a TikTok captcha
     * modal — operator near the phone needs to know to solve the puzzle
     * (or wait for the Magisk autoCaptcha module, once shipped, to do it).
     */
    private fun observeCaptcha() {
        lifecycleScope.launch {
            Autopilot.captchaShowing.collect { showing ->
                if (showing) {
                    Toast.makeText(
                        this@MainActivity,
                        "⚠ TikTok ขึ้น captcha — solve ที่หน้าจอ",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Auto-reconnect whenever we have saved creds and the user hasn't
        // explicitly paused. Either a fresh pairToken (first connection) or a
        // long-lived deviceToken (after pairing succeeded) is enough — the
        // earlier guard only accepted pairToken, so the WS stayed offline on
        // every cold start after the first pair envelope landed.
        // Idempotent: re-issuing startForegroundService on an already-running
        // service is a no-op.
        val haveCreds = prefs.serverUrl.isNotEmpty() &&
            (prefs.pairToken.isNotEmpty() || prefs.deviceToken.isNotEmpty())
        if (haveCreds && !prefs.connectionPaused) {
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
                currentLiveSessionId = cmd.liveSessionId
                currentCommandId = cmd.commandId

                Toast.makeText(this@MainActivity, getString(R.string.receiving_video, cmd.name), Toast.LENGTH_SHORT).show()
                val localFile = try {
                    withContext(Dispatchers.IO) {
                        VideoDownloader.fetch(this@MainActivity, cmd.url)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    return@collect
                }

                // Stage into VcamContentProvider so the patched TikTok's vcam module
                // can read this video over Binder the moment Autopilot opens Device
                // camera. Best-effort: don't block the autopilot on stage failure —
                // vcam falls back to the legacy on-disk path and the user can still
                // run V1 (Screen Share) which doesn't need vcam at all.
                try {
                    withContext(Dispatchers.IO) {
                        VcamContentProvider.stage(this@MainActivity, localFile)
                    }
                    Log.i(TAG, "vcam staged: ${localFile.length()} bytes")
                } catch (t: Throwable) {
                    Log.w(TAG, "vcam stage failed (continuing)", t)
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
                    // Tier picks the autopilot mode:
                    //   V3/V2 = ShoppableVCam (Device camera Go LIVE, VCam feed)
                    //   V1    = Shoppable (Mobile Gaming + Screen Share),
                    //           optionally with Smart Overlay if cmd.useOverlay
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
                            overlayLoopCount = cmd.loopCount,
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

    companion object {
        private const val TAG = "MainActivity"
    }
}
