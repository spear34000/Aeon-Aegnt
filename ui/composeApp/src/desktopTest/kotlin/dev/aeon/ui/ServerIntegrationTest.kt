package dev.aeon.ui

import com.sun.net.httpserver.HttpServer
import dev.aeon.ui.api.AeonClient
import dev.aeon.ui.api.AeonConfig
import dev.aeon.ui.api.AeonException
import dev.aeon.ui.api.TaskDetail
import dev.aeon.ui.platform.DesktopDeviceCrypto
import io.ktor.client.HttpClient
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end: this Kotlin client against the REAL Node server (`aeon serve`) over real HTTP.
 * Only the language model is faked (a tiny local OpenAI-compatible server) so the run is deterministic.
 */
class ServerIntegrationTest {
    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() = cleanup.reversed().forEach { runCatching(it) }

    private class Fixture(val base: String, val code: String, val workspace: File, val cfgDir: File)

    private fun startFakeModel(replies: List<String>): Int {
        val queue = ConcurrentLinkedQueue(replies)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.readBytes()
            val content = queue.poll() ?: """{"action":"finish","summary":"no more scripted replies"}"""
            val json = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":${kotlinx.serialization.json.JsonPrimitive(content)}}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5}}"
            val bytes = json.toByteArray()
            ex.responseHeaders.add("content-type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        cleanup += { server.stop(0) }
        return server.address.port
    }

    private fun startAeon(modelPort: Int): Fixture {
        val root = File(System.getProperty("aeon.repoRoot") ?: error("aeon.repoRoot not set"))
        val tmp = Files.createTempDirectory("aeon-it").toFile()
        val cfg = File(tmp, "cfg").apply { mkdirs() }
        val ws = File(tmp, "ws").apply { mkdirs() }
        File(cfg, "config.json").writeText(
            """{"providers":["openai-compatible"],"dataConsent":["openai-compatible"],"fallbackAllowed":false,"openaiCompatBaseUrl":"http://127.0.0.1:$modelPort/v1","openaiCompatModel":"m"}""",
        )
        val pb = ProcessBuilder("node", File(root, "src/node/cli.ts").absolutePath, "serve", "--workspace", ws.absolutePath, "--port", "0")
            .directory(root)
            .redirectErrorStream(true)
        pb.environment()["AEON_CONFIG_DIR"] = cfg.absolutePath
        pb.environment()["OPENAI_COMPAT_API_KEY"] = "test-key-123456"
        pb.environment()["EXAONE_API_KEY"] = ""
        val proc = pb.start()
        cleanup += { proc.destroyForcibly().waitFor(5, TimeUnit.SECONDS); tmp.deleteRecursively() }

        val lines = mutableListOf<String>()
        val reader = proc.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + 30_000
        var port: Int? = null
        var code: String? = null
        while (System.currentTimeMillis() < deadline && (port == null || code == null)) {
            if (!reader.ready()) { Thread.sleep(50); continue }
            val line = reader.readLine() ?: break
            lines += line
            Regex("listening on http://127\\.0\\.0\\.1:(\\d+)").find(line)?.let { port = it.groupValues[1].toInt() }
            if (line.trim().matches(Regex("[A-Z2-7]{4}(-[A-Z2-7]{4}){3}"))) code = line.trim()
        }
        // Keep draining so the child never blocks on a full pipe.
        Thread { runCatching { reader.forEachLine { } } }.apply { isDaemon = true }.start()
        return Fixture("http://127.0.0.1:${port ?: error("server did not start:\n${lines.joinToString("\n")}")}", code ?: error("no pairing code:\n${lines.joinToString("\n")}"), ws, cfg)
    }

    private fun newClient(base: String): Pair<AeonClient, DesktopDeviceCrypto> {
        val dir = Files.createTempDirectory("aeon-ui-dev").toFile()
        cleanup += { dir.deleteRecursively() }
        val crypto = DesktopDeviceCrypto(dir)
        val http = HttpClient()
        cleanup += { http.close() }
        return AeonClient(base, crypto, http) to crypto
    }

    private suspend fun awaitTask(c: AeonClient, id: String, vararg statuses: String, timeoutMs: Long = 20_000): TaskDetail {
        val end = System.currentTimeMillis() + timeoutMs
        var last: TaskDetail? = null
        while (System.currentTimeMillis() < end) {
            last = c.task(id)
            if (last.status in statuses) return last
            delay(150)
        }
        error("task $id never reached ${statuses.toList()}; last=${last?.status} ${last?.error}")
    }

    @Test
    fun pairsSignsRequestsAndRejectsStrangers() = runBlocking {
        val fx = startAeon(startFakeModel(emptyList()))
        val (client, _) = newClient(fx.base)

        assertEquals(1, client.hello().api)
        assertFailsWith<IllegalStateException> { client.status() } // refuses to send unsigned requests

        val wrong = newClient(fx.base).first
        assertEquals(403, assertFailsWith<AeonException> { wrong.pair("AAAAA-AAAAA-AAAAA-AAAAA", "intruder") }.status)

        val res = client.pair(fx.code, "kotlin test client")
        assertTrue(res.deviceId.startsWith("dev_"))
        val status = client.status()
        assertEquals(res.deviceId, status.device)
        assertTrue(client.tasks().isEmpty())

        // The same code cannot be used twice.
        assertEquals(403, assertFailsWith<AeonException> { newClient(fx.base).first.pair(fx.code, "second device") }.status)

        // A different key claiming this device id is rejected: the signature really is verified.
        val impostor = newClient(fx.base).first.also { it.deviceId = res.deviceId }
        assertEquals(401, assertFailsWith<AeonException> { impostor.status() }.status)
    }

    @Test
    fun runsATaskThroughApprovalAndVerifiesTheRealFile() = runBlocking {
        val reply = { s: String -> s }
        val port = startFakeModel(
            listOf(
                reply("""{"plan":["delete old.txt"],"criteria":[]}"""),
                reply("""{"action":"tool","tool":"fs.delete","args":{"path":"old.txt"}}"""),
                reply("""{"action":"finish","summary":"deleted old.txt"}"""),
            ),
        )
        val fx = startAeon(port)
        File(fx.workspace, "old.txt").writeText("bye")
        val (client, _) = newClient(fx.base)
        client.pair(fx.code, "kotlin test client")

        val created = client.createTask("delete old.txt", listOf(dev.aeon.ui.api.parseCheck("file_exists:keep.txt")!!))
        File(fx.workspace, "keep.txt").writeText("stay")
        val waiting = awaitTask(client, created.id, "WAITING_FOR_APPROVAL")
        assertTrue(File(fx.workspace, "old.txt").exists(), "nothing deleted before approval")
        val approvals = client.approvals("pending")
        assertEquals(1, approvals.size)
        assertTrue(approvals[0].summary.contains("DELETE file"), approvals[0].summary)
        assertEquals(waiting.pendingApprovalId, approvals[0].id)

        client.decide(approvals[0].id, approve = true)
        val done = awaitTask(client, created.id, "COMPLETED", "FAILED")
        assertEquals("COMPLETED", done.status, done.error ?: "")
        assertFalse(File(fx.workspace, "old.txt").exists(), "the approved deletion happened")
        assertEquals("verified", done.verificationDetail?.status)
        assertTrue(done.steps.any { it.tool == "fs.delete" && it.ok })

        assertEquals(409, assertFailsWith<AeonException> { client.decide(approvals[0].id, approve = false) }.status, "an approval can be decided only once")
        assertTrue(client.usage().isNotEmpty())
    }

    @Test
    fun aFailedOrMaliciousPairingAttemptNeverRedirectsTheExistingSession() = runBlocking {
        val fx = startAeon(startFakeModel(emptyList()))
        val crypto = DesktopDeviceCrypto(Files.createTempDirectory("aeon-ui-model").toFile().also { d -> cleanup += { d.deleteRecursively() } })
        val http = HttpClient().also { h -> cleanup += { h.close() } }
        val client = AeonClient(fx.base, crypto, http)
        val store = object : dev.aeon.ui.platform.KeyValueStore {
            val m = mutableMapOf<String, String>()
            override fun get(key: String) = m[key]
            override fun put(key: String, value: String) { m[key] = value }
            override fun remove(key: String) { m.remove(key) }
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        cleanup += { scope.cancel() }
        val model = dev.aeon.ui.state.AppModel(client, store, crypto, scope, pollMillis = 100)
        model.pair(fx.base, fx.code)
        waitFor { model.state.value.connection is dev.aeon.ui.state.Connection.Connected && model.state.value.status != null }
        val pairedDevice = client.deviceId
        assertTrue(pairedDevice.startsWith("dev_"))

        // A hostile "server" that answers the pairing request with a bogus proof, and records every request header it sees.
        val seen = ConcurrentLinkedQueue<String>()
        val evil = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        evil.createContext("/") { ex ->
            seen += ex.requestURI.path + " signed=" + (ex.requestHeaders.getFirst("x-aeon-signature") != null)
            ex.requestBody.readBytes()
            val body = """{"deviceId":"dev_evil","serverProof":"AAAA","serverName":"Evil"}""".toByteArray()
            ex.sendResponseHeaders(201, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        evil.start()
        cleanup += { evil.stop(0) }

        model.pair("http://127.0.0.1:${evil.address.port}", "AAAAA-AAAAA-AAAAA-AAAAA")
        waitFor { !model.state.value.busy && model.state.value.message != null }
        assertTrue(model.state.value.message!!.contains("could not prove"), model.state.value.message ?: "")
        assertEquals(fx.base, client.baseUrl, "the real server address is restored")
        assertEquals(pairedDevice, client.deviceId, "the real identity is restored")
        assertEquals(fx.base, store.get("serverUrl"))

        // Session still works, and the hostile server only ever saw the unsigned pairing request.
        waitFor { model.state.value.connection is dev.aeon.ui.state.Connection.Connected }
        delay(400)
        assertEquals(listOf("/v1/pair signed=false"), seen.toList(), "no signed request ever left for the untrusted address")
        assertEquals(pairedDevice, client.status().device)

        // Pairing to an address that is simply down also keeps the session.
        model.pair("http://127.0.0.1:1", "AAAAA-AAAAA-AAAAA-AAAAA")
        waitFor { !model.state.value.busy && model.state.value.message != null }
        assertEquals(fx.base, client.baseUrl)
        model.stop()
    }

    private suspend fun waitFor(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            delay(50)
        }
        error("condition not reached in ${timeoutMs}ms")
    }

    @Test
    fun settingsAndSecretsBehaveAsDocumented() = runBlocking {
        val fx = startAeon(startFakeModel(emptyList()))
        val (client, _) = newClient(fx.base)
        client.pair(fx.code, "kotlin test client")

        val cfg = client.config()
        assertEquals(listOf("openai-compatible"), cfg.providers)
        val saved = client.saveConfig(cfg.copy(ollamaModel = "llama-x"))
        assertEquals("llama-x", saved.ollamaModel)
        assertEquals(400, assertFailsWith<AeonException> { client.saveConfig(AeonConfig(providers = listOf("skynet"))) }.status)

        client.setSecret("OPENAI_COMPAT_API_KEY", "super-secret-value-0001")
        val names = client.secretNames()
        assertTrue("OPENAI_COMPAT_API_KEY" in names.names)
        assertFalse(names.toString().contains("super-secret-value-0001"), "values are never returned")
        client.deleteSecret("OPENAI_COMPAT_API_KEY")

        val devices = client.devices()
        assertEquals(1, devices.size)
        assertFalse(devices[0].revoked)
        // Revoke ourselves: the next call must fail with 401.
        client.revokeDevice(devices[0].id)
        assertEquals(401, assertFailsWith<AeonException> { client.status() }.status)
    }
}
