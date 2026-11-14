package dev.aeon.ui.platform

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.aeon.ui.api.toBase64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Set once from the Application. The platform code needs a Context for private storage. */
object AppContext {
    @Volatile
    lateinit var app: Context
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }
}

/**
 * The ECDSA P-256 device key lives in the Android Keystore: it is generated there and can never be exported, not
 * even by this app. Hardware-backed (TEE or StrongBox) where the device supports it. The app can only ask the
 * Keystore to sign. Signatures are DER, which the server accepts.
 */
class AndroidDeviceCrypto : DeviceCrypto {
    private val random = SecureRandom()

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) return
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
            if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            gen.initialize(spec(strongBox = true))
            gen.generateKeyPair()
        } catch (e: Exception) {
            // No StrongBox on this device: fall back to the regular hardware-backed (TEE) keystore.
            val gen2 = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            gen2.initialize(spec(strongBox = false))
            gen2.generateKeyPair()
        }
    }

    override suspend fun publicKeyB64(): String {
        ensureKey()
        val pub: PublicKey = keyStore().getCertificate(ALIAS).publicKey
        return pub.encoded.toBase64() // X.509 SubjectPublicKeyInfo DER
    }

    override suspend fun sign(data: ByteArray): String {
        ensureKey()
        val priv = keyStore().getKey(ALIAS, null) as PrivateKey
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv)
        s.update(data)
        return s.sign().toBase64()
    }

    override suspend fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    override suspend fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    override suspend fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    override suspend fun reset() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
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

    private companion object {
        const val ALIAS = "aeon-device-key"
    }
}

/** Non-secret client state in the app's private SharedPreferences (excluded from backups, see the manifest). */
class AndroidKeyValueStore : KeyValueStore {
    private val prefs get() = AppContext.app.getSharedPreferences("aeon-ui", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

actual fun createDeviceCrypto(): DeviceCrypto = AndroidDeviceCrypto()

/** System document chooser. The file is read off the main thread; anything over the limit is refused before reading. */
@androidx.compose.runtime.Composable
actual fun rememberFilePicker(onPicked: (PickedFile?, String?) -> Unit): () -> Unit {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val resolver = AppContext.app.contentResolver
            try {
                var name = "file"
                var size = -1L
                resolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (n >= 0) name = c.getString(n) ?: name
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
                if (size > MAX_ATTACHMENT_BYTES) return@launch onPicked(null, "20MB보다 큰 파일은 올릴 수 없습니다.")
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_ATTACHMENT_BYTES) return@use null
                    }
                    out.toByteArray()
                }
                if (bytes == null) onPicked(null, "20MB보다 큰 파일은 올릴 수 없습니다.") else onPicked(PickedFile(name, bytes), null)
            } catch (e: Exception) {
                onPicked(null, "파일을 읽지 못했습니다.")
            }
        }
    }
    return { launcher.launch("*/*") }
}

actual fun createKeyValueStore(): KeyValueStore = AndroidKeyValueStore()

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun defaultDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL} (Android app)".take(64)

actual fun formatDateTime(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(millis))

actual fun pageAllowsSigning(): Boolean = true

actual fun pageHostLabel(): String = "Android"
