package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
fun SettingsScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier, title: String = "설정") {
    LaunchedEffect(Unit) { model.loadSettings() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsPage.entries.forEach { p ->
                val label = when (p) {
                    SettingsPage.Personal -> "개인화"
                    SettingsPage.Models -> "모델"
                    SettingsPage.Keys -> "API 키"
                    SettingsPage.Devices -> "기기"
                    SettingsPage.Usage -> "사용량"
                    SettingsPage.Legal -> "약관·권한"
                    SettingsPage.About -> "정보"
                }
                AeonButton(label, { model.openSettings(p) }, Modifier.widthIn(min = 64.dp), kind = if (p == state.settingsPage) ButtonKind.Primary else ButtonKind.Ghost, wide = true)
            }
        }
        when (state.settingsPage) {
            SettingsPage.Personal -> PersonalPage(state, model)
            SettingsPage.Models -> ModelsPage(state, model)
            SettingsPage.Keys -> KeysPage(state, model)
            SettingsPage.Devices -> DevicesPage(state, model)
            SettingsPage.Usage -> UsagePage(state)
            SettingsPage.Legal -> LegalPage(state, model)
            SettingsPage.About -> AboutPage(state, model)
        }
    }
}

@Composable
private fun ModelsPage(state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    LaunchedEffect(Unit) { model.loadModels() }
    val cur = state.models.current
    Card {
        Text("지금 쓰는 모델", style = MaterialTheme.typography.titleMedium)
        Text(if (cur == null) "아직 고르지 않았습니다." else "${cur.providerName} · ${cur.model.ifBlank { "모델 이름 없음" }}${cur.reasoningEffort?.let { " · 추론 $it" } ?: ""}", style = MaterialTheme.typography.bodyLarge)
        AeonButton("제공자와 모델 바꾸기", { model.openModelSheet() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
    }
    Text("제공자 목록은 models.dev에서 가져오며, 대화 입력창의 모델 버튼에서도 바꿀 수 있습니다. 새 작업부터 적용됩니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
}

@Composable
private fun KeysPage(state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    LaunchedEffect(Unit) { model.loadModels() }
    Text("키는 저장 후 다시 보이지 않습니다. 저장 위치: ${state.secretsBackend.ifBlank { "Aeon이 실행 중인 기기" }}. 전송 중에는 암호화됩니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
    val rows = state.models.providers.filter { it.keyName != null && (it.hasKey || it.inUse) }
    if (rows.isEmpty()) Text("키가 필요한 제공자를 고르면 여기에 나타납니다.", style = MaterialTheme.typography.bodyMedium, color = s.fg3)
    rows.forEach { p -> KeyRow(p.name, p.keyName!!, p.hasKey || state.secretNames.contains(p.keyName), model) }
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
