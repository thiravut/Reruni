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

/**
 * Cross-process A/V resync trigger — Reruni's autopilot broadcasts this
 * action right after tapping the Go LIVE button so audio (PC stream) and
 * video (Mp4FrameProducer) BOTH rewind to MP4 t=0 at the same wall-clock
 * moment, before TikTok's broadcast pipeline initialises.
 *
 * Why broadcast at Go-LIVE tap (and not just first-encoder-fire): at
 * first-encoder-fire both producers have already been running on their own
 * timelines for a few seconds. Resetting them then causes a visible jump
 * (camera surface contains stale content until video restart completes,
 * PC's "reset" message takes RTT to land). Resetting BEFORE the broadcast
 * starts gives both producers ~3-5 s to settle at MP4 t=0 before the
 * pipeline captures anything.
 *
 * Wire protocol:
 *   - Reruni sends `Intent("com.rerun.vcam.AV_RESYNC").setPackage(tiktokPkg)`
 *   - This receiver fires:
 *       Mp4GWsClient.sendReset()      → server restarts AAC stream from frame 0
 *       Mp4FrameProducer.requestRestart() → video decoder rewinds to MP4 t=0
 *
 * Security: same model as [VcamKillSwitch] / [VcamEndpointReceiver].
 * Action string is single-purpose, side effect bounded to "rewind own
 * playback", no privilege escalation.
 */
object VcamAvResyncReceiver {
    private const val TAG = "TiktokRerunVCam"
    const val ACTION_AV_RESYNC = "com.rerun.vcam.AV_RESYNC"

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
                    if (intent?.action != ACTION_AV_RESYNC) return
                    log("received AV_RESYNC — rewinding PC stream + video producer")
                    try {
                        Mp4GWsClient.sendReset()
                    } catch (t: Throwable) {
                        log("Mp4GWsClient.sendReset failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                    try {
                        Mp4FrameProducer.requestRestart()
                    } catch (t: Throwable) {
                        log("Mp4FrameProducer.requestRestart failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
            val filter = IntentFilter(ACTION_AV_RESYNC)
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            installed = true
            log("installed (action=$ACTION_AV_RESYNC)")
        } catch (t: Throwable) {
            log("registerReceiver failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] VcamAvResyncReceiver: $msg")
        Log.i(TAG, "VcamAvResyncReceiver: $msg")
    }
}
