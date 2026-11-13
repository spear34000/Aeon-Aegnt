package dev.aeon.ui.platform

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Node.js for Mobile in this process. The native library `aeonnode` (see androidApp/src/main/jni) starts Node on the
 * server that ships as an asset. It can be started once per process and then runs until the process ends.
 */
internal object NodeHost {
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
    private val started = AtomicBoolean(false)
    private const val ASSET_DIR = "nodejs-project"

    private fun context(): Context = AppContext.app
    private fun root() = File(context().filesDir, "agent")

    override val available: Boolean by lazy {
        NodeHost.loaded && runCatching { context().assets.list(ASSET_DIR)?.isNotEmpty() == true }.getOrDefault(false)
    }

    /** Copies the bundled server out of the APK when it is new or changed (Node reads real files). */
    private fun unpack(): File {
        val ctx = context()
        val project = File(root(), "project")
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val marker = File(project, ".stamp")
        if (marker.exists() && marker.readText() == stamp) return project
        project.deleteRecursively()
        copyAsset(ASSET_DIR, project)
        marker.writeText(stamp)
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

    override suspend fun start(): String? = withContext(Dispatchers.IO) {
        if (!available) return@withContext "이 앱에는 기기에서 실행하는 기능이 들어 있지 않아요."
        try {
            if (!answers() && started.compareAndSet(false, true)) {
                val project = unpack()
                val cfg = File(root(), "cfg").apply { mkdirs() }
                val ws = File(root(), "workspace").apply { mkdirs() }
                val tmp = context().cacheDir.absolutePath
                Thread({ NodeHost.start(arrayOf("node", File(project, "node/mobile-main.js").absolutePath, cfg.absolutePath, ws.absolutePath, "7421"), tmp) }, "aeon-node").start()
            }
            repeat(100) {
                if (answers()) return@withContext null
                delay(300)
            }
            "에이전트가 시작되지 않았어요. 앱을 다시 열어 보세요."
        } catch (e: Exception) {
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
