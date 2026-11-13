package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

fun riskInfo(a: Approval): RiskInfo = if (a.tool == "skill.create") RiskInfo("스킬", 2) { _ -> "스킬을 만들까요?" } else if (a.tool == "skill.update") RiskInfo("스킬", 2) { _ -> "스킬을 바꿀까요?" } else when (a.risk) {
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
fun ApprovalCard(a: Approval, seenAt: Long?, sourceLabel: String, onDecide: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalAeonStatus.current
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val now = nowMillis()
    val info = riskInfo(a)
    val high = info.level == 3 || a.irreversible
    val isFileAction = a.risk == "read" || a.risk == "write" || a.risk == "delete"
    val isSkill = a.tool == "skill.create" || a.tool == "skill.update"
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
        if (isSkill) {
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
        if (a.irreversible) Text("되돌릴 수 없어요", style = MaterialTheme.typography.labelLarge, color = s.danger)
        // What will run or leave this machine (a command line, a URL, a request body) is always shown as it will be
        // used: it is what the owner is approving. For file actions the names and folders above are enough, and the
        // exact request is one tap away.
        val always = !isSkill && (a.risk == "exec" || a.risk == "network_write" || a.risk == "external_effect" || info.kind == "확인")
        if (!always && !isSkill) Text(if (details) "자세히 닫기" else "자세히", color = scheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { details = !details })
        if (always || (details && !isSkill)) {
            Column(Modifier.fillMaxWidth().background(scheme.surfaceVariant, RoundedCornerShape(AeonShape.CONTROL.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a.tool, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                if (isFileAction) a.resources.forEach { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                SelectionContainer { Text(a.summary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("거부", { onDecide(false) }, Modifier.weight(1f).focusRequester(denyFocus), kind = ButtonKind.Strong)
            AeonButton(
                if (allowReady) "허용" else "잠시만…",
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
