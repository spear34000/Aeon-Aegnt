package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.Approval
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.Tone
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.platform.koreanClock
import dev.aeon.ui.platform.koreanDayPrefix
import dev.aeon.ui.platform.nowMillis

/**
 * How an approval is described to the owner. [level] 3 means the action cannot be undone or leaves this machine: the
 * allow button is then drawn as a red outline. The judgement itself is made by the server's policy, not here.
 */
data class RiskInfo(val kind: String, val level: Int, val title: (Int) -> String)

fun riskInfo(a: Approval): RiskInfo = if (a.tool == "skill.run") RiskInfo("스크립트", 2) { _ -> "스크립트를 실행할까요?" } else if (a.tool == "skill.create") RiskInfo("스킬", 2) { _ -> "스킬을 만들까요?" } else if (a.tool == "skill.update") RiskInfo("스킬", 2) { _ -> "스킬을 바꿀까요?" } else if (a.tool == "memory.save") RiskInfo("기억", 2) { _ -> "이걸 기억할까요?" } else if (a.tool == "memory.forget") RiskInfo("기억", 3) { _ -> "이 기억을 지울까요?" } else when (a.risk) {
    "read" -> RiskInfo("읽기", 1) { n -> "파일 ${n}개를 읽을까요?" }
    "write" -> RiskInfo("쓰기", 2) { n -> "파일 ${n}개를 쓸까요?" }
    "delete" -> RiskInfo("삭제", 3) { n -> "파일 ${n}개를 삭제할까요?" }
    "exec" -> RiskInfo("명령 실행", 3) { _ -> "명령을 실행할까요?" }
    "network_write", "external_effect" -> RiskInfo("외부 전송", 3) { _ -> "외부로 보낼까요?" }
    else -> RiskInfo("확인", 3) { _ -> "이 작업을 실행할까요?" }
}

private fun fileName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
private fun folderOf(path: String): String = path.trimEnd('/', '\\').let { p -> val i = maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\')); if (i > 0) p.substring(0, i) else "" }

/**
 * Approval card. The title says what will happen, the targets are listed by name, and the exact request (full paths,
 * command line, URL) is one tap away. "Deny" is the strong button and has focus; "Allow" is drawn in red for actions
 * that cannot be undone and stays disabled for one second after the card appears, against accidental taps.
 */
@Composable
fun ApprovalCard(a: Approval, seenAt: Long?, sourceLabel: String, onDecide: (Boolean) -> Unit, modifier: Modifier = Modifier, onAnswer: (String) -> Unit = {}) {
    if (a.kind == "question") {
        QuestionCard(a, sourceLabel, onSkip = { onDecide(false) }, onAnswer = onAnswer, modifier = modifier)
        return
    }
    val s = LocalAeonStatus.current
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val now = nowMillis()
    val info = riskInfo(a)
    val isRun = a.tool == "skill.run"
    // How much holds a skill script back, from the server's own words on the card (isolation docker or host; "Isolation: NONE" for Python and shell).
    val isolation = a.resources.firstOrNull { it.startsWith("isolation:") }?.removePrefix("isolation:") ?: "docker"
    val noIsolation = isRun && a.detail?.contains("Isolation: NONE") == true
    val high = if (isRun) isolation == "host" else info.level == 3 || a.irreversible
    val isFileAction = a.risk == "read" || a.risk == "write" || a.risk == "delete"
    val isSkill = a.tool == "skill.create" || a.tool == "skill.update"
    // Remembering is not "sending out": the card shows the sentence exactly as it will be kept, and nothing else.
    val isMemory = a.tool == "memory.save" || a.tool == "memory.forget"
    val isForget = a.tool == "memory.forget"
    val leftMs = a.expiresAt - now
    var allowReady by remember(a.id) { mutableStateOf(seenAt == null || now - seenAt >= 1000) }
    var showAll by remember(a.id) { mutableStateOf(false) }
    var details by remember(a.id) { mutableStateOf(false) }
    val denyFocus = remember { FocusRequester() }
    LaunchedEffect(a.id) {
        denyFocus.requestFocus()
        if (!allowReady) {
            kotlinx.coroutines.delay(1000)
            allowReady = true
        }
    }
    Column(
        modifier.fillMaxWidth().background(scheme.surface, RoundedCornerShape(AeonShape.CARD.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(info.title(a.resources.size.coerceAtLeast(1)), style = MaterialTheme.typography.titleLarge)
                Text(sourceLabel, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ExpiryText(a.expiresAt, leftMs, now)
        }
        val shown = if (showAll) a.resources else a.resources.take(3)
        if (isRun) {
            val skill = a.resources.firstOrNull { it.startsWith("skill:") }?.removePrefix("skill:") ?: ""
            val script = a.resources.firstOrNull { it.startsWith("scripts/") } ?: ""
            val (label, tone) = when {
                isolation == "docker" -> "Docker 안에서 실행" to Tone.Ok
                noIsolation -> "이 컴퓨터에서 직접 · 격리 없음" to Tone.Danger
                else -> "이 컴퓨터 · Node 권한 제한" to Tone.Warn
            }
            Chip(label, tone)
            Column(Modifier.fillMaxWidth().background(scheme.background, RoundedCornerShape(AeonShape.CONTROL.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(skill, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                Text(script, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            when {
                noIsolation -> Banner("격리가 없어요. 이 스크립트는 내 계정 권한으로 이 컴퓨터에서 직접 돌아요. 파일을 읽고 지우고 인터넷으로 보낼 수 있어요. 코드를 읽어 본 뒤에만 허용하세요.", Tone.Danger, "⚠")
                isolation == "host" -> Banner("이 컴퓨터에서 Node 권한 모델로 돌아요. 실수를 막는 안전장치일 뿐 악의적인 코드를 막지는 못하고, 인터넷도 막지 않아요. 코드를 읽어 본 뒤에 허용하세요.", Tone.Warn, "⚠")
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("인터넷 없이, 읽기만 되는 Docker 상자 안에서 돌아요", "이 스킬의 복사본과 임시 폴더만 보여요. 내 파일은 안 보여요", "60초가 지나면 멈춰요").forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall, color = s.ok) }
                }
            }
            if (!a.detail.isNullOrBlank()) {
                Text(if (details) "코드 닫기" else "코드 보기", color = scheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { details = !details })
                if (details) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).background(scheme.surfaceVariant, RoundedCornerShape(AeonShape.CONTROL.dp)).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        SelectionContainer { Text(a.detail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        } else if (isMemory) {
            val full = a.detail?.takeIf { it.isNotBlank() } ?: a.summary.removePrefix("Remember: ").removePrefix("Forget: ")
            val text = full.substringBefore("\n← ")
            val old = full.substringAfter("\n← ", "").takeIf { it.isNotBlank() }
            val until = a.resources.firstOrNull { it.startsWith("expires:") }?.removePrefix("expires:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isForget) Chip(if (a.resources.firstOrNull() == "memory:preference") "일하는 방식" else "나에 대해", Tone.Neutral)
                if (until != null) Chip("${until.substring(5, 7).trimStart('0')}월 ${until.substring(8, 10).trimStart('0')}일까지", Tone.Warn)
            }
            Column(Modifier.fillMaxWidth().background(scheme.background, RoundedCornerShape(AeonShape.CONTROL.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.titleMedium) }
                if (old != null) Text("이전: $old", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (if (isForget) listOf("지우면 이전 기록도 함께 사라져요.") else listOf("이 문장 그대로 저장돼요. 다른 말은 덧붙지 않아요.", "앞으로 모든 작업에서 에이전트가 참고해요.", "내 에이전트 › 기억에서 언제든 지울 수 있어요.")).forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall, color = s.ok) }
            }
        } else if (isSkill) {
            // A skill steers every later task, so the owner sees what it is called, what it is for and, one tap away, what it says.
            val name = a.resources.firstOrNull()?.removePrefix("skill:") ?: ""
            val purpose = a.summary.substringAfter("\": ", "")
            Column(Modifier.fillMaxWidth().background(scheme.background, RoundedCornerShape(AeonShape.CONTROL.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                if (purpose.isNotBlank()) Text(purpose, style = MaterialTheme.typography.bodyMedium)
                a.resources.drop(1).takeIf { it.isNotEmpty() }?.let { files -> Text("파일 ${files.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            }
            if (!a.detail.isNullOrBlank()) {
                Text(if (details) "지침 닫기" else "지침 보기", color = scheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { details = !details })
                if (details) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).background(scheme.surfaceVariant, RoundedCornerShape(AeonShape.CONTROL.dp)).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        SelectionContainer { Text(a.detail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        } else if (shown.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(scheme.background, RoundedCornerShape(AeonShape.CONTROL.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { r ->
                    if (isFileAction) {
                        Column {
                            Text(fileName(r), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            folderOf(r).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    } else {
                        // Addresses and commands are shown whole: cutting a URL down to its last segment would hide the host.
                        SelectionContainer { Text(r, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (a.resources.size > 3) {
                    Text(if (showAll) "접기" else "${a.resources.size - 3}개 더 보기", color = scheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { showAll = !showAll })
                }
            }
        }
        if (a.irreversible && !isRun) Text("되돌릴 수 없어요", style = MaterialTheme.typography.labelLarge, color = s.danger)
        // What will run or leave this machine (a command line, a URL, a request body) is always shown as it will be
        // used: it is what the owner is approving. For file actions the names and folders above are enough, and the
        // exact request is one tap away.
        val always = !isSkill && !isRun && !isMemory && (a.risk == "exec" || a.risk == "network_write" || a.risk == "external_effect" || info.kind == "확인")
        if (!always && !isSkill && !isRun && !isMemory) Text(if (details) "자세히 닫기" else "자세히", color = scheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { details = !details })
        if (always || (details && !isSkill && !isRun && !isMemory)) {
            Column(Modifier.fillMaxWidth().background(scheme.surfaceVariant, RoundedCornerShape(AeonShape.CONTROL.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a.tool, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                if (isFileAction) a.resources.forEach { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                SelectionContainer { Text(a.summary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton(if (isForget) "그대로 두기" else if (isMemory) "기억하지 않기" else "거부", { onDecide(false) }, Modifier.weight(1f).focusRequester(denyFocus), kind = ButtonKind.Strong)
            AeonButton(
                if (!allowReady) "잠시만…" else if (isRun && isolation == "host") "그래도 허용" else if (isForget) "지우기" else if (isMemory) "기억하기" else "허용",
                { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDecide(true) },
                Modifier.weight(1f),
                kind = if (high) ButtonKind.Destructive else ButtonKind.Primary,
                enabled = allowReady,
            )
        }
    }
}

@Composable
private fun ExpiryText(expiresAt: Long, leftMs: Long, now: Long) {
    val s = LocalAeonStatus.current
    val mins = leftMs / 60_000
    when {
        leftMs <= 0 -> Text("만료됨", color = s.danger, style = MaterialTheme.typography.labelMedium)
        leftMs < 60_000 -> Text("${leftMs / 1000}초 남음", color = s.danger, style = MaterialTheme.typography.labelLarge)
        leftMs < 10 * 60_000 -> Text("${mins}:${((leftMs / 1000) % 60).toString().padStart(2, '0')} 남음", color = s.warn, style = MaterialTheme.typography.labelLarge)
        leftMs < 60 * 60_000 -> Text("${mins}분 남음", color = s.warn, style = MaterialTheme.typography.labelMedium)
        else -> Text("${koreanDayPrefix(expiresAt, now)}${koreanClock(expiresAt)}까지", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * A question from the agent (it needs a detail it cannot find itself). Suggested answers are one tap, the owner can type their
 * own, or skip: then the agent goes on with the most careful choice or says what it could not decide.
 */
@Composable
private fun QuestionCard(a: Approval, sourceLabel: String, onSkip: () -> Unit, onAnswer: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val now = nowMillis()
    var text by remember(a.id) { mutableStateOf("") }
    val leftMs = a.expiresAt - now
    Column(modifier.fillMaxWidth().background(scheme.surface, RoundedCornerShape(AeonShape.CARD.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("에이전트가 물어봐요", Tone.Accent)
                Text(a.summary, style = MaterialTheme.typography.titleLarge)
                Text(sourceLabel, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ExpiryText(a.expiresAt, leftMs, now)
        }
        if (a.resources.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                a.resources.forEach { o -> PillChoice(o, text == o) { text = o } }
            }
        }
        AeonTextField(text, { text = it.take(1000) }, "답", placeholder = "직접 답하기", singleLine = false, minHeight = 52.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("건너뛰기", onSkip, Modifier.weight(1f), kind = ButtonKind.Strong)
            AeonButton("답하기", { onAnswer(text.trim()) }, Modifier.weight(1f), kind = ButtonKind.Primary, enabled = text.isNotBlank())
        }
        Text("건너뛰면 에이전트가 가장 조심스러운 쪽으로 진행하거나, 정하지 못한 점을 말하고 끝내요.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}
