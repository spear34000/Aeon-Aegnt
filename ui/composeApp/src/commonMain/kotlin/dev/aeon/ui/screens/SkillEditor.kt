package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.PlatformBackHandler
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.SkillDraft
import dev.aeon.ui.state.UiState

/**
 * Full-screen page for one skill. The agent makes skills; here the owner reads one and can correct its description and
 * instructions or delete it. The name is the folder name and cannot change (the Agent Skills format requires them to match).
 */
@Composable
fun SkillEditor(draft: SkillDraft, state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    var confirm by remember(draft.folder) { mutableStateOf(false) }
    // Only the owner's own skills are corrected here; a project skill is read-only.
    val canEdit = state.skills.canEdit && draft.scope == "user"
    PlatformBackHandler(enabled = true) { model.closeSkillEditor() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) { detectTapGestures { } }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(AeonIcons.Back, "닫기", model::closeSkillEditor)
            Text("스킬", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
            if (canEdit) AeonButton(if (draft.saving) "저장 중…" else "저장", model::saveSkill, kind = ButtonKind.Primary, enabled = draft.description.isNotBlank() && !draft.saving, wide = true)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            draft.error?.let { Banner(it, Tone.Danger, "✕") }
            if (!canEdit) Banner("이 기기는 스킬을 바꿀 수 없어요.", Tone.Warn, "⚠")
            if (draft.problems.isNotEmpty()) Banner("스킬 형식에 맞지 않아 에이전트가 쓰지 않아요. " + draft.problems.joinToString(" "), Tone.Warn, "⚠")
            Text(draft.folder, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge)
            AeonTextField(draft.description, { v -> model.changeSkillDraft { it.copy(description = v.replace('\n', ' ').take(1024)) } }, "설명", singleLine = false, minHeight = 88.dp, placeholder = "무엇을 하는 스킬이고 언제 쓰는지", enabled = canEdit)
            AeonTextField(draft.body, { v -> model.changeSkillDraft { it.copy(body = v.take(90_000)) } }, "지침", singleLine = false, placeholder = "에이전트가 따를 순서나 규칙", enabled = canEdit, minHeight = 240.dp)
            val facts = listOfNotNull(draft.license?.let { "라이선스 $it" }, draft.compatibility?.let { "사용 환경 $it" }, draft.files.takeIf { it.isNotEmpty() }?.let { "파일 ${it.joinToString(", ")}" })
            facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = s.fg3) }
            if (state.skills.canEdit) AeonButton("삭제", { confirm = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Destructive)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("\"${draft.folder}\" 스킬을 삭제할까요?") },
            text = { Text("스킬과 안에 있는 파일이 지워져요. 에이전트는 더 이상 이 스킬을 쓰지 않아요.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { confirm = false; model.deleteSkill(draft.folder, draft.scope) }) { Text("삭제", color = s.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
        )
    }
}
