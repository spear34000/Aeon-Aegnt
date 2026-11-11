package dev.aeon.ui.api

import dev.aeon.ui.platform.DeviceCrypto
import kotlinx.serialization.Serializable

/** A value encrypted for the server. [epk] is this device's one-time ECDH public key. */
@Serializable
data class SealedBox(val epk: String, val iv: String, val ct: String)

/** A value the server encrypted for a one-time key this device sent. */
@Serializable
data class SealedReply(val iv: String, val ct: String)

/**
 * Byte-exact counterpart of src/node/server/seal.ts ("AEON-SEAL-V1"). The connection may be plain HTTP, so API
 * keys going to the server and pairing codes coming back are encrypted with a key only this device and the
 * server can derive: ECDH P-256 between a one-time key made here and the server key pinned while pairing, then
 * key = SHA-256("AEON-SEAL-V1|" + direction + "|" || shared || epk || serverKey) and AES-256-GCM.
 */
object Sealing {
    private suspend fun key(crypto: DeviceCrypto, direction: String, shared: ByteArray, epk: ByteArray, serverKey: ByteArray): ByteArray =
        crypto.sha256("AEON-SEAL-V1|$direction|".encodeToByteArray() + shared + epk + serverKey)

    /** Encrypts [plaintext] for the server. [aad] binds the box to its purpose and must match what the server expects. */
    suspend fun seal(crypto: DeviceCrypto, serverKeyB64: String, plaintext: ByteArray, aad: String): SealedBox {
        val serverKey = serverKeyB64.fromBase64()
        val ex = crypto.ecdh(serverKey)
        val iv = crypto.randomBytes(12)
        val ct = crypto.aesGcmEncrypt(key(crypto, "c2s", ex.shared, ex.publicKey, serverKey), iv, plaintext, aad.encodeToByteArray())
        return SealedBox(ex.publicKey.toBase64(), iv.toBase64(), ct.toBase64())
    }

    /** One-time key to send with a request, and the means to read the server's sealed answer to it. */
    class Exchange internal constructor(val epk: String, private val crypto: DeviceCrypto, private val key: ByteArray) {
        suspend fun open(reply: SealedReply, aad: String): ByteArray =
            crypto.aesGcmDecrypt(key, reply.iv.fromBase64(), reply.ct.fromBase64(), aad.encodeToByteArray())
    }

    suspend fun exchange(crypto: DeviceCrypto, serverKeyB64: String): Exchange {
        val serverKey = serverKeyB64.fromBase64()
        val ex = crypto.ecdh(serverKey)
        return Exchange(ex.publicKey.toBase64(), crypto, key(crypto, "s2c", ex.shared, ex.publicKey, serverKey))
    }

    fun serverKeyProofMessage(serverKey: String, clientNonce: String, deviceId: String): String =
        "aeon-pair-key|$serverKey|$clientNonce|$deviceId"
}
