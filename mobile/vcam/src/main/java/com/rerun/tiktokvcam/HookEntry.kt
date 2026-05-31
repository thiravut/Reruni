package com.rerun.tiktokvcam

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed module entry point for TiktokRerun virtual camera.
 *
 * Registered in `assets/xposed_init` so LSPosed loader invokes
 * [handleLoadPackage] each time a target app process (TikTok variants)
 * starts up.
 *
 * MVP scope (this revision):
 *  - Verify the module loads in the target process by writing a tagged
 *    log line. Subsequent revisions add the Camera2 hooks +
 *    [Mp4FrameSource] + IPC.
 */
class HookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "TiktokRerunVCam"

        /** TikTok packages we want to hook. Mirrors [xposed_scope] in
         *  `res/values/arrays.xml`. */
        private val TARGETS = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGETS) return

        // Dual logging: Xposed bridge log surfaces in LSPosed/LSPatch
        // diagnostics; android Log surfaces in adb logcat with our tag.
        XposedBridge.log("[$TAG] loaded in ${lpparam.packageName} (process=${lpparam.processName})")
        Log.i(TAG, "loaded in ${lpparam.packageName} (process=${lpparam.processName})")

        try {
            Camera2Hook.install(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Camera2Hook.install failed: ${t.message}")
            Log.e(TAG, "Camera2Hook.install failed", t)
        }
    }
}
