package com.rerun.tiktokrerun

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Snapshots the device's runtime readiness — permission grants, installed
 * helper apps, app version, SKU tier — so the portal can render readiness
 * badges + setup guidance per device.
 *
 * Plain data, no Android lifecycle. Call [collect] on the IO thread; it
 * makes only PackageManager / Settings lookups and never blocks on IO.
 */
object CapsCollector {

    /**
     * Returns a JSON blob the WS handler ships under the "device_caps"
     * envelope payload. Schema is loose on purpose: portal renders unknown
     * keys verbatim so we can add new checks without a server change.
     */
    fun collect(context: Context): JSONObject {
        val prefs = AppPrefs(context)
        val o = JSONObject()
        o.put("app_version", BuildConfig.VERSION_NAME)
        o.put("android_sdk", Build.VERSION.SDK_INT)
        o.put("device_model", Build.MANUFACTURER + " " + Build.MODEL)
        o.put("sku_tier", prefs.skuTier.name.lowercase())

        // --- permissions ---
        o.put("overlay_permission", canDrawOverlays(context))
        o.put("notification_permission", hasNotificationPermission(context))
        o.put("battery_unrestricted", isIgnoringBatteryOptimizations(context))
        o.put("accessibility_enabled", isOurAccessibilityEnabled(context))

        // --- installed companions ---
        o.put("tiktok_installed", isPackageInstalled(context, "com.zhiliaoapp.musically") ||
                isPackageInstalled(context, "com.ss.android.ugc.trill"))

        return o
    }

    private fun canDrawOverlays(ctx: Context): Boolean =
        android.provider.Settings.canDrawOverlays(ctx)

    private fun hasNotificationPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * Whether THIS app's TikTokAutopilotService is enabled in Accessibility
     * settings. Robust to formatting variations across Android versions.
     */
    private fun isOurAccessibilityEnabled(ctx: Context): Boolean {
        val flag = try {
            Settings.Secure.getInt(
                ctx.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
            )
        } catch (_: Settings.SettingNotFoundException) { 0 }
        if (flag != 1) return false

        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val target = "${ctx.packageName}/${TikTokAutopilotService::class.java.name}"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (component in splitter) {
            if (component.equals(target, ignoreCase = true)) return true
        }
        return false
    }

    private fun isPackageInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
