package dev.aeon.ui.platform

/**
 * Per-platform security primitives (decision D11/D13). The private key never leaves the platform's protected
 * storage in usable form: Windows DPAPI-protected file (desktop), non-extractable WebCrypto key in IndexedDB (web),
 * Android Keystore (Android, added once the SDK exists).
 */
interface DeviceCrypto {
    /** SPKI DER public key, base64. Creates the key pair on first use. */
    suspend fun publicKeyB64(): String

    /** ECDSA P-256 / SHA-256 signature, base64 (DER on JVM/Android, IEEE-P1363 on web; the server accepts both). */
    suspend fun sign(data: ByteArray): String

    suspend fun sha256(data: ByteArray): ByteArray

    suspend fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray

    suspend fun randomBytes(count: Int): ByteArray

    /** Forget the key pair (used when unpairing). */
    suspend fun reset()
}

/** Small persistent string store for non-secret client state (server URL, device id). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

expect fun createDeviceCrypto(): DeviceCrypto

expect fun createKeyValueStore(): KeyValueStore

expect fun nowMillis(): Long

/** Name shown for this device in the server's device list. */
expect fun defaultDeviceName(): String

/** Local, human-readable date and time. */
expect fun formatDateTime(millis: Long): String
