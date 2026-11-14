package dev.aeon.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.aeon.ui.api.MemoryItem
import dev.aeon.ui.api.MemoryProposal
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Motion
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.formatDateTime
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.UiState

private fun kindLabel(kind: String) = if (kind == "preference") "일하는 방식" else "나에 대해"
private fun sourceLabel(i: MemoryItem) = if (i.source.type == "agent") "에이전트가 제안 · 내가 승인" else "내가 적었어요"
private fun usedLabel(i: MemoryItem) = if (i.uses <= 0) "아직 안 쓰였어요" else "${i.uses}번 쓰였어요"

/** "2026-12-15 23:59:59" as "12월 15일". */
internal fun shortDate(millis: Double): String {
    val d = formatDateTime(millis.toLong()).substringBefore(' ').split('-')
    return if (d.size == 3) "${d[1].toIntOrNull() ?: d[1]}월 ${d[2].toIntOrNull() ?: d[2]}일" else ""
}

/**
 * A sheet over the page: a dimmed backdrop and a panel at the bottom. It is a dialog window, so it sits above the whole
 * screen whatever scrolls underneath, and rises with the keyboard.
 */
@Composable
internal fun SheetDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // The sheet rises over a scrim that fades in, and leaves the same way: the dialog is removed only after it has gone.
    var shown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { shown = true }
    val close: () -> Unit = {
        if (shown) {
            shown = false
            scope.launch {
                delay(Motion.Quick + 80L)
                onDismiss()
            }
        }
    }
    val scrim by animateFloatAsState(if (shown) 0.45f else 0f, if (shown) Motion.arrive(Motion.Standard) else Motion.leave(Motion.Quick + 60), label = "scrim")
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(scheme.scrim.copy(alpha = scrim)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = shown,
                enter = slideInVertically(Motion.arrive(Motion.Slow)) { it } + fadeIn(Motion.arrive(Motion.Standard)),
                exit = slideOutVertically(Motion.leave(Motion.Quick + 60)) { it / 2 } + fadeOut(Motion.leave()),
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
}

/** What the agent remembers across tasks: the owner's words, or what a task suggested and the owner approved. */
@Composable
fun MemoryPage(state: UiState, model: AppModel) {
    val m = state.memory
    val s = LocalAeonStatus.current
    LaunchedEffect(Unit) { model.loadMemory() }
    val prefs = m.items.filter { it.kind == "preference" }
    val facts = m.items.filter { it.kind != "preference" }

    m.error?.let { Banner(it, Tone.Warn, "⚠") }
    if (m.proposals.isNotEmpty()) {
        GroupHeader("에이전트가 제안해요", "${m.proposals.size}")
        ListSurface {
            m.proposals.forEachIndexed { i, p ->
                if (i > 0) RowDivider(16.dp)
                ProposalRow(p, m.items.firstOrNull { it.id == p.replaces }, state, model)
            }
        }
    }
    if (m.loaded && m.items.isEmpty() && m.proposals.isEmpty() && m.past.isEmpty()) {
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
    } else {
        if (prefs.isNotEmpty()) {
            GroupHeader("일하는 방식", "${prefs.size}")
            MemoryRows(prefs, model)
        }
        if (facts.isNotEmpty()) {
            GroupHeader("나에 대해", "${facts.size}")
            MemoryRows(facts, model)
        }
        if (m.past.isNotEmpty()) {
            GroupHeader("지난 기억", "${m.past.size}")
            MemoryRows(m.past, model, past = true)
            Text("기간이 지난 기억은 쓰이지 않고, 30일 뒤에 사라져요.", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = s.fg3)
        }
        if (m.items.isNotEmpty()) Text("일하는 방식은 항상, 나에 대한 내용은 관련 있을 때만 에이전트에게 전해져요. 지우면 바로 사라져요.", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = s.fg3)
    }

    m.draft?.let { MemoryDraftSheet(state, model) }
    m.edit?.let { MemoryEditSheet(state, model) }
    m.picked?.let { MemoryItemSheet(it, state, model) }
}

@Composable
private fun ProposalRow(p: MemoryProposal, old: MemoryItem?, state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(p.text, Modifier.clickable(enabled = state.isAdmin) { model.editProposal(p) }, style = MaterialTheme.typography.titleMedium)
        if (old != null) Text("이전: ${old.text}", style = MaterialTheme.typography.bodySmall, color = s.fg3)
        Text("${kindLabel(p.kind)} · 작업에서 제안했어요" + if (state.isAdmin) " · 눌러서 고치기" else "", style = MaterialTheme.typography.labelMedium, color = s.fg3)
        if (state.isAdmin) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("무시", { model.ignoreMemoryProposal(p) }, Modifier.weight(1f), kind = ButtonKind.Secondary, enabled = !state.memory.busy)
            AeonButton(if (old != null) "고쳐 기억하기" else "기억하기", { model.acceptMemoryProposal(p) }, Modifier.weight(1f), kind = ButtonKind.Primary, enabled = !state.memory.busy)
        }
    }
}

@Composable
private fun MemoryRows(items: List<MemoryItem>, model: AppModel, past: Boolean = false) {
    ListSurface {
        items.forEachIndexed { i, item ->
            if (i > 0) RowDivider(72.dp)
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable { model.pickMemory(item) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(AeonIcons.Memory, contentDescription = null, tint = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(item.text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Text(if (past) "${shortDate(item.expiresAt ?: 0.0)}에 끝났어요" else "${sourceLabel(item)} · ${usedLabel(item)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!past && item.expiresAt != null) Chip("~${shortDate(item.expiresAt)}", Tone.Warn)
                Icon(AeonIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** How long to remember: no end, a week, a month, three months. */
@Composable
private fun PeriodChoices(days: Int?, chosen: Boolean, onPick: (Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillChoice("계속", chosen && days == null) { onPick(null) }
        PillChoice("일주일", chosen && days == 7) { onPick(7) }
        PillChoice("한 달", chosen && days == 30) { onPick(30) }
        PillChoice("석 달", chosen && days == 90) { onPick(90) }
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
        Text("언제까지 기억할까요", style = MaterialTheme.typography.labelMedium)
        PeriodChoices(d.days, true) { days -> model.changeMemoryDraft { it.copy(days = days) } }
        AeonButton("기억하기", model::saveMemory, Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.Primary, enabled = d.text.trim().length >= 3 && !m.busy)
        AeonButton("취소", model::closeMemoryDraft, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
    }
}

/** Rewording a kept memory or a suggestion, and, if the owner wants, how long to remember it. */
@Composable
private fun MemoryEditSheet(state: UiState, model: AppModel) {
    val m = state.memory
    val e = m.edit ?: return
    val s = LocalAeonStatus.current
    SheetDialog(onDismiss = model::closeMemoryEdit) {
        Text(if (e.memoryId != null) "기억 고치기" else "고쳐서 기억하기", style = MaterialTheme.typography.titleLarge)
        AeonTextField(e.text, model::changeMemoryEditText, "기억할 내용", Modifier.padding(top = 6.dp), singleLine = false, minHeight = 84.dp, error = m.error)
        Text("${e.text.length}/${m.textMax}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = s.fg3, textAlign = TextAlign.End)
        Text("언제까지 기억할까요", style = MaterialTheme.typography.labelMedium)
        PeriodChoices(e.days, e.periodChanged, model::changeMemoryEditPeriod)
        if (!e.periodChanged && e.memoryId != null) Text("고르지 않으면 지금 기간 그대로예요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
        AeonButton(if (e.memoryId != null) "저장" else "기억하기", model::saveMemoryEdit, Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.Primary, enabled = e.text.trim().length >= 3 && !m.busy)
        AeonButton("취소", model::closeMemoryEdit, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
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
            Fact("기억 기간", item.expiresAt?.let { "${shortDate(it)}까지" } ?: "계속")
            RowDivider(16.dp)
            Fact("저장한 날", formatDateTime(item.createdAt.toLong()))
            RowDivider(16.dp)
            Fact("쓰인 횟수", usedLabel(item))
            item.history.firstOrNull()?.let { h ->
                RowDivider(16.dp)
                Fact("이전에는", h.text)
            }
        }
        m.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = s.danger) }
        if (state.isAdmin) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("고치기", { model.editMemory(item) }, Modifier.weight(1f), kind = ButtonKind.Secondary, enabled = !m.busy)
            AeonButton("지우기", { model.deleteMemory(item.id) }, Modifier.weight(1f), kind = ButtonKind.Destructive, enabled = !m.busy)
        } else Text("이 기기는 기억을 바꿀 수 없어요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
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
