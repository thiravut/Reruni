package com.rerun.tiktokrerun

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that automates the TikTok "Go Live → Mobile Gaming → Screen Share" flow.
 *
 * v3.0-alpha: selectors are best-effort against Thai TikTok app 2026. Likely to need iteration
 * as TikTok updates UI. See Autopilot.kt for the actual flow logic.
 *
 * Requires user to enable in: Settings → Accessibility → TiktokRerun → On
 */
class TikTokAutopilotService : AccessibilityService() {

    companion object {
        private const val TAG = "AutopilotSvc"

        // TikTok package names by region:
        // - com.zhiliaoapp.musically: global (incl. TH)
        // - com.ss.android.ugc.trill: SEA legacy
        // - com.ss.android.ugc.aweme: mainland China (Douyin)
        val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
        )

        @Volatile
        var instance: TikTokAutopilotService? = null
            private set

        /** Is the user's Accessibility Service enabled for our app? */
        fun isEnabled(context: Context): Boolean {
            val expected = context.packageName + "/" +
                TikTokAutopilotService::class.java.canonicalName
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "service connected — autopilot armed")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        Log.i(TAG, "service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't react to passive events. The Autopilot drives the flow proactively
        // via a coroutine that walks the UI tree on demand.
        // Hook left in place for future state-machine variants.
    }

    override fun onInterrupt() {
        Log.w(TAG, "service interrupted")
    }

    /** Snapshot the currently-active window root for selector queries. */
    fun activeRoot(): AccessibilityNodeInfo? = rootInActiveWindow
}
