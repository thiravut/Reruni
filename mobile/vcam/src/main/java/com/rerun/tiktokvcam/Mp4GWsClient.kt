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

    fun start() {
        if (!NativeAudioHook.available) {
            log("native lib not loaded; refusing to start")
            return
        }
        if (!running.compareAndSet(false, true)) return
        NativeAudioHook.clearAacRing()
        NativeAudioHook.setRtmpInjectEnabled(true)
        Thread { connectLoop() }.apply {
            name = "Mp4GWsClient-connect"
            isDaemon = true
            start()
        }
        log("WS client started; injection enabled")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { socket?.close(1000, "client stop") } catch (_: Throwable) {}
        socket = null
        try { client?.dispatcher?.executorService?.shutdown() } catch (_: Throwable) {}
        client = null
        NativeAudioHook.setRtmpInjectEnabled(false)
        NativeAudioHook.clearAacRing()
        log("WS client stopped; injection disabled")
    }

    private fun connectLoop() {
        var backoffMs = 500L
        while (running.get()) {
            val endpoint = readEndpoint()
            log("connecting to $endpoint")
            try {
                val ok = openOnce(endpoint)
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
            if (!running.get()) break
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
            backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
        }
        log("connect loop exited")
    }

    /** Open one WebSocket attempt; blocks until disconnect.
     *  Returns true if the socket opened successfully (regardless of
     *  later disconnect cause); false if the initial handshake failed. */
    private fun openOnce(endpoint: String): Boolean {
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
                opened.set(true)
                socket = webSocket
                log("WS open (status=${response.code})")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    handleFrame(bytes)
                } catch (t: Throwable) {
                    log("frame handler threw: ${t.javaClass.simpleName}: ${t.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log("WS closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("WS closed: $code $reason after $receivedFrames frames")
                receivedFrames = 0L
                socket = null
                done.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("WS failure: ${t.javaClass.simpleName}: ${t.message}" +
                    (response?.let { " (status=${it.code})" } ?: ""))
                socket = null
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
