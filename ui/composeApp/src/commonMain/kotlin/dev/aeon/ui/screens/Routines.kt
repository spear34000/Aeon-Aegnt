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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.RoutineItem
import dev.aeon.ui.api.RoutineSchedule
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonSwitch
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.koreanClock
import dev.aeon.ui.platform.koreanDayPrefix
import dev.aeon.ui.platform.nowMillis
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.SettingsPage
import dev.aeon.ui.state.UiState

private val DAY_NAMES = listOf("일", "월", "화", "수", "목", "금", "토")

/** "09:00" as "오전 9:00", "18:30" as "오후 6:30". */
internal fun clockText(time: String): String {
    val h = time.substringBefore(':').toIntOrNull() ?: return time
    val m = time.substringAfter(':', "00")
    val part = if (h < 12) "오전" else "오후"
    val hh = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
    return "$part $hh:$m"
}

/** "평일 오전 9:00", "매일 오후 6:30", "매주 월요일 오전 8:00". */
internal fun scheduleText(s: RoutineSchedule): String {
    val when_ = when (s.repeat) {
        "daily" -> "매일"
        "weekdays" -> "평일"
        else -> "매주 ${DAY_NAMES.getOrElse(s.day ?: 1) { "월" }}요일"
    }
    return "$when_ ${clockText(s.time)}"
}

/** A moment on the computer's clock: its day number (days since 1970) and minutes into that day. */
private fun onComputer(millis: Long, offsetMinutes: Int): Pair<Long, Int> {
    val minutes = millis.floorDiv(60_000L) + offsetMinutes
    return minutes.floorDiv(1440L) to minutes.mod(1440L).toInt()
}

/** "오늘", "내일", or the weekday, for a moment, counted on the computer's calendar. */
private fun dayWord(millis: Long, now: Long, offsetMinutes: Int): String {
    val day = onComputer(millis, offsetMinutes).first
    val diff = day - onComputer(now, offsetMinutes).first
    return when (diff) { 0L -> "오늘"; 1L -> "내일"; else -> "${DAY_NAMES[(day + 4).mod(7L).toInt()]}요일" }
}

/** "오후 6:30" for a moment on the computer's clock. */
private fun computerClock(millis: Long, offsetMinutes: Int): String {
    val m = onComputer(millis, offsetMinutes).second
    return clockText("${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}")
}

/** The next run in words, on the computer's clock: "다음은 내일 오전 9:00". */
private fun nextText(item: RoutineItem, now: Long, offsetMinutes: Int): String {
    val next = item.nextRunAt ?: return "꺼져 있어요"
    return "다음은 ${dayWord(next.toLong(), now, offsetMinutes)} ${clockText(item.schedule.time)}"
}

/** A card at the top of the task list: how many routines there are and when the next one starts. */
@Composable
internal fun RoutinesEntry(state: UiState, model: AppModel) {
    val r = state.routines
    val now = nowMillis()
    val next = r.items.mapNotNull { it.nextRunAt }.minOrNull()
    val line = when {
        !r.loaded -> ""
        r.items.isEmpty() -> "정해 둔 시간에 에이전트가 알아서 시작해요"
        else -> "${r.items.size}개" + (r.items.filter { it.nextRunAt != null }.minByOrNull { it.nextRunAt!! }?.let { " · " + nextText(it, now, r.utcOffsetMinutes) } ?: " · 모두 꺼져 있어요")
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).clickable { model.openSettings(SettingsPage.Routines) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(AeonIcons.Repeat, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f)) {
            Text("반복 작업", style = MaterialTheme.typography.titleMedium)
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(AeonIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** 반복 작업: tasks the server starts on its own at a set time. Everything it starts is an ordinary task: approvals still ask. */
@Composable
fun RoutinesPage(state: UiState, model: AppModel) {
    val r = state.routines
    val s = LocalAeonStatus.current
    val now = nowMillis()
    LaunchedEffect(Unit) { model.loadRoutines() }
    r.error?.let { Banner(it, Tone.Warn, "⚠") }
    if (r.loaded && r.items.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Icon(AeonIcons.Repeat, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("반복할 일이 없어요", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
            Text("“평일 아침 9시에 회의록을 요약해 줘”처럼 정해 두면 에이전트가 알아서 시작해요.", style = MaterialTheme.typography.bodyMedium, color = s.fg3, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
            if (state.isAdmin) AeonButton("반복 작업 추가", model::openRoutineDraft, Modifier.padding(top = 12.dp), kind = ButtonKind.Primary)
        }
    } else if (r.items.isNotEmpty()) {
        ListSurface {
            r.items.forEachIndexed { i, item ->
                if (i > 0) RowDivider(72.dp)
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable { model.pickRoutine(item) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Icon(AeonIcons.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${scheduleText(item.schedule)} · ${nextText(item, now, r.utcOffsetMinutes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    AeonSwitch(item.enabled, { model.setRoutineEnabled(item.id, it) }, "${item.title} 켜기", enabled = state.isAdmin && !r.busy, showLabel = false)
                }
            }
        }
        FeedHint("정한 시간이 되면 에이전트가 이 일을 시작해요. 승인이 필요한 일은 그때도 물어봐요. 시간은 컴퓨터 시계 기준이에요.")
        if (r.runsToday > 0) FeedHint("오늘 반복 작업으로 ${r.runsToday}개를 시작했어요 (하루 ${r.cap}개까지).")
    }
    if (!state.isAdmin) FeedHint("이 기기는 반복 작업을 바꿀 수 없어요.")

    r.draft?.let { RoutineDraftSheet(state, model) }
    r.picked?.let { RoutineSheet(it, state, model) }
}

@Composable
private fun RoutineDraftSheet(state: UiState, model: AppModel) {
    val r = state.routines
    val d = r.draft ?: return
    SheetDialog(onDismiss = model::closeRoutineDraft) {
        Text("무엇을 반복할까요?", style = MaterialTheme.typography.titleLarge)
        AeonTextField(d.title, { t -> model.changeRoutineDraft { it.copy(title = t) } }, "할 일", Modifier.padding(top = 6.dp), singleLine = false, minHeight = 72.dp, error = r.error, placeholder = "예: 회의록을 읽고 요약 문서를 만들어 줘")
        Text("언제", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillChoice("매일", d.repeat == "daily") { model.changeRoutineDraft { it.copy(repeat = "daily") } }
            PillChoice("평일", d.repeat == "weekdays") { model.changeRoutineDraft { it.copy(repeat = "weekdays") } }
            PillChoice("매주", d.repeat == "weekly") { model.changeRoutineDraft { it.copy(repeat = "weekly") } }
        }
        if (d.repeat == "weekly") Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAY_NAMES.forEachIndexed { i, n -> PillChoice(n, d.day == i) { model.changeRoutineDraft { it.copy(day = i) } } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("시간", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Stepper2("${if (d.hour < 12) "오전" else "오후"} ${if (d.hour % 12 == 0) 12 else d.hour % 12}시", { model.changeRoutineDraft { it.copy(hour = (it.hour + 23) % 24) } }, { model.changeRoutineDraft { it.copy(hour = (it.hour + 1) % 24) } })
            Stepper2("${d.minute.toString().padStart(2, '0')}분", { model.changeRoutineDraft { it.copy(minute = (it.minute + 55) % 60) } }, { model.changeRoutineDraft { it.copy(minute = (it.minute + 5) % 60) } })
        }
        AeonButton("추가", model::saveRoutine, Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.Primary, enabled = d.title.trim().length >= 3 && !r.busy)
        AeonButton("취소", model::closeRoutineDraft, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
    }
}

@Composable
private fun Stepper2(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(scheme.surfaceVariant).clickable(onClickLabel = "줄이기", onClick = onMinus), contentAlignment = Alignment.Center) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Text(label, Modifier.defaultMinSize(minWidth = 64.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(scheme.surfaceVariant).clickable(onClickLabel = "늘리기", onClick = onPlus), contentAlignment = Alignment.Center) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun RoutineSheet(item: RoutineItem, state: UiState, model: AppModel) {
    val r = state.routines
    val s = LocalAeonStatus.current
    val now = nowMillis()
    SheetDialog(onDismiss = { model.pickRoutine(null) }) {
        Text(scheduleText(item.schedule), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(item.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 2.dp))
        ListSurface(Modifier.padding(top = 6.dp)) {
            RoutineFact("다음", nextText(item, now, r.utcOffsetMinutes).removePrefix("다음은 "))
            RowDivider(16.dp)
            RoutineFact("마지막", item.lastRunAt?.let { "${dayWord(it.toLong(), now, r.utcOffsetMinutes)} ${computerClock(it.toLong(), r.utcOffsetMinutes)}" } ?: "아직 없어요")
        }
        r.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = s.danger) }
        if (state.isAdmin) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("지금 실행", { model.runRoutine(item.id) }, Modifier.weight(1f), kind = ButtonKind.Secondary, enabled = !r.busy)
            AeonButton("지우기", { model.deleteRoutine(item.id) }, Modifier.weight(1f), kind = ButtonKind.Destructive, enabled = !r.busy)
        }
        AeonButton("닫기", { model.pickRoutine(null) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
    }
}

@Composable
private fun RoutineFact(k: String, v: String) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
    }
}
