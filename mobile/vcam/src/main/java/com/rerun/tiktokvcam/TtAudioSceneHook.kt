package com.rerun.tiktokvcam

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Force TikTok's LIVE audio pipeline into a music-friendly scene
 * (KARAOKE by default) instead of the voice-tuned CHATROOM default.
 *
 * The "ทุ้ม + แตก" residue that survived every PCM-side knob (rate, amp,
 * LPF cutoff, noise floor, encoder rewrite) traces to TikTok's voice
 * DSP downstream of the AudioRecord buffer. The pipeline has a scene
 * selector ([com.ss.bytertc.engine.type.AudioSceneType]):
 *
 *   DEFAULT(0), CHATROOM(1), HIGH_QUALITY_CHATROOM(2),
 *   LOW_LATENCY(3), KARAOKE(4)
 *
 * TikTok LIVE defaults to CHATROOM — voice-tuned ANS/AGC that mangles
 * music. KARAOKE swaps to music-friendly processing that preserves
 * full-band content.
 *
 * Hook points (in order of certainty for this build):
 *  1. `Y.ARunnableS27S0101000_19.LIZ$2()` — the runnable that calls
 *     `rTCVideo.setAudioScene(...)`. Computes the enum from int field
 *     `i1`. Rewriting `i1` to 4 before the method runs forces KARAOKE.
 *  2. Concrete `*RTCVideo*.setAudioScene(AudioSceneType)` impls — for
 *     callers that bypass the runnable.
 *  3. `LyraxAudioImpl.setAudioScene(LyraxAudioSceneType)` — the
 *     lower-level engine setter.
 *
 * Configurable via file [SCENE_OVERRIDE_PATH] so Pond can try other
 * scenes (LOW_LATENCY=3 also worth trying for minimal DSP).
 */
object TtAudioSceneHook {

    private const val TAG = "TiktokRerunVCam"
    private const val DEFAULT_SCENE = 4   // KARAOKE
    private const val SCENE_OVERRIDE_PATH =
        "/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_scene.txt"

    private fun readSceneOverride(): Int {
        return try {
            val f = java.io.File(SCENE_OVERRIDE_PATH)
            if (!f.exists() || f.length() <= 0L || f.length() > 8L) DEFAULT_SCENE
            else f.readText().trim().toIntOrNull()?.takeIf { it in 0..4 } ?: DEFAULT_SCENE
        } catch (_: Throwable) {
            DEFAULT_SCENE
        }
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var hooks = 0

        // 1. The Runnable that drives setAudioScene from TikTok's ClientImpl.
        //    Field `i1` is the scene-selector int (0..4). Rewrite before
        //    the method body computes the enum + dispatches.
        try {
            val runnableCls = XposedHelpers.findClassIfExists(
                "Y.ARunnableS27S0101000_19", cl,
            )
            if (runnableCls != null) {
                val liz2 = runnableCls.declaredMethods.firstOrNull { it.name == "LIZ\$2" }
                if (liz2 != null) {
                    val i1Field = try {
                        runnableCls.getDeclaredField("i1").apply { isAccessible = true }
                    } catch (_: Throwable) {
                        log("Y.ARunnableS27S0101000_19 has no i1 field")
                        null
                    }
                    if (i1Field != null) {
                        XposedBridge.hookMethod(liz2, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val target = readSceneOverride()
                                    val before = i1Field.getInt(param.thisObject)
                                    if (before != target) {
                                        i1Field.setInt(param.thisObject, target)
                                        log("ARunnable.LIZ\$2: i1 $before → $target")
                                    }
                                } catch (t: Throwable) {
                                    log("LIZ\$2 i1 rewrite threw: ${t.message}")
                                }
                            }
                        })
                        hooks++
                        log("hooked Y.ARunnableS27S0101000_19.LIZ\$2")
                    }
                } else {
                    log("LIZ\$2 not found on Y.ARunnableS27S0101000_19")
                }
            } else {
                log("Y.ARunnableS27S0101000_19 not found (TikTok refactored?)")
            }
        } catch (t: Throwable) {
            log("ARunnable hook failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        // 2. Direct setAudioScene hooks on concrete impls — covers any
        //    call path that doesn't go through the Runnable above.
        for (className in listOf(
            "com.ss.bytertc.engine.engineimpl.RTCVideoImpl",
            "com.ss.bytertc.engine.engineimpl.RTCVideoImplV2",
            "com.ss.lyrax.audio.LyraxAudioImpl",
        )) {
            try {
                val klass = XposedHelpers.findClassIfExists(className, cl) ?: continue
                for (m in klass.declaredMethods) {
                    if (m.name != "setAudioScene") continue
                    val argType = m.parameterTypes.getOrNull(0) ?: continue
                    if (!argType.isEnum) continue
                    val targetIdx = readSceneOverride()
                    val target = argType.enumConstants
                        ?.firstOrNull { e ->
                            try {
                                val v = argType.getDeclaredField("value").run {
                                    isAccessible = true; getInt(e)
                                }
                                v == targetIdx
                            } catch (_: Throwable) {
                                e.toString().contains("KARAOKE", ignoreCase = true)
                            }
                        } ?: continue
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val before = param.args[0]
                                if (before != target) {
                                    param.args[0] = target
                                    log("$className.setAudioScene: $before → $target")
                                }
                            }
                        })
                        hooks++
                        log("hooked $className.setAudioScene")
                    } catch (t: Throwable) {
                        log("$className.setAudioScene hook failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                log("$className lookup failed: ${t.message}")
            }
        }

        log("install: $hooks hook(s) registered, default scene=$DEFAULT_SCENE (KARAOKE)")
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] TtAudioSceneHook: $msg")
        Log.i(TAG, "TtAudioSceneHook: $msg")
    }
}
