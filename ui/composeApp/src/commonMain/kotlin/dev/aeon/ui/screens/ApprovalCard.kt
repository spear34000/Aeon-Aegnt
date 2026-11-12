package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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

fun riskInfo(a: Approval): RiskInfo = when (a.risk) {
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
        if (shown.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(scheme.background, RoundedCornerShape(AeonShape.CONTROL.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { r ->
                    Column {
                        Text(fileName(r), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        folderOf(r).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (a.resources.size > 3) {
                    Text(if (showAll) "접기" else "${a.resources.size - 3}개 더 보기", color = scheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { showAll = !showAll })
                }
            }
        }
        if (a.irreversible) Text("되돌릴 수 없어요", style = MaterialTheme.typography.labelLarge, color = s.danger)
        Text(if (details) "자세히 닫기" else "자세히", color = scheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { details = !details })
        if (details) {
            Column(Modifier.fillMaxWidth().background(scheme.surfaceVariant, RoundedCornerShape(AeonShape.CONTROL.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                a.resources.forEach { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                SelectionContainer { Text(a.summary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
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
