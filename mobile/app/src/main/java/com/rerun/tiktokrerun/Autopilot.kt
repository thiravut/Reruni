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
 * Coordinates the "Open TikTok → Go Live → Mobile Gaming → Screen Share" tap sequence.
 *
 * Run on the main thread (UI selectors must be queried from the AccessibilityService).
 * Each step has a timeout; on miss, the whole flow fails fast so the user can correct manually.
 */
object Autopilot {

    private const val TAG = "Autopilot"

    val state = MutableStateFlow(AutopilotState.Idle)
    val lastStep = MutableStateFlow("")
    val activeMode = MutableStateFlow<AutopilotMode?>(null)

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
    private val ADD_PRODUCT_LABELS      = listOf("+ Add product", "Add product", "+ Add", "เพิ่มสินค้า", "Add Product")
    private val DONE_LABELS             = listOf("Done", "เสร็จสิ้น", "เสร็จ", "ตกลง", "OK")

    /**
     * Start the autopilot flow.
     * @param mode Personal (no pin) or Shoppable (Creator Centre with product pin).
     * @param followup intent to launch after broadcast is set up (typically PlayerActivity with
     *                 the video that should be captured by TikTok's screen-share).
     */
    fun start(
        context: Context,
        mode: AutopilotMode = AutopilotMode.Personal,
        followup: Intent? = null,
        productKeywords: List<String> = emptyList(),
        liveTitle: String = "",
        overlayVideoUri: android.net.Uri? = null,
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

    private suspend fun runFlow(context: Context, launchIntent: Intent) {
        state.value = AutopilotState.Running

        // 1. Launch TikTok (CLEAR_TASK already set in pickTikTokLaunchIntent → fresh start at Home)
        setStep("เปิด TikTok…")
        context.startActivity(launchIntent)
        delay(3000)  // wait for TikTok to load to For You

        // 1.5 Safety net: confirm we have bottom nav + tap Home tab (idempotent)
        setStep("กลับไปหน้าหลัก TikTok…")
        ensureTikTokHome()
        delay(500)

        // 2. Tap "+" (create button)
        setStep("กด + (Create)")
        if (!tapByText(CREATE_BUTTON_LABELS, allowContentDesc = true, retries = 8)) {
            return fail("ไม่พบปุ่ม + บน TikTok — UI อาจเปลี่ยน")
        }
        delay(2500)  // camera screen has heavier load + animation

        // 3. Tap "LIVE" tab in the create menu (icon+label horizontal scroller)
        setStep("กด LIVE")
        if (!tapByText(LIVE_TAB_LABELS, allowContentDesc = true, retries = 8)) {
            return fail("ไม่พบแท็บ LIVE — ดู logcat tag 'Autopilot' สำหรับ node dump")
        }
        delay(1800)

        // 4. Tap "Mobile Gaming" tab
        setStep("กด Mobile Gaming")
        if (!tapByText(MOBILE_GAMING_LABELS, allowContentDesc = true, retries = 8)) {
            return fail("ไม่พบแท็บ Mobile Gaming — บัญชีอาจไม่มีสิทธิ์ Gaming category")
        }
        delay(1800)

        // 5. Tap "Go LIVE" — opens the broadcast-mode chooser where Screen Share lives
        setStep("กด Go LIVE")
        if (!tapByText(GO_LIVE_LABELS, allowContentDesc = true, retries = 8)) {
            return fail("ไม่พบปุ่ม Go LIVE — UI อาจมีหน้า setup เพิ่มเติม (title/game) ก่อน")
        }
        delay(2000)  // mode chooser needs animation time

        // 6. Tap "Screen Share"
        setStep("กด Screen Share")
        if (!tapByText(SCREEN_SHARE_LABELS, allowContentDesc = true, retries = 8)) {
            return fail("ไม่พบ Screen Share — UI อาจเปลี่ยน หรือบัญชีไม่มีสิทธิ์")
        }
        delay(1500)

        // 7. System "Start recording?" confirmation dialog (MediaProjection)
        setStep("กด Start (system dialog)")
        if (!tapByText(RECORDING_OK_LABELS)) {
            // Not necessarily a failure — TikTok may show its own confirm first
            Log.w(TAG, "no recording dialog button found yet; proceeding")
        }
        delay(800)

        // 8. Final TikTok confirmation (best-effort)
        tapByText(RECORDING_OK_LABELS, retries = 2)

        // 9. Place broadcast content on top of TikTok during the countdown.
        //    Smart Overlay path: SAW overlay (TikTok stays foreground, no flash).
        //    Legacy path: foreground-switch to PlayerActivity / MainActivity.
        deliverBroadcastContent(context)

        // 10. Wait for the broadcast countdown to end + TikTok's floating control panel
        // to appear, then collapse it (tap profile icon at top-left) so the panel
        // doesn't show in the broadcast.
        setStep("ซ่อน TikTok overlay panel…")
        delay(4000)  // countdown ~3s + panel appearance buffer
        collapseTikTokOverlay()

        setStep("✓ พร้อม Live")
        state.value = AutopilotState.Done
        activeMode.value = null
    }

    /**
     * Get the broadcast content (video) onto the screen so TikTok's screen-share
     * captures it. Picks Smart Overlay if [overlayVideoUri] was set; otherwise
     * falls back to the legacy foreground-switch path.
     */
    private fun deliverBroadcastContent(context: Context) {
        val uri = overlayVideoUri
        overlayVideoUri = null
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
        Log.i(TAG, "TikTok overlay bounds: $winBounds")

        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)

        // Dump for selector tuning when this step needs iteration
        Log.i(TAG, "── TikTok overlay (${all.size} nodes) ──")
        all.forEach { n ->
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            val id = n.viewIdResourceName
            if (text != null || desc != null || id != null || n.isClickable) {
                val r = Rect(); n.getBoundsInScreen(r)
                Log.i(TAG, "  text='$text' desc='$desc' id='$id' clickable=${n.isClickable} bounds=$r")
            }
        }
        Log.i(TAG, "── end overlay dump ──")

        // Strategy: profile icon is rendered in the top-left quadrant of the panel.
        // Pick the clickable node whose bounds fully sit in the top-left quadrant.
        val midX = winBounds.left + winBounds.width() / 2
        val midY = winBounds.top + winBounds.height() / 2
        val topLeftClickable = all
            .filter { it.isClickable }
            .firstOrNull { n ->
                val r = Rect(); n.getBoundsInScreen(r)
                r.right <= midX && r.bottom <= midY && !r.isEmpty
            }
            ?: all.firstOrNull { it.isClickable }  // any-clickable fallback

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
     * Shoppable Live flow — Profile → TikTok Shop Creator Centre → Create now → LIVE →
     * Start your shoppable LIVE → +Add product (user picks) → Done×2 → Mobile gaming → Go LIVE
     * → Screen Share → system dialog → followup intent → collapse overlay.
     *
     * The Add-product step is semi-manual: Autopilot opens the picker, then polls for
     * the post-pin "Mobile gaming" tab to reappear (= user finished + tapped Done×2).
     */
    private suspend fun runShoppableFlow(context: Context, launchIntent: Intent, vcam: Boolean) {
        state.value = AutopilotState.Running

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
        delay(2800)

        // 5b. Commerce sheet open. Detect which case we're in:
        //     Case A — has products: sheet shows product list + "+ Add products" (outlined) + "Done" (filled red).
        //              We just tap Done to confirm and close.
        //     Case B — empty: sheet shows "No products yet" + "+ Add products" (only button).
        //              We tap "+ Add products" → user picks in picker → taps Done×2 → returns.
        // Differentiator: presence of "Done" button at this stage.
        val rootAfterBusiness = TikTokAutopilotService.instance?.activeRoot()
        val hasDone = rootAfterBusiness != null &&
            findMatch(rootAfterBusiness, DONE_LABELS, allowContentDesc = true) != null

        if (hasDone) {
            // Case A: products already selected, just confirm.
            setStep("กด Done (มี product อยู่แล้ว)")
            if (!tapByText(DONE_LABELS, allowContentDesc = true, retries = 4)) {
                return fail("Case A: ไม่พบ Done button — ดู dump")
            }
            delay(1500)
        } else {
            // Case B: empty list. Tap + Add products → AUTO-PIN by keyword → Done × 2.
            setStep("กด + Add products (ยังไม่มี product)")
            var tappedAddProduct = false
            for (round in 0 until 3) {
                // verifyDisappear: the "+ Add products" label is a Compose button —
                // a synthetic ACTION_CLICK on its wrapper returns true but the button
                // doesn't react. Verify the label is gone after each tap, otherwise
                // fall through to the next strategy (gesture).
                if (tapByText(
                        ADD_PRODUCT_LABELS,
                        allowContentDesc = true,
                        retries = 3,
                        intervalMs = 400,
                        verifyDisappear = true,
                    )
                ) {
                    tappedAddProduct = true
                    delay(2500)  // wait for picker to fully open
                    break
                }
                delay(800)
            }
            if (!tappedAddProduct) {
                return fail("Case B: ไม่พบ + Add products button — ดู dump")
            }

            // Auto-pin products. Prefer server-sent keywords (per-run); fall back to AppPrefs.
            val prefs = AppPrefs(context)
            val keywords = keywordsOverride ?: prefs.productKeywordList
            if (keywords.isEmpty()) {
                return fail("ไม่มี Product keywords — ใส่ใน web dashboard หรือ Settings ก่อน")
            }
            Log.i(TAG, "auto-pin keywords (${if (keywordsOverride != null) "from web" else "from prefs"}): $keywords")

            setStep("🛍 auto-pin ${keywords.size} product(s)")
            val pinned = autoPinProducts(keywords)
            if (pinned == 0) {
                return fail("auto-pin: ไม่พบ product ใดตรง keyword — เช็ค Settings + ดู dump")
            }
            Log.i(TAG, "auto-pinned $pinned/${keywords.size} products")
            delay(1200)

            // Close picker → back to commerce sheet (which now has Done button).
            setStep("กด Done (ปิด picker)")
            if (!tapByText(DONE_LABELS, allowContentDesc = true, retries = 4)) {
                return fail("ไม่พบ Done ปิด picker")
            }
            delay(1500)

            // Close commerce sheet → back to Device camera setup.
            setStep("กด Done (ปิด commerce sheet)")
            if (!tapByText(DONE_LABELS, allowContentDesc = true, retries = 4)) {
                // Optional — some flows have only one Done step. Verify by waiting for markers.
                Log.i(TAG, "no 2nd Done found — checking if already returned to Device camera")
            }
            delay(1000)

            // Verify return to Device camera setup.
            val resumed = waitForAny(DEVICE_CAMERA_MARKERS, timeoutMs = 10_000L)
            if (!resumed) {
                return fail("auto-pin หลัง Done ไม่กลับมาที่ Device camera — ดู dump")
            }
            delay(500)
        }

        if (vcam) {
            // V3 path: stay in Device camera → Go LIVE directly. VCam (Magisk GhostCam
            // or equivalent) is already feeding the prerecorded video into camera2, so
            // TikTok captures that as the live stream. No Screen Share, no overlay,
            // no foreground switch.
            setStep("กด Go LIVE (Device camera, V3)")
            if (!tapByText(GO_LIVE_LABELS, allowContentDesc = true, retries = 8)) {
                return fail("ไม่พบ Go LIVE (Device camera)")
            }
            delay(2000)
            // Some device-camera Go LIVE flows show a recording / mic confirmation
            // dialog — best-effort tap. Doesn't fail if absent.
            tapByText(RECORDING_OK_LABELS, retries = 2)
            delay(1500)
            setStep("✓ V3 Shoppable Live พร้อม (VCam feeding camera)")
            state.value = AutopilotState.Done
            activeMode.value = null
            return
        }

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
                val gOk = gestureTap(mgVisible)
                Log.i(TAG, "  gestureTap returned $gOk")
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

        // 8. Go LIVE
        setStep("กด Go LIVE")
        if (!tapByText(GO_LIVE_LABELS, allowContentDesc = true, retries = 8)) {
            return fail("ไม่พบ Go LIVE")
        }
        delay(2000)

        // 11. Screen Share + system confirm — same as personal flow
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

        // 12. Place broadcast content (overlay or foreground player) during countdown
        deliverBroadcastContent(context)

        // 13. Collapse TikTok floating panel
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
    private suspend fun autoPinProducts(keywords: List<String>): Int {
        var pinned = 0
        for (keyword in keywords) {
            val needle = keyword.lowercase().trim()
            if (needle.isEmpty()) continue

            val root = TikTokAutopilotService.instance?.activeRoot() ?: continue
            val all = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(root, all)

            // Find product name node containing the keyword (substring, case-insensitive).
            val productNode = all.firstOrNull { n ->
                val t = n.text?.toString()?.lowercase()
                t != null && t.contains(needle) && t.length > 2
            }
            if (productNode == null) {
                Log.w(TAG, "auto-pin: no product node matching '$keyword'")
                continue
            }
            val pRect = Rect(); productNode.getBoundsInScreen(pRect)

            // Find clickable "Add" button in the same row (centerY within ±100px) to the right.
            val addBtn = all.firstOrNull { n ->
                if (!n.isClickable) return@firstOrNull false
                val t = n.text?.toString()?.trim()?.equals("Add", ignoreCase = true) == true
                val d = n.contentDescription?.toString()?.trim()?.equals("Add", ignoreCase = true) == true
                if (!t && !d) return@firstOrNull false
                val r = Rect(); n.getBoundsInScreen(r)
                kotlin.math.abs(r.centerY() - pRect.centerY()) < 110 && r.left > pRect.left
            }
            if (addBtn == null) {
                Log.w(TAG, "auto-pin: no Add button near '${productNode.text}'")
                continue
            }
            Log.i(TAG, "auto-pin: tap Add for '${productNode.text?.toString()?.take(50)}'")
            val gOk = gestureTap(addBtn)
            if (!gOk) addBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
