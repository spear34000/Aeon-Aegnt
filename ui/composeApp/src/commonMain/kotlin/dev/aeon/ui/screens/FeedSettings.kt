package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonSwitch
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.UiState

/**
 * 피드 설정: what the feed is (the brief, in the owner's words), how much it may make in a day, what is searched, and the taste
 * summary that is the only thing the writer learns about the owner's reactions. The owner reads and edits all of it; changing
 * it needs the machine or an admin device.
 */
@Composable
fun FeedSettingsPage(state: UiState, model: AppModel) {
    val f = state.feed
    val s = LocalAeonStatus.current
    LaunchedEffect(Unit) { model.loadFeed() }
    val data = f.data ?: run {
        FeedHint(if (f.loaded) "피드를 불러오지 못했어요." else "불러오는 중이에요.")
        return
    }
    val set = data.settings
    val can = state.isAdmin
    f.error?.let { Banner(it, Tone.Warn, "⚠") }

    Text("이런 피드를 만들어 줘요", style = MaterialTheme.typography.labelMedium)
    val brief = f.briefDraft ?: set.brief
    AeonTextField(brief, model::editBrief, "피드 설명", singleLine = false, minHeight = 96.dp, enabled = can, help = "어떤 소식을 어떤 말투로 받을지 적어 주세요. (${brief.length}/600)")
    if (f.briefDraft != null && f.briefDraft != set.brief) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("되돌리기", model::revertBrief, Modifier.weight(1f), kind = ButtonKind.Secondary)
            AeonButton("저장", model::saveBrief, Modifier.weight(1f), kind = ButtonKind.Primary, enabled = f.briefDraft.trim().length >= 5)
        }
    }

    GroupHeader("오늘 쓴 만큼")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        UseBox("만든 횟수", "${data.status.today.runs} / ${data.status.caps.runs}번", Modifier.weight(1f))
        UseBox("새 글", "${data.status.today.posts} / ${data.status.caps.posts}개", Modifier.weight(1f))
    }
    ListSurface {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { AeonSwitch(set.auto, { model.setFeedAuto(it) }, "자동으로 만들기 · ${set.intervalMinutes / 60}시간마다", enabled = can) }
        RowDivider(16.dp)
        Stepper("하루 만드는 횟수", set.dailyRuns, 1, 12, "번", can) { model.setFeedDailyRuns(it) }
        RowDivider(16.dp)
        Stepper("하루 새 글", set.dailyPosts, 1, 30, "개", can) { model.setFeedDailyPosts(it) }
    }
    FeedHint("글을 만들 때마다 AI를 한 번 쓰고 기사 몇 개를 내려받아요. 한도에 닿으면 그날은 쉬어요. 앱이 꺼져 있으면 만들지 못해요.")
    if (!can) FeedHint("이 기기는 피드 설정을 바꿀 수 없어요.")

    GroupHeader("찾아보는 말")
    if (data.queries.isEmpty()) FeedHint("관심사를 고르면 여기에 찾아볼 말이 보여요.")
    else ListSurface {
        data.queries.forEachIndexed { i, q ->
            if (i > 0) RowDivider(16.dp)
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(q.interest, style = MaterialTheme.typography.bodyMedium, color = s.fg3)
                Text(q.query, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
            }
        }
    }
    FeedHint("관심사로만 찾아요. 기억이나 대화 내용은 검색에 쓰지 않아요.")

    GroupHeader("취향 요약")
    val taste = data.taste
    val draft = f.tasteDraft
    if (draft != null) {
        AeonTextField(draft, model::editTaste, "취향 요약", singleLine = false, minHeight = 84.dp, help = "비우고 저장하면 지워져요. (${draft.length}/300)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("취소", model::revertTaste, Modifier.weight(1f), kind = ButtonKind.Secondary)
            AeonButton("저장", model::saveTaste, Modifier.weight(1f), kind = ButtonKind.Primary)
        }
    } else {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(taste?.text ?: "아직 없어요. 글에 좋아요를 누르거나 지우면 만들어져요.", style = MaterialTheme.typography.bodyMedium, color = if (taste == null) s.fg3 else MaterialTheme.colorScheme.onSurface)
            if (taste != null) Text(if (taste.source == "owner") "내가 쓴 요약이에요" else "좋아요와 지우기에서 뽑은 요약이에요", style = MaterialTheme.typography.labelMedium, color = s.fg3)
            if (can) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                AeonButton("고치기", { model.editTaste(taste?.text ?: "") }, Modifier.weight(1f), kind = ButtonKind.Secondary)
                AeonButton("지우기", model::clearTaste, Modifier.weight(1f), kind = ButtonKind.Secondary, enabled = taste != null)
            }
        }
    }
    FeedHint("다음 글을 쓸 때 이 요약만 전해요. 대화 내용은 전하지 않아요.")
}

@Composable
private fun UseBox(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalAeonStatus.current.fg3)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, unit: String, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        StepButton("−", "줄이기", enabled && value > min) { onChange(value - 1) }
        Text("$value$unit", Modifier.defaultMinSize(minWidth = 48.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        StepButton("+", "늘리기", enabled && value < max) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(scheme.surfaceVariant).clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = MaterialTheme.typography.titleMedium, color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.4f)) }
}
