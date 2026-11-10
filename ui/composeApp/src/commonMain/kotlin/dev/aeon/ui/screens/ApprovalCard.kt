package dev.aeon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.Approval
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.RiskBand
import dev.aeon.ui.platform.koreanClock
import dev.aeon.ui.platform.koreanDayPrefix
import dev.aeon.ui.platform.nowMillis

/** Risk kind, level (1 low, 2 medium, 3 high) and the title for each tool class. */
data class RiskInfo(val kind: String, val level: Int, val levelLabel: String, val title: (Int) -> String)

fun riskInfo(a: Approval): RiskInfo = when (a.risk) {
    "read" -> RiskInfo("읽기", 1, "낮음") { n -> "파일 ${n}개 읽기" }
    "write" -> RiskInfo("쓰기", 2, "보통") { n -> "파일 ${n}개 새로 만들기 / 덮어쓰기" }
    "delete" -> RiskInfo("파일 삭제", 3, "높음") { n -> "파일 ${n}개 영구 삭제" }
    "exec" -> RiskInfo("명령 실행", 3, "높음") { _ -> "명령 1개 실행" }
    "network_write", "external_effect" -> RiskInfo("외부 전송", 3, "높음") { n -> "데이터 ${n}개를 외부 서버로 전송" }
    else -> RiskInfo("확인 필요", 3, "높음") { _ -> "작업 1개 실행" }
}

/**
 * Approval card (design: 01 승인 카드). Shows what, where, how risky, when it expires and whether it can be undone.
 * "Allow" is disabled for one second after the card appears (against accidental taps); "Deny" has focus by default.
 */
@Composable
fun ApprovalCard(a: Approval, seenAt: Long?, sourceLabel: String, onDecide: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalAeonStatus.current
    val now = nowMillis()
    val info = riskInfo(a)
    val high = info.level == 3
    val leftMs = a.expiresAt - now
    var allowReady by remember(a.id) { mutableStateOf(seenAt == null || now - seenAt >= 1000) }
    var showAll by remember(a.id) { mutableStateOf(false) }
    val denyFocus = remember { FocusRequester() }
    LaunchedEffect(a.id) {
        denyFocus.requestFocus()
        if (!allowReady) {
            kotlinx.coroutines.delay(1000)
            allowReady = true
        }
    }
    val border = if (high) s.danger else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(AeonShape.BUBBLE.dp))
            .border(1.dp, border, RoundedCornerShape(AeonShape.BUBBLE.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1 risk band + 2 expiry
        Row(verticalAlignment = Alignment.CenterVertically) {
            RiskBand(info.kind, info.level, info.levelLabel, null)
            Spacer(Modifier.weight(1f))
            ExpiryText(a.expiresAt, leftMs, now)
        }
        // 3 what
        Text(info.title(a.resources.size.coerceAtLeast(1)), style = MaterialTheme.typography.titleMedium)
        // 5 where (monospace, first 3, then "N개 더 보기")
        val shown = if (showAll) a.resources else a.resources.take(3)
        if (shown.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shown.forEach { r ->
                    SelectionContainer {
                        Text(r, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (a.resources.size > 3) {
                    Text(
                        if (showAll) "접기" else "${a.resources.size - 3}개 더 보기",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { showAll = !showAll },
                    )
                }
            }
        }
        // The exact action, as the server recorded it (command line, URL with query, body preview). Copyable.
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(AeonShape.CONTROL.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("정확한 내용", style = MaterialTheme.typography.labelMedium, color = s.fg3)
            SelectionContainer { Text(a.summary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
        }
        // 6 result line. Fails closed: Aeon has no undo, so it never claims an action is reversible.
        if (a.irreversible || a.risk != "read") {
            Text(
                if (a.irreversible) "휴지통을 거치지 않습니다. 되돌릴 수 없습니다."
                else "Aeon에는 이 작업을 되돌리는 기능이 없습니다. 되돌릴 수 있는지 확인되지 않았습니다.",
                color = s.danger,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        // 7 decision buttons: same size, deny on the left, allow on the right
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton(
                "거부",
                onClick = { onDecide(false) },
                kind = ButtonKind.Secondary,
                modifier = Modifier.weight(1f).focusRequester(denyFocus),
            )
            AeonButton(
                if (allowReady) "이 한 번만 허용" else "허용 준비 중…",
                onClick = { onDecide(true) },
                kind = if (high) ButtonKind.Destructive else ButtonKind.Primary,
                enabled = allowReady,
                modifier = Modifier.weight(1f),
            )
        }
        // 8 scope and source
        Text(
            "허용은 이 요청 1건에만 적용됩니다 · $sourceLabel",
            style = MaterialTheme.typography.bodySmall,
            color = s.fg3,
        )
    }
}

@Composable
private fun ExpiryText(expiresAt: Long, leftMs: Long, now: Long) {
    val s = LocalAeonStatus.current
    val mins = leftMs / 60_000
    when {
        leftMs <= 0 -> Text("만료됨", color = s.danger, style = MaterialTheme.typography.labelMedium)
        leftMs < 60_000 -> Text("${leftMs / 1000}초 남음", color = s.danger, style = MaterialTheme.typography.labelLarge)
        leftMs < 10 * 60_000 -> {
            val sec = (leftMs / 1000) % 60
            Text("${mins}:${sec.toString().padStart(2, '0')} 남음", color = s.warn, style = MaterialTheme.typography.labelLarge)
        }
        leftMs < 60 * 60_000 -> Text("${mins}분 남음", color = s.warn, style = MaterialTheme.typography.labelMedium)
        else -> Text("${koreanDayPrefix(expiresAt, now)}${koreanClock(expiresAt)}까지", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}
