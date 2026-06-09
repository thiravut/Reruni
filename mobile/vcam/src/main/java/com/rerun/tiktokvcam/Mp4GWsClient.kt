package com.rerun.tiktokvcam

import android.util.Log
import de.robv.android.xposed.XposedBridge
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Option G — Mobile WebSocket client.
 *
 * Connects to the PC AAC encoder service (`tools/aac-server.py`),
 * receives pre-encoded AAC AU frames in real-time, and pushes them into
 * the native AAC ring. The `aacEncEncode` PLT hook in `audio_hook.c`
 * then pops one frame per encoder call and overwrites the encoder's
 * output buffer — viewers hear PC's clean encoded audio instead of
 * mic-derived audio that voice DSP would mangle.
 *
 * Wire protocol (matches `tools/aac-server.py:encode_frame_for_wire`):
 *   [u32 BE: payload_len]
 *   [u64 BE: pts_us]
 *   [u8:    kind]            (0x01 = audio AAC raw AU, 0x02 = video H264 future)
 *   [payload bytes]
 *
 * Activation:
 *   - Mode override file [vcam_audio_mode.txt] = "ws_inject"
 *   - PC IP/port override file [vcam_ws_endpoint.txt] = "ws://192.168.1.100:8765"
 *     (default falls back to ws://192.168.1.100:8765 if file missing)
 *
 * Lifecycle:
 *   - [start] opens a WebSocket and a background pump thread; reconnects
 *     with exponential backoff on disconnect (capped at 10 s) so a PC
 *     restart doesn't kill the LIVE broadcast.
 *   - [stop] closes the socket, flushes pending state.
 */
object Mp4GWsClient {
    private const val TAG = "TiktokRerunVCam"

    private const val ENDPOINT_OVERRIDE_PATH =
        "/sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_ws_endpoint.txt"
    private const val DEFAULT_ENDPOINT = "ws://192.168.1.100:8765"

    private const val KIND_AUDIO: Byte = 0x01
    private const val KIND_VIDEO: Byte = 0x02

    private val running = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var client: OkHttpClient? = null

    private var receivedFrames = 0L
    private var lastLogTimeMs = 0L

    /**
     * Generation counter incremented on every [stop] (and seeded by [start]).
     * Each [openOnce] call captures the value at entry; in-flight listener
     * callbacks (onMessage etc.) compare their captured id against the
     * current value before pushing AAC frames into the native ring.
     *
     * Why this exists: Mp4FrameProducer.onSurfacesChanged invokes stop() →
     * start() in rapid succession during camera surface transitions. The
     * previous WebSocket's OkHttp listener can still drain onMessage events
     * AFTER stop() returns, while the next start() opens a fresh socket.
     * Without this guard, the AAC ring receives interleaved frames from
     * both the stale socket and the active one — viewer hears the same MP4
     * audio twice, time-offset = "doubled audio".
     */
    private val sessionId = AtomicLong(0L)

    fun start() {
        if (!NativeAudioHook.available) {
            log("native lib not loaded; refusing to start")
            return
        }
        if (!running.compareAndSet(false, true)) return
        // Capture the sessionId at start time so this connect-loop only
        // owns connections for its specific session. The previous connect
        // loop (if still alive) sees the mismatch and exits when its
        // current openOnce returns, instead of looping back into a fresh
        // reconnect on top of ours.
        val myConnectSession = sessionId.get()
        NativeAudioHook.clearAacRing()
        NativeAudioHook.setRtmpInjectEnabled(true)
        Thread { connectLoop(myConnectSession) }.apply {
            name = "Mp4GWsClient-connect"
            isDaemon = true
            start()
        }
        log("WS client started; injection enabled (session=$myConnectSession)")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Invalidate any in-flight listener BEFORE we close the socket —
        // OkHttp may still deliver buffered onMessage events after close,
        // and we don't want those frames in the AAC ring.
        val invalidated = sessionId.incrementAndGet()
        try { socket?.close(1000, "client stop") } catch (_: Throwable) {}
        socket = null
        try { client?.dispatcher?.executorService?.shutdown() } catch (_: Throwable) {}
        client = null
        NativeAudioHook.setRtmpInjectEnabled(false)
        NativeAudioHook.clearAacRing()
        log("WS client stopped; injection disabled (sessionId → $invalidated)")
    }

    private fun connectLoop(myConnectSession: Long) {
        var backoffMs = 500L
        while (running.get() && sessionId.get() == myConnectSession) {
            val endpoint = readEndpoint()
            log("connecting to $endpoint (session=$myConnectSession)")
            try {
                val ok = openOnce(endpoint, myConnectSession)
                if (ok) {
                    // Connection established + closed normally → reset
                    // backoff so next reconnect is fast.
                    backoffMs = 500L
                } else {
                    log("connection failed; retry in ${backoffMs}ms")
                }
            } catch (t: Throwable) {
                log("connectLoop error: ${t.javaClass.simpleName}: ${t.message}")
            }
            if (!running.get() || sessionId.get() != myConnectSession) break
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
            backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
        }
        log("connect loop exited (session=$myConnectSession)")
    }

    /** Open one WebSocket attempt; blocks until disconnect.
     *  Returns true if the socket opened successfully (regardless of
     *  later disconnect cause); false if the initial handshake failed. */
    private fun openOnce(endpoint: String, mySession: Long): Boolean {
        // Re-check before allocating the OkHttpClient — if our session was
        // superseded between connectLoop's gate and now, skip cleanly.
        if (sessionId.get() != mySession) return false
        val httpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)        // long-lived stream
            .pingInterval(20, TimeUnit.SECONDS)            // keepalive
            .build()
        client = httpClient
        val request = Request.Builder().url(endpoint).build()
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = java.util.concurrent.CountDownLatch(1)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (sessionId.get() != mySession) {
                    // We've already been superseded by a newer start cycle.
                    // Close immediately so OkHttp tears down the listener.
                    try { webSocket.close(1000, "stale session on open") } catch (_: Throwable) {}
                    return
                }
                opened.set(true)
                socket = webSocket
                log("WS open (status=${response.code}, session=$mySession)")
                // libvolcenginertc.so is typically dlopen()'d when the user
                // taps "Go LIVE" — by the time WS connects, the broadcast
                // pipeline is initialising. Re-refresh xhook so the
                // aacEncEncode PLT slot gets rewritten on this just-loaded
                // lib. Idempotent: redundant on sessions where the
                // AudioRecord ctor hook already triggered refresh.
                NativeAudioHook.refresh()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Drop frames if this listener has been superseded — without
                // this guard, a stale socket draining buffered messages
                // after stop() would push duplicate MP4 audio into the AAC
                // ring alongside the active socket's stream.
                if (sessionId.get() != mySession) return
                try {
                    handleFrame(bytes)
                } catch (t: Throwable) {
                    log("frame handler threw: ${t.javaClass.simpleName}: ${t.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log("WS closing: $code $reason (session=$mySession)")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("WS closed: $code $reason after $receivedFrames frames (session=$mySession)")
                if (sessionId.get() == mySession) {
                    receivedFrames = 0L
                    socket = null
                }
                done.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("WS failure: ${t.javaClass.simpleName}: ${t.message}" +
                    (response?.let { " (status=${it.code})" } ?: "") +
                    " (session=$mySession)")
                if (sessionId.get() == mySession) socket = null
                done.countDown()
            }
        }
        httpClient.newWebSocket(request, listener)
        // Block this thread until disconnect (onClosed/onFailure).
        done.await()
        return opened.get()
    }

    /** Wire format parser (binary frame from `aac-server.py`).
     *  See file header comment for layout. */
    private fun handleFrame(bytes: ByteString) {
        if (bytes.size < 4 + 8 + 1) {
            log("frame too short: ${bytes.size} bytes")
            return
        }
        val raw = bytes.toByteArray()
        val payloadLen = ((raw[0].toInt() and 0xff) shl 24) or
                        ((raw[1].toInt() and 0xff) shl 16) or
                        ((raw[2].toInt() and 0xff) shl 8) or
                         (raw[3].toInt() and 0xff)
        // pts_us at offset 4..11 (we don't use it on mobile yet — the
        // hook substitutes 1:1 with TikTok's encoder cadence which has
        // its own pts).
        val kind = raw[12]
        val payloadStart = 13
        if (payloadStart + payloadLen > raw.size) {
            log("frame size mismatch: declared=$payloadLen, available=${raw.size - payloadStart}")
            return
        }
        receivedFrames++
        when (kind) {
            KIND_AUDIO -> {
                val payload = raw.copyOfRange(payloadStart, payloadStart + payloadLen)
                NativeAudioHook.pushAacFrame(payload, payloadLen)
                logProgressIfDue()
            }
            KIND_VIDEO -> {
                // Phase 6: video path not wired yet
            }
            else -> {
                log("unknown kind=$kind, skipping")
            }
        }
    }

    private fun logProgressIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastLogTimeMs >= 5_000L) {
            lastLogTimeMs = now
            log("received=$receivedFrames frames, ring=${NativeAudioHook.aacRingFrames()}")
        }
    }

    private fun readEndpoint(): String = try {
        val f = File(ENDPOINT_OVERRIDE_PATH)
        if (!f.exists() || f.length() <= 0L || f.length() > 200L) DEFAULT_ENDPOINT
        else f.readText().trim().takeIf { it.startsWith("ws://") || it.startsWith("wss://") }
            ?: DEFAULT_ENDPOINT
    } catch (_: Throwable) {
        DEFAULT_ENDPOINT
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] Mp4GWsClient: $msg")
        Log.i(TAG, "Mp4GWsClient: $msg")
    }
}
