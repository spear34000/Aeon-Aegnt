package dev.aeon.ui.screens

import dev.aeon.ui.design.Glyph
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aeon.ui.api.Approval
import dev.aeon.ui.api.TaskDetail
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.EmptyState
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.design.TaskStatusChip
import dev.aeon.ui.design.VerifyBadge
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.Connection
import dev.aeon.ui.state.UiState

/** One item in the conversation. A task is one conversation: the goal, the plan, the actions, and the result. */
@Composable
fun ConversationScreen(state: UiState, model: AppModel, wide: Boolean, modifier: Modifier = Modifier) {
    val detail = state.selectedTask?.takeIf { it.id == state.selectedTaskId }
    val summary = state.tasks.firstOrNull { it.id == state.selectedTaskId }
    val offline = state.connection !is Connection.Connected
    val listState = rememberLazyListState()

    Column(modifier.fillMaxSize()) {
        ConversationHeader(summary?.goal, summary?.status, detail?.verificationDetail?.status, wide, onDetail = { state.selectedTaskId?.let { model.selectTask(it, asDetail = true) } }, hasTask = state.selectedTaskId != null)
        if (offline) {
            Banner("서버 연결이 끊겼습니다. PC가 켜져 있고 Aeon 서버가 실행 중인지 확인하세요.", Tone.Danger, "⚠", null)
        }
        state.status?.setupProblem?.let {
            Banner("모델 키가 필요합니다. API 키를 넣거나 로컬 모델을 켜세요.", Tone.Warn, "•")
        }
        state.message?.let { Banner(it, Tone.Neutral, "·", model::dismissMessage) }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            ) {
                if (detail == null) {
                    item(key = "welcome") {
                        EmptyState("무엇을 도와드릴까요?", "목표를 말하면 Aeon이 계획을 세우고, 삭제나 명령 실행은 먼저 묻습니다.")
                    }
                } else {
                    item(key = "user-${detail.id}") { UserBubble(detail.goal) }
                    if (detail.plan.isNotEmpty()) {
                        item(key = "plan-${detail.id}") {
                            AgentMessage("계획을 세웠습니다.") {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    detail.plan.forEachIndexed { i, p -> Text("${i + 1}. $p", style = MaterialTheme.typography.bodyMedium) }
                                }
                            }
                        }
                    }
                    items(detail.steps.size, key = { "step-${detail.id}-${it}" }) { i ->
                        val st = detail.steps[i]
                        Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(if (st.ok) "✓" else "✕", color = if (st.ok) LocalAeonStatus.current.ok else LocalAeonStatus.current.danger, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.size(8.dp))
                            Text(st.tool, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val pending: Approval? = state.approvals.firstOrNull { it.id == detail.pendingApprovalId && it.status == "pending" }
                    if (pending != null) {
                        item(key = "approval-${pending.id}") {
                            Column(Modifier.padding(start = 40.dp)) {
                                ApprovalCard(
                                    a = pending,
                                    seenAt = state.approvalSeenAt[pending.id],
                                    sourceLabel = "${detail.goal} · ${pending.tool}",
                                    onDecide = { model.decide(pending.id, it) },
                                )
                            }
                        }
                    }
                    if (detail.status == "WAITING_FOR_QUOTA") {
                        item(key = "quota-${detail.id}") { QuotaCard(detail, model) }
                    }
                    when (detail.status) {
                        "RUNNING", "PENDING", "RETRY_WAIT" -> item(key = "running-${detail.id}") {
                            Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("실행 중 · ${detail.steps.size}단계", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.weight(1f))
                                AeonButton("멈추기", { model.cancel(detail.id) }, kind = ButtonKind.Ghost)
                            }
                        }
                        "COMPLETED" -> item(key = "result-${detail.id}") { ResultBlock(detail) }
                        "FAILED" -> item(key = "failed-${detail.id}") {
                            AgentMessage("작업이 끝나지 않았습니다.") {
                                Text(detail.error ?: "원인을 알 수 없습니다.", color = LocalAeonStatus.current.danger, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        "CANCELLED" -> item(key = "cancel-${detail.id}") { Chip("멈춤 · 에이전트 보고 없음", Tone.Neutral, "■") }
                    }
                }
            }
        }

        Composer(
            enabled = !offline,
            onSend = { text -> model.createTask(text, emptyList()) },
            hint = if (offline) "연결되면 보낼 수 있습니다." else null,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    LaunchedEffect(detail?.steps?.size, detail?.status) {
        if (detail != null) listState.animateScrollToItem(0)
    }
}

@Composable
private fun ConversationHeader(title: String?, status: String?, verify: String?, wide: Boolean, onDetail: () -> Unit, hasTask: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title ?: "새 대화", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (status != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskStatusChip(status)
                    if (status == "COMPLETED") VerifyBadge(verify)
                }
            }
        }
        if (hasTask) AeonButton(if (wide) "작업 상세" else "상세", onDetail, kind = ButtonKind.Ghost, wide = wide)
    }
}

@Composable
fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.widthIn(max = 480.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(AeonShape.BUBBLE.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Agent message: no bubble, the Æ mark, body text. Reports are labelled as the agent's words. */
@Composable
fun AgentMessage(text: String, body: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text("Æ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).widthIn(min = 28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (body != null) body()
        }
    }
}

@Composable
private fun ResultBlock(d: TaskDetail) {
    AgentMessage("에이전트 보고: 완료") {
        if (d.result != null) SelectionContainer { Text(d.result, style = MaterialTheme.typography.bodyLarge) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VerifyBadge(d.verificationDetail?.status, d.verificationDetail?.evidence?.count { !it.passed } ?: 0)
        }
        Text(
            "위 보고는 에이전트의 말입니다. 확인 배지만 실제 파일을 보고 판단한 결과입니다.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalAeonStatus.current.fg3,
        )
    }
}

@Composable
private fun QuotaCard(d: TaskDetail, model: AppModel) {
    val s = LocalAeonStatus.current
    Column(
        Modifier.fillMaxWidth().padding(start = 40.dp)
            .background(s.warnSoft, RoundedCornerShape(AeonShape.CARD.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("한도 대기", color = s.warn, style = MaterialTheme.typography.labelLarge)
        Text(d.note ?: "모델 사용 한도에 걸렸습니다. 잠시 뒤 자동으로 이어서 합니다.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("작업 정지", { model.cancel(d.id) }, kind = ButtonKind.Secondary)
            AeonButton("설정 바꾸기", { model.openSettings(dev.aeon.ui.state.SettingsPage.Models) }, kind = ButtonKind.Ghost)
        }
    }
}

/** Composer: multi-line field, send button, Enter sends and Shift+Enter adds a line (desktop keyboards). */
@Composable
fun Composer(enabled: Boolean, onSend: (String) -> Unit, hint: String?, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    val s = LocalAeonStatus.current
    val canSend = enabled && text.isNotBlank()
    fun send() {
        if (!canSend) return
        onSend(text)
        text = ""
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(26.dp))
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f).heightIn(min = 36.dp, max = 160.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text("목표를 말해 주세요", color = s.fg3, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = enabled,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && !ev.isShiftPressed) {
                            send()
                            true
                        } else {
                            false
                        }
                    },
                )
            }
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier.size(36.dp).background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(enabled = canSend) { send() },
                contentAlignment = Alignment.Center,
            ) {
                Glyph("↑", color = if (canSend) MaterialTheme.colorScheme.onPrimary else s.fg3, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = s.fg3, modifier = Modifier.padding(start = 16.dp))
        else Text("삭제·명령 실행·외부 전송은 항상 먼저 묻습니다. Shift+Enter 줄바꿈", style = MaterialTheme.typography.bodySmall, color = s.fg3, modifier = Modifier.padding(start = 16.dp))
    }
}
