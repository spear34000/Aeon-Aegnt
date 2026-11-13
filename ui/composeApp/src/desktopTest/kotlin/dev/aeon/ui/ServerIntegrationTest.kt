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
        // AEON_NODE18 points at a Node 18 executable: the same tests then run against the compiled mobile bundle
        // (`npm run build:mobile`), which is what runs inside the Android app.
        val node18 = System.getenv("AEON_NODE18")
        val command = if (node18 != null) listOf(node18, File(root, "dist-mobile/node/cli.js").absolutePath) else listOf("node", File(root, "src/node/cli.ts").absolutePath)
        val pb = ProcessBuilder(command + listOf("serve", "--workspace", ws.absolutePath, "--port", "0"))
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
        Thread { runCatching { reader.forEachLine { if (System.getenv("AEON_DEBUG") != null) System.err.println("[server] $it") } } }.apply { isDaemon = true }.start()
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

    private suspend fun waitFor(timeoutMs: Long = 15_000, label: String = "", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            delay(50)
        }
        error("condition not reached in ${timeoutMs}ms: $label")
    }

    @Test
    fun sealedKeysAndPairingCodesWorkAgainstTheRealServer() = runBlocking {
        val fx = startAeon(startFakeModel(emptyList()))
        val (client, _) = newClient(fx.base)
        client.pair(fx.code, "kotlin test client")
        assertTrue(client.serverKey.isNotEmpty(), "the sealing key is pinned while pairing")
        assertTrue(client.status().sealing)

        // The key is encrypted here (ECDH + AES-GCM in Kotlin) and opened by the Node server.
        client.setSecret("AEON_EP_TEST_KEY", "sealed-value-0001")
        assertTrue("AEON_EP_TEST_KEY" in client.secretNames().names)
        assertFalse(File(fx.cfgDir, "config.json").readText().contains("sealed-value-0001"))

        // A code for another device comes back sealed by Node and is opened here.
        val (code, info) = client.newPairingCode(admin = false)
        assertTrue(code.matches(Regex("[A-Z2-7]{16}")), "got: $code")
        assertFalse(info.admin)
        val (second, _) = newClient(fx.base)
        second.pair(code, "second device")
        // This test talks to the server over loopback, where every caller counts as the machine itself, so the
        // limit is checked on the stored flag (test/models-and-devices.test.ts covers the refusals over a network).
        val devices = client.devices()
        assertEquals(listOf(true, false), devices.map { it.admin }, "the device was let in without the right to change settings")
        assertEquals(listOf(true, false), devices.map { it.current })
        val renamed = client.updateDevice(devices[1].id, name = "거실 태블릿", admin = true)
        assertEquals("거실 태블릿", renamed.name)
        assertTrue(renamed.admin)

        // An uploaded file lands inside the workspace.
        val up = client.upload("메모 1.txt", "hello".encodeToByteArray())
        assertTrue(up.path.startsWith("uploads/") && up.path.endsWith("메모 1.txt"), up.path)
        assertEquals("hello", File(fx.workspace, up.path).readText())
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

    /** A skill folder on this machine to install from (what the owner types as a path). */
    private fun skillSource(root: File): File {
        val dir = File(root, "src/tidy-notes").apply { File(this, "scripts").mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: tidy-notes\ndescription: Tidy messy notes. Use when notes are untidy.\n---\n\nRun scripts/tidy.py on the notes.\n")
        File(dir, "scripts/tidy.py").writeText("print('tidy')\n")
        return dir
    }

    @Test
    fun skillsAreInstalledReadApprovedAndSwitchedThroughTheRealServer() = runBlocking {
        val fx = startAeon(startFakeModel(emptyList()))
        val (client, _) = newClient(fx.base)
        client.pair(fx.code, "kotlin test client")
        val src = skillSource(fx.workspace.parentFile)

        val preview = client.inspectSkillSource(src.absolutePath)
        val c = preview.candidates.single()
        assertEquals("tidy-notes", c.name)
        assertTrue(c.scripts && c.scriptTexts.single().text.contains("print('tidy')"), "the script text is shown before installing")
        assertTrue(client.skills().skills.isEmpty(), "looking installs nothing")

        val installed = client.installSkill(preview.id, "tidy-notes", "user", null, false)
        assertEquals(listOf("user", "tracked", true, true), listOf(installed.scope, installed.status, installed.enabled, installed.active))
        assertTrue(installed.hash.length == 64 && installed.scripts)
        assertTrue(installed.autoRun && installed.safety?.level == "clean", "scripts that pass the check run in Docker without a question")
        assertEquals(false, client.setSkillAutoRun("tidy-notes", "user", false).autoRun)
        assertEquals(true, client.setSkillAutoRun("tidy-notes", "user", true).autoRun)
        assertEquals(409, assertFailsWith<AeonException> { client.installSkill(preview.id, "tidy-notes", "user", null, false) }.status, "the same name is not overwritten silently")
        assertEquals("print('tidy')\n", client.skillFile("tidy-notes", "user", "scripts/tidy.py").text)
        assertEquals(false, client.setSkillEnabled("tidy-notes", "user", false).active)
        assertEquals(true, client.setSkillEnabled("tidy-notes", "user", true).active)

        // A skill that merely appeared in the project is listed but never approved by itself.
        val dropped = File(fx.workspace, ".agents/skills/from-a-clone").apply { mkdirs() }
        File(dropped, "SKILL.md").writeText("---\nname: from-a-clone\ndescription: Arrived with a repository. Use never.\n---\n\nbody\n")
        val clone = client.skills().skills.single { it.folder == "from-a-clone" }
        assertEquals(listOf("project", "untracked", false), listOf(clone.scope, clone.status, clone.active))
        assertEquals(409, assertFailsWith<AeonException> { client.setSkillEnabled("from-a-clone", "project", true) }.status, "switching on is not approving")
        assertEquals(409, assertFailsWith<AeonException> { client.trustSkill("from-a-clone", "project", "0".repeat(64)) }.status, "a hash that was not shown is refused")
        assertEquals(true, client.trustSkill("from-a-clone", "project", clone.hash).active)
        client.deleteSkill("from-a-clone", "project")
        assertTrue(client.skills().skills.none { it.folder == "from-a-clone" })
    }

    @Test
    fun theSkillsManagerWalksFromASourceToAnInstalledSkill() = runBlocking {
        val fx = startAeon(startFakeModel(emptyList()))
        val (client, crypto) = newClient(fx.base)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        cleanup += { scope.cancel() }
        val map = mutableMapOf<String, String>()
        val store = object : dev.aeon.ui.platform.KeyValueStore {
            override fun get(key: String) = map[key]
            override fun put(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
        }
        val m = dev.aeon.ui.state.AppModel(client, store, crypto, scope, pollMillis = 60_000, defaultServerUrl = fx.base)
        m.pair(fx.base, fx.code)
        waitFor(label = "step 1") { m.state.value.connection is dev.aeon.ui.state.Connection.Connected }
        val src = skillSource(fx.workspace.parentFile)
        val sk = { m.state.value.skills }

        m.openSkills()
        waitFor(label = "step 2") { sk().loaded }
        assertEquals(dev.aeon.ui.state.SkillScreen.List, sk().screen)
        m.openAddSkill()
        m.changeSkillSource(src.absolutePath)
        m.inspectSkillSource()
        waitFor(label = "step 3") { sk().screen == dev.aeon.ui.state.SkillScreen.Review }
        assertEquals("tidy-notes", sk().add.pick, "one skill that follows the format goes straight to its review")

        m.installSkill()
        waitFor(label = "step 4") { sk().screen == dev.aeon.ui.state.SkillScreen.Detail }
        assertEquals("tracked", sk().detail?.skill?.status)

        // Installing it again clashes: the sheet opens with a new name suggested, and a new name installs next to it.
        m.openAddSkill(); m.changeSkillSource(src.absolutePath); m.inspectSkillSource()
        waitFor(label = "step 5") { sk().screen == dev.aeon.ui.state.SkillScreen.Review }
        m.installSkill()
        waitFor(label = "step 6") { sk().add.conflict }
        assertEquals("tidy-notes-2", sk().add.rename)
        m.installSkill(renamed = true)
        waitFor(label = "step 7") { sk().screen == dev.aeon.ui.state.SkillScreen.Detail && sk().detail?.skill?.folder == "tidy-notes-2" }

        // Reading a skill loads its files; an unapproved skill is approved with the hash that was shown.
        File(fx.workspace, ".agents/skills/dropped").mkdirs()
        File(fx.workspace, ".agents/skills/dropped/SKILL.md").writeText("---\nname: dropped\ndescription: Dropped here. Use never.\n---\n\nbody\n")
        m.openSkill("dropped", "project")
        waitFor(label = "step 8") { sk().screen == dev.aeon.ui.state.SkillScreen.Detail && sk().detail?.skill?.folder == "dropped" }
        assertEquals("untracked", sk().detail?.skill?.status)
        m.readSkill()
        waitFor(label = "step 9") { sk().read?.busy == false }
        m.trustSkill()
        waitFor(label = "step 10") { sk().detail?.skill?.status == "tracked" }
        assertEquals(dev.aeon.ui.state.SkillScreen.Detail, sk().screen)

        m.deleteSkill("dropped", "project")
        waitFor(label = "step 11") { sk().list.none { it.folder == "dropped" } }
        assertEquals(dev.aeon.ui.state.SkillScreen.List, sk().screen, "deleting returns to the list")
        m.skillsBack()
        assertEquals(null, sk().screen)
        m.stop()
    }
}
