package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.platform.rememberFilePicker
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.UiState

private fun effortLabel(e: String?): String? = when (e) {
    "low" -> "낮음"
    "medium" -> "보통"
    "high" -> "높음"
    "max" -> "최대"
    else -> null
}

/**
 * Message box. Left: attach a file (+) and the model in use (provider, model, reasoning effort) which opens the
 * model sheet. Right: the blue send button. Enter sends and Shift+Enter adds a line on keyboards.
 */
@Composable
fun Composer(state: UiState, model: AppModel, enabled: Boolean, onSend: (String) -> Unit, hint: String?, modifier: Modifier = Modifier, initialText: String = "") {
    var text by remember(initialText) { mutableStateOf(initialText) }
    val s = LocalAeonStatus.current
    val scheme = MaterialTheme.colorScheme
    val pick = rememberFilePicker(model::attach)
    val canSend = enabled && !state.uploading && text.isNotBlank()
    fun send() {
        if (!canSend) return
        onSend(text)
        text = ""
    }
    LaunchedEffect(Unit) { if (state.models.current == null) model.loadModels() }
    val cur = state.models.current
    val modelLabel = when {
        cur == null -> "모델 선택"
        cur.model.isBlank() -> cur.providerName.ifBlank { cur.provider }
        else -> cur.model
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.attachments.isNotEmpty() || state.uploading) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.attachments.forEach { a ->
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(scheme.surfaceVariant).padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AeonIcons.File, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Text(a.name, Modifier.padding(start = 6.dp).widthIn(max = 180.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.size(36.dp).clickable { model.removeAttachment(a.path) }.semantics { contentDescription = "첨부 빼기: ${a.name}" }, contentAlignment = Alignment.Center) {
                            Icon(AeonIcons.Close, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                if (state.uploading) Text("올리는 중…", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = s.fg3)
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(scheme.surface).border(1.dp, scheme.outlineVariant, RoundedCornerShape(26.dp)).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        ) {
            Box(Modifier.coachTarget("composer.input").fillMaxWidth().heightIn(min = 28.dp, max = 160.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text("무엇을 맡길까요?", color = s.fg3, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = enabled,
                    textStyle = TextStyle(color = scheme.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && !ev.isShiftPressed) {
                            send()
                            true
                        } else false
                    },
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.coachTarget("composer.attach").size(40.dp).clip(CircleShape).background(scheme.surfaceVariant).clickable(enabled = enabled && !state.uploading) { pick() }.semantics { contentDescription = "파일 올리기" },
                    contentAlignment = Alignment.Center,
                ) { Icon(AeonIcons.Plus, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(20.dp)) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Row(
                    Modifier.coachTarget("composer.model").heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(scheme.surfaceVariant)
                        .clickable { model.openModelSheet() }.padding(horizontal = 12.dp).semantics { contentDescription = "모델 바꾸기: $modelLabel" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(AeonIcons.Cpu, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Text(modelLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    effortLabel(cur?.reasoningEffort)?.let { Text("· 추론 $it", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1) }
                }
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (canSend) scheme.primary else scheme.surfaceVariant)
                        .clickable(enabled = canSend) { send() }.semantics { contentDescription = "보내기" },
                    contentAlignment = Alignment.Center,
                ) { Icon(AeonIcons.Send, contentDescription = null, tint = if (canSend) scheme.onPrimary else s.fg3, modifier = Modifier.size(20.dp)) }
            }
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = s.fg3, modifier = Modifier.padding(start = 16.dp))
    }
}
