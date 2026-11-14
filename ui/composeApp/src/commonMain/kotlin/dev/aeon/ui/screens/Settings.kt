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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.AeonConfig
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
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

internal fun pageTitle(p: SettingsPage): String = when (p) {
    SettingsPage.Personal -> "개인화"
    SettingsPage.Models -> "AI"
    SettingsPage.Keys -> "접속 키"
    SettingsPage.Devices -> "연결된 기기"
    SettingsPage.Feed -> "피드 설정"
    SettingsPage.Memory -> "기억"
    SettingsPage.Routines -> "반복 작업"
    SettingsPage.Usage -> "사용량"
    SettingsPage.Legal -> "약관 · 권한"
    SettingsPage.About -> "정보"
}

/**
 * Settings. On the phone ([onBack] given) one page fills the screen under a back button and its title, as in the
 * mockup. In the wide layout the pages are tabs on one screen.
 */
@Composable
fun SettingsScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier, title: String = "설정", onBack: (() -> Unit)? = null) {
    LaunchedEffect(Unit) { model.loadSettings() }
    Column(modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleButton(AeonIcons.Back, "뒤로", onBack)
                Text(pageTitle(state.settingsPage), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                // The page for what the agent remembers has its "add" button where the mockup puts it.
                if (state.settingsPage == SettingsPage.Memory && state.isAdmin) CircleButton(AeonIcons.Plus, "기억 추가", model::openMemoryDraft) else if (state.settingsPage == SettingsPage.Routines && state.isAdmin) CircleButton(AeonIcons.Plus, "반복 작업 추가", model::openRoutineDraft) else Spacer(Modifier.width(48.dp))
            }
        }
        if (state.settingsPage == SettingsPage.Personal) {
            // The personalization page scrolls by itself and keeps its save bar in view.
            if (onBack == null) Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { SettingsTabs(state, model, title) }
            PersonalPage(state, model, Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = if (onBack != null) 4.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (onBack == null) SettingsTabs(state, model, title)
                SettingsBody(state, model)
            }
        }
    }
}

@Composable
private fun SettingsTabs(state: UiState, model: AppModel, title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsPage.entries.forEach { p ->
            val label = when (p) {
                SettingsPage.Personal -> "개인화"
                SettingsPage.Models -> "AI"
                SettingsPage.Keys -> "접속 키"
                SettingsPage.Devices -> "기기"
                SettingsPage.Feed -> "피드"
                SettingsPage.Memory -> "기억"
                SettingsPage.Routines -> "반복"
                SettingsPage.Usage -> "사용량"
                SettingsPage.Legal -> "약관·권한"
                SettingsPage.About -> "정보"
            }
            AeonButton(label, { model.openSettings(p) }, Modifier.widthIn(min = 64.dp), kind = if (p == state.settingsPage) ButtonKind.Primary else ButtonKind.Ghost, wide = true)
        }
    }
}

@Composable
private fun SettingsBody(state: UiState, model: AppModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (state.settingsPage) {
            SettingsPage.Personal -> {}
            SettingsPage.Models -> ModelsPage(state, model)
            SettingsPage.Keys -> KeysPage(state, model)
            SettingsPage.Devices -> DevicesPage(state, model)
            SettingsPage.Feed -> FeedSettingsPage(state, model)
            SettingsPage.Memory -> MemoryPage(state, model)
            SettingsPage.Routines -> RoutinesPage(state, model)
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
        Text("지금 쓰는 AI", style = MaterialTheme.typography.titleMedium)
        Text(if (cur == null) "아직 고르지 않았습니다." else "${cur.providerName} · ${cur.model.ifBlank { "모델 이름 없음" }}${cur.reasoningEffort?.let { " · 추론 $it" } ?: ""}", style = MaterialTheme.typography.bodyLarge)
        AeonButton("AI 바꾸기", { model.openModelSheet() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
    }
    
}

@Composable
private fun KeysPage(state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    LaunchedEffect(Unit) { model.loadModels() }
    Text("저장한 키는 다시 보이지 않아요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
    val rows = state.models.providers.filter { it.keyName != null && (it.hasKey || it.inUse) }
    if (rows.isEmpty()) Text("AI를 고르면 여기에 나타나요.", style = MaterialTheme.typography.bodyMedium, color = s.fg3)
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
            help = null,
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
        Text("AI 사용 횟수", style = MaterialTheme.typography.titleMedium)
        Text("${ok.size}번 · 읽고 쓴 양 ${ok.sumOf { it.inputTokens + it.outputTokens }}", style = MaterialTheme.typography.bodyLarge)
        Text("그중 생각에 쓴 양 ${ok.sumOf { it.reasoningTokens }}", style = MaterialTheme.typography.bodySmall, color = s.fg3)
    }
    if (state.usage.isEmpty()) Text("아직 AI를 쓰지 않았어요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
}

@Composable
private fun AboutPage(state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    Card {
        Text("연결된 컴퓨터", style = MaterialTheme.typography.titleMedium)
        Text(state.serverUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        state.status?.let { Text("작업 ${state.tasks.size}개 · 키 저장 위치: ${it.secretsBackend}", style = MaterialTheme.typography.bodySmall, color = s.fg3) }
    }
    state.status?.setupProblem?.let { Banner(friendlySetupProblem(it), Tone.Warn, "•") }
    AeonButton("이 기기 연결 끊기", { model.unpair() }, kind = ButtonKind.Secondary, modifier = Modifier.widthIn(min = 200.dp))
    
}
