package dev.aeon.ui.platform

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.aeon.ui.api.AeonClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun PlatformSystemBars(dark: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val bars = androidx.core.view.WindowCompat.getInsetsController(window, view)
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark
    }
}

private const val NOTIFICATIONS = "notifications"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Android has one runtime permission for this app: notifications (asked on Android 13+, a system switch before).
 * It is optional. Files are chosen through the system picker, which needs no permission.
 */
@Composable
actual fun rememberPermissionController(): PermissionController {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // After the system has answered once in this session, "denied and no rationale" means it will not ask again.
    var answered by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        answered = true
        tick++
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    return remember(context) {
        object : PermissionController {
            override val items = listOf(PermissionItem(NOTIFICATIONS, "알림", "승인이 필요한 일이 생기면 알려 드려요."))

            override fun status(id: String): PermissionStatus {
                tick // read so the caller recomposes after an answer
                val manager = context.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT < 33) return if (manager.areNotificationsEnabled()) PermissionStatus.Granted else PermissionStatus.Blocked
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return PermissionStatus.Granted
                val activity = context.findActivity()
                val blocked = answered && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                return if (blocked) PermissionStatus.Blocked else PermissionStatus.Denied
            }

            override fun request(id: String) {
                if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else openSettings()
            }

            override fun openSettings() {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                runCatching { settings.launch(intent) }
            }
        }
    }
}

actual val supportsBackgroundCheck: Boolean = true

private const val JOB_ID = 7421
private const val CHANNEL = "approvals"
private const val KEY_NOTIFIED = "notifiedApprovals"

/** Intent extra set by the approval notification: the app opens on 피드, where approvals are answered. */
const val EXTRA_OPEN = "aeon.open"

/**
 * About every 15 minutes (the shortest period Android allows), while there is a network, the system runs
 * [ApprovalCheckService]. Scheduled only when the owner agreed to it; cancelled when they turn it off or unpair.
 */
actual fun setBackgroundApprovalCheck(enabled: Boolean) {
    val context = AppContext.app
    val scheduler = context.getSystemService(JobScheduler::class.java)
    if (!enabled) {
        scheduler.cancel(JOB_ID)
        return
    }
    if (scheduler.getPendingJob(JOB_ID) != null) return
    val job = JobInfo.Builder(JOB_ID, ComponentName(context, ApprovalCheckService::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setPeriodic(15 * 60_000L)
        .build()
    scheduler.schedule(job)
}

/** Asks the server whether anything waits for approval and posts one notification when something new does. */
class ApprovalCheckService : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                checkApprovals(applicationContext)
            } catch (e: Exception) {
                // Server off, other network, or device removed: nothing to show; the next period tries again.
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return false
    }
}

private suspend fun checkApprovals(context: Context) {
    AppContext.init(context)
    val store = AndroidKeyValueStore()
    val url = store.get("serverUrl") ?: return
    val device = store.get("deviceId") ?: return
    val http = HttpClient()
    try {
        val client = AeonClient(url, AndroidDeviceCrypto(), http).also { it.deviceId = device }
        val pending = client.approvals("pending")
        val known = store.get(KEY_NOTIFIED)?.split(',')?.toSet().orEmpty()
        store.put(KEY_NOTIFIED, pending.joinToString(",") { it.id })
        if (pending.any { it.id !in known }) notifyApprovals(context, pending.size)
    } finally {
        http.close()
    }
}

private fun notifyApprovals(context: Context, count: Int) {
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "승인 요청", NotificationManager.IMPORTANCE_HIGH).apply { description = "에이전트가 실행 전에 묻는 일" })
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.putExtra(EXTRA_OPEN, "feed")
    val tap = open?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
    val icon = context.resources.getIdentifier("ic_stat_aeon", "drawable", context.packageName).takeIf { it != 0 } ?: android.R.drawable.stat_notify_more
    // The text says only that something waits: details stay inside the app, not on the lock screen.
    val notification = Notification.Builder(context, CHANNEL)
        .setSmallIcon(icon)
        .setContentTitle("승인이 필요한 일 ${count}건")
        .setContentText("Aeon을 열어 내용을 확인하세요.")
        .setAutoCancel(true)
        .setContentIntent(tap)
        .build()
    manager.notify(1, notification)
}
