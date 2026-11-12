package dev.aeon.ui.platform

import dev.aeon.ui.api.fromBase64
import dev.aeon.ui.api.toBase64
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

/** Per-user config directory following platform convention (same place the Node side uses, in a `ui` subfolder). */
internal fun desktopConfigDir(): File {
    System.getenv("AEON_UI_DIR")?.let { return File(it) }
    val base = when {
        isWindows -> System.getenv("APPDATA")?.let { File(it, "Aeon") } ?: File(System.getProperty("user.home"), "AppData/Roaming/Aeon")
        System.getProperty("os.name").lowercase().contains("mac") -> File(System.getProperty("user.home"), "Library/Application Support/Aeon")
        else -> File(System.getenv("XDG_CONFIG_HOME") ?: (System.getProperty("user.home") + "/.config"), "aeon")
    }
    return File(base, "ui")
}

private val posix: Boolean = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

/** Creates the directory (0700 on POSIX systems when newly created). */
internal fun createPrivateDir(dir: File) {
    if (dir.exists()) return
    if (posix) {
        java.nio.file.Files.createDirectories(
            dir.toPath(),
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")),
        )
    } else dir.mkdirs()
}

/** Writes secret bytes to a file that is owner-only from the moment it exists (POSIX); replaces any old file. */
internal fun writeOwnerOnly(file: File, bytes: ByteArray) {
    java.nio.file.Files.deleteIfExists(file.toPath())
    if (posix) {
        java.nio.file.Files.createFile(
            file.toPath(),
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),
        )
    }
    file.writeBytes(bytes)
}

/**
 * Windows: the private key (PKCS#8) is encrypted with DPAPI (current user + app entropy) before it touches disk,
 * using the same PowerShell-over-stdin scheme as the Node secret store; nothing secret appears in argv.
 * Other desktops: a file with owner-only permissions (documented as not encrypted).
 */
class DesktopDeviceCrypto(private val dir: File = desktopConfigDir()) : DeviceCrypto {
    private val random = SecureRandom()
    private var cached: KeyPair? = null
    private val keyFile get() = File(dir, if (isWindows) "device-key.dpapi" else "device-key.pk8")

    private fun load(): KeyPair {
        cached?.let { return it }
        createPrivateDir(dir)
        val pair = if (keyFile.exists()) readKey() else newKey()
        cached = pair
        return pair
    }

    private fun newKey(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = gen.generateKeyPair()
        val pkcs8 = pair.private.encoded
        if (isWindows) keyFile.writeText(dpapi("Protect", pkcs8.toBase64()))
        else writeOwnerOnly(keyFile, pkcs8) // created 0600 BEFORE any key byte is written (no permission race)
        return pair
    }

    private fun readKey(): KeyPair {
        val pkcs8 = if (isWindows) dpapi("Unprotect", keyFile.readText()).fromBase64() else keyFile.readBytes()
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        // Re-derive the public key from the stored file next to it (SPKI), written when the pair was created.
        val pubFile = File(dir, "device-key.pub")
        val pub = if (pubFile.exists()) kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())) else error("public key file is missing; re-pair this device")
        return KeyPair(pub, priv)
    }

    private fun persistPublic(pair: KeyPair) {
        File(dir, "device-key.pub").writeBytes(pair.public.encoded)
    }

    override suspend fun publicKeyB64(): String {
        val p = load()
        persistPublic(p)
        return p.public.encoded.toBase64()
    }

    override suspend fun sign(data: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(load().private)
        s.update(data)
        return s.sign().toBase64() // DER, accepted by the server
    }

    override suspend fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    override suspend fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    override suspend fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    override suspend fun reset() {
        cached = null
        keyFile.delete()
        File(dir, "device-key.pub").delete()
    }

    override suspend fun ecdh(peerSpki: ByteArray): EcdhResult {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val peer = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(peerSpki))
        val agreement = javax.crypto.KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(peer, true)
        return EcdhResult(pair.public.encoded, agreement.generateSecret())
    }

    private fun aesGcm(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(data)
    }

    override suspend fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        aesGcm(javax.crypto.Cipher.ENCRYPT_MODE, key, iv, plaintext, aad)

    override suspend fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray): ByteArray =
        aesGcm(javax.crypto.Cipher.DECRYPT_MODE, key, iv, data, aad)

    private fun dpapi(op: String, base64In: String): String {
        val script = listOf(
            "\$ErrorActionPreference='Stop'",
            "Add-Type -AssemblyName System.Security",
            "\$in=[Console]::In.ReadToEnd().Trim()",
            "\$b=[Convert]::FromBase64String(\$in)",
            "\$e=[Text.Encoding]::UTF8.GetBytes('aeon-agent-ui-v1')",
            "\$o=[Security.Cryptography.ProtectedData]::$op(\$b,\$e,[Security.Cryptography.DataProtectionScope]::CurrentUser)",
            "[Console]::Out.Write([Convert]::ToBase64String(\$o))",
        ).joinToString(";")
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
            .redirectErrorStream(false)
            .start()
        p.outputStream.use { it.write(base64In.toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = p.waitFor()
        check(code == 0) { "DPAPI $op failed (exit $code)" }
        return out.trim()
    }
}

class FileKeyValueStore(private val file: File = File(desktopConfigDir(), "settings.properties")) : KeyValueStore {
    private fun read(): MutableMap<String, String> {
        if (!file.exists()) return mutableMapOf()
        return file.readLines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap().toMutableMap()
    }

    private fun write(m: Map<String, String>) {
        file.parentFile.mkdirs()
        file.writeText(m.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    override fun get(key: String): String? = read()[key]
    override fun put(key: String, value: String) = write(read().also { it[key] = value })
    override fun remove(key: String) = write(read().also { it.remove(key) })
}

actual fun createDeviceCrypto(): DeviceCrypto = DesktopDeviceCrypto()

/** Native file dialog (Windows, Linux, macOS). */
@androidx.compose.runtime.Composable
actual fun rememberFilePicker(onPicked: (PickedFile?, String?) -> Unit): () -> Unit {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, "파일 선택", java.awt.FileDialog.LOAD)
            dialog.isVisible = true
            val name = dialog.file ?: return@launch
            val file = File(dialog.directory, name)
            try {
                if (file.length() > MAX_ATTACHMENT_BYTES) onPicked(null, "20MB보다 큰 파일은 올릴 수 없습니다.")
                else onPicked(PickedFile(name, file.readBytes()), null)
            } catch (e: Exception) {
                onPicked(null, "파일을 읽지 못했습니다.")
            }
        }
    }
}

actual fun createKeyValueStore(): KeyValueStore = FileKeyValueStore()

/** Desktop windows have no system back gesture. */
@androidx.compose.runtime.Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}

private object NoPermissions : PermissionController {
    override val items: List<PermissionItem> = emptyList()
    override fun status(id: String) = PermissionStatus.Granted
    override fun request(id: String) {}
    override fun openSettings() {}
}

@androidx.compose.runtime.Composable
actual fun rememberPermissionController(): PermissionController = NoPermissions

actual val supportsBackgroundCheck: Boolean = false

actual fun setBackgroundApprovalCheck(enabled: Boolean) {
}

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun defaultDeviceName(): String = "${System.getProperty("user.name") ?: "user"}@${runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("desktop")} (${if (isWindows) "Windows" else "Desktop"} app)".take(64)

private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

actual fun formatDateTime(millis: Long): String = formatter.format(Instant.ofEpochMilli(millis))

actual fun pageAllowsSigning(): Boolean = true

actual fun pageHostLabel(): String = "127.0.0.1"
