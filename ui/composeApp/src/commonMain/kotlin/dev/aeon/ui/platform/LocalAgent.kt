package dev.aeon.ui.platform

/**
 * The agent running inside this app, where that is possible: on Android, the compiled server on the Node.js for Mobile
 * runtime that ships in the app (decision D19). Elsewhere there is none, and the app connects to another device.
 */
interface LocalAgentHost {
    /** True when this build contains the runtime and the server. */
    val available: Boolean

    /**
     * Set by the app to follow a start: the stage it is in (an index into [DEVICE_STAGES]) and one line for the log.
     * A host that starts nothing ignores it.
     */
    var onProgress: ((stage: Int, line: String) -> Unit)?
        get() = null
        set(_) {}

    /** Starts it if it is not running. Returns null once it answers, or a sentence saying why it could not start. */
    suspend fun start(): String?

    /** A fresh one-time pairing code that only this app can read (the server writes it into the app's private folder), or null. */
    suspend fun pairingCode(): String?
}

/** The steps shown while the agent starts on this device, in order. */
val DEVICE_STAGES = listOf("에이전트 파일 준비", "에이전트 켜기", "응답 기다리기", "연결 코드 받기", "이 기기와 연결")

expect fun localAgentHost(): LocalAgentHost

/** The address the on-device agent listens on. Loopback only: nothing else on the network can reach it. */
const val LOCAL_AGENT_URL = "http://127.0.0.1:7421"
