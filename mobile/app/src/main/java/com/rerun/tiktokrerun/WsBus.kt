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
    val playCommands = MutableSharedFlow<PlayCommand>(extraBufferCapacity = 8)
    val startLiveCommands = MutableSharedFlow<StartLiveCommand>(extraBufferCapacity = 4)
}
