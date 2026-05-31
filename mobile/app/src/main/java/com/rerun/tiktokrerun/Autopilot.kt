package com.rerun.tiktokrerun

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.rerun.tiktokrerun.script.ScriptContext
import com.rerun.tiktokrerun.script.ScriptRunner
import com.rerun.tiktokrerun.script.ScriptStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class AutopilotState { Idle, Running, Failed, Done }

/** Which live flow Autopilot drives. */
enum class AutopilotMode {
    /** V1 Lite — + → LIVE → Mobile Gaming → Go LIVE → Screen Share. No pin product. */
    Personal,
    /** V1 Lite — + → LIVE → Device camera (pin product) → Mobile gaming → Go LIVE → Screen Share.
     *  Broadcast content arrives via Smart Overlay / PlayerActivity (caller decides). */
    Shoppable,
    /** V3 Pro — + → LIVE → Device camera (pin product) → Go LIVE directly from Device camera.
     *  No Screen Share, no overlay. Magisk VCam supplies the video as the device "camera" feed.
     *  Requires rooted device with GhostCam (or equivalent) feeding camera2. */
    ShoppableVCam,
}

/**
 * Coordinates the "Open TikTok → Go Live" tap sequence across tiers:
 *   - V1 Lite (Personal/Shoppable) → Mobile Gaming + Screen Share + PlayerActivity/Overlay
 *   - V2/V3 (ShoppableVCam) → Device camera + GhostCam/modded VCam feed
 *
 * Run on the main thread (UI selectors must be queried from the AccessibilityService).
 * Each step has a timeout; on miss, the whole flow fails fast so the user can correct manually.
 */
object Autopilot {

    private const val TAG = "Autopilot"

    val state = MutableStateFlow(AutopilotState.Idle)
    val lastStep = MutableStateFlow("")
    val activeMode = MutableStateFlow<AutopilotMode?>(null)
    /**
     * Flips to `true` the moment the autopilot detects a TikTok captcha
     * modal in the accessibility tree, and back to `false` once it's gone.
     * UI can use this to surface a "solve the puzzle" toast / dialog; the
     * (future) Magisk autoCaptcha module can also subscribe and auto-solve.
     */
    val captchaShowing = MutableStateFlow(false)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null

    /** Intent that Autopilot launches after the broadcast is set up. Cleared after consumption. */
    private var followupIntent: Intent? = null

    /** Optional server-sent product keywords for this run; overrides AppPrefs if non-empty. */
    private var keywordsOverride: List<String>? = null

    /** Optional server-sent live title for this run; if non-blank, autopilot sets it on Device camera. */
    private var liveTitleOverride: String = ""

    /**
     * Smart Overlay mode: if non-null, after Screen Share starts we launch
     * [OverlayService] with this URI instead of foreground-switching to
     * [PlayerActivity]. The overlay sits above TikTok so the live capture is the
     * overlay content — and Accessibility taps still reach TikTok underneath
     * (verification gate G3).
     */
    private var overlayVideoUri: android.net.Uri? = null

    /** Loop budget passed to OverlayService (V1 path) — 0 = play forever. */
    private var overlayLoopCount: Int = 0

    // Multilingual labels — TikTok 2026 Thai app + global fallbacks.
    // Order matters: more specific text first.
    private val HOME_TAB_LABELS         = listOf("Home", "หน้าหลัก", "For You", "สำหรับคุณ", "Following", "Friends")
    private val PROFILE_TAB_LABELS      = listOf("Profile", "โปรไฟล์")
    private val CREATE_BUTTON_LABELS    = listOf("สร้าง", "Create", "post", "โพสต์")
    private val LIVE_TAB_LABELS         = listOf("LIVE", "ไลฟ์", "Live", "ถ่ายทอดสด")
    private val MOBILE_GAMING_LABELS    = listOf("Mobile Gaming", "เกมมือถือ", "Mobile gaming", "Gaming", "เกม")
    private val GO_LIVE_LABELS          = listOf("Go LIVE", "Go Live", "GO LIVE", "เริ่มไลฟ์", "เริ่ม LIVE", "เริ่ม Live", "ไลฟ์ทันที", "ไปสด", "Start")
    private val SCREEN_SHARE_LABELS     = listOf("Screen Share", "แชร์หน้าจอ", "Share Screen", "Screen sharing", "หน้าจอ")
    // System "Start recording?" dialog buttons (vary by OEM, locale)
    private val RECORDING_OK_LABELS     = listOf("Start now", "เริ่มเลย", "Start", "เริ่ม", "ตกลง", "OK")

    // Shoppable flow (+ → LIVE → Device camera → business icon → Add product → ... → Mobile gaming)
    private val DEVICE_CAMERA_LABELS    = listOf("Device camera", "กล้องอุปกรณ์")
    private val BUSINESS_ICON_LABELS    = listOf("Business", "ธุรกิจ")
    // Camera-specific icons that ONLY appear on Device camera mode (verified vs Mobile gaming screenshot).
    private val DEVICE_CAMERA_MARKERS   = listOf("Beautify", "Effects", "Flip", "Interact", "ปรับแต่ง", "เอฟเฟกต์")
    // Both Layer 2 (commerce sheet) and Layer 3 (product manager) carry a
    // button whose *text* label is "Add products" — the "+" prefix that
    // appears at Layer 3 is rendered as a separate icon (ImageView with no
    // text/desc), so accessibility sees the same string at both layers.
    // We can't distinguish layers by label match; sequence + fixed settle
    // delays decide which layer we're on.
    private val OPEN_PRODUCT_MANAGER_LABELS = listOf("Add products", "Add Product", "เพิ่มสินค้า", "Manage products", "จัดการสินค้า")
    private val ADD_PRODUCT_LABELS          = OPEN_PRODUCT_MANAGER_LABELS
    // Per-row remove control in product manager — used to clear pre-selected
    // products before we add the operator's keyword target.
    private val REMOVE_PRODUCT_LABELS   = listOf("Remove", "Delete", "ลบ", "นำออก", "Remove product", "ลบสินค้า")
    private val DONE_LABELS             = listOf("Done", "เสร็จสิ้น", "เสร็จ", "ตกลง", "OK")

    // End-live flow — tapped in TikTok's broadcast UI after server-stitched
    // loop_count playbacks finish. Two stages: initial close button, then
    // confirmation in modal. Labels include English + Thai variants seen in
    // TikTok 2026.
    private val END_LIVE_LABELS         = listOf("End", "End live", "End LIVE", "End broadcast", "Stop live", "Stop LIVE", "Stop broadcast", "ปิดไลฟ์", "จบไลฟ์", "ปิด LIVE", "สิ้นสุดไลฟ์", "หยุดไลฟ์", "หยุด")
    private val END_LIVE_CONFIRM_LABELS = listOf("End", "End LIVE", "End broadcast", "Confirm", "ยืนยัน", "ปิดไลฟ์", "ใช่", "Yes", "OK", "ตกลง")

    // Captcha markers — TikTok throws a slide-puzzle / slider-verify modal
    // periodically (login, mid-broadcast, suspicious-activity). Markers below
    // were collected from community reports; tune via dumpVisibleNodesToLog
    // when we capture a real one. Matched against text + contentDescription
    // anywhere in the active accessibility tree.
    private val CAPTCHA_MARKERS = listOf(
        "Drag the puzzle",
        "Slide to verify",
        "Slide right to complete",
        "Verify to continue",
        "ลากชิ้นส่วน",
        "ลากปริศนา",
        "เลื่อนเพื่อยืนยัน",
        "เลื่อนไปทางขวา",
        "ยืนยันตัวตน",
        "captcha",
        "puzzle",
    )

    /**
     * Start the autopilot flow.
     * @param mode Personal (no pin / Mobile Gaming), Shoppable (V1 pin + Mobile Gaming + Screen Share),
     *             or ShoppableVCam (V2/V3 Device camera + VCam feed).
     * @param followup intent to launch after Screen Share starts (typically PlayerActivity); ignored
     *                 when overlayVideoUri is non-null or in ShoppableVCam mode.
     */
    fun start(
        context: Context,
        mode: AutopilotMode = AutopilotMode.Personal,
        followup: Intent? = null,
        productKeywords: List<String> = emptyList(),
        liveTitle: String = "",
        overlayVideoUri: android.net.Uri? = null,
        overlayLoopCount: Int = 0,
    ) {
        if (state.value == AutopilotState.Running) {
            Log.w(TAG, "already running")
            return
        }
        if (!TikTokAutopilotService.isEnabled(context)) {
            fail("ยังไม่ได้เปิด Accessibility permission")
            return
        }
        val launchIntent = pickTikTokLaunchIntent(context)
        if (launchIntent == null) {
            fail("ไม่พบ TikTok app บนเครื่อง")
            return
        }
        // Overlay mode supersedes foreground-switch followup intent.
        this.followupIntent = if (overlayVideoUri != null) null else followup
        this.overlayVideoUri = overlayVideoUri
        this.overlayLoopCount = overlayLoopCount
        keywordsOverride = productKeywords.takeIf { it.isNotEmpty() }
        liveTitleOverride = liveTitle
        activeMode.value = mode
        job?.cancel()
        job = scope.launch {
            when (mode) {
                AutopilotMode.Personal      -> runFlow(context, launchIntent)
                AutopilotMode.Shoppable     -> runShoppableFlow(context, launchIntent, vcam = false)
                AutopilotMode.ShoppableVCam -> runShoppableFlow(context, launchIntent, vcam = true)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        followupIntent = null
        state.value = AutopilotState.Idle
        lastStep.value = ""
        activeMode.value = null
    }

    /**
     * Drive the end-of-broadcast UI in TikTok. Called by [MainActivity]'s
     * loop-goal observer when the server-stitched playback finishes its
     * configured cycles (api-contract §3.4 — `live_ended`). Posts back to
     * [state]/[lastStep] like any other flow so the UI Toast layer surfaces
     * progress.
     */
    fun endLive(context: Context) {
        if (!TikTokAutopilotService.isEnabled(context)) {
            fail("ยังไม่ได้เปิด Accessibility permission")
            return
        }
        val launchIntent = pickTikTokLaunchIntent(context)
        if (launchIntent == null) {
            fail("ไม่พบ TikTok app บนเครื่อง")
            return
        }
        job?.cancel()
        job = scope.launch { runEndLiveFlow(context, launchIntent) }
    }

    private suspend fun runEndLiveFlow(context: Context, launchIntent: Intent) {
        state.value = AutopilotState.Running

        val script = try {
            ScriptStore(context).getScript(
                ScriptStore.END_LIVE,
                R.raw.script_end_live_v1,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "ScriptStore.getScript(end_live) failed", t)
            return fail("โหลด script_end_live ไม่สำเร็จ")
        }
        val runner = ScriptRunner(buildScriptContext(context, launchIntent))
        when (val result = runner.execute(script)) {
            is ScriptRunner.Result.Success -> {
                state.value = AutopilotState.Done
                activeMode.value = null
            }
            is ScriptRunner.Result.Failed -> return fail(result.message)
        }
    }

    /**
     * V1 Personal flow — Mobile Gaming + Screen Share + foreground player (no pin product).
     * Used by Lite tier with stock TikTok + Smart Overlay.
     */
    private suspend fun runFlow(context: Context, launchIntent: Intent) {
        state.value = AutopilotState.Running

        // V1 Personal is executed from a JSON script. ScriptStore returns the
        // freshest copy it has — disk cache (last server fetch) first, then
        // the bundled baseline in res/raw. The fetch itself is triggered from
        // MainActivity on launch + on demand; this code path is read-only.
        val script = try {
            ScriptStore(context).getScript(
                ScriptStore.PERSONAL_LIVE,
                R.raw.script_personal_live_v1,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "ScriptStore.getScript(personal_live) failed", t)
            return fail("โหลด script_personal_live ไม่สำเร็จ")
        }
        val runner = ScriptRunner(buildScriptContext(context, launchIntent))
        when (val result = runner.execute(script)) {
            is ScriptRunner.Result.Success -> {
                state.value = AutopilotState.Done
                activeMode.value = null
            }
            is ScriptRunner.Result.Failed -> return fail(result.message)
        }
    }

    /**
     * Wraps Autopilot's private primitives in the [ScriptContext] surface
     * [ScriptRunner] expects. Created per-run so it can close over the
     * caller-supplied [launchIntent].
     */
    private fun buildScriptContext(androidContext: Context, intent: Intent): ScriptContext =
        object : ScriptContext {
            override val context: Context = androidContext
            override val launchIntent: Intent = intent

            override suspend fun setStep(label: String) {
                this@Autopilot.setStep(label)
            }

            override suspend fun delayMs(ms: Long) {
                delay(ms)
            }

            override suspend fun tapByText(
                labels: List<String>,
                allowContentDesc: Boolean,
                retries: Int,
                verifyDisappear: Boolean,
            ): Boolean = this@Autopilot.tapByText(
                labels = labels,
                retries = retries,
                allowContentDesc = allowContentDesc,
                verifyDisappear = verifyDisappear,
            )

            override suspend fun waitForAny(
                labels: List<String>,
                timeoutMs: Long,
                intervalMs: Long,
            ): Boolean = this@Autopilot.waitForAny(labels, timeoutMs, intervalMs)

            override suspend fun swipeHorizontal(
                startX: Float,
                endX: Float,
                y: Float,
                durationMs: Long,
            ): Boolean = this@Autopilot.swipeHorizontal(startX, endX, y, durationMs)

            override suspend fun ensureTikTokHome() {
                this@Autopilot.ensureTikTokHome()
            }

            override suspend fun deliverBroadcastContent() {
                this@Autopilot.deliverBroadcastContent(androidContext)
            }

            override suspend fun collapseTikTokOverlay() {
                this@Autopilot.collapseTikTokOverlay()
            }

            override suspend fun swipeToFindTab(
                tabLabel: String,
                confirmMarkers: List<String>,
                swipeX1: Float,
                swipeX2: Float,
                swipeY: Float,
                swipeDurationMs: Long,
                maxIterations: Int,
                settleDelayMs: Long,
                betweenSwipeDelayMs: Long,
            ): Boolean = this@Autopilot.swipeToFindTab(
                tabLabel, confirmMarkers,
                swipeX1, swipeX2, swipeY, swipeDurationMs,
                maxIterations, settleDelayMs, betweenSwipeDelayMs,
            )

            override suspend fun setLiveTitleIfProvided(): Boolean {
                if (liveTitleOverride.isBlank()) return true
                return this@Autopilot.setLiveTitle(liveTitleOverride)
            }

            override suspend fun removePreSelectedProducts(): Int =
                this@Autopilot.removePreSelectedProducts()

            override suspend fun searchInPickerFirstKeyword(): Boolean {
                val keywords = effectiveKeywords(androidContext)
                if (keywords.isEmpty()) return false
                return this@Autopilot.searchInPicker(keywords.first())
            }

            override suspend fun autoPinProducts(): Int {
                val keywords = effectiveKeywords(androidContext)
                if (keywords.isEmpty()) return 0
                return this@Autopilot.autoPinProducts(keywords)
            }

            override fun warn(message: String) {
                Log.w(TAG, message)
            }
        }

    /** Shared keyword resolution for picker-related ops — server override
     *  wins over the user's saved keyword list. */
    private fun effectiveKeywords(androidContext: Context): List<String> =
        keywordsOverride ?: AppPrefs(androidContext).productKeywordList

    /** Phase C helper — generalizes the "swipe a tab strip until a marker
     *  confirms the destination" pattern used twice in the old Shoppable
     *  flow (Device camera, Mobile gaming). */
    private suspend fun swipeToFindTab(
        tabLabel: String,
        confirmMarkers: List<String>,
        swipeX1: Float,
        swipeX2: Float,
        swipeY: Float,
        swipeDurationMs: Long,
        maxIterations: Int,
        settleDelayMs: Long,
        betweenSwipeDelayMs: Long,
    ): Boolean {
        fun present(): Boolean {
            val root = TikTokAutopilotService.instance?.activeRoot() ?: return false
            return findMatch(root, confirmMarkers, allowContentDesc = true) != null
        }

        var found = present()
        for (iter in 0 until maxIterations) {
            if (found) break
            val tabNode = findTabLabelIfVisible(tabLabel)
            if (tabNode != null) {
                val r = Rect(); tabNode.getBoundsInScreen(r)
                Log.i(TAG, "swipeToFindTab: gesture tap '$tabLabel' at (${r.centerX()},${r.centerY()})")
                gestureTap(tabNode)
                delay(settleDelayMs)
                found = present()
                if (found) break
            }
            Log.i(TAG, "swipeToFindTab: swipe #$iter for '$tabLabel'")
            swipeHorizontal(swipeX1, swipeX2, swipeY, swipeDurationMs)
            delay(betweenSwipeDelayMs)
            found = present()
        }
        return found
    }


    /**
     * Get the broadcast content (video) onto the screen so TikTok's screen-share
     * captures it. Picks Smart Overlay if [overlayVideoUri] was set; otherwise
     * falls back to the foreground-switch path.
     */
    private fun deliverBroadcastContent(context: Context) {
        val uri = overlayVideoUri
        overlayVideoUri = null
        overlayLoopCount = 0
        if (uri != null) {
            setStep("เปิด Smart Overlay…")
            OverlayService.startVideo(context, uri)
            return
        }
        val followup = followupIntent
        followupIntent = null
        if (followup != null) {
            setStep("เปิด PlayerActivity…")
            if (followup.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
                followup.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(followup)
        } else {
            setStep("กลับมาที่ TiktokRerun…")
            val home = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(home)
        }
    }

    /**
     * Find TikTok's floating control overlay (which appears after broadcast starts) and tap
     * its profile-icon area at the top-left of the panel — this collapses it so the panel
     * doesn't get captured by the screen-share.
     */
    private suspend fun collapseTikTokOverlay() {
        val service = TikTokAutopilotService.instance ?: return
        val windows = service.windows ?: run {
            Log.w(TAG, "no windows available — flagRetrieveInteractiveWindows off?")
            return
        }

        val tiktokWin = windows.firstOrNull { w ->
            val pkg = w.root?.packageName?.toString()
            pkg in TikTokAutopilotService.TIKTOK_PACKAGES
        }
        if (tiktokWin == null) {
            Log.i(TAG, "no TikTok overlay window — panel may already be collapsed or not visible")
            return
        }

        val root = tiktokWin.root ?: return
        val winBounds = Rect()
        tiktokWin.getBoundsInScreen(winBounds)

        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)

        val midX = winBounds.left + winBounds.width() / 2
        val midY = winBounds.top + winBounds.height() / 2
        val topLeftClickable = all
            .filter { it.isClickable }
            .firstOrNull { n ->
                val r = Rect(); n.getBoundsInScreen(r)
                r.right <= midX && r.bottom <= midY && !r.isEmpty
            }
            ?: all.firstOrNull { it.isClickable }

        if (topLeftClickable == null) {
            Log.w(TAG, "no clickable node in TikTok overlay — cannot collapse")
            return
        }

        val ok = topLeftClickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (ok) {
            Log.i(TAG, "collapsed overlay via ACTION_CLICK on '${labelOf(topLeftClickable)}'")
            return
        }
        val tapped = gestureTap(topLeftClickable)
        Log.i(TAG, "collapsed overlay via gesture on '${labelOf(topLeftClickable)}' ok=$tapped")
    }

    /**
     * Find an interactive node whose text (or contentDescription if enabled) matches any of
     * the given labels (substring, case-insensitive). Walks up to the nearest clickable parent
     * if the matched node isn't directly clickable.
     *
     * On final retry, dumps visible nodes to logcat so selectors can be tuned.
     */
    private suspend fun tapByText(
        labels: List<String>,
        retries: Int = 5,
        intervalMs: Long = 400,
        allowContentDesc: Boolean = false,
        /**
         * When true, after each "successful" tap we wait briefly and check if
         * the matched label is still on screen. If it is, the tap was a no-op
         * (e.g., ACTION_CLICK on an ancestor that wraps a Compose pointerInput
         * button) and we fall through to the next strategy. Use this on taps
         * that are expected to navigate / change screen.
         */
        verifyDisappear: Boolean = false,
        verifyDelayMs: Long = 600,
    ): Boolean {
        for (attempt in 0 until retries) {
            // Before each retry, see if TikTok has interrupted us with a
            // captcha modal. If so wait for it to clear (operator solves
            // manually or Magisk autoCaptcha module solves) before we
            // continue tapping — otherwise we'd hit the captcha's slider
            // by accident or fail silently.
            if (isCaptchaShowing()) {
                if (!waitOutCaptcha()) return false
            }
            val root = TikTokAutopilotService.instance?.activeRoot()
            if (root != null) {
                val match = findMatch(root, labels, allowContentDesc)
                if (match != null) {
                    // Strategy 1: matched node ITSELF clickable → ACTION_CLICK on it.
                    if (match.isClickable &&
                        match.performAction(AccessibilityNodeInfo.ACTION_CLICK) &&
                        tapConsumed(labels, allowContentDesc, verifyDisappear, verifyDelayMs)
                    ) {
                        Log.i(TAG, "tap '${labelOf(match)}' via ACTION_CLICK (self) ok")
                        return true
                    }
                    // Strategy 2: real touch gesture on the matched label's bounds.
                    // Modern Compose buttons (Modifier.pointerInput) only respond to
                    // genuine touch events — accessibility ACTION_CLICK on the parent
                    // is silently dropped. Gesture survives Compose + custom touch
                    // listeners. Preferred over ancestor click for that reason.
                    if (gestureTap(match) &&
                        tapConsumed(labels, allowContentDesc, verifyDisappear, verifyDelayMs)
                    ) {
                        Log.i(TAG, "tap '${labelOf(match)}' via gesture ok")
                        return true
                    }
                    // Strategy 3: ACTION_CLICK on the smallest non-screen-wide
                    // clickable ancestor. Fallback for cases where the accessibility
                    // service has been told to ignore gesture dispatch (rare, but
                    // some system dialogs / overlays do this).
                    val target = climbToClickable(match)
                    if (target != null && !isScreenWideContainer(target) &&
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK) &&
                        tapConsumed(labels, allowContentDesc, verifyDisappear, verifyDelayMs)
                    ) {
                        Log.i(TAG, "tap '${labelOf(match)}' via ACTION_CLICK (ancestor) ok")
                        return true
                    }
                    Log.w(TAG, "tap '${labelOf(match)}' — all strategies failed this attempt")
                }
                if (attempt == retries - 1) {
                    dumpVisibleNodesToLog(root, labels)
                }
            }
            delay(intervalMs)
        }
        return false
    }

    /**
     * Returns true when the tap is considered to have done its job. When
     * [verifyDisappear] is false this is always true (caller didn't ask to
     * verify). When true, we wait [verifyDelayMs] and confirm the matched
     * label is no longer on screen — if it's still visible, the tap landed
     * on something that didn't react and the caller should try another
     * strategy.
     */
    private suspend fun tapConsumed(
        labels: List<String>,
        allowContentDesc: Boolean,
        verifyDisappear: Boolean,
        verifyDelayMs: Long,
    ): Boolean {
        if (!verifyDisappear) return true
        delay(verifyDelayMs)
        val root = TikTokAutopilotService.instance?.activeRoot() ?: return true
        val stillVisible = findMatch(root, labels, allowContentDesc) != null
        if (stillVisible) {
            Log.w(TAG, "tap fired but label still visible — falling through")
            return false
        }
        return true
    }

    /**
     * Heuristic: a "clickable" ancestor whose bounds nearly cover the whole window is
     * almost certainly a layout root, not the actual button. ACTION_CLICK on it succeeds
     * silently but doesn't fire the intended button handler.
     */
    private fun isScreenWideContainer(node: AccessibilityNodeInfo): Boolean {
        val r = Rect(); node.getBoundsInScreen(r)
        return r.width() > 850 && r.height() > 1700
    }

    private fun labelOf(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString() ?: "?"

    /**
     * Shoppable VCam Live flow — + → LIVE → Device camera → business icon (Details) →
     * commerce sheet → product manager → product picker → search + tap-Add → Done × N →
     * back to Device camera → Go LIVE. Magisk VCam supplies the prerecorded video to
     * camera2 so TikTok captures it as the live stream. Native portrait 9:16, no
     * Screen Share, no foreground switch.
     */
    private suspend fun runShoppableFlow(context: Context, launchIntent: Intent, vcam: Boolean = true) {
        state.value = AutopilotState.Running

        if (vcam) {
            // V3/V2 path now lives in a JSON script (Phase C). The V1 path
            // (Mobile Gaming + Screen Share) keeps its Kotlin implementation
            // below until it gets its own script — both share early setup
            // but differ enough at the end that duplicating is simpler than
            // weaving the two paths through one script.
            val script = try {
                ScriptStore(context).getScript(
                    ScriptStore.SHOPPABLE_VCAM,
                    R.raw.script_shoppable_vcam_v1,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "ScriptStore.getScript(shoppable_vcam) failed", t)
                return fail("โหลด script_shoppable_vcam ไม่สำเร็จ")
            }
            val runner = ScriptRunner(buildScriptContext(context, launchIntent))
            when (val result = runner.execute(script)) {
                is ScriptRunner.Result.Success -> {
                    state.value = AutopilotState.Done
                    activeMode.value = null
                }
                is ScriptRunner.Result.Failed -> return fail(result.message)
            }
            return
        }

        // V1 (Mobile Gaming + Screen Share) — Kotlin until ported in Phase D.
        // 1. Launch TikTok (CLEAR_TASK → fresh start at Home)
        setStep("เปิด TikTok…")
        context.startActivity(launchIntent)
        delay(3000)
        setStep("กลับไปหน้าหลัก TikTok…")
        ensureTikTokHome()
        delay(500)

        // 2. Tap "+" (Create button on bottom nav)
        setStep("กด + (Create)")
        if (!tapByText(CREATE_BUTTON_LABELS, allowContentDesc = true, retries = 8)) {
            return fail("ไม่พบปุ่ม +")
        }
        delay(2500)

        // 3. Tap LIVE tab (horizontal scroller in camera screen)
        setStep("กด LIVE")
        if (!tapByText(LIVE_TAB_LABELS, allowContentDesc = true, retries = 8)) {
            return fail("ไม่พบ LIVE")
        }
        delay(1800)

        // 4. Switch to Device camera mode. Tab order (user-confirmed):
        //    Voice chat | Device camera | LIVE Manager | Mobile gaming
        // The bottom tab labels are NOT individually clickable views; the strip is
        // bound to a ViewPager that responds to horizontal swipes on the FORM area
        // above. To navigate: swipe the form, then detect current mode by markers.
        // "Details" (business icon) is unique to Device camera mode.
        setStep("ไปยัง Device camera (swipe tab strip + tap)")
        var foundDeviceCamera = onDeviceCameraMode()
        for (iter in 0 until 6) {
            if (foundDeviceCamera) break
            val dcVisible = findDeviceCameraTabIfVisible()
            if (dcVisible != null) {
                // Skip climbToClickable — for these tab labels the clickable ancestor is
                // a screen-wide ViewGroup, not the actual tab. gestureTap at the label's
                // own coordinates is what mirrors a finger-on-label touch.
                val r = Rect(); dcVisible.getBoundsInScreen(r)
                Log.i(TAG, "gesture tap Device camera label at center=(${r.centerX()},${r.centerY()}) bounds=$r")
                val gOk = gestureTap(dcVisible)
                Log.i(TAG, "  gestureTap returned $gOk")
                delay(1800)
                foundDeviceCamera = onDeviceCameraMode()
                if (foundDeviceCamera) break
            }
            Log.i(TAG, "swipe tab strip @y=1965 (left→right) #$iter")
            swipeHorizontal(startX = 150f, endX = 950f, y = 1965f, durationMs = 350L)
            delay(700)
            foundDeviceCamera = onDeviceCameraMode()
        }
        // Manual fallback: give user 20s to switch to Device camera by hand
        // if accessibility couldn't trigger the mode change.
        if (!foundDeviceCamera) {
            setStep("⏳ สลับไป Device camera ด้วยมือ (รอ 20s)")
            Log.w(TAG, "autopilot couldn't switch to Device camera — waiting for manual switch")
            foundDeviceCamera = waitForAny(DEVICE_CAMERA_MARKERS, timeoutMs = 20_000L)
        }
        if (!foundDeviceCamera) {
            return fail("ไม่พบ Device camera mode (Details icon ไม่ปรากฏ) — ดู dump")
        }
        delay(800)

        // 4c. (Optional) Set live title on Device camera setup if one was supplied this run.
        if (liveTitleOverride.isNotBlank()) {
            setStep("📝 ตั้ง live title")
            val titleSet = setLiveTitle(liveTitleOverride)
            if (!titleSet) {
                Log.w(TAG, "couldn't set live title — continuing without title change")
            }
            delay(800)
        }

        // 5. Tap business icon (top-left on Device camera setup, labeled "Details" in EN).
        // Opens the commerce / Add product flow (a sheet that slides up — animation takes time).
        setStep("กด business icon (Details)")
        if (!tapByText(BUSINESS_ICON_LABELS, allowContentDesc = true, retries = 8)) {
            return fail("ไม่พบ business icon (Details) — ดู dump")
        }

        // 5b. There are 3 sheet layers between Business icon and the actual
        //     product picker — each animates in separately. We must traverse
        //     them in order, never short-circuiting on label match alone:
        //
        //     Layer 2 (commerce sheet)    — has "Add products" (NO "+")
        //         tap → wait → arrives at
        //     Layer 3 (product manager)   — list of pre-selected + "+ Add products"
        //         remove pre-selected → tap "+ Add products" → wait → arrives at
        //     Layer 4 (product picker)    — list of available products to add
        //         find keyword → tap Add → returns to Layer 3 → Done → Layer 2 → Done → Device camera
        //
        //     Each transition needs ~3-5s settle delay because TikTok's
        //     bottom-sheet animation finishes before the next layer's onClick
        //     handlers are wired (we observed this in fail dumps where the
        //     button label was in the tree but tap had no effect).

        setStep("รอ commerce sheet เปิด (Layer 2)…")
        if (!waitForAny(OPEN_PRODUCT_MANAGER_LABELS, timeoutMs = 8_000L, intervalMs = 300)) {
            return fail("commerce sheet (Layer 2) ไม่เปิด — กด Business แล้วไม่เห็น 'Add products' — ดู dump")
        }
        delay(3500)  // settle: button onClick wires after animation

        // Layer 2 → 3. verifyDisappear is OFF: Layer 3 also has an "Add
        // products" button (the "+" is icon-only), so the label persists in
        // the tree after navigation and would falsely trip the verify check.
        // Trust the sequence + fixed settle delay instead.
        setStep("กด 'Add products' (Layer 2 → 3)")
        if (!tapByText(OPEN_PRODUCT_MANAGER_LABELS, allowContentDesc = true, retries = 4)) {
            return fail("Layer 2: ไม่พบ 'Add products' — ดู dump")
        }

        setStep("รอ product manager render (Layer 3)…")
        delay(5000)  // fixed wait — Layer 3 takes 3-5s per operator's observation

        // Optional clean step: remove pre-selected products so the operator's
        // keyword target is the only thing pinned (broker workflow expects
        // each broadcast to start with a clean slate).
        setStep("🧹 ลบ product ที่ค้างอยู่ (ถ้ามี)")
        val removed = removePreSelectedProducts()
        if (removed > 0) {
            Log.i(TAG, "cleaned $removed pre-selected product(s)")
            delay(1200)
        }

        // Layer 3 → 4. Same label as Layer 2, same reasoning — no verifyDisappear.
        setStep("กด 'Add products' (Layer 3 → 4)")
        if (!tapByText(ADD_PRODUCT_LABELS, allowContentDesc = true, retries = 4, intervalMs = 400)) {
            return fail("Layer 3: ไม่พบ 'Add products' — ดู dump")
        }

        setStep("รอ product picker เปิด (Layer 4)…")
        delay(5000)  // picker render + product list load

        // Auto-pin: pick operator's keyword target from the picker list.
        val prefs = AppPrefs(context)
        val keywords = keywordsOverride ?: prefs.productKeywordList
        if (keywords.isEmpty()) {
            return fail("ไม่มี Product keywords — ใส่ใน web dashboard หรือ Settings ก่อน")
        }
        Log.i(TAG, "auto-pin keywords (${if (keywordsOverride != null) "from web" else "from prefs"}): $keywords")

        // Default-load shows recent/suggested — type the keyword into
        // the picker's search input first so the target product surfaces
        // even if it's not in the initial visible list.
        setStep("🔍 ค้นหา product '${keywords.first()}'")
        val searched = searchInPicker(keywords.first())
        if (!searched) {
            Log.w(TAG, "search input not found — falling back to scroll-less match on visible list")
        }

        setStep("🛍 auto-pin ${keywords.size} product(s)")
        val pinned = autoPinProducts(keywords)
        if (pinned == 0) {
            return fail("auto-pin: ไม่พบ product ใดตรง keyword — เช็ค Settings + ดู dump")
        }
        Log.i(TAG, "auto-pinned $pinned/${keywords.size} products")
        delay(1500)

        // Layer 4 → 3 (Done in picker)
        setStep("กด Done (Layer 4 → 3)")
        if (!tapByText(DONE_LABELS, allowContentDesc = true, retries = 4)) {
            return fail("ไม่พบ Done ปิด picker")
        }
        delay(2000)

        // Layer 3 → 2 (Done in product manager)
        setStep("กด Done (Layer 3 → 2)")
        if (!tapByText(DONE_LABELS, allowContentDesc = true, retries = 4)) {
            Log.i(TAG, "no Done at Layer 3 — may have auto-collapsed")
        }
        delay(1500)

        // Layer 2 → Device camera (auto-close or explicit Done)
        // Some TikTok versions auto-close after picker Done; try one more
        // Done tap best-effort then verify we're back on Device camera setup.
        tapByText(DONE_LABELS, allowContentDesc = true, retries = 2)
        delay(1500)
        val resumed = waitForAny(DEVICE_CAMERA_MARKERS, timeoutMs = 10_000L)
        if (!resumed) {
            return fail("auto-pin หลัง Done ไม่กลับมาที่ Device camera — ดู dump")
        }
        delay(500)

        // V1 path: switch to Mobile gaming tab — broadcast mode for screen-share.
        // After pinning product on Device camera mode, the tab strip typically shows
        // Voice chat | Device camera (selected) | LIVE Manager — with Mobile gaming
        // OFF-SCREEN to the right. Swipe right→left to reveal it.
        setStep("กด Mobile gaming (swipe ขวา-ซ้าย ก่อน)")
        var foundMobileGaming = false
        for (iter in 0 until 6) {
            if (foundMobileGaming) break
            val mgVisible = findTabLabelIfVisible("Mobile gaming")
            if (mgVisible != null) {
                val r = Rect(); mgVisible.getBoundsInScreen(r)
                Log.i(TAG, "Mobile gaming tab visible at (${r.centerX()},${r.centerY()}) — tapping")
                gestureTap(mgVisible)
                delay(1800)
                foundMobileGaming = true
                break
            }
            Log.i(TAG, "swipe tab strip @y=1965 (right→left) #$iter — reveal Mobile gaming")
            swipeHorizontal(startX = 950f, endX = 150f, y = 1965f, durationMs = 350L)
            delay(700)
        }
        if (!foundMobileGaming) {
            return fail("ไม่พบ Mobile gaming แม้ swipe — ดู dump")
        }
        delay(800)

        // Re-set live title — Mobile gaming has its own title field that
        // doesn't inherit the value set at Device camera setup.
        if (liveTitleOverride.isNotBlank()) {
            setStep("📝 ตั้ง live title ซ้ำ (Mobile gaming)")
            setLiveTitle(liveTitleOverride)
            delay(800)
        }

        setStep("กด Go LIVE")
        if (!tapByText(GO_LIVE_LABELS, allowContentDesc = true, retries = 8)) {
            return fail("ไม่พบ Go LIVE")
        }
        delay(2000)

        setStep("กด Screen Share")
        if (!tapByText(SCREEN_SHARE_LABELS, allowContentDesc = true, retries = 8)) {
            return fail("ไม่พบ Screen Share")
        }
        delay(1500)
        setStep("กด Start (system dialog)")
        if (!tapByText(RECORDING_OK_LABELS)) {
            Log.w(TAG, "no recording dialog button found yet; proceeding")
        }
        delay(800)
        tapByText(RECORDING_OK_LABELS, retries = 2)

        deliverBroadcastContent(context)

        setStep("ซ่อน TikTok overlay panel…")
        delay(4000)
        collapseTikTokOverlay()

        setStep("✓ Shoppable Live พร้อม")
        state.value = AutopilotState.Done
        activeMode.value = null
    }

    /**
     * Set the LIVE title on the Device camera setup screen. Returns true on best-effort success.
     *
     * Flow: find clickable title text node (large clickable text in the upper card area, around
     * y=1400-1600 in the standard layout) → tap to open the title editor → ACTION_SET_TEXT on the
     * EditText that appears → tap Save / Done / Confirm.
     *
     * Fails gracefully if any step doesn't find expected UI — caller logs and continues without
     * changing the title.
     */
    private suspend fun setLiveTitle(newTitle: String): Boolean {
        val service = TikTokAutopilotService.instance ?: return false
        val root = service.activeRoot() ?: return false
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)

        // Title is a clickable TextView in the upper card area of Device camera setup.
        // Identify by: clickable=true, has text, bounds in upper card y-range, on-screen.
        val titleNode = all.firstOrNull { n ->
            if (!n.isClickable) return@firstOrNull false
            val t = n.text?.toString()
            if (t.isNullOrBlank()) return@firstOrNull false
            val r = Rect(); n.getBoundsInScreen(r)
            r.top in 1400..1700 && r.left in 100..1000 && r.right in 200..1080 && r.width() > 200
        }
        if (titleNode == null) {
            Log.w(TAG, "title node not found on Device camera setup")
            return false
        }
        Log.i(TAG, "open title editor for '${titleNode.text}'")
        if (!gestureTap(titleNode)) {
            titleNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        delay(1500)  // wait for editor

        // Find the EditText input in the editor.
        val rootEdit = service.activeRoot() ?: return false
        val editAll = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(rootEdit, editAll)
        val editText = editAll.firstOrNull { n ->
            n.className?.toString()?.contains("EditText", ignoreCase = true) == true && n.isEditable
        } ?: editAll.firstOrNull { n ->
            n.className?.toString()?.contains("EditText", ignoreCase = true) == true
        }
        if (editText == null) {
            Log.w(TAG, "EditText not found in title editor")
            return false
        }

        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newTitle
            )
        }
        val setOk = editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "ACTION_SET_TEXT on EditText returned $setOk")
        if (!setOk) return false
        delay(600)

        // Save / Done / Confirm — best-effort across locales.
        val saveLabels = listOf("Save", "Done", "Confirm", "OK", "บันทึก", "เสร็จ", "ตกลง", "ยืนยัน")
        val saved = tapByText(saveLabels, allowContentDesc = true, retries = 4, intervalMs = 350)
        Log.i(TAG, "title editor save tap: $saved")
        return saved
    }

    /**
     * Auto-pin products from a keyword list inside the open product picker.
     * For each keyword: substring-match (case-insensitive) against any text node, then tap
     * the "Add" button nearest in y to the matched product row. Returns count successfully pinned.
     */
    /**
     * Layer 4 picker has a "Search products" input at the top — typing the
     * keyword there filters the list so the target product comes into view
     * (default-load shows recent/suggested, not all products). Returns true
     * iff the input was found + ACTION_SET_TEXT succeeded.
     */
    private suspend fun searchInPicker(keyword: String): Boolean {
        val root = TikTokAutopilotService.instance?.activeRoot() ?: return false
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)
        // The input shows hint "Search products" / "ค้นหาสินค้า" — match by
        // text OR contentDescription on a clickable node.
        val searchInput = all.firstOrNull { n ->
            val t = n.text?.toString()?.lowercase() ?: ""
            val d = n.contentDescription?.toString()?.lowercase() ?: ""
            (t.contains("search") || t.contains("ค้นหา") ||
                d.contains("search") || d.contains("ค้นหา")) &&
                (n.isClickable || n.isEditable)
        } ?: return false

        // Tap to focus the input first — some Compose inputs only accept
        // SET_TEXT after they have keyboard focus.
        searchInput.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(600)

        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                keyword,
            )
        }
        // Re-fetch the (now-focused) input — the post-tap tree may have a
        // different node object for the EditText that actually accepts text.
        val rootAfter = TikTokAutopilotService.instance?.activeRoot()
        val typeTarget = if (rootAfter != null) {
            val afterAll = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(rootAfter, afterAll)
            afterAll.firstOrNull { n -> n.isEditable || n.isFocused } ?: searchInput
        } else searchInput
        val ok = typeTarget.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "search input typed='$keyword' ok=$ok")
        if (!ok) return false
        delay(400)

        // ACTION_SET_TEXT only fills the buffer — it doesn't trigger the
        // input's IME action (the magnifying-glass / Search key on the
        // keyboard). On Android 11+ we ask the input to fire its IME action
        // directly. Without this the filtered results never appear.
        val imeFired = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            typeTarget.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
        } else false
        Log.i(TAG, "search ime-enter ok=$imeFired (sdk=${Build.VERSION.SDK_INT})")

        // Fallback for SDK < 30: dispatch a system ENTER key event. Requires
        // the AccessibilityService to be active. Some keyboards still consume
        // ENTER as "search" when imeOptions=actionSearch, which is what
        // TikTok's picker input uses.
        if (!imeFired) {
            val svc = TikTokAutopilotService.instance
            if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Use global action to dispatch ENTER via key event injection.
                // Note: GLOBAL_ACTION ids don't include direct key codes — the
                // cleanest fallback would be a shell `input keyevent 66` but
                // that requires root. For non-root SDK<30, document the limit.
                Log.w(TAG, "ime-enter unavailable on this SDK; search may not trigger")
            }
        }
        delay(1500) // let the picker filter + re-render results
        return true
    }

    /**
     * Remove any products already on the product-manager (Layer 3) list, so
     * the operator's keyword target ends up being the only one pinned for
     * this broadcast. Best-effort: looks for Remove / Delete / ลบ clickables
     * and taps each. Returns the count removed.
     *
     * Each tap can trigger a confirmation modal — we auto-confirm with OK /
     * ตกลง if one appears. Stops after MAX_REMOVALS in case the matcher
     * misfires and we'd otherwise loop forever.
     */
    private suspend fun removePreSelectedProducts(): Int {
        val MAX_REMOVALS = 20
        var removed = 0
        while (removed < MAX_REMOVALS) {
            val root = TikTokAutopilotService.instance?.activeRoot() ?: break
            val all = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(root, all)
            val removeBtn = all.firstOrNull { n ->
                if (!n.isClickable) return@firstOrNull false
                val t = n.text?.toString()?.trim() ?: ""
                val d = n.contentDescription?.toString()?.trim() ?: ""
                REMOVE_PRODUCT_LABELS.any { label ->
                    t.equals(label, ignoreCase = true) || d.equals(label, ignoreCase = true) ||
                        // Some TalkBack hints use ", button" / ", remove" suffixes
                        t.contains(label, ignoreCase = true) || d.contains(label, ignoreCase = true)
                }
            } ?: break  // no more remove buttons in the list

            val ok = removeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                gestureTap(removeBtn)
            if (!ok) {
                Log.w(TAG, "remove tap failed — bailing after $removed removals")
                break
            }
            delay(800)
            // Best-effort confirm dialog ("Remove product?" → OK).
            tapByText(listOf("OK", "ตกลง", "Remove", "ลบ", "Confirm", "ยืนยัน"),
                allowContentDesc = true, retries = 2, intervalMs = 300)
            delay(600)
            removed++
        }
        return removed
    }

    private suspend fun autoPinProducts(keywords: List<String>): Int {
        var pinned = 0
        for (keyword in keywords) {
            val needle = keyword.lowercase().trim()
            if (needle.isEmpty()) continue

            // KEY: TikTok's picker renders product names through Compose
            // custom drawing — text shows on screen but accessibility tree
            // has text=null for those rows. We CANNOT match products by
            // keyword in the tree. The earlier searchInPicker() step
            // already filtered the picker to the keyword's results; here
            // we just locate the topmost "Add" button (= most-relevant
            // filtered product) and tap it.
            //
            // To avoid false matches we filter out:
            //  - the search input row (y < 400)
            //  - the "Done" footer button at the bottom
            //  - oversized containers (Add icon button is ~250×80)
            var addLabels: List<AccessibilityNodeInfo> = emptyList()
            val pollDeadline = System.currentTimeMillis() + 6_000L
            var attempts = 0
            while (System.currentTimeMillis() < pollDeadline) {
                attempts++
                val root = TikTokAutopilotService.instance?.activeRoot()
                if (root != null) {
                    val all = mutableListOf<AccessibilityNodeInfo>()
                    collectNodes(root, all)
                    addLabels = all.filter { n ->
                        val t = n.text?.toString()?.trim() ?: ""
                        val d = n.contentDescription?.toString()?.trim() ?: ""
                        // Match "Add" / "เพิ่ม" — exclude "Done" (Done,button)
                        val matchesAdd = listOf("Add", "เพิ่ม").any { kw ->
                            (t.contains(kw, ignoreCase = true) || d.contains(kw, ignoreCase = true)) &&
                                !t.contains("Done", ignoreCase = true) &&
                                !d.contains("Done", ignoreCase = true)
                        }
                        if (!matchesAdd) return@filter false
                        val r = Rect(); n.getBoundsInScreen(r)
                        !r.isEmpty &&
                            r.top > 400 &&             // skip search input area
                            r.width() < 400 &&         // small button only
                            r.height() < 200
                    }
                    if (addLabels.isNotEmpty()) {
                        Log.i(TAG, "auto-pin: found ${addLabels.size} Add button(s) on attempt $attempts (${all.size} nodes visible)")
                        break
                    }
                }
                delay(500)
            }
            if (addLabels.isEmpty()) {
                Log.w(TAG, "auto-pin: no Add buttons in picker after $attempts polls — search may have returned no matches")
                val root = TikTokAutopilotService.instance?.activeRoot()
                if (root != null) dumpVisibleNodesToLog(root, listOf(keyword, "Add"))
                continue
            }

            // De-duplicate: occasionally two Add nodes overlap (icon + button
            // semantics both expose the label). Keep the one with the lower
            // y per unique centerY bucket.
            val unique = addLabels
                .sortedBy { Rect().also { r -> it.getBoundsInScreen(r) }.top }
                .distinctBy {
                    val r = Rect(); it.getBoundsInScreen(r); r.centerY() / 50  // 50px buckets
                }
            val topAdd = unique.first()
            val r = Rect(); topAdd.getBoundsInScreen(r)
            Log.i(TAG, "auto-pin: tap top Add at $r (filtered '${keyword}' → ${unique.size} unique row(s))")
            val gOk = gestureTap(topAdd)
            if (!gOk) {
                val ancestor = climbToClickable(topAdd)
                if (ancestor != null && !isScreenWideContainer(ancestor)) {
                    ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else if (topAdd.isClickable) {
                    topAdd.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            pinned++
            delay(900)
        }
        return pinned
    }

    /** Poll for any of the labels to appear in the active window. */
    private suspend fun waitForAny(labels: List<String>, timeoutMs: Long, intervalMs: Long = 600): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = TikTokAutopilotService.instance?.activeRoot()
            if (root != null && findMatch(root, labels, allowContentDesc = true) != null) return true
            delay(intervalMs)
        }
        return false
    }

    /**
     * Returns true iff a TikTok captcha modal is detectable in the current
     * accessibility tree. Cheap — one snapshot, substring match across all
     * text + contentDescription against [CAPTCHA_MARKERS].
     */
    private fun isCaptchaShowing(): Boolean {
        val root = TikTokAutopilotService.instance?.activeRoot() ?: return false
        return findMatch(root, CAPTCHA_MARKERS, allowContentDesc = true) != null
    }

    /**
     * If a captcha is currently visible, pause the flow until it clears
     * (operator solves manually, or a future autoCaptcha module solves it).
     * Returns true when the captcha cleared within [timeoutMs], false if we
     * gave up. Caller should fail the run on timeout.
     *
     * Side-effects: flips [captchaShowing] state so UI can render an alert;
     * dumps visible nodes once on first detection so we can tune
     * [CAPTCHA_MARKERS] and discover the real resource ids.
     */
    private suspend fun waitOutCaptcha(timeoutMs: Long = 5 * 60_000L): Boolean {
        if (!isCaptchaShowing()) return true
        captchaShowing.value = true
        val savedStep = lastStep.value
        setStep("⚠ TikTok ขึ้น captcha — กำลังรอ solve")
        Log.w(TAG, "captcha detected — pausing autopilot (was on step: '$savedStep')")
        // One-shot dump to logcat so we can refine markers later.
        TikTokAutopilotService.instance?.activeRoot()?.let { root ->
            dumpVisibleNodesToLog(root, CAPTCHA_MARKERS)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(800)
            if (!isCaptchaShowing()) {
                captchaShowing.value = false
                Log.i(TAG, "captcha cleared — resuming '$savedStep'")
                setStep(savedStep)
                // Brief settle before the next tap so the post-captcha UI
                // has time to render whatever TikTok was about to show.
                delay(1000)
                return true
            }
        }
        captchaShowing.value = false
        Log.w(TAG, "captcha did not clear within ${timeoutMs/1000}s — giving up")
        return false
    }

    /**
     * Force TikTok to land on the Home (For You) tab.
     *
     * The check "bottom nav has Home + Profile labels" is too loose — those tabs are visible
     * from any top-level page. So we (a) back-press to dismiss modals/sub-pages, then
     * (b) explicitly tap the Home tab to commit to it.
     */
    private suspend fun ensureTikTokHome() {
        val service = TikTokAutopilotService.instance ?: return
        // Step 1: back-press until bottom nav appears (i.e., we're on a top-level tab)
        for (attempt in 0 until 5) {
            val root = service.activeRoot()
            if (root != null && hasBottomNav(root)) {
                Log.i(TAG, "bottom nav visible on attempt $attempt")
                break
            }
            Log.i(TAG, "back-press to dismiss sub-page (attempt $attempt)")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(600)
        }
        // Step 2: tap Home tab — idempotent if already on Home
        if (tapByText(HOME_TAB_LABELS, allowContentDesc = true, retries = 3)) {
            Log.i(TAG, "tapped Home tab")
            delay(800)
        } else {
            Log.w(TAG, "couldn't find Home tab — proceeding best-effort")
        }
    }

    private fun hasBottomNav(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString()
        if (pkg !in TikTokAutopilotService.TIKTOK_PACKAGES) return false
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)
        val hasHome = all.any { matchesAny(it, HOME_TAB_LABELS, allowDesc = true) }
        val hasProfile = all.any { matchesAny(it, PROFILE_TAB_LABELS, allowDesc = true) }
        return hasHome && hasProfile
    }

    private fun matchesAny(n: AccessibilityNodeInfo, labels: List<String>, allowDesc: Boolean): Boolean {
        val t = n.text?.toString()
        val d = if (allowDesc) n.contentDescription?.toString() else null
        return labels.any { label ->
            val needle = label.lowercase()
            (t != null && t.lowercase().contains(needle)) ||
            (d != null && d.lowercase().contains(needle))
        }
    }

    /** Find the "Device camera" tab label if it is currently on-screen in the tab strip. */
    private fun findDeviceCameraTabIfVisible(): AccessibilityNodeInfo? =
        findTabLabelIfVisible("Device camera")

    /** Generic: find a mode-tab label by exact text/desc if it's currently on-screen. */
    private fun findTabLabelIfVisible(label: String): AccessibilityNodeInfo? {
        val root = TikTokAutopilotService.instance?.activeRoot() ?: return null
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)
        return all.firstOrNull { n ->
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            if (t != label && d != label) return@firstOrNull false
            val r = Rect(); n.getBoundsInScreen(r)
            r.top in 1900..2050 && r.left in 0..1080 && r.right > r.left
        }
    }

    /**
     * Find the LIVE setup ViewPager (containing Voice chat / Device camera / LIVE Manager / Mobile gaming)
     * and perform a semantic scroll. Returns true if a scrollable node was found AND the action succeeded.
     */
    private fun scrollPagerBackward(): Boolean = performPagerScroll(forward = false)
    private fun scrollPagerForward(): Boolean = performPagerScroll(forward = true)

    private fun performPagerScroll(forward: Boolean): Boolean {
        val root = TikTokAutopilotService.instance?.activeRoot() ?: return false
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)
        // Prefer the explicit viewpager id we've seen in TikTok dumps; fall back to any scrollable.
        val pager = all.firstOrNull { n ->
            n.viewIdResourceName?.endsWith(":id/viewpager") == true && n.isScrollable
        } ?: all.firstOrNull { n ->
            n.isScrollable && n.className?.toString()?.contains("Pager", true) == true
        } ?: all.firstOrNull { it.isScrollable }
        if (pager == null) {
            Log.w(TAG, "no scrollable pager node found")
            return false
        }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val ok = pager.performAction(action)
        Log.i(TAG, "performPagerScroll forward=$forward on '${pager.viewIdResourceName ?: pager.className}' ok=$ok")
        return ok
    }

    /** Detect whether the current LIVE-setup screen is on Device camera mode. */
    private fun onDeviceCameraMode(): Boolean {
        val root = TikTokAutopilotService.instance?.activeRoot() ?: return false
        // Camera-specific action icons (Beautify / Effects / Flip / Interact) appear ONLY
        // on Device camera mode. "Details" (which was the previous marker) appears on
        // Mobile gaming too — it labels the product card — so cannot be used.
        return findMatch(root, DEVICE_CAMERA_MARKERS, allowContentDesc = true) != null
    }

    /**
     * Tap a tab in a horizontal tab strip by label, restricted to a vertical y range.
     * Prevents matching labels elsewhere on the screen (e.g. a sub-option inside another mode).
     */
    private suspend fun tapTabInStrip(
        labels: List<String>,
        tabYMin: Int,
        tabYMax: Int,
    ): Boolean {
        val root = TikTokAutopilotService.instance?.activeRoot() ?: return false
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)
        // Pass 1: exact match within y-band
        for (label in labels) {
            val needle = label.lowercase().trim()
            val hit = all.firstOrNull { n ->
                val t = n.text?.toString()?.lowercase()?.trim()
                val d = n.contentDescription?.toString()?.lowercase()?.trim()
                val matches = t == needle || d == needle
                if (!matches) return@firstOrNull false
                val r = Rect(); n.getBoundsInScreen(r)
                r.top in tabYMin..tabYMax
            }
            if (hit != null) {
                val target = climbToClickable(hit) ?: hit
                val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) {
                    Log.i(TAG, "tap tab '${labelOf(hit)}' (in y=$tabYMin..$tabYMax) via ACTION_CLICK ok")
                    return true
                }
                if (gestureTap(hit)) {
                    Log.i(TAG, "tap tab '${labelOf(hit)}' (in y=$tabYMin..$tabYMax) via gesture ok")
                    return true
                }
            }
        }
        return false
    }

    /** Dispatch a horizontal swipe at the given y. Used to reveal off-screen scroller items. */
    private suspend fun swipeHorizontal(startX: Float, endX: Float, y: Float, durationMs: Long = 500L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "swipeHorizontal skipped — Android < 7")
            return false
        }
        val service = TikTokAutopilotService.instance
        if (service == null) {
            Log.w(TAG, "swipeHorizontal skipped — service null")
            return false
        }
        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    Log.i(TAG, "swipe completed ($startX→$endX y=$y, ${durationMs}ms)")
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(g: GestureDescription) {
                    Log.w(TAG, "swipe cancelled ($startX→$endX y=$y)")
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = service.dispatchGesture(gesture, cb, null)
            Log.i(TAG, "swipe dispatched=$dispatched ($startX→$endX y=$y, ${durationMs}ms)")
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    /** Dispatch a single tap gesture at the node's on-screen center. Requires Android 7+. */
    private suspend fun gestureTap(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val service = TikTokAutopilotService.instance ?: return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription) { if (cont.isActive) cont.resume(false) }
            }
            val dispatched = service.dispatchGesture(gesture, cb, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    /**
     * Dump every interactive-looking node from TikTok's window if it is visible
     * (even when TikTok is not the topmost app), falling back to the active window.
     * Allows debugging TikTok UI while our own app is in foreground.
     */
    fun dumpVisibleNodes(): String {
        val service = TikTokAutopilotService.instance ?: return "no active root (Accessibility service off?)"

        val tiktokWindow = service.windows?.firstOrNull { w ->
            w.root?.packageName?.toString() in TikTokAutopilotService.TIKTOK_PACKAGES
        }
        val root = tiktokWindow?.root ?: service.activeRoot()
            ?: return "no active root"
        val sourceLabel = if (tiktokWindow != null) "TikTok window" else "active window (${root.packageName})"

        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)
        return buildString {
            appendLine("=== $sourceLabel: ${all.size} nodes ===")
            all.forEach { n ->
                val text = n.text?.toString()
                val desc = n.contentDescription?.toString()
                val id = n.viewIdResourceName
                if (text != null || desc != null || id != null || n.isClickable) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    appendLine("text='$text' desc='$desc' id='$id' clickable=${n.isClickable} class=${n.className} bounds=$r")
                }
            }
        }
    }

    private fun dumpVisibleNodesToLog(root: AccessibilityNodeInfo, searched: List<String>) {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)
        Log.w(TAG, "── FAILED to match any of: $searched ──")
        Log.w(TAG, "── Visible nodes (${all.size}) — logcat: first 80 only, full dump saved to file ──")

        val sb = StringBuilder().apply {
            appendLine("FAILED to match: $searched")
            appendLine("Total nodes: ${all.size}")
            appendLine()
        }
        all.forEachIndexed { i, n ->
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            val id = n.viewIdResourceName
            val hasContent = text != null || desc != null || id != null || n.isClickable
            if (hasContent) {
                val r = Rect(); n.getBoundsInScreen(r)
                val line = "text='$text' desc='$desc' id='$id' clickable=${n.isClickable} bounds=$r"
                sb.appendLine(line)
                if (i < 80) Log.w(TAG, "  $line")
            }
        }

        // Always save full dump to a file so the user can grep through all 137+ nodes.
        try {
            val service = TikTokAutopilotService.instance
            val dir = service?.externalCacheDir ?: service?.cacheDir
            if (dir != null) {
                val file = java.io.File(dir, "autopilot_fail_${System.currentTimeMillis()}.txt")
                file.writeText(sb.toString())
                Log.w(TAG, "── full dump saved: ${file.absolutePath} ──")
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to write dump file: ${e.message}")
        }
    }

    private fun findMatch(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        allowContentDesc: Boolean,
    ): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)
        // Pass 1 — exact (trimmed, case-insensitive). Prevents "Create" matching "Create now",
        // "LIVE" matching "LIVE preview", etc.
        for (label in labels) {
            val needle = label.lowercase().trim()
            val hit = all.firstOrNull { n ->
                val t = n.text?.toString()?.lowercase()?.trim()
                val d = if (allowContentDesc) n.contentDescription?.toString()?.lowercase()?.trim() else null
                t == needle || d == needle
            }
            if (hit != null) return hit
        }
        // Pass 2 — substring fallback for tabs/labels embedded in longer text (Thai descs etc.)
        for (label in labels) {
            val needle = label.lowercase()
            val hit = all.firstOrNull { n ->
                val t = n.text?.toString()?.lowercase()
                val d = if (allowContentDesc) n.contentDescription?.toString()?.lowercase() else null
                (t != null && t.contains(needle)) || (d != null && d.contains(needle))
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodes(it, out) }
        }
    }

    private fun climbToClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(5) {
            if (cur == null) return null
            if (cur!!.isClickable) return cur
            cur = cur!!.parent
        }
        return null
    }

    private fun pickTikTokLaunchIntent(context: Context): Intent? {
        val pm = context.packageManager
        val base = TikTokAutopilotService.TIKTOK_PACKAGES
            .firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) } ?: return null
        // CLEAR_TASK forces TikTok to drop its previous navigation stack and re-open at the
        // launcher activity (For You / Home). Without this, TikTok resumes whatever page it
        // was last on — e.g., Creator Centre dashboard — which has no Home/Profile bottom nav
        // and breaks all subsequent selectors.
        return base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    private fun setStep(step: String) {
        lastStep.value = step
        Log.i(TAG, "step: $step")
    }

    private fun fail(reason: String) {
        Log.w(TAG, "fail: $reason")
        state.value = AutopilotState.Failed
        lastStep.value = "✗ $reason"
        activeMode.value = null
    }
}
