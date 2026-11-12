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

/** Full-screen editor for one skill: name, one-line description and the instructions. Covers the screen under it. */
@Composable
fun SkillEditor(draft: SkillDraft, state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    var confirm by remember(draft.folder) { mutableStateOf(false) }
    val canEdit = state.skills.canEdit
    PlatformBackHandler(enabled = true) { model.closeSkillEditor() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) { detectTapGestures { } }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(AeonIcons.Back, "닫기", model::closeSkillEditor)
            Text(if (draft.folder == null) "새 스킬" else "스킬 편집", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
            if (canEdit) AeonButton(if (draft.saving) "저장 중…" else "저장", model::saveSkill, kind = ButtonKind.Primary, enabled = draft.name.isNotBlank() && !draft.saving, wide = true)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            draft.error?.let { Banner(it, Tone.Danger, "✕") }
            if (!canEdit) Banner("이 기기는 스킬을 바꿀 수 없어요.", Tone.Warn, "⚠")
            AeonTextField(draft.name, { v -> model.changeSkillDraft { it.copy(name = v.replace('\n', ' ').take(80)) } }, "이름", placeholder = "예: 회의록 정리", enabled = canEdit)
            AeonTextField(draft.description, { v -> model.changeSkillDraft { it.copy(description = v.replace('\n', ' ').take(300)) } }, "설명", placeholder = "언제 쓰는 스킬인지 한 줄로", enabled = canEdit)
            AeonTextField(draft.body, { v -> model.changeSkillDraft { it.copy(body = v.take(90_000)) } }, "지침", singleLine = false, placeholder = "에이전트가 따를 순서나 규칙을 적어요", enabled = canEdit, minHeight = 240.dp)
            draft.folder?.let { Text("${state.skills.dir}/$it/SKILL.md", style = MaterialTheme.typography.bodySmall, color = s.fg3) }
            if (canEdit && draft.folder != null) AeonButton("삭제", { confirm = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Destructive)
        }
    }
    if (confirm && draft.folder != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("\"${draft.name}\" 스킬을 삭제할까요?") },
            text = { Text("SKILL.md가 지워져요.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { confirm = false; model.deleteSkill(draft.folder) }) { Text("삭제", color = s.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
        )
    }
}
