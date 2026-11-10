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

actual fun createKeyValueStore(): KeyValueStore = AndroidKeyValueStore()

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun defaultDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL} (Android app)".take(64)

actual fun formatDateTime(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(millis))

actual fun pageAllowsSigning(): Boolean = true

actual fun pageHostLabel(): String = "Android"
