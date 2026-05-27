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
    private var reconnectAttempt: Int = 0
    private var reconnectJob: Job? = null

    val origin: String get() = serverOrigin

    fun startManaged(prefs: AppPrefs) {
        wantConnected = true
        managedPrefs = prefs
        reconnectAttempt = 0
        connectNow(prefs)
    }

    fun stopManaged() {
        wantConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "user disconnect")
        socket = null
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
            "start_live" -> handleStartLive(payload, prefs)
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
    }

    private fun handleStartLive(payload: JSONObject, prefs: AppPrefs) {
        // Server's `start_live` envelope (api-contract §3.3) carries an
        // already-uploaded video reference. We map it onto our existing
        // PlayCommand bus so the UI layer's autopilot routing (V1 vs V3,
        // overlay-on/off) doesn't need to change.
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
        // New contract assumes start_live = "go live now", so autoStartLive=true.
        // use_overlay defaults true to preserve the Smart Overlay behavior that the
        // contract was written around; V3 tier ignores it on the mobile side anyway.
        val useOverlay = payload.optBoolean("use_overlay", true)
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
