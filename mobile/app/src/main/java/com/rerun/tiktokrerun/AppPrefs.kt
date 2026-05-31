package com.rerun.tiktokrerun

import android.content.Context
import android.content.SharedPreferences

/** Which TiktokRerun SKU is active on this device. */
enum class SkuTier(val key: String) {
    /** V1 Lite — screen-share + Mobile Gaming + Smart Overlay. No root needed. Uses stock TikTok. */
    V1Lite("v1_lite"),
    /** V2 Standard — Device camera Go LIVE; customer supplies their own VCam-capable
     *  TikTok APK (typically a sideloaded modded build from a partner). No root needed.
     *  Autopilot drives the modded TikTok the same way it drives stock TikTok. */
    V2Standard("v2_standard"),
    /** V3 Pro — rooted device + Magisk VCam + Device camera Go LIVE + data-dir-swap
     *  account rotation (50+ accounts). */
    V3Pro("v3_pro");

    companion object {
        fun fromKey(k: String?): SkuTier = entries.firstOrNull { it.key == k } ?: V1Lite
    }
}

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("tiktokrerun", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) = prefs.edit().putString("server_url", value).apply()

    /**
     * One-time pair token used on first connection. After server returns the
     * envelope-protocol `paired` response, [deviceToken] is what we use for
     * every subsequent connection (via `hello`) and the pair token can be cleared.
     */
    var pairToken: String
        get() = prefs.getString("pair_token", "") ?: ""
        set(value) = prefs.edit().putString("pair_token", value).apply()

    /** Device-scoped session token, assigned by the server in the `paired` envelope. */
    var deviceToken: String
        get() = prefs.getString("device_token", "") ?: ""
        set(value) = prefs.edit().putString("device_token", value).apply()

    var deviceId: String
        get() = prefs.getString("device_id", "") ?: ""
        set(value) = prefs.edit().putString("device_id", value).apply()

    /** Email of the portal user this device is paired to. Sent by the server
     *  in `paired` / `welcome` envelopes; cached so the home screen can show
     *  the identity even before the next handshake. */
    var ownerEmail: String
        get() = prefs.getString("owner_email", "") ?: ""
        set(value) = prefs.edit().putString("owner_email", value).apply()

    var deviceName: String
        get() = prefs.getString("device_name", android.os.Build.MODEL ?: "android") ?: "android"
        set(value) = prefs.edit().putString("device_name", value).apply()

    /**
     * Newline-separated product keywords. Each non-blank line = one product to auto-pin.
     * Matched as substring (case-insensitive) against product name in TikTok picker, so
     * a short unique fragment works even when names are long/truncated on screen.
     */
    var productKeywords: String
        get() = prefs.getString("product_keywords", "") ?: ""
        set(value) = prefs.edit().putString("product_keywords", value).apply()

    val productKeywordList: List<String>
        get() = productKeywords.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Which SKU is active. Default V1 Lite — V3 requires root + Magisk + GhostCam, so
     * the operator opts in explicitly after setting up their broadcast device.
     */
    var skuTier: SkuTier
        get() = SkuTier.fromKey(prefs.getString("sku_tier", null))
        set(value) = prefs.edit().putString("sku_tier", value.key).apply()

    /**
     * User-driven "pause connection" toggle. When true, [MainActivity.onStart]
     * skips its auto-start of [ConnectionService] so the WS stays offline even
     * across app restarts. Set from the home-screen Disconnect button.
     */
    var connectionPaused: Boolean
        get() = prefs.getBoolean("connection_paused", false)
        set(value) = prefs.edit().putBoolean("connection_paused", value).apply()
}
