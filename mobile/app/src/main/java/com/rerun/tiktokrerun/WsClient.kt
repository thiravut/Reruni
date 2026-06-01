package com.rerun.tiktokrerun

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WebSocket client speaking the envelope protocol defined in api-contract §3.
 *
 * Connection lifecycle:
 *  - On open: send `pair` (first time, using [AppPrefs.pairToken]) or `hello`
 *    (subsequent, using [AppPrefs.deviceToken]).
 *  - On `paired` envelope: persist `device_id` + `device_token`; clear pair
 *    token so a stale token can't accidentally re-pair the device.
 *  - On `welcome` envelope: confirms hello succeeded; just log.
 *  - On `start_live` (and other server-to-device commands): unwrap payload,
 *    fan out to [WsBus] for the UI layer to consume.
 *
 * Reconnect uses exponential backoff (1s → 30s cap). The first reconnect
 * after a successful pair reuses [AppPrefs.deviceToken] via `hello`.
 */
object WsClient {

    private const val TAG = "WsClient"

    private val client by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private var socket: WebSocket? = null
    private var serverOrigin: String = ""
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var wantConnected: Boolean = false
    private var managedPrefs: AppPrefs? = null
    // Application context kept around so CapsCollector can read package
    // installs + system settings without an Activity. Set in startManaged().
    private var appContext: android.content.Context? = null
    // Snapshot of the credential triple we connected with — compared against
    // fresh prefs in startManaged() so repeated activity-lifecycle invocations
    // don't churn the WebSocket. Without snapshotting, both sides of the
    // comparison would read live SharedPreferences and always match.
    private var lastServerUrl: String = ""
    private var lastPairToken: String = ""
    private var lastDeviceToken: String = ""
    private var reconnectAttempt: Int = 0
    private var reconnectJob: Job? = null

    val origin: String get() = serverOrigin

    fun startManaged(context: android.content.Context, prefs: AppPrefs) {
        appContext = context.applicationContext
        // Idempotent: if we're already managing the same effective credentials,
        // leave the existing connection alone. ConnectionService.start() can be
        // invoked from many UI surfaces (MainActivity.onStart on every resume,
        // Settings on Save+Connect, etc.) — without this guard, every activity
        // restart would close + reopen the WS and the status chip would flicker.
        val curUrl = prefs.serverUrl
        val curPair = prefs.pairToken
        val curDevice = prefs.deviceToken
        val sameConfig = wantConnected &&
            socket != null &&
            curUrl == lastServerUrl &&
            curPair == lastPairToken &&
            curDevice == lastDeviceToken
        if (sameConfig) {
            Log.i(TAG, "startManaged: same config + socket alive — skipping reconnect")
            return
        }
        wantConnected = true
        managedPrefs = prefs
        lastServerUrl = curUrl
        lastPairToken = curPair
        lastDeviceToken = curDevice
        reconnectAttempt = 0
        connectNow(prefs)
    }

    fun stopManaged() {
        wantConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "user disconnect")
        socket = null
        // Forget snapshot so the next startManaged actually connects (otherwise
        // idempotency check would think we're still up).
        lastServerUrl = ""
        lastPairToken = ""
        lastDeviceToken = ""
        WsBus.state.value = ConnState.Disconnected
        WsBus.statusLine.value = ""
    }

    private fun connectNow(prefs: AppPrefs) {
        val base = prefs.serverUrl.trimEnd('/')
        if (base.isEmpty()) {
            WsBus.state.value = ConnState.Disconnected
            WsBus.statusLine.value = "ยังไม่ได้ตั้ง Server URL"
            return
        }
        // Need either a usable device token (already paired) OR a fresh pair token.
        if (prefs.deviceToken.isEmpty() && prefs.pairToken.isEmpty()) {
            WsBus.state.value = ConnState.Disconnected
            WsBus.statusLine.value = "ต้อง pair กับ server ก่อน"
            return
        }

        socket?.close(1000, "reconnect")
        socket = null

        serverOrigin = base
        val wsBase = base.replace(Regex("^http"), "ws")
        val url = "$wsBase/ws/device"

        WsBus.state.value = ConnState.Connecting
        WsBus.statusLine.value = "เชื่อมต่อไป $base ..."

        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                // A freshly-scanned QR (pairToken non-empty) wins over a stale
                // deviceToken — without this, deleting the device from the
                // portal and re-pairing without reinstalling the app would try
                // `hello` with a now-invalid token and the server closes us.
                val usePair = prefs.pairToken.isNotEmpty()
                Log.i(TAG, "ws open — sending ${if (usePair) "pair" else "hello"} frame")
                if (usePair) {
                    sendPair(webSocket, prefs)
                } else {
                    sendHello(webSocket, prefs)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEnvelope(text, prefs)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                WsBus.state.value = ConnState.Disconnected
                WsBus.statusLine.value = "ปิดการเชื่อมต่อ ($code)"
                maybeReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                WsBus.state.value = ConnState.Error
                WsBus.statusLine.value = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "ws failure: ${t.message}")
                maybeReconnect()
            }
        })
    }

    private fun maybeReconnect() {
        if (!wantConnected) return
        val prefs = managedPrefs ?: return
        if (reconnectJob?.isActive == true) {
            Log.i(TAG, "reconnect already pending — ignoring duplicate trigger")
            return
        }
        val delayMs = (1000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
        reconnectAttempt += 1
        Log.i(TAG, "scheduling reconnect in ${delayMs}ms (attempt ${reconnectAttempt})")
        WsBus.statusLine.value = "จะลองใหม่ใน ${delayMs / 1000}s (#${reconnectAttempt})"
        reconnectJob = scope.launch {
            delay(delayMs)
            if (wantConnected) connectNow(prefs)
        }
    }

    // -------------------------------------------------------------------------
    // Send: pair / hello / pong
    // -------------------------------------------------------------------------

    private fun sendPair(ws: WebSocket, prefs: AppPrefs) {
        sendEnvelope(ws, "pair", mapOf(
            "token" to prefs.pairToken,
            "device_info" to mapOf(
                "name" to prefs.deviceName,
                "app_version" to APP_VERSION,
            ),
        ))
    }

    private fun sendHello(ws: WebSocket, prefs: AppPrefs) {
        sendEnvelope(ws, "hello", mapOf(
            "device_token" to prefs.deviceToken,
            "app_version" to APP_VERSION,
        ))
    }

    private fun sendEnvelope(ws: WebSocket, type: String, payload: Map<String, Any?>) {
        val env = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("type", type)
            put("payload", JSONObject(payload))
            put("timestamp", java.time.Instant.now().toString())
        }
        val ok = ws.send(env.toString())
        if (!ok) Log.w(TAG, "send($type) returned false")
    }

    /**
     * Notify the server that the broadcast has finished on this device
     * (api-contract §3.4 — `live_ended`). Server closes the live_sessions
     * row, flips the device to idle, and fans `live_ended` +
     * `device_status_changed` out to the portal.
     *
     * @param liveSessionId echoed from the start_live envelope payload
     * @param reason free-form; canonical values:
     *               `loop_completed`, `playback_error`, `device_stopped`.
     *               Empty → server defaults to `loop_completed`.
     */
    fun sendLiveEnded(liveSessionId: Long, reason: String) {
        val ws = socket ?: return
        sendEnvelope(ws, "live_ended", mapOf(
            "live_session_id" to liveSessionId,
            "reason" to reason,
        ))
        Log.i(TAG, "sent live_ended: live_session_id=$liveSessionId reason='$reason'")
    }

    /**
     * Notify the server about autopilot progress / outcome so the portal
     * can render a live timeline + a translated failure message.
     *
     * Schema:
     *  - state: "running" | "done" | "failed"
     *  - step_index / step_label: the most recent [Autopilot.setStep] value
     *  - error_user: a friendly Thai string built from the failing step's
     *    label ("ติดที่ขั้นตอน 'X'") — what the dashboard shows directly
     *  - error_debug: the raw [ScriptRunner.Result.Failed] message; the
     *    dashboard hides this behind a "ดูรายละเอียด" expand
     *
     * Quiet best-effort: if the WS isn't connected, nothing happens — the
     * portal just won't see this run's progress for now.
     */
    fun sendAutopilotStatus(
        liveSessionId: Long,
        commandId: String,
        script: String,
        state: String,
        stepIndex: Int,
        stepLabel: String,
        errorUser: String? = null,
        errorDebug: String? = null,
    ) {
        val ws = socket ?: return
        val payload = mutableMapOf<String, Any?>(
            "live_session_id" to liveSessionId,
            "command_id" to commandId,
            "script" to script,
            "state" to state,
            "step_index" to stepIndex,
            "step_label" to stepLabel,
        )
        if (errorUser != null) payload["error_user"] = errorUser
        if (errorDebug != null) payload["error_debug"] = errorDebug
        sendEnvelope(ws, "autopilot_status", payload)
    }

    /** Ack a server command (api-contract §3.4 — `ack`). */
    fun sendAck(commandId: String, success: Boolean, errorCode: String? = null, errorMessage: String? = null) {
        if (commandId.isEmpty()) return
        val ws = socket ?: return
        val payload = mutableMapOf<String, Any?>(
            "command_id" to commandId,
            "success" to success,
        )
        if (errorCode != null) payload["error_code"] = errorCode
        if (errorMessage != null) payload["error_message"] = errorMessage
        sendEnvelope(ws, "ack", payload)
    }

    /**
     * Ships a snapshot of permission/install state to the server right after
     * the connection becomes authenticated. The portal reads this off the
     * /api/devices payload and renders readiness badges + setup hints.
     * Call from handlePaired and handleWelcome — never before.
     */
    private fun sendCaps() {
        val ws = socket ?: return
        val ctx = appContext ?: return
        val caps = try {
            CapsCollector.collect(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "caps collect failed: ${e.message}")
            return
        }
        val env = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("type", "device_caps")
            put("payload", caps)
            put("timestamp", java.time.Instant.now().toString())
        }
        val ok = ws.send(env.toString())
        Log.i(TAG, "sendCaps: ok=$ok payload=$caps")
    }

    // -------------------------------------------------------------------------
    // Receive: envelope dispatch
    // -------------------------------------------------------------------------

    private fun handleEnvelope(text: String, prefs: AppPrefs) {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "non-json frame: $text"); return
        }
        val type = obj.optString("type")
        val payload = obj.optJSONObject("payload") ?: JSONObject()

        when (type) {
            "paired" -> handlePaired(payload, prefs)
            "welcome" -> handleWelcome(payload, prefs)
            "start_live" -> handleStartLive(obj, payload, prefs)
            "stop_live" -> {
                Log.i(TAG, "stop_live (not yet wired) payload=$payload")
            }
            "switch_video" -> {
                Log.i(TAG, "switch_video (not yet wired) payload=$payload")
            }
            "pin_product", "unpin_product" -> {
                Log.i(TAG, "$type (handled by autopilot) payload=$payload")
            }
            "force_disconnect" -> {
                val reason = payload.optString("reason", "(no reason)")
                Log.w(TAG, "force_disconnect from server: $reason")
                stopManaged()
            }
            "error" -> {
                val code = payload.optString("code")
                val msg = payload.optString("message")
                Log.w(TAG, "server error: code=$code msg=$msg")
                WsBus.statusLine.value = "$code: $msg"
                if (code == "UNAUTHORIZED") {
                    // The token we sent is bad — either the device row was wiped
                    // on the server side (DB reset, dev restart) or the pair
                    // token expired. Clear both so the reconnect loop bails
                    // (no usable credential left) and the home screen tells
                    // the operator to scan a fresh pair QR.
                    Log.w(TAG, "UNAUTHORIZED — clearing local tokens, need fresh pair QR")
                    prefs.deviceToken = ""
                    prefs.pairToken = ""
                    prefs.ownerEmail = ""
                    WsBus.ownerEmail.value = ""
                    stopManaged()
                    WsBus.statusLine.value = "ต้อง pair กับ server ใหม่ (scan QR)"
                }
            }
            "ping" -> {
                // Server-app-level ping (separate from WS control ping handled by OkHttp).
                val ws = socket ?: return
                sendEnvelope(ws, "pong", emptyMap())
            }
            else -> {
                Log.i(TAG, "unhandled envelope type=$type payload=$payload")
            }
        }
    }

    private fun handlePaired(payload: JSONObject, prefs: AppPrefs) {
        val deviceId = payload.optString("device_id")
        val deviceToken = payload.optString("device_token")
        if (deviceId.isEmpty() || deviceToken.isEmpty()) {
            Log.w(TAG, "paired envelope missing fields: $payload")
            return
        }
        prefs.deviceId = deviceId
        prefs.deviceToken = deviceToken
        val ownerEmail = payload.optString("owner_email")
        if (ownerEmail.isNotEmpty()) prefs.ownerEmail = ownerEmail
        // Pair token is single-use — clear so a stale one can't accidentally
        // re-pair (and create a duplicate device row).
        prefs.pairToken = ""
        WsBus.state.value = ConnState.Connected
        WsBus.statusLine.value = "$serverOrigin · paired as $deviceId"
        WsBus.ownerEmail.value = prefs.ownerEmail
        Log.i(TAG, "paired: device_id=$deviceId owner=$ownerEmail")
        sendCaps()
    }

    private fun handleWelcome(payload: JSONObject, prefs: AppPrefs) {
        val deviceId = payload.optString("device_id")
        if (deviceId.isNotEmpty() && prefs.deviceId != deviceId) {
            prefs.deviceId = deviceId
        }
        val ownerEmail = payload.optString("owner_email")
        if (ownerEmail.isNotEmpty()) prefs.ownerEmail = ownerEmail
        WsBus.state.value = ConnState.Connected
        WsBus.statusLine.value = serverOrigin
        WsBus.ownerEmail.value = prefs.ownerEmail
        Log.i(TAG, "welcome: device_id=$deviceId owner=$ownerEmail")
        sendCaps()
    }

    private fun handleStartLive(envelope: JSONObject, payload: JSONObject, prefs: AppPrefs) {
        // Server's `start_live` envelope (api-contract §3.3) carries an
        // already-uploaded (and server-stitched) video reference. We map it
        // onto our PlayCommand bus so the UI layer's autopilot routing
        // (V1 / V2 / V3) handles the rest.
        val videoUrl = payload.optString("video_url")
        if (videoUrl.isEmpty()) {
            Log.w(TAG, "start_live missing video_url: $payload")
            return
        }
        val absolute = if (videoUrl.startsWith("http")) videoUrl else serverOrigin + videoUrl
        val videoId = payload.optLong("video_id", -1L)
        val name = payload.optString("video_name", payload.optString("title", "video"))
        val title = payload.optString("title", "")
        val keywords = readKeywords(payload) + listOfNotNull(payload.optString("pinned_sku").ifBlank { null })
        val useOverlay = payload.optBoolean("use_overlay", true)
        // Server tells us how many cycles to play the stitched file. 0 / missing
        // = legacy infinite-loop behaviour (operator stops manually).
        val loopCount = payload.optInt("loop_count", 0)
        // Echo the envelope id back as command_id when we ack later.
        val commandId = envelope.optString("id")
        // Server currently emits the field as `live_session` (long), but the
        // outbound `live_ended` envelope spec uses `live_session_id`. Accept
        // either incoming key for resilience.
        val liveSessionId = payload.optLong("live_session", payload.optLong("live_session_id", 0L))
        scope.launch {
            WsBus.playCommands.emit(
                PlayCommand(
                    videoId = videoId,
                    name = name,
                    url = absolute,
                    autoStartLive = true,
                    useOverlay = useOverlay,
                    productKeywords = keywords.distinct(),
                    liveTitle = title,
                    loopCount = loopCount,
                    commandId = commandId,
                    liveSessionId = liveSessionId,
                )
            )
        }
    }

    private fun readKeywords(obj: JSONObject): List<String> {
        val arr = obj.optJSONArray("product_keywords") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private const val APP_VERSION = "0.1.0"
}
