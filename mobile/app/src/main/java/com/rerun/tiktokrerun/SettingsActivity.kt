package com.rerun.tiktokrerun

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.rerun.tiktokrerun.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        try {
            val obj = JSONObject(contents)
            val url = obj.optString("url")
            val token = obj.optString("token")
            if (url.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "QR ไม่ใช่ pair format ที่ถูกต้อง", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            binding.serverUrlInput.setText(url)
            binding.pairTokenInput.setText(token)
            Toast.makeText(this, "✓ scanned: $url", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "QR parse failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val msg = if (granted) "✓ อนุญาต notification แล้ว" else "✗ ไม่อนุญาต notification (service ยังรันได้ แต่ไม่เห็น notif)"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        persistAndStartService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.settingsToolbar.setNavigationOnClickListener { finish() }

        observeConnectionInline()

        prefs = AppPrefs(this)
        binding.serverUrlInput.setText(prefs.serverUrl)
        binding.pairTokenInput.setText(prefs.pairToken)
        binding.deviceNameInput.setText(prefs.deviceName)
        binding.productKeywordsInput.setText(prefs.productKeywords)

        // SKU tier selection — persisted immediately; affects autopilot routing.
        when (prefs.skuTier) {
            SkuTier.V1Lite     -> binding.skuV1Radio.isChecked = true
            SkuTier.V2Standard -> binding.skuV2Radio.isChecked = true
            SkuTier.V3Pro      -> binding.skuV3Radio.isChecked = true
        }
        binding.skuTierGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.skuTier = when (checkedId) {
                R.id.skuV3Radio -> SkuTier.V3Pro
                R.id.skuV2Radio -> SkuTier.V2Standard
                else            -> SkuTier.V1Lite
            }
        }
        binding.deviceIdText.text = if (prefs.deviceId.isNotEmpty()) "Device ID: ${prefs.deviceId}" else "Device ID: (จะถูก assign หลัง connect)"

        binding.scanQrButton.setOnClickListener {
            scanLauncher.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("วาง QR ของ dashboard ในกรอบ")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                }
            )
        }

        binding.saveConnectButton.setOnClickListener {
            ensureNotifPermissionThenConnect()
        }

        binding.disconnectButton.setOnClickListener {
            ConnectionService.stop(this)
            finish()
        }

        binding.openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
    }

    private fun refreshAccessibilityStatus() {
        val on = TikTokAutopilotService.isEnabled(this)
        binding.accessibilityStatusText.text = getString(
            if (on) R.string.accessibility_status_on else R.string.accessibility_status_off
        )
    }

    private fun ensureNotifPermissionThenConnect() {
        // Android < 13 — POST_NOTIFICATIONS doesn't exist as a runtime permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Android ${Build.VERSION.SDK_INT} — ไม่ต้องขอ permission notification", Toast.LENGTH_SHORT).show()
            persistAndStartService()
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            Toast.makeText(this, "✓ Notification permission อนุญาตอยู่แล้ว", Toast.LENGTH_SHORT).show()
            persistAndStartService()
            return
        }

        // Not granted. Either never asked OR permanently denied.
        // shouldShowRequestPermissionRationale = true → user denied once, asking again OK
        // shouldShowRequestPermissionRationale = false AND not granted → either initial OR permanently denied
        val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.POST_NOTIFICATIONS
        )

        if (canAskAgain) {
            // System will show the dialog
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Best-effort: try launching first. If user previously permanently denied,
            // the system might silently call back as denied without showing dialog.
            // We launch — if denied, the launcher callback above shows a Toast.
            // Show a fallback path explicitly via dialog so user can flip in app settings.
            AlertDialog.Builder(this)
                .setTitle("ต้องการ Notification Permission")
                .setMessage("Foreground service ต้องการ permission แจ้งเตือนเพื่อแสดงสถานะ\n\nถ้าระบบไม่ขึ้นกล่องขอ permission แสดงว่าเคยปฏิเสธถาวรไว้ — กด \"เปิด Settings\" เพื่อตั้งค่าด้วยตัวเอง")
                .setPositiveButton("ขออีกครั้ง") { _, _ ->
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .setNeutralButton("เปิด Settings") { _, _ ->
                    openAppSettings()
                }
                .setNegativeButton("ข้าม") { _, _ -> persistAndStartService() }
                .show()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun persistAndStartService() {
        prefs.serverUrl = binding.serverUrlInput.text.toString().trim()
        prefs.pairToken = binding.pairTokenInput.text.toString().trim()
        prefs.deviceName = binding.deviceNameInput.text.toString().trim().ifEmpty { android.os.Build.MODEL ?: "android" }
        prefs.productKeywords = binding.productKeywordsInput.text.toString()
        ConnectionService.start(this)
        binding.connectStatusInline.visibility = View.VISIBLE
        binding.connectStatusInline.text = getString(R.string.connect_started)
    }

    private fun observeConnectionInline() {
        lifecycleScope.launch {
            WsBus.state.collect { renderConnectionInline(it, WsBus.statusLine.value) }
        }
        lifecycleScope.launch {
            WsBus.statusLine.collect { renderConnectionInline(WsBus.state.value, it) }
        }
    }

    private fun renderConnectionInline(st: ConnState, line: String) {
        // Only show after the user has tapped Save (status row was made visible).
        if (binding.connectStatusInline.visibility != View.VISIBLE) return
        val (msg, colorRes) = when (st) {
            ConnState.Disconnected -> getString(R.string.conn_disconnected) to R.color.status_offline
            ConnState.Connecting   -> getString(R.string.conn_connecting) to R.color.status_warn
            ConnState.Connected    -> getString(R.string.conn_connected, line.ifEmpty { "online" }) to R.color.status_online
            ConnState.Error        -> getString(R.string.conn_error, line) to R.color.status_error
        }
        binding.connectStatusInline.text = getString(R.string.connect_status_inline, msg)
        binding.connectStatusInline.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
