package dev.aeon.ui.screens

import dev.aeon.ui.design.Glyph
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import dev.aeon.ui.design.AeonIcons
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import dev.aeon.ui.design.itemMotion
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
                    TaskRow(t, selected = t.id == state.selectedTaskId, modifier = itemMotion()) { model.selectTask(t.id) }
                }
            }
        }
    }
}

@Composable
fun TaskRow(t: TaskSummary, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val s = LocalAeonStatus.current
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background
    val (dot, text) = taskStatusLine(t)
    Column(
        modifier.fillMaxWidth().background(bg, RoundedCornerShape(AeonShape.CONTROL.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(t.goal, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.size(7.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
            Text(text, style = MaterialTheme.typography.bodySmall, color = s.fg3)
            Spacer(Modifier.weight(1f))
            Text(koreanClock(t.updatedAt), style = MaterialTheme.typography.bodySmall, color = s.fg3)
        }
    }
}

/**
 * 작업 상세: what the task was, how it ended, what was checked against the real files, and what the agent did, in
 * plain words. The raw arguments and output of each action are behind "자세히".
 */
@Composable
fun TaskDetailPanel(state: UiState, model: AppModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val summary = state.tasks.firstOrNull { it.id == state.selectedTaskId }
    val d = state.selectedTask?.takeIf { it.id == state.selectedTaskId }
    val s = LocalAeonStatus.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircleButton(dev.aeon.ui.design.AeonIcons.Back, "뒤로", onBack)
        if (summary == null || d == null) {
            EmptyState("불러오는 중", "작업 정보를 가져오고 있습니다.")
            return@Column
        }
        Text(d.goal, style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskStatusChip(d.status, d.note)
            VerifyBadge(d.verificationDetail?.status, d.verificationDetail?.evidence?.count { !it.passed } ?: 0)
            Spacer(Modifier.weight(1f))
            Text(koreanClock(d.updatedAt), style = MaterialTheme.typography.bodySmall, color = s.fg3)
        }
        val report = d.result?.takeIf { it.isNotBlank() } ?: d.error?.takeIf { it.isNotBlank() }?.let { friendlyError(it) }
        if (report != null) {
            Card {
                Text(if (d.status == "FAILED") "실패 이유" else "에이전트 보고", style = MaterialTheme.typography.labelLarge, color = s.fg3)
                SelectionContainer { Text(report, style = MaterialTheme.typography.bodyLarge, color = if (d.status == "FAILED") s.danger else MaterialTheme.colorScheme.onSurface) }
            }
        }
        EvidenceCard(d)
        if (d.steps.isNotEmpty()) {
            Card {
                Text("한 일 ${d.steps.size}", style = MaterialTheme.typography.titleMedium)
                d.steps.forEach { StepRow(it) }
            }
        } else if (d.plan.isNotEmpty()) {
            Card {
                Text("계획", style = MaterialTheme.typography.titleMedium)
                d.plan.forEachIndexed { i, p -> Text("${i + 1}. $p", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.status !in setOf("COMPLETED", "FAILED", "CANCELLED")) AeonButton("멈추기", { model.cancel(d.id) }, kind = ButtonKind.Secondary)
            if (d.status == "COMPLETED" || d.status == "FAILED") AeonButton("다시 확인", { model.reverify(d.id) }, kind = ButtonKind.Secondary)
            if (d.status == "COMPLETED" && d.verificationDetail?.status == "unverified") AeonButton("확인했어요", { model.confirm(d.id) }, kind = ButtonKind.Ghost)
        }
    }
}

/** What was checked against the real files, one plain line per check. Hidden when nothing was checked and nothing failed. */
@Composable
fun EvidenceCard(d: TaskDetail) {
    val s = LocalAeonStatus.current
    val v = d.verificationDetail
    if (v == null || v.evidence.isEmpty()) return
    Card {
        Text("확인한 것", style = MaterialTheme.typography.titleMedium)
        v.evidence.forEach { e -> EvidenceRow(e) }
        if (!v.userDefined) Text("확인 기준은 모델이 정했어요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
    }
}

@Composable
private fun EvidenceRow(e: Evidence) {
    val s = LocalAeonStatus.current
    val crit = e.criterion?.toString().orEmpty()
    val target = Regex("\"(?:path|url)\":\"([^\"]+)\"").find(crit)?.groupValues?.get(1)?.trimEnd('/')?.substringAfterLast('/')
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(20.dp).background(if (e.passed) s.okSoft else s.dangerSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (e.passed) AeonIcons.Check else AeonIcons.Close, contentDescription = if (e.passed) "통과" else "실패", tint = if (e.passed) s.ok else s.danger, modifier = Modifier.size(12.dp))
        }
        Text(listOfNotNull(target, evidenceLabel(crit, e.passed)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Plain-language label for a criterion. The check reads the files directly; it does not go through a tool. */
fun evidenceLabel(criterionJson: String, passed: Boolean): String {
    val kind = Regex("\"kind\":\"([a-z_0-9]+)\"").find(criterionJson)?.groupValues?.get(1)
    val label = when (kind) {
        "file_exists" -> if (passed) "파일이 있어요" else "파일이 없어요"
        "file_contains" -> if (passed) "내용이 맞아요" else "내용이 달라요"
        "file_sha256" -> if (passed) "내용이 같아요" else "내용이 달라요"
        "http_status" -> if (passed) "응답이 맞아요" else "응답이 달라요"
        else -> if (passed) "통과" else "실패"
    }
    return label
}

private fun stepLabel(tool: String): String = when (tool) {
    "fs.list" -> "폴더 목록 보기"
    "fs.read" -> "파일 읽기"
    "fs.write" -> "파일 쓰기"
    "fs.delete" -> "파일 삭제"
    "http.get" -> "웹 주소 읽기"
    "http.post" -> "외부로 보내기"
    "sandbox.exec" -> "격리된 명령 실행"
    else -> tool
}

@Composable
private fun StepRow(st: Step) {
    val s = LocalAeonStatus.current
    var open by remember(st.index, st.tool) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(20.dp).background(if (st.ok) s.okSoft else s.dangerSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (st.ok) AeonIcons.Check else AeonIcons.Close, contentDescription = if (st.ok) "성공" else "실패", tint = if (st.ok) s.ok else s.danger, modifier = Modifier.size(12.dp))
            }
            Text(stepLabel(st.tool), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(if (open) "접기" else "자세히", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(AeonShape.CONTROL.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(st.tool, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(st.args?.toString().orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                SelectionContainer { Text(st.output.take(2000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
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
            EmptyState("승인 대기 없음", "승인이 필요한 일이 생기면 여기에 나타납니다.")
        }
        state.approvals.forEach { a ->
            val goal = state.tasks.firstOrNull { it.id == a.taskId }?.goal ?: "작업"
            ApprovalCard(a, state.approvalSeenAt[a.id], goal, { model.decide(a.id, it) }, onAnswer = { model.decide(a.id, true, it) })
        }
        if (state.recentApprovals.isNotEmpty()) {
            SectionLabel("최근 처리")
            state.recentApprovals.forEach { a ->
                val s = LocalAeonStatus.current
                val (mark, word, color) = when (a.status) {
                    "approved" -> Triple("✓", "허용함", s.ok)
                    "denied" -> Triple("✕", "거부함", s.danger)
                    "expired" -> Triple("⌛", "만료됨", s.warn)
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
