package dev.aeon.ui.api

/**
 * Byte-exact counterparts of src/node/server/device-auth.ts. Any change here must be mirrored there; the
 * shared test vectors in SigningVectorsTest fail if the two sides drift apart.
 */
object Signing {
    /** The exact text a client signs: method, path+query as sent, time, nonce, SHA-256 of the raw body. */
    fun canonicalRequest(method: String, pathAndQuery: String, timestamp: String, nonce: String, bodySha256Hex: String): String =
        listOf("AEON-V1", method.uppercase(), pathAndQuery, timestamp, nonce, bodySha256Hex).joinToString("\n")

    /** Keep only base32 letters/digits, upper-cased (what the server prints is grouped with dashes). */
    fun normalizeCode(code: String): String = code.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '2'..'7' }.uppercase()

    fun clientProofMessage(deviceName: String, publicKey: String, clientNonce: String): String =
        "aeon-pair-client|$deviceName|$publicKey|$clientNonce"

    fun serverProofMessage(deviceId: String, clientNonce: String, publicKey: String): String =
        "aeon-pair-server|$deviceId|$clientNonce|$publicKey"
}

/** Lower-case hex of a byte array. */
fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(digits[v ushr 4]).append(digits[v and 15])
    }
    return sb.toString()
}

private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** Standard base64 with padding (the server uses Node's Buffer base64, which matches). */
fun ByteArray.toBase64(): String {
    val sb = StringBuilder((size + 2) / 3 * 4)
    var i = 0
    while (i < size) {
        val b0 = this[i].toInt() and 0xff
        val b1 = if (i + 1 < size) this[i + 1].toInt() and 0xff else 0
        val b2 = if (i + 2 < size) this[i + 2].toInt() and 0xff else 0
        sb.append(B64[b0 ushr 2])
        sb.append(B64[((b0 and 3) shl 4) or (b1 ushr 4)])
        sb.append(if (i + 1 < size) B64[((b1 and 15) shl 2) or (b2 ushr 6)] else '=')
        sb.append(if (i + 2 < size) B64[b2 and 63] else '=')
        i += 3
    }
    return sb.toString()
}

fun String.fromBase64(): ByteArray {
    val clean = trim().trimEnd('=')
    val out = ArrayList<Byte>(clean.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (c in clean) {
        val v = B64.indexOf(c)
        require(v >= 0) { "invalid base64" }
        buffer = (buffer shl 6) or v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add(((buffer shr bits) and 0xff).toByte())
        }
    }
    return out.toByteArray()
}
