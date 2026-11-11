package dev.aeon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.DeviceInfo
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonSwitch
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Card
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.nowMillis
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.UiState

private fun platformName(p: String?): String = when (p) {
    "win32" -> "Windows"
    "linux" -> "Linux"
    "darwin" -> "macOS"
    else -> p ?: ""
}

/** 연결된 기기: every paired device, adding a new one from any of them, and which may change settings. */
@Composable
fun DevicesPage(state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    LaunchedEffect(Unit) { model.loadDevices() }
    val now = nowMillis()
    var revoke by remember { mutableStateOf<DeviceInfo?>(null) }
    var rename by remember { mutableStateOf<DeviceInfo?>(null) }
    var newName by remember { mutableStateOf("") }
    var newAdmin by remember { mutableStateOf(true) }
    val active = state.devices.filter { !it.revoked }

    Card {
        Text("Aeon이 실행 중인 기기", style = MaterialTheme.typography.titleMedium)
        val host = state.status?.host
        Text(listOfNotNull(host?.let { platformName(it.platform) + " " + it.arch }, state.serverUrl.removePrefix("http://").removePrefix("https://")).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = s.fg3)
        if (state.status?.addresses.isNullOrEmpty()) Text("지금은 이 기기에서만 접속할 수 있습니다. 폰이나 다른 컴퓨터를 연결하려면 서버를 serve --host 0.0.0.0 으로 시작하세요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
    }

    Text("연결된 기기 ${active.size}", style = MaterialTheme.typography.labelLarge, color = s.fg3)
    active.forEach { d ->
        val ago = (now - d.lastSeenAt) / 60_000
        Card {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (d.current) "이 기기 · 지금 접속 중" else if (ago < 2) "지금 접속 중" else "${ago}분 전 접속", style = MaterialTheme.typography.bodySmall, color = s.fg3)
                }
                Chip(if (d.admin) "설정 변경 가능" else "대화·승인만", if (d.admin) Tone.Accent else Tone.Neutral)
            }
            if (state.isAdmin) {
                AeonSwitch(d.admin, { model.setDeviceAdmin(d.id, it) }, "설정·키·연결 바꾸기 허용", enabled = !d.current)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AeonButton("이름 바꾸기", { rename = d; newName = d.name }, kind = ButtonKind.Secondary, wide = true)
                    AeonButton(if (d.current) "이 기기 연결 끊기" else "연결 끊기", { revoke = d }, kind = ButtonKind.Destructive, wide = true)
                }
            } else if (d.current) {
                AeonButton("이 기기 연결 끊기", { revoke = d }, kind = ButtonKind.Destructive, wide = true)
            }
        }
    }

    if (state.isAdmin) {
        Card {
            Text("새 기기 연결", style = MaterialTheme.typography.titleMedium)
            Text("Android, Windows(x64·ARM), Linux, 브라우저 어디서든 Aeon 앱을 열고 서버 주소와 아래 코드를 입력하세요. 코드는 5분 동안, 한 번만 쓸 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
            AeonSwitch(newAdmin, { newAdmin = it }, "이 기기가 설정·키·연결을 바꿀 수 있게 하기")
            AeonButton("연결 코드 만들기", { model.newPairingCode(newAdmin) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !state.busy)
        }
    }
    Text("끊은 기기는 다시 쓰려면 새 코드로 연결해야 합니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)

    state.newDevice?.let { n ->
        AlertDialog(
            onDismissRequest = model::dismissPairingCode,
            title = { Text("연결 코드") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer { Text(n.code.chunked(4).joinToString("-"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge) }
                    Text("서버 주소", style = MaterialTheme.typography.labelLarge)
                    if (n.addresses.isEmpty()) Text(state.serverUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    n.addresses.forEach { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                    Text("5분 안에 한 번만 쓸 수 있습니다. ${if (n.admin) "이 기기는 설정을 바꿀 수 있습니다." else "이 기기는 대화와 승인만 할 수 있습니다."}", style = MaterialTheme.typography.bodySmall, color = s.fg3)
                }
            },
            confirmButton = { TextButton(onClick = model::dismissPairingCode) { Text("닫기") } },
        )
    }
    rename?.let { d ->
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("기기 이름") },
            text = { AeonTextField(newName, { newName = it.take(64) }, "이름") },
            confirmButton = { TextButton(enabled = newName.isNotBlank(), onClick = { model.renameDevice(d.id, newName); rename = null }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("취소") } },
        )
    }
    revoke?.let { d ->
        AlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text("\"${d.name}\" 연결을 끊을까요?") },
            text = { Text(if (d.current) "이 기기에서 더는 Aeon에 요청할 수 없습니다. 다시 쓰려면 새 코드로 연결해야 합니다." else "이 기기에서 더는 요청할 수 없습니다.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { model.revokeDevice(d.id); revoke = null }) { Text("끊기", color = s.danger) } },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text("취소") } },
        )
    }
}
