package dev.aeon.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * When the app was closed by an error, the next start shows what happened in a window the owner can copy from:
 * the Java crash saved by the handler below, the reason Android gives for the last exit (this also covers native
 * crashes inside Node, which no Java handler can see) and the app's own recent log lines.
 */
object CrashReport {
    private const val FILE = "crash.log"
    private const val PREFS = "crash-report"
    private const val SEEN = "seen"
    private const val MARKER = "foreground.marker"

    /** True when the previous run ended while the app was on screen (read once, when the process starts). */
    private var previousRunDied = false

    /** The marker exists only while the app is on screen: it is written on start and removed on stop. */
    fun setForeground(context: Context, on: Boolean) {
        val f = File(context.filesDir, MARKER)
        runCatching { if (on) f.writeText(System.currentTimeMillis().toString()) else f.delete() }
    }

    /** Saves any uncaught Java error to a file before the process ends, then lets the system carry on as usual. */
    fun install(context: Context) {
        File(context.filesDir, MARKER).let {
            previousRunDied = it.exists()
            it.delete()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(context.filesDir, FILE).writeText(
                    "${stamp(System.currentTimeMillis())} thread=${thread.name}\n${error.stackTraceToString()}",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF (앱 안에서 exit() 호출)"
        else -> "OTHER($r)"
    }

    /** Only an exit the owner asked for is left out; the process calling exit() itself (Node does) must show up too. */
    private val notReported = setOf(ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED, ApplicationExitInfo.REASON_PERMISSION_CHANGE)

    /** The text to show, or null when the last run ended normally. Each exit is reported once. */
    fun collect(context: Context): String? {
        val parts = mutableListOf<String>()
        val file = File(context.filesDir, FILE)
        if (file.exists()) {
            runCatching { file.readText() }.getOrNull()?.let { parts += "[Java 오류]\n$it" }
            file.delete()
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(SEEN, 0)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5).filter { it.timestamp > seen }
            exits.filter { it.reason !in notReported }.forEach {
                parts += "[종료 이유] ${stamp(it.timestamp)} ${it.processName} ${reasonName(it.reason)} status=${it.status}" +
                    (if (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_SIGNALED) " (신호 번호: 11=SEGV 메모리 오류, 6=ABRT 강제 종료, 9=KILL)" else "") +
                    "\n${it.description.orEmpty()}"
            }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(SEEN, it).apply() }
        }
        runCatching { File(context.filesDir, "agent/cfg/node-error.log").takeIf { it.exists() }?.readText()?.takeLast(3000) }.getOrNull()?.let { parts += "[에이전트(Node) 오류 기록]\n$it" }
        if (previousRunDied) parts += "[감지] 앱이 화면에 떠 있는 동안 종료됐어요."
        if (parts.isEmpty()) return null
        parts += "[앱 로그]\n" + recentLog()
        return "Aeon ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()} " +
            "· Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.firstOrNull()}\n\n" + parts.joinToString("\n\n")
    }

    /** The app's own log lines (an app may read its own): Node's output, Java errors and native crash notes. */
    private fun recentLog(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "1500", "-v", "time", "AeonNode:V", "*:F").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(3, TimeUnit.SECONDS)
        text.lines().takeLast(250).joinToString("\n").ifBlank { "(로그 없음)" }
    }.getOrElse { "(로그를 읽지 못했어요: ${it.message})" }

    /** Shown over the app on the first start after an error. */
    @Composable
    fun Dialog() {
        val context = LocalContext.current
        var text by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { text = withContext(Dispatchers.IO) { runCatching { collect(context) }.getOrNull() } }
        val shown = text ?: return
        AlertDialog(
            onDismissRequest = { text = null },
            title = { Text("앱이 오류로 종료됐어요") },
            text = {
                Column {
                    Text("아래 내용을 복사해서 보내 주세요.", fontSize = 13.sp)
                    SelectionContainer {
                        Text(
                            shown, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton({
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Aeon log", shown))
                    Toast.makeText(context, "복사했어요", Toast.LENGTH_SHORT).show()
                }) { Text("복사") }
            },
            dismissButton = { TextButton({ text = null }) { Text("닫기") } },
        )
    }
}
