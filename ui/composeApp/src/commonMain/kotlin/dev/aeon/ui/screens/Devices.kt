package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.DeviceInfo
import dev.aeon.ui.api.PairLink
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonSwitch
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.nowMillis
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.NewDevice
import dev.aeon.ui.state.UiState
import dev.aeon.ui.state.thisDevice
import kotlinx.coroutines.delay

private fun platformName(p: String?): String = when (p) {
    "win32" -> "Windows"
    "linux" -> "Linux"
    "darwin" -> "macOS"
    else -> p ?: ""
}

private fun deviceIcon(name: String): ImageVector = when {
    name.contains("Android", true) -> AeonIcons.Phone
    name.contains("Web", true) || name.contains("browser", true) -> AeonIcons.Globe
    else -> AeonIcons.Terminal
}

private fun lastSeen(d: DeviceInfo, now: Long): String {
    val min = (now - d.lastSeenAt) / 60_000
    return when {
        d.current -> "이 기기"
        min < 2 -> "지금 접속 중"
        min < 60 -> "${min}분 전"
        min < 24 * 60 -> "${min / 60}시간 전"
        else -> "${min / (24 * 60)}일 전"
    }
}

/** 연결된 기기: one row per device, a sheet for what can be changed, and a QR code to let another device in. */
@Composable
fun DevicesPage(state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    LaunchedEffect(Unit) { model.loadDevices() }
    val now = nowMillis()
    var picked by remember { mutableStateOf<DeviceInfo?>(null) }
    val active = state.devices.filter { !it.revoked }
    val host = state.status?.host

    if (host != null) {
        val where = "${platformName(host.platform)} ${host.arch}"
        val line = if (state.thisDevice) "이 기기에서 실행 중 · $where" else "다른 기기에서 실행 중 · $where · ${state.serverUrl.removePrefix("http://").removePrefix("https://")}"
        Text(line, style = MaterialTheme.typography.labelMedium, color = s.fg3)
    }
    ListSurface {
        active.forEachIndexed { i, d ->
            if (i > 0) RowDivider(72.dp)
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable { picked = d }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(deviceIcon(d.name), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(d.name.substringBefore(" (").ifBlank { d.name }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(lastSeen(d, now), style = MaterialTheme.typography.labelMedium, color = if (d.current || (now - d.lastSeenAt) < 120_000) s.ok else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!d.admin) Chip("대화·승인만", Tone.Neutral)
                Icon(AeonIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
    if (state.isAdmin) {
        AeonButton("새 기기 연결", { model.newPairingCode(true) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !state.busy)
    }

    state.newDevice?.let { n -> NewDeviceDialog(n, state, model) }
    picked?.let { d -> DeviceDialog(d, state, model) { picked = null } }
}

@Composable
private fun DeviceDialog(d: DeviceInfo, state: UiState, model: AppModel, onClose: () -> Unit) {
    val s = LocalAeonStatus.current
    var name by remember(d.id) { mutableStateOf(d.name) }
    var confirm by remember(d.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(d.name.substringBefore(" (").ifBlank { d.name }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.isAdmin) {
                    AeonTextField(name, { name = it.take(64) }, "이름")
                    AeonSwitch(d.admin, { model.setDeviceAdmin(d.id, it) }, "설정 변경 허용", enabled = !d.current)
                }
                if (confirm) Text(if (d.current) "이 기기에서 더는 요청할 수 없어요." else "이 기기에서 더는 요청할 수 없어요.", style = MaterialTheme.typography.bodyMedium, color = s.danger)
            }
        },
        confirmButton = {
            if (confirm) TextButton(onClick = { model.revokeDevice(d.id); onClose() }) { Text("끊기", color = s.danger) }
            else if (state.isAdmin) TextButton(enabled = name.isNotBlank() && name != d.name, onClick = { model.renameDevice(d.id, name); onClose() }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (!confirm && (state.isAdmin || d.current)) TextButton(onClick = { confirm = true }) { Text("연결 끊기", color = s.danger) }
                TextButton(onClick = onClose) { Text("닫기") }
            }
        },
    )
}

/**
 * The code for another device: a QR code for the phone's camera when the server can be reached from elsewhere, the
 * code itself to type otherwise, and a live countdown. The code is valid for five minutes and works once.
 */
@Composable
private fun NewDeviceDialog(n: NewDevice, state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    var now by remember(n.code) { mutableStateOf(nowMillis()) }
    LaunchedEffect(n.code) {
        while (true) {
            now = nowMillis()
            delay(1000)
        }
    }
    val left = ((n.expiresAt - now) / 1000).coerceAtLeast(0)
    val expired = left == 0L
    val link = remember(n.code, n.addresses) { if (n.addresses.isEmpty()) null else runCatching { PairLink.build(n.addresses, n.code) }.getOrNull() }
    AlertDialog(
        onDismissRequest = model::dismissPairingCode,
        title = { Text("새 기기 연결") },
        text = {
            Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (link != null && !expired) {
                    QrImage(link, Modifier.fillMaxWidth(), "연결 QR 코드")
                    Text("새 기기의 카메라로 스캔하세요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
                }
                SelectionContainer { Text(n.code.chunked(4).joinToString("-"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge, color = if (expired) s.fg3 else MaterialTheme.colorScheme.onSurface) }
                if (link == null) SelectionContainer { Text(n.addresses.firstOrNull() ?: state.serverUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = s.fg3) }
                // The bar drains with the five minutes, so the time left can be read without the numbers.
                val fraction by animateFloatAsState((left / 300f).coerceIn(0f, 1f), tween(1000, easing = LinearEasing), label = "codeLeft")
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(if (left < 60) s.danger else MaterialTheme.colorScheme.primary, CircleShape))
                }
                Text(if (expired) "만료됐어요" else "${left / 60}:${(left % 60).toString().padStart(2, '0')} 남음", style = MaterialTheme.typography.labelLarge, color = if (expired || left < 60) s.danger else s.fg3)
                AeonSwitch(n.admin, { model.newPairingCode(it) }, "설정 변경 허용")
            }
        },
        confirmButton = { TextButton(onClick = model::dismissPairingCode) { Text("닫기") } },
        dismissButton = { TextButton(onClick = { model.newPairingCode(n.admin) }) { Text("새 코드") } },
    )
}
