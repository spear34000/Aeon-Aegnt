package dev.aeon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.Profile
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.UiState

@Composable
private fun Choice(title: String, options: List<Pair<String?, String>>, value: String?, onPick: (String?) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) -> PillChoice(label, value == v) { onPick(v) } }
    }
}

/**
 * 개인화: how the agent addresses the owner. These only change the wording of what the agent writes; they never
 * change what it may do or what needs approval. Saved on the server, so every paired device sees the same agent.
 */
@Composable
fun PersonalPage(state: UiState, model: AppModel) {
    val saved = state.profile
    var draft by remember(state.config?.profile) { mutableStateOf(saved) }
    LaunchedEffect(Unit) { if (state.config == null) model.loadSettings() }
    val scheme = MaterialTheme.colorScheme
    val preview = run {
        val me = draft.userName?.takeIf { it.isNotBlank() } ?: "사용자"
        val polite = draft.tone != "casual"
        when {
            draft.language == "en" -> "Hi $me. I found 3 files. Shall I go ahead?"
            polite -> "${me}님, 파일 3개를 찾았어요. 진행할까요?"
            else -> "$me, 파일 3개 찾았어. 진행할까?"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("에이전트가 나를 어떻게 부르고, 어떤 말투로 답할지 정합니다. 말투만 바뀌고, 무엇을 할 수 있는지와 승인 규칙은 그대로입니다.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        AeonTextField(draft.userName.orEmpty(), { draft = draft.copy(userName = it.take(40)) }, "나를 부르는 이름", placeholder = "예: 지우")
        AeonTextField(draft.agentName.orEmpty(), { draft = draft.copy(agentName = it.take(40)) }, "에이전트 이름", placeholder = "Aeon")
        Choice("말투", listOf("polite" to "존댓말", "casual" to "반말"), draft.tone) { draft = draft.copy(tone = it) }
        Choice("답변 길이", listOf("brief" to "짧게", null to "기본", "detailed" to "자세히"), draft.detail) { draft = draft.copy(detail = it) }
        Choice("언어", listOf(null to "자동", "ko" to "한국어", "en" to "English"), draft.language) { draft = draft.copy(language = it) }
        AeonTextField(
            draft.about.orEmpty(), { draft = draft.copy(about = it.replace('\n', ' ').take(1000)) }, "에이전트가 알아 둘 것", singleLine = false,
            placeholder = "예: 개발자입니다. 코드는 영어 주석으로 써 주세요.", help = "${draft.about.orEmpty().length}/1000 · 여기 적은 내용은 모델 제공자에게 전달됩니다.",
        )
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("미리보기", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Text("${draft.agentName?.takeIf { it.isNotBlank() } ?: "Aeon"}: $preview", style = MaterialTheme.typography.bodyLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("되돌리기", { draft = saved }, kind = ButtonKind.Secondary, enabled = draft != saved)
            AeonButton("저장", { model.saveProfile(draft) }, kind = ButtonKind.Primary, enabled = draft != saved && !state.busy, modifier = Modifier.weight(1f))
        }
    }
}
