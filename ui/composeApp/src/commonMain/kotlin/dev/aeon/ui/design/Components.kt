package dev.aeon.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Primary = accent fill, Strong = ink fill (the safe, decisive answer), Secondary = soft fill, Destructive = red outline. */
enum class ButtonKind { Primary, Strong, Secondary, Destructive, Ghost }

/** Pill buttons, 48dp tall on phones and 40dp on wide screens; one primary per screen. */
@Composable
fun AeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    enabled: Boolean = true,
    wide: Boolean = false,
    icon: String? = null,
) {
    val s = LocalAeonStatus.current
    val scheme = MaterialTheme.colorScheme
    val (bg, fg, border) = when (kind) {
        ButtonKind.Primary -> Triple(scheme.primary, scheme.onPrimary, null)
        ButtonKind.Strong -> Triple(scheme.onSurface, scheme.background, null)
        ButtonKind.Secondary -> Triple(scheme.surfaceVariant, scheme.onSurface, null)
        ButtonKind.Destructive -> Triple(Color.Transparent, s.danger, s.danger)
        ButtonKind.Ghost -> Triple(Color.Transparent, scheme.onSurfaceVariant, null)
    }
    val alpha = if (enabled) 1f else 0.4f
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .defaultMinSize(minHeight = if (wide) 40.dp else 48.dp)
            .clip(shape)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .then(if (border != null) Modifier.border(1.5.dp, border.copy(alpha = alpha), shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Glyph(icon, color = fg.copy(alpha = alpha), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg.copy(alpha = alpha), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}


enum class Tone { Neutral, Accent, Ok, Warn, Danger }

/** Pill chip: colour + icon + word, never colour alone. */
@Composable
fun Chip(text: String, tone: Tone, icon: String? = null, modifier: Modifier = Modifier) {
    val s = LocalAeonStatus.current
    val scheme = MaterialTheme.colorScheme
    val (fg, bg) = when (tone) {
        Tone.Neutral -> scheme.onSurfaceVariant to scheme.surfaceVariant
        Tone.Accent -> s.onAccentSoft to s.accentSoft
        Tone.Ok -> s.ok to s.okSoft
        Tone.Warn -> s.warn to s.warnSoft
        Tone.Danger -> s.danger to s.dangerSoft
    }
    Row(
        modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Glyph(icon, color = fg, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

/** The task-status chip (design: 계획 중, 실행 중, 승인 대기, 한도 대기, 끝남, 실패, 멈춤). */
@Composable
fun TaskStatusChip(status: String) {
    val (text, tone, icon) = when (status) {
        "PENDING" -> Triple("계획 중", Tone.Neutral, "◌")
        "RUNNING" -> Triple("실행 중", Tone.Accent, "◌")
        "RETRY_WAIT" -> Triple("다시 시도 중", Tone.Warn, "◌")
        "WAITING_FOR_APPROVAL" -> Triple("승인 대기", Tone.Warn, "✋")
        "WAITING_FOR_QUOTA" -> Triple("한도 대기", Tone.Warn, "⌛")
        "WAITING_FOR_DEVICE" -> Triple("기기 대기", Tone.Warn, "▭")
        "COMPLETED" -> Triple("끝남", Tone.Ok, "⚑")
        "FAILED" -> Triple("실패", Tone.Danger, "✕")
        "CANCELLED" -> Triple("멈춤", Tone.Neutral, "■")
        else -> Triple(status, Tone.Neutral, "·")
    }
    Chip(text, tone, icon)
}

/**
 * Verification badge. The agent's own "done" is shown separately (see [AgentReport]); this badge only
 * states what was checked against the real files.
 */
@Composable
fun VerifyBadge(status: String?, failedCount: Int = 0) {
    when (status) {
        "verified" -> Chip("실제 파일로 확인됨", Tone.Ok, "✓")
        "user_confirmed" -> Chip("사용자가 확인함", Tone.Accent, "✓")
        "failed" -> Chip(if (failedCount > 0) "확인 실패 · 파일 ${failedCount}개 없음" else "확인 실패", Tone.Danger, "✕")
        "unverified" -> Chip("확인 안 됨 · 검사 기준 없음", Tone.Neutral, "○")
        null -> Chip("확인 전", Tone.Neutral, "◌")
        else -> Chip(status, Tone.Neutral, "·")
    }
}

/** Risk band: kind + word + three bars. Colour is secondary to the bars and the word. */
@Composable
fun RiskBand(kind: String, level: Int, label: String, reversibleLine: String?) {
    val s = LocalAeonStatus.current
    val tone = when (level) {
        1 -> Tone.Neutral
        2 -> Tone.Warn
        else -> Tone.Danger
    }
    val fg = when (tone) {
        Tone.Danger -> s.danger
        Tone.Warn -> s.warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(kind, color = fg, style = MaterialTheme.typography.labelLarge)
        Text("· 위험도 $label", color = fg, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(3) { i ->
                Box(Modifier.width(10.dp).height(6.dp).background(if (i < level) fg else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
            }
        }
    }
    if (reversibleLine != null) Text(reversibleLine, color = fg, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun Banner(text: String, tone: Tone, icon: String, onDismiss: (() -> Unit)? = null) {
    val s = LocalAeonStatus.current
    val (fg, bg) = when (tone) {
        Tone.Danger -> s.danger to s.dangerSoft
        Tone.Warn -> s.warn to s.warnSoft
        Tone.Ok -> s.ok to s.okSoft
        else -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(AeonShape.CARD.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, color = fg, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
        if (onDismiss != null) TextButton(onClick = onDismiss) { Text("닫기", color = fg, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
fun AeonTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    help: String? = null,
    placeholder: String = "",
    error: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    mono: Boolean = false,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    /** Shows a dash every N characters without changing the stored value (used for pairing codes). */
    group: Int = 0,
) {
    val s = LocalAeonStatus.current
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        error != null -> s.danger
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else s.fg3)
        Box(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
                .background(if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(AeonShape.CONTROL.dp))
                .border(if (focused || error != null) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(AeonShape.CONTROL.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            run {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    textStyle = TextStyle(
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else s.fg3,
                        fontSize = if (mono) 13.sp else 15.sp,
                        fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = when {
                        group > 0 -> GroupTransformation(group)
                        secret -> androidx.compose.ui.text.input.PasswordVisualTransformation()
                        else -> androidx.compose.ui.text.input.VisualTransformation.None
                    },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        if (value.isEmpty()) Text(placeholder, color = s.fg3, style = MaterialTheme.typography.bodyLarge)
                        inner()
                    },
                )
            }
        }
        val help2 = error ?: help
        if (help2 != null) Text(help2, style = MaterialTheme.typography.bodySmall, color = if (error != null) s.danger else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Switch 44x24 with the on/off word next to it (design: never a bare toggle for meaningful settings). */
@Composable
fun AeonSwitch(on: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.defaultMinSize(minHeight = 48.dp).clickable(enabled = enabled) { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (enabled) scheme.onSurface else LocalAeonStatus.current.fg3)
        Text(if (on) "켬" else "끔", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(width = 44.dp, height = 24.dp)
                .background(if (on) scheme.primary else scheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(18.dp).background(if (on) scheme.onPrimary else scheme.surface, RoundedCornerShape(9.dp)))
        }
    }
}

/** Card surface used across screens: 20dp radius, no border and no shadow; the page behind it is slightly darker. */
@Composable
fun Card(modifier: Modifier = Modifier, tint: Color? = null, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AeonShape.CARD.dp))
            .background(tint ?: MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = { content() },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = LocalAeonStatus.current.fg3)
}

@Composable
fun EmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(8.dp)); action() }
    }
}


/**
 * Displays the value with a dash every [group] characters. The stored value has no dashes, so typing, pasting
 * and deleting never fight a reformat (which is what dropped characters before).
 */
class GroupTransformation(private val group: Int) : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        val out = StringBuilder()
        for (i in raw.indices) {
            if (i > 0 && i % group == 0) out.append('-')
            out.append(raw[i])
        }
        val mapping = object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = if (offset <= 0) 0 else offset + (offset - 1) / group
            override fun transformedToOriginal(offset: Int): Int {
                var dashes = 0
                var o = 0
                while (o < offset && o < out.length) {
                    if (out[o] == '-') dashes++
                    o++
                }
                return (offset - dashes).coerceIn(0, raw.length)
            }
        }
        return androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(out.toString()), mapping)
    }
}
