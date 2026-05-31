package com.rerun.tiktokrerun.script

import android.util.Log
import org.json.JSONObject

/**
 * Walks an automation script (JSON), dispatching each op to [ScriptContext].
 *
 * Schema (v1):
 * ```
 * {
 *   "name": "personal_live",
 *   "version": 1,
 *   "min_tiktok_version": "45.0.0",
 *   "steps": [
 *     {"op":"step", "label":"…"},
 *     {"op":"delay", "ms":3000},
 *     {"op":"launch_tiktok"},
 *     {"op":"ensure_home"},
 *     {"op":"tap_by_text", "labels":["…","…"], "retries":8,
 *      "allow_content_desc":true, "fail":"…",
 *      "ignore_failure":false, "warn_on_fail":"…"},
 *     {"op":"wait_for_any", "labels":["…"], "timeout_ms":8000, "interval_ms":300,
 *      "fail":"…"},
 *     {"op":"swipe", "x1":150, "x2":950, "y":1965, "duration_ms":350},
 *     {"op":"deliver_broadcast"},
 *     {"op":"collapse_overlay"}
 *   ]
 * }
 * ```
 *
 * Failure handling:
 *  - If an op returns false and `ignore_failure` is not set, the runner
 *    returns [Result.Failed] with the op's `fail` message (caller surfaces
 *    via Autopilot.fail). The script does not continue.
 *  - `warn_on_fail` logs a warning when set; combined with `ignore_failure`
 *    it captures "best-effort tap, but proceed if missing".
 *
 * Unknown ops log a warning and are skipped — so adding a new op on the
 * server side won't crash an older client; it just no-ops until the
 * client is updated.
 */
class ScriptRunner(private val ctx: ScriptContext) {

    sealed class Result {
        object Success : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun execute(script: JSONObject): Result {
        val name = script.optString("name", "<unnamed>")
        val version = script.optInt("version", 0)
        Log.i(TAG, "execute script: $name v$version")
        val steps = script.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val res = runOp(step, i)
            if (res is Result.Failed) {
                Log.w(TAG, "script $name failed at step $i (${step.optString("op")}): ${res.message}")
                return res
            }
        }
        Log.i(TAG, "script $name completed (${steps.length()} steps)")
        return Result.Success
    }

    private suspend fun runOp(step: JSONObject, index: Int): Result {
        return when (val op = step.getString("op")) {
            "step" -> {
                ctx.setStep(step.getString("label"))
                Result.Success
            }
            "delay" -> {
                ctx.delayMs(step.getLong("ms"))
                Result.Success
            }
            "launch_tiktok" -> {
                ctx.context.startActivity(ctx.launchIntent)
                Result.Success
            }
            "ensure_home" -> {
                ctx.ensureTikTokHome()
                Result.Success
            }
            "tap_by_text" -> {
                val labels = step.jsonStringArray("labels")
                val retries = step.optInt("retries", 4)
                val allowContentDesc = step.optBoolean("allow_content_desc", true)
                val verifyDisappear = step.optBoolean("verify_disappear", false)
                val ok = ctx.tapByText(labels, allowContentDesc, retries, verifyDisappear)
                if (!ok) {
                    val warnMsg = step.optString("warn_on_fail")
                    if (warnMsg.isNotEmpty()) ctx.warn(warnMsg)
                    if (!step.optBoolean("ignore_failure", false)) {
                        return Result.Failed(step.optString("fail", "tap_by_text failed at step $index"))
                    }
                }
                Result.Success
            }
            "wait_for_any" -> {
                val labels = step.jsonStringArray("labels")
                val timeoutMs = step.optLong("timeout_ms", 8000L)
                val intervalMs = step.optLong("interval_ms", 600L)
                val ok = ctx.waitForAny(labels, timeoutMs, intervalMs)
                if (!ok && !step.optBoolean("ignore_failure", false)) {
                    return Result.Failed(step.optString("fail", "wait_for_any timed out at step $index"))
                }
                Result.Success
            }
            "swipe" -> {
                val ok = ctx.swipeHorizontal(
                    startX = step.getDouble("x1").toFloat(),
                    endX = step.getDouble("x2").toFloat(),
                    y = step.getDouble("y").toFloat(),
                    durationMs = step.optLong("duration_ms", 500L),
                )
                if (!ok && !step.optBoolean("ignore_failure", false)) {
                    return Result.Failed(step.optString("fail", "swipe failed at step $index"))
                }
                Result.Success
            }
            "deliver_broadcast" -> {
                ctx.deliverBroadcastContent()
                Result.Success
            }
            "collapse_overlay" -> {
                ctx.collapseTikTokOverlay()
                Result.Success
            }
            else -> {
                Log.w(TAG, "unknown op '$op' at step $index — skipped")
                Result.Success
            }
        }
    }

    private fun JSONObject.jsonStringArray(name: String): List<String> {
        val arr = getJSONArray(name)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    companion object {
        private const val TAG = "ScriptRunner"
    }
}
