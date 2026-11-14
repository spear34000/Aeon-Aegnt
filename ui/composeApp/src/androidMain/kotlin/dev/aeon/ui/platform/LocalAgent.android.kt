package dev.aeon.ui.platform

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Node.js for Mobile in this process. The native library `aeonnode` (see androidApp/src/main/jni) starts Node on the
 * server that ships as an asset. It can be started once per process and then runs until the process ends.
 */
object NodeHost {
    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("aeonnode")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /** Blocks until Node exits: run it on its own thread. */
    external fun start(args: Array<String>, tmpDir: String): Int
}

private object AndroidLocalAgent : LocalAgentHost {
    private const val ASSET_DIR = "nodejs-project"

    private fun context(): Context = AppContext.app
    private fun root() = File(context().filesDir, "agent")

    override val available: Boolean by lazy {
        NodeHost.loaded && runCatching { context().assets.list(ASSET_DIR)?.isNotEmpty() == true }.getOrDefault(false)
    }

    /** Copies the bundled server out of the APK when it is new or changed (Node reads real files). */
    private fun unpack(say: (Int, String) -> Unit): File {
        val ctx = context()
        val project = File(root(), "project")
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val marker = File(project, ".stamp")
        if (marker.exists() && marker.readText() == stamp) {
            say(0, "에이전트 파일이 이미 준비돼 있어요")
            return project
        }
        say(0, "처음이라 에이전트 파일을 풀어요")
        project.deleteRecursively()
        copyAsset(ASSET_DIR, project)
        marker.writeText(stamp)
        say(0, "파일을 풀었어요 (${project.walkTopDown().count { it.isFile }}개)")
        return project
    }

    private fun copyAsset(path: String, to: File) {
        val children = context().assets.list(path).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            context().assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
        } else {
            to.mkdirs()
            children.forEach { copyAsset("$path/$it", File(to, it)) }
        }
    }

    /** One plain HTTP request to the loopback server. A raw socket on purpose: the app does not allow cleartext through the system HTTP stack. */
    private fun answers(): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", 7421), 400)
            s.soTimeout = 800
            s.getOutputStream().apply { write("GET /v1/hello HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray()); flush() }
            String(s.getInputStream().readBytes()).contains("\"api\"")
        }
    } catch (e: Exception) {
        false
    }

    override var onProgress: ((Int, String) -> Unit)? = null

    private fun say(stage: Int, line: String) {
        android.util.Log.i("AeonApp", line) // not "AeonNode": the node lines are read back from that tag and this would repeat
        onProgress?.invoke(stage, line)
    }

    /** The agent's process (":node"), or null while it is not running. */
    private fun nodeProcess(): android.app.ActivityManager.RunningAppProcessInfo? = runCatching {
        val am = context().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.runningAppProcesses?.firstOrNull { it.processName == "${context().packageName}:node" }
    }.getOrNull()

    /**
     * What Node printed (and the native bridge and the service logged) since [token] was written to the log, one clean line
     * each, only those not returned by an earlier call ([emitted] counts them). It does not depend on knowing the process,
     * which may already be gone.
     */
    private fun nodeOutput(token: String, emitted: IntArray): List<String> = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "brief", "AeonNode:V", "*:S").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        val mine = lines.drop(lines.indexOfLast { it.contains(token) } + 1).filter { it.isNotBlank() && !it.startsWith("---") }
            .map { it.replace(Regex("^[A-Z]/AeonNode\\(\\s*\\d+\\):\\s?"), "node: ") }
        val fresh = mine.drop(emitted[0])
        emitted[0] = mine.size
        fresh
    }.getOrDefault(emptyList())

    private fun errorLog(): List<String> =
        runCatching { File(File(root(), "cfg"), "node-error.log").readLines().filter { it.isNotBlank() }.takeLast(12) }.getOrDefault(emptyList())

    /** Why Android says the agent's process ended, when it can tell (Android 11 and later). */
    private fun exitReason(since: Long): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 30) return@runCatching null
        val am = context().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getHistoricalProcessExitReasons(context().packageName, 0, 5)
            .firstOrNull { it.processName.endsWith(":node") && it.timestamp >= since }
            ?.let { "종료 이유: reason=${it.reason} status=${it.status} ${it.description.orEmpty()}" }
    }.getOrNull()

    override suspend fun start(): String? = withContext(Dispatchers.IO) {
        if (!available) return@withContext "이 앱에는 기기에서 실행하는 기능이 들어 있지 않아요."
        try {
            if (answers()) {
                say(2, "에이전트가 이미 켜져 있어요")
                return@withContext null
            }
            val began = System.currentTimeMillis()
            val token = "begin-${System.nanoTime()}"
            android.util.Log.i("AeonNode", token)
            say(0, "에이전트 파일을 확인해요")
            val project = unpack(::say)
            val cfg = File(root(), "cfg").apply { mkdirs() }
            val ws = File(root(), "workspace").apply { mkdirs() }
            val tmp = context().cacheDir.absolutePath
            File(cfg, "node-error.log").delete()
            say(1, "에이전트 프로세스를 시작해요 (Node 18, ${android.os.Build.SUPPORTED_ABIS.firstOrNull()})")
            // Node runs in its own process (":node"): when it exits or crashes, only that process goes, not the app.
            context().startService(
                android.content.Intent().setClassName(context().packageName, "dev.aeon.app.NodeService")
                    .putExtra("args", arrayOf("node", File(project, "node/mobile-main.js").absolutePath, cfg.absolutePath, ws.absolutePath, "7421"))
                    .putExtra("tmp", tmp),
            )
            say(2, "응답을 기다려요 (최대 30초)")
            val emitted = IntArray(1)
            var reportedAlive = false
            repeat(100) { i ->
                if (answers()) {
                    nodeOutput(token, emitted).forEach { l -> say(2, l) }
                    say(2, "에이전트가 응답했어요")
                    return@withContext null
                }
                if (i % 3 == 2) {
                    val lines = nodeOutput(token, emitted)
                    lines.forEach { l -> say(2, l) }
                    val proc = nodeProcess()
                    if (proc != null && !reportedAlive) { reportedAlive = true; say(2, "에이전트 프로세스가 켜졌어요 (pid ${proc.pid})") }
                    // Node returned (the service logs it first), or the process is gone, or it never showed up.
                    val ended = lines.any { it.contains("node returned") || it.contains("node stopped") }
                    val gone = (reportedAlive && proc == null) || (!reportedAlive && i >= 20)
                    if (ended || gone) {
                        errorLog().forEach { l -> say(2, "node-error.log: $l") }
                        exitReason(began)?.let { say(2, it) }
                        return@withContext "에이전트가 시작하자마자 멈췄어요. 아래 로그를 복사해서 알려 주세요."
                    }
                }
                delay(300)
            }
            errorLog().forEach { l -> say(2, "node-error.log: $l") }
            "30초 안에 에이전트가 응답하지 않았어요. 아래 로그를 복사해서 알려 주세요."
        } catch (e: Throwable) {
            say(2, "예외: ${e.message}")
            "에이전트를 시작하지 못했어요: ${e.message}"
        }
    }

    override suspend fun pairingCode(): String? = withContext(Dispatchers.IO) {
        val cfg = File(root(), "cfg")
        val code = File(cfg, "pairing-code.txt")
        code.delete()
        File(cfg, "pairing-code.txt.request").writeText("1")
        repeat(60) {
            delay(150)
            val text = runCatching { code.readText().trim() }.getOrDefault("")
            if (text.length == 16) return@withContext text
        }
        null
    }
}

actual fun localAgentHost(): LocalAgentHost = AndroidLocalAgent
