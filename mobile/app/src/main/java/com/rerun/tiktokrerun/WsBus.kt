package com.rerun.tiktokrerun

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class ConnState { Disconnected, Connecting, Connected, Error }

data class PlayCommand(
    val videoId: Long,
    val name: String,
    val url: String, // absolute, including server origin
    val autoStartLive: Boolean = false,
    val useOverlay: Boolean = false,
    val productKeywords: List<String> = emptyList(),
    val liveTitle: String = "",
    /**
     * How many times to replay the (server-stitched) video file before the
     * broadcast ends. 0 / -1 = loop indefinitely (legacy behaviour). When
     * positive, mobile counts playbacks and triggers end-live + WS notify
     * when the count is reached.
     */
    val loopCount: Int = 0,
    /**
     * Command id from the server's issueCommand row, echoed back in `ack`
     * envelopes once the broadcast actually ends.
     */
    val commandId: String = "",
    /**
     * Server's live_sessions row id. Mobile echoes it back inside the
     * `live_ended` envelope so the server can close the matching row
     * (api-contract §3.4).
     */
    val liveSessionId: Long = 0L,
    /**
     * Absolute WebSocket URL (`wss://...`) the vcam module's Mp4GWsClient
     * connects to for Option G AAC injection. Pushed into the override
     * file `/sdcard/Android/data/com.zhiliaoapp.musically/files/
     * vcam_ws_endpoint.txt` by Autopilot before TikTok launches, so each
     * LIVE session can target a different encoder backend (server may
     * route different sessions to different edge boxes).
     *
     * Empty string = no override; mobile keeps whatever endpoint is
     * already in the file (legacy PC-server flow).
     */
    val aacWsUrl: String = "",
)

data class StartLiveCommand(
    val productKeywords: List<String> = emptyList(),
    val liveTitle: String = "",
    val useOverlay: Boolean = false,
)

/** App-scoped event bus shared between WsClient and UI. */
object WsBus {
    val state = MutableStateFlow(ConnState.Disconnected)
    val statusLine = MutableStateFlow<String>("")
    /** Email of the portal user this device is paired to (server-supplied). */
    val ownerEmail = MutableStateFlow<String>("")
    val playCommands = MutableSharedFlow<PlayCommand>(extraBufferCapacity = 8)
    val startLiveCommands = MutableSharedFlow<StartLiveCommand>(extraBufferCapacity = 4)
}
