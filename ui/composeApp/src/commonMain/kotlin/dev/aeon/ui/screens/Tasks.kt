package dev.aeon.ui.screens

import dev.aeon.ui.design.Glyph
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.Approval
import dev.aeon.ui.api.Evidence
import dev.aeon.ui.api.Step
import dev.aeon.ui.api.TaskDetail
import dev.aeon.ui.api.TaskSummary
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Card
import dev.aeon.ui.design.EmptyState
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.SectionLabel
import dev.aeon.ui.design.TaskStatusChip
import dev.aeon.ui.design.VerifyBadge
import dev.aeon.ui.platform.koreanClock
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.UiState

/** Task rows: goal, status word, verification word, time. Used in the wide middle column and on the phone. */
@Composable
fun TaskList(state: UiState, model: AppModel, modifier: Modifier = Modifier, showHeader: Boolean = true) {
    Column(modifier.fillMaxSize()) {
        if (showHeader) {
            Text("대화", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        }
        if (state.tasks.isEmpty()) {
            EmptyState("아직 대화가 없습니다", "새 대화에서 목표를 말해 보세요.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.tasks, key = { it.id }) { t ->
                    TaskRow(t, selected = t.id == state.selectedTaskId) { model.selectTask(t.id) }
                }
            }
        }
    }
}

@Composable
fun TaskRow(t: TaskSummary, selected: Boolean, onClick: () -> Unit) {
    val s = LocalAeonStatus.current
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background
    Column(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(AeonShape.CONTROL.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(t.goal, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskStatusChip(t.status)
            val v = t.verification?.status
            if (t.status == "COMPLETED" && v != null) {
                Text(
                    when (v) {
                        "verified" -> "실제 파일로 확인됨"
                        "unverified" -> "확인 안 됨"
                        "user_confirmed" -> "사용자가 확인함"
                        else -> "확인 실패"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (v == "failed") s.danger else s.fg3,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(koreanClock(t.updatedAt), style = MaterialTheme.typography.bodySmall, color = s.fg3)
        }
    }
}

/** 작업 상세: 검증 증거 first, then the run record (or the plan). Arguments and output are copyable mono text. */
@Composable
fun TaskDetailPanel(state: UiState, model: AppModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val summary = state.tasks.firstOrNull { it.id == state.selectedTaskId }
    val d = state.selectedTask?.takeIf { it.id == state.selectedTaskId }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CircleButton(dev.aeon.ui.design.AeonIcons.Back, "뒤로", onBack)
        if (summary == null || d == null) {
            EmptyState("불러오는 중", "작업 정보를 가져오고 있습니다.")
            return@Column
        }
        Text(d.goal, style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskStatusChip(d.status)
            Text("${d.steps.size}단계 · ${if (d.status == "COMPLETED") "에이전트 보고: 완료" else ""}", style = MaterialTheme.typography.bodySmall, color = LocalAeonStatus.current.fg3)
        }
        EvidenceCard(d)
        RunTabs(d)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.status !in setOf("COMPLETED", "FAILED", "CANCELLED")) AeonButton("멈추기", { model.cancel(d.id) }, kind = ButtonKind.Secondary)
            if (d.status == "COMPLETED" || d.status == "FAILED") AeonButton("다시 확인", { model.reverify(d.id) }, kind = ButtonKind.Secondary)
            if (d.status == "COMPLETED" && d.verificationDetail?.status == "unverified") AeonButton("확인함으로 표시", { model.confirm(d.id) }, kind = ButtonKind.Ghost)
        }
    }
}

/** 검증 증거: what was checked against the real files. Shown above the run record. */
@Composable
fun EvidenceCard(d: TaskDetail) {
    val s = LocalAeonStatus.current
    val v = d.verificationDetail
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("검증 증거", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            VerifyBadge(v?.status, v?.evidence?.count { !it.passed } ?: 0)
        }
        if (v == null || v.evidence.isEmpty()) {
            Text(
                "이 작업에는 자동 검사 기준이 없습니다. 파일을 열어 직접 확인하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = s.fg3,
            )
        } else {
            v.evidence.forEach { e -> EvidenceRow(e) }
            if (!v.userDefined) {
                Text("이 검사 기준은 모델이 제안한 것입니다. 기준을 직접 정하려면 작업을 새로 만드세요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
            }
        }
    }
}

@Composable
private fun EvidenceRow(e: Evidence) {
    val s = LocalAeonStatus.current
    val crit = e.criterion?.toString().orEmpty()
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Glyph(if (e.passed) "✓" else "✕", color = if (e.passed) s.ok else s.danger, style = MaterialTheme.typography.labelLarge)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(evidenceLabel(crit, e.passed), style = MaterialTheme.typography.bodyMedium)
            SelectionContainer { Text(crit, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = s.fg3) }
        }
    }
}

/** Plain-language label for a criterion. The check reads the files directly; it does not go through a tool. */
fun evidenceLabel(criterionJson: String, passed: Boolean): String {
    val kind = Regex("\"kind\":\"([a-z_0-9]+)\"").find(criterionJson)?.groupValues?.get(1)
    val label = when (kind) {
        "file_exists" -> "파일이 있음"
        "file_contains" -> "파일 내용 포함"
        "file_sha256" -> "SHA-256 일치"
        "http_status" -> "HTTP 응답 확인"
        else -> "검사"
    }
    return if (passed) label else "$label · 실패"
}

@Composable
private fun RunTabs(d: TaskDetail) {
    var tab by remember(d.id) { mutableStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AeonButton("실행 기록", { tab = 0 }, kind = if (tab == 0) ButtonKind.Primary else ButtonKind.Ghost)
        AeonButton("계획", { tab = 1 }, kind = if (tab == 1) ButtonKind.Primary else ButtonKind.Ghost)
    }
    if (tab == 0) {
        if (d.steps.isEmpty()) Text("아직 실행한 단계가 없습니다.", style = MaterialTheme.typography.bodySmall, color = LocalAeonStatus.current.fg3)
        d.steps.forEach { StepRow(it) }
    } else {
        if (d.plan.isEmpty()) Text("계획이 없습니다.", style = MaterialTheme.typography.bodySmall, color = LocalAeonStatus.current.fg3)
        d.plan.forEachIndexed { i, p -> Text("${i + 1}. $p", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun StepRow(st: Step) {
    val s = LocalAeonStatus.current
    var open by remember(st.index, st.tool) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(AeonShape.CONTROL.dp))
            .clickable { open = !open }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Glyph(if (st.ok) "✓" else "✕", color = if (st.ok) s.ok else s.danger, style = MaterialTheme.typography.labelLarge)
            Text(st.tool, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(if (open) "접기" else "펼치기", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (open) {
            SectionLabel("인자")
            SelectionContainer { Text(st.args?.toString().orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            SectionLabel("결과")
            SelectionContainer { Text(st.output.take(2000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** 승인 tab: what is waiting now, then what was decided today. */
@Composable
fun ApprovalsPanel(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    val now = dev.aeon.ui.platform.nowMillis()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("승인", style = MaterialTheme.typography.titleLarge)
        if (state.approvals.isEmpty()) {
            EmptyState("승인 대기 없음", "Aeon이 위험한 일을 하기 전에 여기와 대화에 물어봅니다.")
        }
        state.approvals.forEach { a ->
            val goal = state.tasks.firstOrNull { it.id == a.taskId }?.goal ?: "작업"
            ApprovalCard(a, state.approvalSeenAt[a.id], "$goal · ${a.tool}", { model.decide(a.id, it) })
        }
        if (state.recentApprovals.isNotEmpty()) {
            SectionLabel("최근 처리")
            state.recentApprovals.forEach { a ->
                val s = LocalAeonStatus.current
                val (mark, word, color) = when (a.status) {
                    "approved" -> Triple("✓", "허용함 · 이 한 번만", s.ok)
                    "denied" -> Triple("✕", "거부함", s.danger)
                    "expired" -> Triple("⌛", "만료됨 → 거부 처리", s.warn)
                    else -> Triple("·", a.status, s.fg3)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(mark, color = color, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.size(8.dp))
                    Text(a.summary.take(60), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(word, style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        @Suppress("UNUSED_VARIABLE") val unused = now
    }
}
