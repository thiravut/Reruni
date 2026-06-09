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
        var i = 0
        while (i < steps.length()) {
            val step = steps.getJSONObject(i)
            // `skip_if_no_keywords` lets the script declare a conditional
            // block — when the operator has no product keywords, we
            // fast-forward to the step labelled by `to`. Used to skip the
            // commerce-sheet / Add-products section so we don't open it
            // pointlessly.
            if (step.getString("op") == "skip_if_no_keywords" && !ctx.hasKeywords()) {
                val target = step.getString("to")
                val advanced = advanceToLabel(steps, i + 1, target)
                if (advanced < 0) {
                    val msg = "skip_if_no_keywords: no label '$target' downstream"
                    Log.w(TAG, "script $name aborted at step $i: $msg")
                    return Result.Failed(msg)
                }
                Log.i(TAG, "script $name: skip_if_no_keywords → label '$target' (step $advanced)")
                i = advanced + 1
                continue
            }
            val res = runOp(step, i)
            if (res is Result.Failed) {
                Log.w(TAG, "script $name failed at step $i (${step.optString("op")}): ${res.message}")
                return res
            }
            i++
        }
        Log.i(TAG, "script $name completed (${steps.length()} steps)")
        return Result.Success
    }

    /** Linear scan for `{"op":"label", "name":"<target>"}`. Returns the
     *  matching step index, or -1 if no match. */
    private fun advanceToLabel(steps: org.json.JSONArray, fromIndex: Int, target: String): Int {
        for (i in fromIndex until steps.length()) {
            val s = steps.getJSONObject(i)
            if (s.optString("op") == "label" && s.optString("name") == target) {
                return i
            }
        }
        return -1
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
            "swipe_to_find_tab" -> {
                val ok = ctx.swipeToFindTab(
                    tabLabel = step.getString("tab_label"),
                    confirmMarkers = step.jsonStringArray("confirm_markers"),
                    swipeX1 = step.getDouble("swipe_x1").toFloat(),
                    swipeX2 = step.getDouble("swipe_x2").toFloat(),
                    swipeY = step.getDouble("swipe_y").toFloat(),
                    swipeDurationMs = step.optLong("swipe_duration_ms", 350L),
                    maxIterations = step.optInt("max_iterations", 6),
                    settleDelayMs = step.optLong("settle_delay_ms", 1800L),
                    betweenSwipeDelayMs = step.optLong("between_swipe_delay_ms", 700L),
                )
                if (!ok && !step.optBoolean("ignore_failure", false)) {
                    return Result.Failed(step.optString("fail", "swipe_to_find_tab gave up at step $index"))
                }
                Result.Success
            }
            "set_live_title_if_provided" -> {
                ctx.setLiveTitleIfProvided()
                Result.Success
            }
            "remove_pre_selected_products" -> {
                val removed = ctx.removePreSelectedProducts()
                if (removed > 0) ctx.warn("cleaned $removed pre-selected product(s)")
                Result.Success
            }
            "search_in_picker_first_keyword" -> {
                val found = ctx.searchInPickerFirstKeyword()
                if (!found) ctx.warn(
                    step.optString("warn_on_fail",
                        "search input not found — falling back to scroll-less match on visible list")
                )
                Result.Success
            }
            "auto_pin_products" -> {
                val pinned = ctx.autoPinProducts()
                if (pinned <= 0) {
                    return Result.Failed(step.optString("fail_if_zero",
                        "auto-pin: ไม่พบ product ใดตรง keyword — เช็ค Settings + ดู dump"))
                }
                ctx.warn("auto-pinned $pinned product(s)")
                Result.Success
            }
            "label" -> {
                // No-op marker; used as a jump target by `skip_if_no_keywords`.
                Result.Success
            }
            "skip_if_no_keywords" -> {
                // Handled in execute()'s pre-pass — reaching here means the
                // condition was false (keywords ARE present), so we just
                // proceed past this marker.
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
