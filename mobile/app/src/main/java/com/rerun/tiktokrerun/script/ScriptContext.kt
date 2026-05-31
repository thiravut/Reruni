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

    // ── Phase C: domain-specific helpers exposed as DSL ops ─────────────────
    // Inner retry / animation-settle / accessibility-tree logic stays in
    // Kotlin; the script provides the structural parameters (labels, swipe
    // coords, max iterations) and reads back the outcome.

    /**
     * Swipes a horizontal tab strip until a tab labeled [tabLabel] is visible,
     * taps it, then checks whether [confirmMarkers] indicate we've landed on
     * the intended screen. Used by the Device camera / Mobile gaming reveal
     * loops. Returns true once any of [confirmMarkers] matches.
     */
    suspend fun swipeToFindTab(
        tabLabel: String,
        confirmMarkers: List<String>,
        swipeX1: Float,
        swipeX2: Float,
        swipeY: Float,
        swipeDurationMs: Long,
        maxIterations: Int,
        settleDelayMs: Long,
        betweenSwipeDelayMs: Long,
    ): Boolean

    /** Best-effort wait until any of [labels] appears, identical to [waitForAny]
     *  but used here for the manual fallback path's longer timeout. */
    // (no new method — reuses waitForAny)

    /** No-op when the autopilot has no live title queued for this run. */
    suspend fun setLiveTitleIfProvided(): Boolean

    /** Returns the number of products removed (0 when none were present). */
    suspend fun removePreSelectedProducts(): Int

    /** Searches the product picker for the first configured keyword.
     *  Returns true when the search input was found and used. */
    suspend fun searchInPickerFirstKeyword(): Boolean

    /** Pins all configured keywords; returns the number actually pinned.
     *  Returns 0 when no keywords are configured. */
    suspend fun autoPinProducts(): Int

    /** Used by ops that want to log a warning but proceed (e.g. optional taps). */
    fun warn(message: String)
}
