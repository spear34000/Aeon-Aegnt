package dev.aeon.ui.platform

/**
 * The agent running inside this app, where that is possible: on Android, the compiled server on the Node.js for Mobile
 * runtime that ships in the app (decision D19). Elsewhere there is none, and the app connects to another device.
 */
interface LocalAgentHost {
    /** True when this build contains the runtime and the server. */
    val available: Boolean

    /** Starts it if it is not running. Returns null once it answers, or a sentence saying why it could not start. */
    suspend fun start(): String?

    /** A fresh one-time pairing code that only this app can read (the server writes it into the app's private folder), or null. */
    suspend fun pairingCode(): String?
}

expect fun localAgentHost(): LocalAgentHost

/** The address the on-device agent listens on. Loopback only: nothing else on the network can reach it. */
const val LOCAL_AGENT_URL = "http://127.0.0.1:7421"
