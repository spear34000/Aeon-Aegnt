package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.aeon.ui.api.MemoryItem
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.formatDateTime
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.UiState

private fun kindLabel(kind: String) = if (kind == "preference") "일하는 방식" else "나에 대해"
private fun sourceLabel(i: MemoryItem) = if (i.source.type == "agent") "에이전트가 제안 · 내가 승인" else "내가 적었어요"
private fun usedLabel(i: MemoryItem) = if (i.uses <= 0) "아직 안 쓰였어요" else "${i.uses}번 쓰였어요"

/**
 * A sheet over the page: a dimmed backdrop and a panel at the bottom. It is a dialog window, so it sits above the whole
 * screen whatever scrolls underneath, and rises with the keyboard.
 */
@Composable
private fun SheetDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(scheme.scrim.copy(alpha = 0.45f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().imePadding()
                    .clip(RoundedCornerShape(topStart = AeonShape.SHEET.dp, topEnd = AeonShape.SHEET.dp)).background(scheme.surface)
                    .pointerInput(Unit) { detectTapGestures { } }.padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
            )
        }
    }
}

/** What the agent remembers across tasks (D24): the owner's words, or what the agent proposed and the owner approved. */
@Composable
fun MemoryPage(state: UiState, model: AppModel) {
    val m = state.memory
    val s = LocalAeonStatus.current
    LaunchedEffect(Unit) { model.loadMemory() }
    val prefs = m.items.filter { it.kind == "preference" }
    val facts = m.items.filter { it.kind != "preference" }

    if (m.loaded && m.items.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Icon(AeonIcons.Memory, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("아직 기억하는 게 없어요", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
            Text(
                "“앞으로 보고서는 짧게 써 줘”처럼 말해 두면 에이전트가 기억해도 되는지 물어봐요. 직접 적어 둘 수도 있어요.",
                style = MaterialTheme.typography.bodyMedium, color = s.fg3, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (state.isAdmin) AeonButton("기억 추가", model::openMemoryDraft, Modifier.padding(top = 12.dp), kind = ButtonKind.Primary)
        }
    } else if (m.items.isNotEmpty()) {
        Banner("에이전트가 다음 작업에서도 알고 있을 내용이에요. 내가 적었거나 내가 승인한 것만 저장돼요.", Tone.Neutral, "·")
        if (prefs.isNotEmpty()) {
            GroupHeader("일하는 방식", "${prefs.size}")
            MemoryRows(prefs, model)
        }
        if (facts.isNotEmpty()) {
            GroupHeader("나에 대해", "${facts.size}")
            MemoryRows(facts, model)
        }
        Text("일하는 방식은 매번 에이전트에게 전해져요. 나에 대한 내용은 작업과 관련이 있을 때만 전해져요. 지우면 바로 사라져요.", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = s.fg3)
    }

    m.draft?.let { MemoryDraftSheet(state, model) }
    m.picked?.let { MemoryItemSheet(it, state, model) }
}

@Composable
private fun MemoryRows(items: List<MemoryItem>, model: AppModel) {
    ListSurface {
        items.forEachIndexed { i, item ->
            if (i > 0) RowDivider(72.dp)
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable { model.pickMemory(item) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(AeonIcons.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(item.text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${sourceLabel(item)} · ${usedLabel(item)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(AeonIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MemoryDraftSheet(state: UiState, model: AppModel) {
    val m = state.memory
    val d = m.draft ?: return
    val s = LocalAeonStatus.current
    SheetDialog(onDismiss = model::closeMemoryDraft) {
        Text("무엇을 기억할까요?", style = MaterialTheme.typography.titleLarge)
        Text("한 문장으로 짧게 적어 주세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AeonTextField(d.text, { t -> model.changeMemoryDraft { it.copy(text = t) } }, "기억할 내용", Modifier.padding(top = 6.dp), singleLine = false, minHeight = 84.dp, error = m.error)
        Text("${d.text.length}/${m.textMax}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = s.fg3, textAlign = TextAlign.End)
        Text("종류", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillChoice("일하는 방식", d.kind == "preference") { model.changeMemoryDraft { it.copy(kind = "preference") } }
            PillChoice("나에 대해", d.kind == "fact") { model.changeMemoryDraft { it.copy(kind = "fact") } }
        }
        AeonButton("기억하기", model::saveMemory, Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.Primary, enabled = d.text.trim().length >= 3 && !m.busy)
        AeonButton("취소", model::closeMemoryDraft, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
    }
}

@Composable
private fun MemoryItemSheet(item: MemoryItem, state: UiState, model: AppModel) {
    val m = state.memory
    val s = LocalAeonStatus.current
    SheetDialog(onDismiss = { model.pickMemory(null) }) {
        Chip(kindLabel(item.kind), Tone.Neutral)
        SelectionContainer { Text(item.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp)) }
        ListSurface(Modifier.padding(top = 6.dp)) {
            Fact("누가 정했나요", sourceLabel(item))
            RowDivider(16.dp)
            Fact("저장한 날", formatDateTime(item.createdAt.toLong()))
            RowDivider(16.dp)
            Fact("쓰인 횟수", usedLabel(item))
        }
        m.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = s.danger) }
        if (state.isAdmin) AeonButton("지우기", { model.deleteMemory(item.id) }, Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.Destructive, enabled = !m.busy)
        else Text("이 기기는 기억을 바꿀 수 없어요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
        AeonButton("닫기", { model.pickMemory(null) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
    }
}

@Composable
private fun Fact(k: String, v: String) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
    }
}
