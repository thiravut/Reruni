package com.rerun.tiktokrerun

import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.rerun.tiktokrerun.databinding.ActivityOnboardingBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * First-time pair screen — shown whenever the device has no usable creds.
 *
 * Single Activity owns the QR scan flow: a fresh scan persists serverUrl +
 * pairToken, starts [ConnectionService], and transitions to [MainActivity].
 * Routing in / out of here is centralized in [routeForPairState] so any
 * future entry points (deep links, notification taps) land on the right
 * screen without duplicating logic.
 *
 * The launcher target is OnboardingActivity. MainActivity's role is the
 * post-pair home screen; an unpaired open of the app bounces through here
 * first.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var prefs: AppPrefs

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        handleScannedQr(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)

        // If creds already exist from a previous install/session, skip
        // onboarding entirely. Tapping the launcher should never linger
        // on this screen when the device is already paired.
        if (isPaired()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyLogoAccent()

        binding.onboardingScanButton.setOnClickListener { launchScanner() }
        binding.onboardingManualEntry.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        observePairCompletion()
    }

    /**
     * Stay on this screen until the WS hands back a long-lived deviceToken
     * (via the `paired` envelope), then route to Home. Watching [WsBus.state]
     * is enough because the WS handler always updates deviceToken before
     * flipping state to Connected — re-checking [isPaired] on each emission
     * catches the right moment without polling.
     */
    private fun observePairCompletion() {
        lifecycleScope.launch {
            WsBus.state.collect {
                if (isPaired()) {
                    startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // SettingsActivity can pair the device manually — if it did, route to
        // the home screen so the user doesn't have to back out twice.
        if (isPaired()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    /**
     * Tint the trailing "-i" of the logo with the accent color so the
     * mobile hero echoes the landing's `Rerun<span>i</span>` brand mark.
     */
    private fun applyLogoAccent() {
        val text = binding.onboardingLogo.text.toString()
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
        binding.onboardingLogo.text = span
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.onboarding_title))
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
        )
    }

    private fun handleScannedQr(contents: String) {
        // A non-JSON QR is the wrong-QR case — distinguish from a JSON QR
        // missing url/token fields, since the recovery is different
        // (try a different code vs ask portal to regenerate).
        val obj = try {
            JSONObject(contents)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.onboarding_qr_not_reruni), Toast.LENGTH_LONG).show()
            return
        }
        val url = obj.optString("url")
        val token = obj.optString("token")
        if (url.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, getString(R.string.onboarding_qr_missing_fields), Toast.LENGTH_LONG).show()
            return
        }
        prefs.serverUrl = url
        prefs.pairToken = token
        prefs.connectionPaused = false
        ConnectionService.start(this)
        // Stay on Onboarding until observePairCompletion sees deviceToken.
        // No URL in the toast — leaks IP if shown during screen-share.
        Toast.makeText(this, getString(R.string.onboarding_pairing_toast), Toast.LENGTH_SHORT).show()
    }

    /** "Fully paired" — server already returned a long-lived deviceToken (or
     *  at least the deviceId was assigned). A bare pairToken does NOT count:
     *  that's a transient mid-pair state, and routing to Home in that window
     *  leaves the user stuck if the handshake never completes. We stay on
     *  Onboarding until the WS observer confirms the device record. */
    private fun isPaired(): Boolean =
        prefs.deviceToken.isNotEmpty() || prefs.deviceId.isNotEmpty()
}
