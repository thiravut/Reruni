package com.rerun.tiktokrerun

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.rerun.tiktokrerun.databinding.ActivityMainBinding
import com.rerun.tiktokrerun.script.ScriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Post-pair home screen. Shows the connection chip, owner + device identity,
 * any permission gaps with per-row "เปิด" CTAs, and a full-width red
 * Disconnect button pinned to the bottom.
 *
 * Routing is centralized: if the device isn't paired when this Activity
 * resumes, we bounce to [OnboardingActivity] and finish — so deep links
 * and notification taps don't have to know which screen is currently the
 * "right" one for the device's state.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs

    /** Captured from the most recent start_live PlayCommand so the loop-goal
     *  observer can echo the right ids into the `live_ended` + `ack`
     *  envelopes when overlay playback finishes. */
    private var currentLiveSessionId: Long = 0L
    private var currentCommandId: String = ""

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshUiState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)

        if (!isPaired()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyLogoAccent()

        binding.permAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.permNotificationButton.setOnClickListener { requestNotificationPermission() }
        binding.disconnectButton.setOnClickListener { confirmDisconnect() }

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

    override fun onStart() {
        super.onStart()
        val haveCreds = prefs.serverUrl.isNotEmpty() &&
            (prefs.pairToken.isNotEmpty() || prefs.deviceToken.isNotEmpty())
        if (haveCreds && !prefs.connectionPaused) {
            ConnectionService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // If state changed via Settings / another Activity (e.g. user toggled
        // accessibility, or another flow unpaired us), reroute / redraw.
        if (!isPaired()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        refreshUiState()
    }

    private fun refreshAutomationScripts() {
        if (prefs.serverUrl.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val store = ScriptStore(applicationContext)
            store.refresh(ScriptStore.PERSONAL_LIVE)
            store.refresh(ScriptStore.SHOPPABLE_VCAM)
            store.refresh(ScriptStore.END_LIVE)
        }
    }

    /**
     * Recompute identity / permission rendering from current prefs +
     * permission state. Idempotent — safe to call from any flow-state observer.
     */
    private fun refreshUiState() {
        val accessibilityOn = TikTokAutopilotService.isEnabled(this)
        val notifGranted = notificationPermissionGranted()
        val anyPermMissing = !accessibilityOn || !notifGranted

        // Permissions card: only when anything is still missing. Once everything
        // is granted the panel disappears so the home screen stays clean.
        binding.permissionsCard.visibility = if (anyPermMissing) View.VISIBLE else View.GONE
        renderPermissionRow(
            statusView = binding.permAccessibilityStatus,
            button = binding.permAccessibilityButton,
            granted = accessibilityOn,
            descRes = R.string.permission_accessibility_desc,
        )
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

        binding.identityOwnerText.text = if (prefs.ownerEmail.isNotEmpty()) {
            prefs.ownerEmail
        } else {
            getString(R.string.identity_owner_unknown)
        }
        // Identity row: prefer the operator-set deviceName, then the system's
        // user-set device name (Bluetooth-display label), then fall back to
        // the raw model code. "SM-A156E" is meaningless to a reseller, so it
        // sits last on the chain — only shown when nothing else is set.
        binding.identityDeviceText.text = prefs.deviceName.ifEmpty {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
                ?: Build.MODEL ?: "—"
        }
        binding.identityDeviceIdText.text = if (prefs.deviceId.isNotEmpty()) {
            "${getString(R.string.identity_device_id_label)}: ${prefs.deviceId}"
        } else {
            ""
        }
        binding.identityDeviceIdText.visibility =
            if (prefs.deviceId.isEmpty()) View.GONE else View.VISIBLE
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

    /**
     * Confirm + unpair. Wipes server creds, stops the WS service, and bounces
     * to OnboardingActivity. Server-side device record remains; the user has
     * to scan a fresh QR to come back online.
     */
    private fun confirmDisconnect() {
        AlertDialog.Builder(this)
            .setTitle(R.string.conn_disconnect_confirm_title)
            .setMessage(R.string.conn_disconnect_confirm_msg)
            .setPositiveButton(R.string.conn_disconnect_confirm_yes) { _, _ -> unpairAndExit() }
            .setNegativeButton(R.string.conn_disconnect_confirm_cancel, null)
            .show()
    }

    private fun unpairAndExit() {
        ConnectionService.stop(this)
        prefs.serverUrl = ""
        prefs.pairToken = ""
        prefs.deviceToken = ""
        prefs.deviceId = ""
        prefs.ownerEmail = ""
        prefs.connectionPaused = false
        startActivity(Intent(this, OnboardingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
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

    /** Patched-TikTok is the only ship path now (the vcam LSPosed module
     *  ships with the APK); the tier picker is gone, so every Shoppable run
     *  goes through Device camera + vcam regardless of saved [SkuTier].
     *  Kept as a function rather than inlined so a future re-introduction
     *  of tiers (e.g. a screen-share fallback for non-rooted devices) only
     *  needs to touch this one place. */
    private fun shoppableModeForTier(): AutopilotMode = AutopilotMode.ShoppableVCam

    private fun observeOverlayLoopGoal() {
        lifecycleScope.launch {
            OverlayService.isRunning.collect { /* observe to keep flow alive */ }
        }
    }

    private fun observeAutopilot() {
        lifecycleScope.launch {
            Autopilot.lastStep.collect { step ->
                if (Autopilot.state.value == AutopilotState.Failed && step.isNotEmpty()) {
                    Toast.makeText(this@MainActivity, step, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun observeIdentity() {
        lifecycleScope.launch {
            WsBus.ownerEmail.collect { refreshUiState() }
        }
    }

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
                // camera. Best-effort: don't block the autopilot on stage failure.
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
                            overlayVideoUri = uri,
                            overlayLoopCount = cmd.loopCount,
                            productKeywords = cmd.productKeywords,
                            liveTitle = cmd.liveTitle,
                        )
                    } else {
                        Autopilot.start(
                            this@MainActivity,
                            AutopilotMode.Shoppable,
                            followup = playIntent,
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

    private fun applyLogoAccent() {
        val text = binding.titleText.text.toString()
        val accentStart = text.indexOf('-').takeIf { it >= 0 } ?: return
        val accent = ContextCompat.getColor(this, R.color.reruni_accent)
        val span = SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(accent),
                accentStart,
                text.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        binding.titleText.text = span
    }

    /** Matches the definition in [OnboardingActivity.isPaired] — only the
     *  server-issued deviceToken / deviceId counts. A bare pairToken means
     *  the handshake is still in flight; that case lives on Onboarding. */
    private fun isPaired(): Boolean =
        prefs.deviceToken.isNotEmpty() || prefs.deviceId.isNotEmpty()

    companion object {
        private const val TAG = "MainActivity"
    }
}
