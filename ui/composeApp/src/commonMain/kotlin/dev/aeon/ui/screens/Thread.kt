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
        // On the phone the top bar already says who this is; the title row appears only once a task is open.
        if (wide || state.selectedTaskId != null) ConversationHeader(summary?.goal, wide, onDetail = { state.selectedTaskId?.let { model.selectTask(it, asDetail = true) } }, hasTask = state.selectedTaskId != null)
        if (offline) {
            Banner("서버에 연결할 수 없습니다. Aeon 서버가 켜져 있고 같은 네트워크에 있는지 확인하세요.", Tone.Danger, "⚠", null)
        }
        state.status?.setupProblem?.let {
            Banner("지금 고른 모델은 키가 없어 쓸 수 없습니다. 입력창의 모델 버튼에서 키를 넣거나 다른 모델을 고르세요.", Tone.Warn, "•")
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
                    item(key = "welcome") { WelcomeBlock(state, model) }
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
                    val pending: Approval? = state.approvals.firstOrNull { it.id == detail.pendingApprovalId && it.status == "pending" }
                    if (detail.steps.isNotEmpty()) {
                        item(key = "progress-${detail.id}") { ProgressCard(detail, waiting = pending != null) }
                    }
                    if (pending != null) {
                        item(key = "approval-${pending.id}") {
                            Column(Modifier.padding(start = 40.dp)) {
                                ApprovalCard(
                                    a = pending,
                                    seenAt = state.approvalSeenAt[pending.id],
                                    sourceLabel = detail.goal,
                                    onDecide = { model.decide(pending.id, it) },
                                    onAnswer = { model.decide(pending.id, true, it) },
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
                        "CANCELLED" -> item(key = "cancel-${detail.id}") { Chip("중단됨", Tone.Neutral) }
                    }
                }
            }
        }

        Composer(
            state, model,
            enabled = !offline,
            initialText = state.prefill.orEmpty(),
            onSend = { text -> model.createTask(text, emptyList()); model.consumePrefill() },
            hint = if (offline) "연결되면 보낼 수 있습니다." else null,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    LaunchedEffect(detail?.steps?.size, detail?.status) {
        if (detail != null) listState.animateScrollToItem(0)
    }
}

@Composable
private fun ConversationHeader(title: String?, wide: Boolean, onDetail: () -> Unit, hasTask: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title ?: "새 대화", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        if (hasTask) AeonButton(if (wide) "작업 상세" else "상세", onDetail, kind = ButtonKind.Ghost, wide = wide)
    }
}

@Composable
fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.widthIn(max = 480.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                .padding(horizontal = 15.dp, vertical = 11.dp),
        ) {
            Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Agent message: no bubble, the Æ mark, body text. Reports are labelled as the agent's words. */
@Composable
fun AgentMessage(text: String, body: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        androidx.compose.material3.Icon(dev.aeon.ui.design.AeonIcons.Mark, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp, end = 8.dp).size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (body != null) body()
        }
    }
}

@Composable
private fun ResultBlock(d: TaskDetail) {
    // The agent's own report and the check against the real files are shown apart: only the badge is a check.
    AgentMessage(d.result?.takeIf { it.isNotBlank() } ?: "작업을 마쳤습니다.") {
        VerifyBadge(d.verificationDetail?.status, d.verificationDetail?.evidence?.count { !it.passed } ?: 0)
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
        Text(d.note ?: "모델 사용 한도에 걸렸어요. 잠시 뒤 이어서 합니다.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("작업 정지", { model.cancel(d.id) }, kind = ButtonKind.Secondary)
            AeonButton("설정 바꾸기", { model.openSettings(dev.aeon.ui.state.SettingsPage.Models) }, kind = ButtonKind.Ghost)
        }
    }
}


private fun toolLabel(tool: String): String = when (tool) {
    "fs.list" -> "폴더 목록 보기"
    "fs.read" -> "파일 읽기"
    "fs.write" -> "파일 쓰기"
    "fs.delete" -> "파일 삭제"
    "http.get" -> "웹 주소 읽기"
    "http.post" -> "외부로 보내기"
    "sandbox.exec" -> "격리된 명령 실행"
    else -> tool
}

/** What the agent has done so far, one line per action, with the real tool name beside each. */
@Composable
private fun ProgressCard(d: TaskDetail, waiting: Boolean) {
    val s = LocalAeonStatus.current
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(scheme.surface, RoundedCornerShape(AeonShape.CARD.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("한 일 ${d.steps.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TaskStatusChip(d.status)
        }
        d.steps.forEach { st ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(20.dp).background(if (st.ok) s.okSoft else s.dangerSoft, CircleShape), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(if (st.ok) dev.aeon.ui.design.AeonIcons.Check else dev.aeon.ui.design.AeonIcons.Close, contentDescription = if (st.ok) "성공" else "실패", tint = if (st.ok) s.ok else s.danger, modifier = Modifier.size(12.dp))
                }
                Text(toolLabel(st.tool), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(st.tool, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

private val SUGGESTIONS = listOf(
    "다운로드 폴더에서 오래된 설치 파일을 찾아 목록으로 알려 줘",
    "회의록 폴더의 파일을 읽고 요약 문서를 새로 만들어 줘",
    "작업 폴더에서 내용이 같은 파일을 찾아 줘",
)

/** New conversation: greets the owner the way they asked to be addressed and offers a few things to start from. */
@Composable
private fun WelcomeBlock(state: UiState, model: AppModel) {
    val p = state.profile
    val name = p.userName?.trim()?.takeIf { it.isNotEmpty() }
    val greeting = when {
        p.language == "en" -> if (name != null) "What can I take care of, $name?" else "What can I take care of?"
        p.tone == "casual" -> if (name != null) "$name, 뭘 맡길까?" else "뭘 맡길까?"
        else -> if (name != null) "${name}님, 무엇을 맡길까요?" else "무엇을 맡길까요?"
    }
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.Icon(dev.aeon.ui.design.AeonIcons.Mark, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
        Text(greeting, style = MaterialTheme.typography.headlineMedium)
        ListSurface(Modifier.padding(top = 12.dp)) {
            SUGGESTIONS.forEachIndexed { i, text ->
                if (i > 0) RowDivider(16.dp)
                Row(Modifier.fillMaxWidth().clickable { model.startWith(text) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Icon(dev.aeon.ui.design.AeonIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
