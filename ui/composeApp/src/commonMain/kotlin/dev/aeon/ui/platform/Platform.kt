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

    /** ECDH P-256 with a one-time key pair against [peerSpki] (SPKI DER). Used to seal values for the server. */
    suspend fun ecdh(peerSpki: ByteArray): EcdhResult

    /** AES-256-GCM. Returns the ciphertext followed by the 16-byte tag. */
    suspend fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** AES-256-GCM. [data] is the ciphertext followed by the tag. Throws when the data or [aad] was changed. */
    suspend fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray): ByteArray
}

/** Our one-time public key (SPKI DER) and the 32-byte shared secret. */
class EcdhResult(val publicKey: ByteArray, val shared: ByteArray)

/** A file the owner chose to attach. */
class PickedFile(val name: String, val bytes: ByteArray)

/** Largest attachment the app will read into memory and send (the server's default limit). */
const val MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024

/**
 * Returns a function that opens the platform's file chooser. [onPicked] receives the file, or an error text
 * (too large, unreadable). Nothing is called when the owner cancels.
 */
@androidx.compose.runtime.Composable
expect fun rememberFilePicker(onPicked: (PickedFile?, String?) -> Unit): () -> Unit

/** Makes the system bar icons readable on the app's own background (dark icons on a light theme and the reverse). */
@androidx.compose.runtime.Composable
expect fun PlatformSystemBars(dark: Boolean)

/** Calls [onBack] for the system back gesture or button while [enabled]. No effect where there is none. */
@androidx.compose.runtime.Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Granted, Denied (can be asked again) or Blocked (only the system settings can change it now). */
enum class PermissionStatus { Granted, Denied, Blocked }

/** One runtime permission this platform has, with the reason shown before it is requested. */
class PermissionItem(val id: String, val title: String, val reason: String)

/** The runtime permissions of this platform. [items] is empty where the app needs none (desktop, web). */
interface PermissionController {
    val items: List<PermissionItem>
    fun status(id: String): PermissionStatus
    fun request(id: String)
    /** Opens the system page where a blocked permission can be turned on again. */
    fun openSettings()
}

@androidx.compose.runtime.Composable
expect fun rememberPermissionController(): PermissionController

/** Whether this platform can check for waiting approvals while the app is closed (Android only). */
expect val supportsBackgroundCheck: Boolean

/** Turns the periodic background check for waiting approvals on or off. Safe to call repeatedly. */
expect fun setBackgroundApprovalCheck(enabled: Boolean)

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

/** Korean clock text such as "오후 3:44", built from the local time string so it works on every platform. */
fun koreanClock(millis: Long): String {
    val s = formatDateTime(millis) // yyyy-MM-dd HH:mm:ss, local time
    val h = s.substring(11, 13).toInt()
    val m = s.substring(14, 16)
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "${if (h < 12) "오전" else "오후"} $h12:$m"
}

/** "오늘 / 내일" prefix for an absolute time, based on the local calendar date. */
fun koreanDayPrefix(millis: Long, now: Long): String {
    val d = formatDateTime(millis).substring(0, 10)
    val today = formatDateTime(now).substring(0, 10)
    return if (d == today) "오늘 " else if (millis - now < 2 * 86_400_000L && d != today) "내일 " else ""
}

/** Whether this page may sign requests (WebCrypto needs a secure context). Non-web platforms are always true. */
expect fun pageAllowsSigning(): Boolean

/** Host the page was loaded from (for the "not supported on this address" screen). */
expect fun pageHostLabel(): String
