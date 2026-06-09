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

        // Audio path = speaker-mode acoustic loopback (production default
        // as of 2026-06-08). After exhaustive PCM injection investigation
        // we proved TikTok 45.3.2 + Samsung A15's voice DSP is
        // incompatible with any non-silent injected PCM — every knob
        // (rate, amp, LPF cutoff, encoder rewrite, audio scene, noise
        // floor, source bitrate, mono downmix, cubic vs linear vs pure
        // passthrough) all leave the same "ลำโพงแตก" residue. Speaker
        // mode bypasses the issue: MediaPlayer plays MP4 audio through
        // device speaker → mic captures acoustically → TikTok handles
        // mic input normally.
        //
        // The AudioRecord substitution hooks below stay installed so the
        // "mp4"/"tone" override modes (via vcam_audio_mode.txt) still
        // function for diagnostic A/B testing. Mp4AudioProducer's start()
        // routes to runSpeakerLoop() by default — runDecodeLoop and
        // runToneLoop only fire when an override file requests them.
        try {
            AudioRecordHook.install(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] AudioRecordHook.install failed: ${t.message}")
            Log.e(TAG, "AudioRecordHook.install failed", t)
        }
        try {
            NativeAudioHook.install()
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] NativeAudioHook.install failed: ${t.message}")
            Log.e(TAG, "NativeAudioHook.install failed", t)
        }

        // TtLivePusherAudioHook intentionally NOT installed — none of the
        // method hooks it sets up fire in 45.3.2 (kept in tree as the
        // V3 / Lyrax / encoder-path attempt log).

        // Encoder rewrite (AAC-LC 256 kbps). Toggleable via file —
        // create /sdcard/Android/data/com.zhiliaoapp.musically/files/
        // vcam_encoder_rewrite_off.txt to skip the install so TikTok's
        // default HE-AACv1 64 kbps stays in effect (for A/B against
        // the rewrite).
        val encoderRewriteDisabled = try {
            java.io.File("/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_encoder_rewrite_off.txt").exists()
        } catch (_: Throwable) { false }
        if (encoderRewriteDisabled) {
            XposedBridge.log("[$TAG] TtRtcEncoderHook.install SKIPPED (off-flag present)")
            Log.i(TAG, "TtRtcEncoderHook.install SKIPPED (off-flag present)")
        } else {
            try {
                TtRtcEncoderHook.install(lpparam)
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] TtRtcEncoderHook.install failed: ${t.message}")
                Log.e(TAG, "TtRtcEncoderHook.install failed", t)
            }
        }

        // Force every WebRTC voice processor switch to OFF on
        // AudioDeviceModule. Phase3 recon found they were already false
        // on the build we tested then, but TikTok 45.3.2's pipeline may
        // re-assert them — belt-and-braces.
        try {
            TtRtcAudioHook.install(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] TtRtcAudioHook.install failed: ${t.message}")
            Log.e(TAG, "TtRtcAudioHook.install failed", t)
        }

        // Force audio scene to KARAOKE (music-friendly) instead of the
        // CHATROOM default that voice-tunes the broadcast DSP. The
        // "ทุ้ม + แตก" residue after every PCM-side fix traces here.
        try {
            TtAudioSceneHook.install(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] TtAudioSceneHook.install failed: ${t.message}")
            Log.e(TAG, "TtAudioSceneHook.install failed", t)
        }

        // EncoderOutputRecon DISABLED — confirmed via Phase 1 testing that
        // Java-side AudioEncoder.nativeEncoded never fires on TikTok 45.3.2.
        // Re-enable only when investigating a NEW TikTok build's encoder
        // path. Leaving it installed seems to coincide with the
        // aacEncEncode PLT hook intermittently not firing — possibly
        // because hooking the abstract Java method affects class
        // initialisation order or encoder selection.

        // MediaCodecRecon is in the tree as a diagnostic-only file (used
        // briefly while we suspected per-session encoder randomisation
        // was the root cause). It's not installed in the build — the
        // actual root cause turned out to be xhook refresh timing
        // (libvolcenginertc.so not yet dlopen()'d at install-time
        // refresh, and the only re-refresh trigger was the AudioRecord
        // ctor hook which intermittently misses). The refresh is now
        // also triggered from Mp4GWsClient.onOpen + the ws_inject
        // bootstrap path in Mp4AudioProducer.

        // Cross-process kill switch — lets Reruni controller force-stop
        // this TikTok process before launching a new LIVE session. See
        // [VcamKillSwitch] for the rationale (encoder pipeline enters
        // partial-suspend state when TikTok is backgrounded; only a full
        // process kill recovers).
        try {
            VcamKillSwitch.install(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] VcamKillSwitch.install failed: ${t.message}")
            Log.e(TAG, "VcamKillSwitch.install failed", t)
        }

        // Cross-process endpoint setter — Reruni broadcasts the per-session
        // AAC WS URL via this receiver because Android 11+ scoped storage
        // blocks Reruni from writing TikTok's app-specific external file
        // directly. The receiver runs inside TikTok's UID so the same write
        // succeeds. See [VcamEndpointReceiver] for the rationale.
        try {
            VcamEndpointReceiver.install(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] VcamEndpointReceiver.install failed: ${t.message}")
            Log.e(TAG, "VcamEndpointReceiver.install failed", t)
        }
    }
}
