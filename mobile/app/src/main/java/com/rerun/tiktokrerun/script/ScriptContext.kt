package com.rerun.tiktokrerun.script

import android.content.Context
import android.content.Intent

/**
 * The surface that [ScriptRunner] needs from the host (today: [com.rerun.tiktokrerun.Autopilot]).
 *
 * Concrete implementations adapt the runner's op vocabulary onto the
 * existing primitive functions (tap by text, ensure home, swipe, deliver
 * broadcast, etc.). This indirection is what lets the automation script
 * live in JSON — and eventually be served by the backend — without the
 * runner reaching into Autopilot's privates.
 */
interface ScriptContext {
    /** Android Context used for `launch_tiktok`, `deliver_broadcast`, etc. */
    val context: Context

    /** Intent that launches TikTok (built by the caller — Autopilot.start). */
    val launchIntent: Intent

    suspend fun setStep(label: String)

    suspend fun delayMs(ms: Long)

    suspend fun tapByText(
        labels: List<String>,
        allowContentDesc: Boolean = true,
        retries: Int = 4,
        verifyDisappear: Boolean = false,
    ): Boolean

    suspend fun waitForAny(
        labels: List<String>,
        timeoutMs: Long,
        intervalMs: Long = 600,
    ): Boolean

    suspend fun swipeHorizontal(
        startX: Float,
        endX: Float,
        y: Float,
        durationMs: Long = 500L,
    ): Boolean

    suspend fun ensureTikTokHome()

    suspend fun deliverBroadcastContent()

    suspend fun collapseTikTokOverlay()

    /** Used by ops that want to log a warning but proceed (e.g. optional taps). */
    fun warn(message: String)
}
