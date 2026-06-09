package com.rerun.tiktokvcam

import android.app.AndroidAppHelper
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * Cross-process IPC for the AAC WebSocket endpoint URL — the Reruni
 * controller broadcasts this to push a per-LIVE-session endpoint into
 * TikTok's process.
 *
 * Why this exists: the file path `/sdcard/Android/data/com.zhiliaoapp.musically/
 * files/vcam_ws_endpoint.txt` lives in TikTok's app-specific external storage,
 * which Android 11+'s scoped storage protects from other apps' direct writes.
 * Reruni (different UID) gets `ENOENT` trying to open it via `File.writeText`.
 * But this receiver runs INSIDE TikTok's process, so the same write succeeds —
 * we are the owning UID here.
 *
 * Wire protocol:
 *   - Reruni sends `Intent("com.rerun.vcam.SET_WS_ENDPOINT").setPackage(tiktokPkg)`
 *     with extra `"url"` = absolute `wss://...` (or `ws://...`) URL.
 *   - This receiver writes the URL into the same path that
 *     [Mp4GWsClient.readEndpoint] reads on connect.
 *
 * Ordering: Reruni broadcasts SET_WS_ENDPOINT BEFORE the KILL_SELF kill switch
 * fires. The receiver's file write is synchronous on the main thread, so it
 * completes before the kill broadcast's delayed `Process.killProcess` runs
 * (which itself defers 50 ms to let logs flush).
 *
 * Cross-process security: `Context.RECEIVER_EXPORTED` lets cross-UID broadcasts
 * reach us. The receiver only writes a single file to TikTok's own external
 * storage and validates the action string — no privilege escalation possible.
 */
object VcamEndpointReceiver {
    private const val TAG = "TiktokRerunVCam"
    const val ACTION_SET_WS_ENDPOINT = "com.rerun.vcam.SET_WS_ENDPOINT"
    const val EXTRA_URL = "url"

    private const val TARGET_PATH =
        "/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_ws_endpoint.txt"

    @Volatile private var installed = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val app = AndroidAppHelper.currentApplication()
        if (app != null) {
            registerOnContext(app)
            return
        }
        val appClass = XposedHelpers.findClass(
            "android.app.Application", lpparam.classLoader,
        )
        XposedBridge.hookAllMethods(appClass, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val ctx = param.thisObject as? Application ?: return
                registerOnContext(ctx)
            }
        })
        log("deferred: Application.onCreate hook installed")
    }

    private fun registerOnContext(ctx: Context) {
        if (installed) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_SET_WS_ENDPOINT) return
                    val url = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
                    if (url.isEmpty()) {
                        log("SET_WS_ENDPOINT received with empty url — ignored")
                        return
                    }
                    if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                        log("SET_WS_ENDPOINT received non-ws scheme '$url' — ignored")
                        return
                    }
                    try {
                        val f = File(TARGET_PATH)
                        f.parentFile?.mkdirs()
                        f.writeText(url)
                        log("wrote endpoint: $url → $TARGET_PATH")
                    } catch (t: Throwable) {
                        log("write failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
            val filter = IntentFilter(ACTION_SET_WS_ENDPOINT)
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            installed = true
            log("installed (action=$ACTION_SET_WS_ENDPOINT)")
        } catch (t: Throwable) {
            log("registerReceiver failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] VcamEndpointReceiver: $msg")
        Log.i(TAG, "VcamEndpointReceiver: $msg")
    }
}
