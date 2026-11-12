package dev.aeon.ui

import dev.aeon.ui.api.PairLink
import dev.aeon.ui.api.Qr
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The vectors are the ones in test/qr.test.ts: the Kotlin and TypeScript encoders must agree module for module. */
class QrTest {
    private class Vector(val text: String, val version: Int, val mask: Int, val sha: String)

    private val vectors = listOf(
        Vector("hello", 1, 0, "bcb1c127e8a305bb237b297a8e376eeb7ec1504d5766f4dba30ed1e023c74b94"),
        Vector("aeon://pair?u=http%3A%2F%2F192.168.0.12%3A7421&c=75KFXQWOXQGQ7VLQ", 5, 6, "7895bddfda0b25e853efd3aa67b8b008b121eb6e2c5cb82d63ff2cfbd0263e34"),
        Vector("aeon://pair?u=http%3A%2F%2F192.168.0.12%3A7421&u=http%3A%2F%2F100.115.170.78%3A7421&c=75KFXQWOXQGQ7VLQ", 6, 2, "7736f2758286cb660eb0767ee1789d6e9a38a989e1f1ad05be23cf063a5ba8dd"),
        Vector("한글", 1, 6, "8b675c1c5745f0752ef07dd80ea413b93f778b73373085f32aa3bd35507041a1"),
    )

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun matchesTheTypeScriptEncoderModuleForModule() {
        for (v in vectors) {
            val q = Qr.encode(v.text)
            assertEquals(Triple(v.version, v.mask, v.sha), Triple(q.version, q.mask, sha(q.toText())), v.text)
        }
    }

    @Test
    fun growsWithTheTextAndRefusesWhatCannotFit() {
        assertEquals(1, Qr.encode("a").version)
        assertEquals(10, Qr.encode("x".repeat(Qr.MAX_BYTES)).version)
        assertFailsWith<IllegalArgumentException> { Qr.encode("x".repeat(Qr.MAX_BYTES + 1)) }
    }

    @Test
    fun pairLinkRoundTripsAndDropsUnsafeParts() {
        val link = PairLink.build(listOf("http://192.168.0.12:7421", "http://100.115.170.78:7421"), "75KF-XQWO-XQGQ-7VLQ")
        assertEquals("aeon://pair?u=http%3A%2F%2F192.168.0.12%3A7421&u=http%3A%2F%2F100.115.170.78%3A7421&c=75KFXQWOXQGQ7VLQ", link)
        assertEquals(PairLink(listOf("http://192.168.0.12:7421", "http://100.115.170.78:7421"), "75KFXQWOXQGQ7VLQ"), PairLink.parse(link))
        assertTrue(Qr.encode(link).version <= 6)

        val many = PairLink.build(listOf("http://a:1", "javascript:alert(1)", "http://u:p@evil:1", "http://b:2", "http://c:3", "http://d:4"), "75KFXQWOXQGQ7VLQ")
        assertEquals(listOf("http://a:1", "http://b:2", "http://c:3"), PairLink.parse(many)!!.addresses)

        val code = "75KFXQWOXQGQ7VLQ"
        for (bad in listOf("http://pair?c=$code", "aeon://other?c=$code", "aeon://pair?c=nothex", "aeon://pair?u=http%3A%2F%2Fa%3A1", "aeon://pair?c=$code%", "aeon://pair?c=$code%zz", "")) assertNull(PairLink.parse(bad), bad)
        assertEquals(PairLink(emptyList(), code), PairLink.parse("aeon://pair?u=http%3A%2F%2Fu%3Ap%40evil%3A1&u=file%3A%2F%2F%2Fetc&c=$code"))
    }
}
