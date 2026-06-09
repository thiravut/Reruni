package com.rerun.tiktokvcam

import android.app.AndroidAppHelper
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Cross-process kill switch — lets the Reruni controller force-stop the
 * patched TikTok process before launching a new LIVE session.
 *
 * Why this exists: Android's swipe-from-recents and `FLAG_ACTIVITY_CLEAR_TASK`
 * both clear the navigation stack but leave the process running. TikTok 45.3.2
 * keeps `libvolcenginertc.so` loaded and its encoder pipeline in a
 * partial-suspend state — on re-foreground the encoder fires at ~2 Hz
 * instead of 50 Hz, so the [hooked_aacEncEncode] PLT hook only has a
 * trickle of opportunities to substitute PC AAC frames. Viewer hears brief
 * audio snippets then silence. Force-stopping the process (full kernel kill)
 * is the only way to reset that state cleanly.
 *
 * Reruni cannot reach TikTok's process via `ActivityManager.killBackgroundProcesses`
 * reliably (only kills cached/background processes, and TikTok may still be
 * in foreground when stop_live → start_live arrive back-to-back). So this
 * receiver runs INSIDE TikTok's process and calls [Process.killProcess] on
 * itself — guaranteed to work, no permission needed.
 *
 * Wire protocol:
 *   - Reruni sends `Intent("com.rerun.vcam.KILL_SELF").setPackage(tiktokPkg)`
 *   - This receiver fires `Process.killProcess(myPid)` → TikTok dies
 *   - Reruni's next `pickTikTokLaunchIntent` spawns a fresh process
 *
 * Cross-process security: the receiver is registered with
 * [Context.RECEIVER_EXPORTED] so cross-UID broadcasts targeting our
 * package can deliver. Since it's filtered to one action string and
 * fires `killProcess(myPid)` (self only, no escalation), the attack
 * surface is "any app can crash TikTok if they know the action string" —
 * harmless on rooted/LSPatch'd devices the operator controls.
 */
object VcamKillSwitch {
    private const val TAG = "TiktokRerunVCam"
    const val ACTION_KILL = "com.rerun.vcam.KILL_SELF"

    @Volatile private var installed = false

    /**
     * Install the receiver. Called from [HookEntry.handleLoadPackage] —
     * tries [AndroidAppHelper.currentApplication] first; if null (early in
     * process lifecycle) hooks [Application.onCreate] to retry once the
     * app context is available.
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val app = AndroidAppHelper.currentApplication()
        if (app != null) {
            registerOnContext(app)
            return
        }
        // Defer until Application.onCreate — the host process is still in
        // early static-init at handleLoadPackage time, so the Application
        // instance isn't usable yet.
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
                    if (intent?.action != ACTION_KILL) return
                    log("received KILL_SELF — calling Process.killProcess(${Process.myPid()})")
                    // Tiny delay so the log line actually flushes to logcat
                    // before we vanish. SIGKILL is immediate.
                    Thread {
                        try { Thread.sleep(50) } catch (_: InterruptedException) {}
                        Process.killProcess(Process.myPid())
                    }.start()
                }
            }
            val filter = IntentFilter(ACTION_KILL)
            // RECEIVER_EXPORTED is required on Android 13+ for the
            // dynamically-registered receiver to accept broadcasts from
            // other UIDs (Reruni's UID ≠ TikTok's UID).
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            installed = true
            log("installed (action=$ACTION_KILL, pid=${Process.myPid()})")
        } catch (t: Throwable) {
            log("registerReceiver failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] VcamKillSwitch: $msg")
        Log.i(TAG, "VcamKillSwitch: $msg")
    }
}
