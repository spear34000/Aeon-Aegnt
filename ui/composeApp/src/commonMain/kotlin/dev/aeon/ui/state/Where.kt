package dev.aeon.ui.state

import dev.aeon.ui.api.PairLink

/**
 * Where the agent runs, as far as the screens need to say it. The app only talks to a server over the local API; when
 * that server is this device itself (a loopback address) the agent runs here, otherwise on another device. Screens use
 * these instead of the word "server", so the same wording works when the agent is on the phone and when it is on a PC.
 */
val UiState.thisDevice: Boolean get() = PairLink.isThisDevice(serverUrl)

/** "이 기기에서 실행" or "다른 기기에서 실행". */
val UiState.runsOn: String get() = if (thisDevice) "이 기기에서 실행" else "다른 기기에서 실행"

/** Status line for a healthy connection. */
val UiState.connectedText: String get() = if (thisDevice) "이 기기에서 실행 중" else "서버와 연결됨"
