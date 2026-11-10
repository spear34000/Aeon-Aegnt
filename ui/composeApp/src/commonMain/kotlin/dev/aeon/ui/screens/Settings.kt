package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.AeonConfig
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.AeonSwitch
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Card
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.SectionLabel
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.formatDateTime
import dev.aeon.ui.platform.nowMillis
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.SettingsPage
import dev.aeon.ui.state.UiState

/** Provider ids the app knows, with the key name each one needs (empty = local, no key). */
private data class ProviderRow(val id: String, val title: String, val keyName: String?, val external: Boolean)

private val PROVIDERS = listOf(
    ProviderRow("exaone", "EXAONE (LG AI연구원 API)", "EXAONE_API_KEY", external = true),
    ProviderRow("openai-compatible", "OpenAI 호환 API", "OPENAI_COMPAT_API_KEY", external = true),
    ProviderRow("ollama", "로컬 · Ollama", null, external = false),
    ProviderRow("llamacpp", "로컬 · llama.cpp", null, external = false),
)

@Composable
fun SettingsScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { model.loadSettings() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("설정", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsPage.entries.forEach { p ->
                val label = when (p) {
                    SettingsPage.Models -> "모델"
                    SettingsPage.Keys -> "API 키"
                    SettingsPage.Devices -> "기기"
                    SettingsPage.Usage -> "사용량"
                    SettingsPage.About -> "정보"
                }
                AeonButton(label, { model.openSettings(p) }, kind = if (p == state.settingsPage) ButtonKind.Primary else ButtonKind.Ghost, wide = true)
            }
        }
        when (state.settingsPage) {
            SettingsPage.Models -> ModelsPage(state, model)
            SettingsPage.Keys -> KeysPage(state, model)
            SettingsPage.Devices -> DevicesPage(state, model)
            SettingsPage.Usage -> UsagePage(state)
            SettingsPage.About -> AboutPage(state, model)
        }
    }
}

@Composable
private fun ModelsPage(state: UiState, model: AppModel) {
    val draft = state.draft ?: state.config ?: AeonConfig()
    var consentDialog by remember { mutableStateOf(false) }
    val s = LocalAeonStatus.current
    val keyNames = state.secretNames.toSet()

    SectionLabel("제공업체 순서")
    Text("위에서부터 씁니다. 화살표로 순서를 바꾸세요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
    val order = draft.providers
    PROVIDERS.filter { it.id in order }.sortedBy { order.indexOf(it.id) }.forEach { p ->
        val idx = order.indexOf(p.id)
        val status = when {
            !p.external -> "이 PC 안에서 처리 · 외부 전송 없음"
            p.keyName != null && p.keyName in keyNames -> "키 저장됨 · 외부 전송"
            else -> "키 없음 · 건너뜀"
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${idx + 1}. ${p.title}", style = MaterialTheme.typography.bodyLarge)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = s.fg3)
                }
                AeonButton("↑", { model.editDraft { c -> c.copy(providers = swap(c.providers, idx, idx - 1)) } }, kind = ButtonKind.Ghost, enabled = idx > 0, wide = true)
                AeonButton("↓", { model.editDraft { c -> c.copy(providers = swap(c.providers, idx, idx + 1)) } }, kind = ButtonKind.Ghost, enabled = idx < order.size - 1, wide = true)
                AeonButton("빼기", { model.editDraft { c -> c.copy(providers = c.providers - p.id, dataConsent = c.dataConsent - p.id) } }, kind = ButtonKind.Ghost, wide = true)
            }
        }
    }
    val missing = PROVIDERS.filter { it.id !in order }
    if (missing.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            missing.forEach { p -> AeonButton("+ ${p.title}", { model.editDraft { c -> c.copy(providers = c.providers + p.id) } }, kind = ButtonKind.Secondary, wide = true) }
        }
    }

    SectionLabel("외부 전송 · 대체")
    val exConsent = draft.dataConsent.isNotEmpty()
    AeonSwitch(
        on = exConsent,
        onChange = { on ->
            if (on) consentDialog = true
            else model.editDraft { c -> c.copy(dataConsent = emptyList()) }
        },
        label = "외부 전송 동의",
    )
    Text("대화와 파일 내용을 클라우드 모델로 보낼 수 있습니다. 끄면 로컬 모델만 씁니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
    AeonSwitch(
        on = draft.fallbackAllowed,
        onChange = { on -> model.editDraft { c -> c.copy(fallbackAllowed = on) } },
        label = "대체 모델 허용",
    )
    Text("1순위가 한도에 걸리면 다음 순서로 바로 넘어갑니다. 끄면 기다립니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)

    AeonTextField(
        value = draft.ollamaModel.orEmpty(),
        onValueChange = { v -> model.editDraft { c -> c.copy(ollamaModel = v.ifBlank { null }) } },
        label = "Ollama 모델 이름",
        placeholder = "예: exaone3.5:7.8b",
        help = "로컬 모델을 쓸 때만 필요합니다.",
        mono = true,
    )

    if (consentDialog) {
        AlertDialog(
            onDismissRequest = { consentDialog = false },
            title = { Text("외부 전송에 동의할까요?") },
            text = { Text("대화와 파일 내용이 클라우드 모델 서비스로 전송됩니다. 언제든 끌 수 있습니다.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    consentDialog = false
                    model.editDraft { c -> c.copy(dataConsent = c.providers.filter { id -> PROVIDERS.firstOrNull { it.id == id }?.external == true }) }
                }) { Text("동의함", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { consentDialog = false }) { Text("취소") } },
        )
    }

    if (state.dirtyCount > 0) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(AeonShape.CARD.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("저장하지 않은 변경 ${state.dirtyCount}개", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            AeonButton("되돌리기", { model.revertDraft() }, kind = ButtonKind.Ghost, wide = true)
            AeonButton("저장", { model.saveDraft() }, kind = ButtonKind.Primary, wide = true, enabled = !state.busy)
        }
    }
}

private fun <T> swap(list: List<T>, a: Int, b: Int): List<T> {
    if (b < 0 || b >= list.size) return list
    val m = list.toMutableList()
    val t = m[a]; m[a] = m[b]; m[b] = t
    return m
}

@Composable
private fun KeysPage(state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    Text("키는 저장 후 다시 보이지 않습니다. 저장 위치: ${state.secretsBackend.ifBlank { "이 PC" }}", style = MaterialTheme.typography.bodySmall, color = s.fg3)
    PROVIDERS.filter { it.keyName != null }.forEach { p -> KeyRow(p.title, p.keyName!!, state.secretNames.contains(p.keyName), model) }
    Text("Anthropic과 Google 연동은 아직 없습니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
}

@Composable
private fun KeyRow(title: String, name: String, stored: Boolean, model: AppModel) {
    val s = LocalAeonStatus.current
    var value by remember(name) { mutableStateOf("") }
    var reveal by remember(name) { mutableStateOf(false) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (stored) Chip("저장됨", Tone.Ok, "🔒") else Chip("비어 있음", Tone.Neutral, "○")
        }
        if (stored) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•••••••• 값은 다시 보이지 않습니다", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = s.fg3)
                AeonButton("삭제", { model.deleteSecret(name) }, kind = ButtonKind.Destructive, wide = true)
            }
        }
        AeonTextField(
            value = value,
            onValueChange = { value = it },
            label = if (stored) "새 키로 바꾸기" else "키 붙여 넣기",
            placeholder = "예: sk-로 시작하는 키",
            help = "Windows는 DPAPI로 이 PC에만 암호화해 저장합니다.",
            secret = !reveal,
            mono = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(AeonShape.CONTROL.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .pointerInput(name) {
                        detectTapGestures(onPress = {
                            reveal = true
                            tryAwaitRelease()
                            reveal = false
                        })
                    },
            ) { Text("보기 (누르는 동안)", style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.weight(1f))
            AeonButton(if (stored) "교체" else "저장", { model.setSecret(name, value); value = "" }, kind = ButtonKind.Primary, enabled = value.isNotBlank(), wide = true)
        }
    }
}

@Composable
private fun DevicesPage(state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    LaunchedEffect(Unit) { model.openSettings(SettingsPage.Devices) }
    val now = nowMillis()
    Text("연결된 기기 ${state.devices.count { !it.revoked }}", style = MaterialTheme.typography.bodyMedium)
    var confirm by remember { mutableStateOf<dev.aeon.ui.api.DeviceInfo?>(null) }
    state.devices.filter { !it.revoked }.forEach { d ->
        val ago = (now - d.lastSeenAt) / 60_000
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(d.name, style = MaterialTheme.typography.bodyLarge)
                    Text(if (ago < 2) "지금 접속 중" else "${ago}분 전 접속 · ${formatDateTime(d.createdAt).take(10)} 연결", style = MaterialTheme.typography.bodySmall, color = s.fg3)
                }
                AeonButton("폐기", { confirm = d }, kind = ButtonKind.Destructive, wide = true)
            }
        }
    }
    Text("폐기하면 이 기기는 다시 연결할 수 없습니다. 다시 쓰려면 새 코드로 연결해야 합니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
    confirm?.let { d ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("\"${d.name}\" 을 폐기할까요?") },
            text = { Text("이 기기에서 더는 요청할 수 없습니다.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { model.revokeDevice(d.id); confirm = null }) { Text("폐기", color = s.danger) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun UsagePage(state: UiState) {
    val s = LocalAeonStatus.current
    val ok = state.usage.filter { it.ok }
    Card {
        Text("모델 호출", style = MaterialTheme.typography.titleMedium)
        Text("${ok.size}번 · 입력+출력 ${ok.sumOf { it.inputTokens + it.outputTokens }} 토큰", style = MaterialTheme.typography.bodyLarge)
        Text("추론 토큰 ${ok.sumOf { it.reasoningTokens }}", style = MaterialTheme.typography.bodySmall, color = s.fg3)
    }
    if (state.usage.isEmpty()) Text("아직 모델을 호출하지 않았습니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
}

@Composable
private fun AboutPage(state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    Card {
        Text("서버", style = MaterialTheme.typography.titleMedium)
        Text(state.serverUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        state.status?.let { Text("작업 ${state.tasks.size}개 · 키 저장 위치: ${it.secretsBackend}", style = MaterialTheme.typography.bodySmall, color = s.fg3) }
    }
    state.status?.setupProblem?.let { Banner("설정 필요: $it", Tone.Warn, "•") }
    AeonButton("이 기기 연결 해제", { model.unpair() }, kind = ButtonKind.Secondary, modifier = Modifier.widthIn(min = 200.dp))
    Text("연결 해제는 이 기기에서만 끊습니다. 서버의 기기 목록에서 폐기하려면 기기 탭을 쓰세요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
}
