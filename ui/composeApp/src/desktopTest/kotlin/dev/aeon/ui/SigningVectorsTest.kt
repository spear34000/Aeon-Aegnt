package dev.aeon.ui

import dev.aeon.ui.api.Signing
import dev.aeon.ui.api.fromBase64
import dev.aeon.ui.api.toBase64
import dev.aeon.ui.api.toHex
import dev.aeon.ui.platform.DesktopDeviceCrypto
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Vectors produced by the Node implementation (src/node/server/device-auth.ts) for the same inputs.
 * If either side changes its byte format, this test (and the Node tests) fail instead of production pairing.
 */
class SigningVectorsTest {
    private val crypto = DesktopDeviceCrypto(Files.createTempDirectory("aeon-ui-test").toFile())

    @Test
    fun canonicalRequestAndBodyHashMatchNode() = runBlocking {
        val body = "{\"goal\":\"héllo ✓\"}" // non-ASCII on purpose: UTF-8 bytes must be hashed, not UTF-16 chars
        val hash = crypto.sha256(body.encodeToByteArray()).toHex()
        assertEquals("16a4f3bccfb9dddd975d37d238981f4cc7120c00320af95ae773045658b52824", hash)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", crypto.sha256(ByteArray(0)).toHex())
        assertEquals(
            "AEON-V1\nPOST\n/v1/tasks?x=1\n1700000000000\nnonce-abc12345\n$hash",
            Signing.canonicalRequest("post", "/v1/tasks?x=1", "1700000000000", "nonce-abc12345", hash),
        )
    }

    @Test
    fun pairingCodeNormalizationAndProofsMatchNode() = runBlocking {
        val norm = Signing.normalizeCode("ABCDE-FGHIJ-KLMNO-23456")
        assertEquals("ABCDEFGHIJKLMNO23456", norm)
        assertEquals("ABCDEFGHIJKLMNO23456", Signing.normalizeCode("abcde fghij-klmno 23456\n"))
        val client = crypto.hmacSha256(norm.encodeToByteArray(), Signing.clientProofMessage("my phone", "UEtJ", "bm9uY2U=").encodeToByteArray()).toBase64()
        assertEquals("2mqiNos2JLZwDmpl59nQrQ/SVNnY6j0QMt1qLYXrnII=", client)
        val server = crypto.hmacSha256(norm.encodeToByteArray(), Signing.serverProofMessage("dev_abc123", "bm9uY2U=", "UEtJ").encodeToByteArray()).toBase64()
        assertEquals("NsvIwA7C6Ltl64IfF8FcNLop/oRb04SFC2Zi2Y835L0=", server)
    }

    @Test
    fun base64RoundTripsAndMatchesStandardEncoding() {
        for (n in 0..40) {
            val bytes = ByteArray(n) { (it * 7 + n).toByte() }
            assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), bytes.toBase64())
            assertContentEquals(bytes, bytes.toBase64().fromBase64())
        }
    }

    @Test
    fun publicKeyIsSpkiDerAndKeyPersistsAcrossInstances() = runBlocking {
        val dir = Files.createTempDirectory("aeon-ui-key").toFile()
        val a = DesktopDeviceCrypto(dir)
        val pub1 = a.publicKeyB64()
        val b = DesktopDeviceCrypto(dir)
        assertEquals(pub1, b.publicKeyB64(), "key survives a restart (DPAPI-protected file on Windows)")
        val der = pub1.fromBase64()
        assertEquals(0x30, der[0].toInt(), "DER SEQUENCE")
        if (System.getProperty("os.name").lowercase().contains("windows")) {
            val stored = java.io.File(dir, "device-key.dpapi").readText()
            val raw = stored.fromBase64()
            // PKCS#8 for P-256 starts with 0x30 0x81/0x87; DPAPI blobs start with 01 00 00 00 (version 1).
            assertEquals(1, raw[0].toInt()); assertEquals(0, raw[1].toInt())
        }
        a.reset()
        val c = DesktopDeviceCrypto(dir)
        assert(c.publicKeyB64() != pub1) { "reset creates a new identity" }
    }
}
