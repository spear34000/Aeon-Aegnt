package dev.aeon.ui.screens

import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.ButtonKind
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Where the real controls are on screen, so the coach marks can point at them. */
class CoachRegistry {
    val rects = mutableStateMapOf<String, Rect>()
}

val LocalCoach = staticCompositionLocalOf<CoachRegistry?> { null }

/** Marks a real control as something the coach marks can highlight. Costs nothing when no registry is present. */
fun Modifier.coachTarget(id: String): Modifier = composed {
    val registry = LocalCoach.current ?: return@composed this
    onGloballyPositioned { registry.rects[id] = it.boundsInRoot() }
}

private class CoachStep(val target: String, val title: String, val body: String)

/** Order of the tour. A step whose control is not on screen (for example on the wide layout) is left out. */
private val STEPS = listOf(
    CoachStep("composer.input", "맡길 일을 적으세요", "예: “다운로드 폴더에서 오래된 설치 파일 정리해 줘”. 한 일은 단계마다 기록으로 남습니다."),
    CoachStep("composer.attach", "파일 올리기", "문서나 이미지를 작업 폴더에 올리고 함께 맡길 수 있습니다."),
    CoachStep("composer.model", "모델 고르기", "제공자, 모델, 추론 강도를 여기서 바꿉니다. API 키는 암호화해서 보냅니다."),
    CoachStep("nav.feed", "피드에서 승인하기", "되돌릴 수 없는 일은 실행 전에 여기서 묻습니다. 누르지 않으면 실행하지 않습니다."),
    CoachStep("nav.explore", "둘러보기", "연동, MCP 서버, 스킬을 찾아 연결합니다."),
    CoachStep("nav.agent", "내 에이전트", "부르는 이름과 말투, 기기, 키, 약관과 권한을 언제든 다시 바꿀 수 있습니다."),
)

/**
 * Coach marks over the real main screen: everything is dimmed except the control being explained, and a card
 * next to it says what it does. Taps do not reach the screen underneath while the tour is open.
 */
@Composable
fun CoachOverlay(index: Int, onIndex: (Int) -> Unit, onDone: () -> Unit) {
    val registry = LocalCoach.current ?: return
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val steps = STEPS.filter { registry.rects.containsKey(it.target) }
    // The controls report their position after the first layout. If none ever does, there is nothing to point at.
    LaunchedEffect(steps.isEmpty()) {
        if (steps.isEmpty()) {
            delay(2500)
            if (STEPS.none { registry.rects.containsKey(it.target) }) onDone()
        }
    }
    if (steps.isEmpty()) return
    val i = index.coerceIn(0, steps.lastIndex)
    val step = steps[i]
    var origin by remember { mutableStateOf(Offset.Zero) }
    var tipHeight by remember { mutableStateOf(0) }
    val pad = with(density) { 6.dp.toPx() }
    val raw = registry.rects[step.target] ?: return
    val target by animateRectAsState(Rect(raw.left - origin.x - pad, raw.top - origin.y - pad, raw.right - origin.x + pad, raw.bottom - origin.y + pad), tween(260), label = "coach")
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f)

    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }.pointerInput(Unit) { detectTapGestures { } }
            .semantics { contentDescription = "사용법 안내 ${i + 1} / ${steps.size}" },
    ) {
        val screenH = with(density) { maxHeight.toPx() }
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(scrim)
            val r = minOf(target.height / 2f, 28.dp.toPx())
            drawRoundRect(Color.Black, Offset(target.left, target.top), Size(target.width, target.height), CornerRadius(r, r), blendMode = BlendMode.Clear)
        }
        // The card goes on the side of the control that has more room.
        val gap = with(density) { 14.dp.toPx() }
        val above = target.center.y > screenH / 2f
        val y = if (above) (target.top - gap - tipHeight).coerceAtLeast(gap) else (target.bottom + gap).coerceAtMost(screenH - tipHeight - gap)
        Column(
            Modifier.align(Alignment.TopCenter).offset { IntOffset(0, y.roundToInt()) }.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth()
                .onSizeChanged { tipHeight = it.height }
                .clip(RoundedCornerShape(AeonShape.CARD.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${i + 1} / ${steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(step.title, style = MaterialTheme.typography.titleLarge)
            Text(step.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AeonButton("건너뛰기", onDone, kind = ButtonKind.Ghost, wide = true)
                Spacer(Modifier.weight(1f))
                if (i > 0) AeonButton("이전", { onIndex(i - 1) }, kind = ButtonKind.Secondary, wide = true)
                AeonButton(
                    if (i == steps.lastIndex) "완료" else "다음",
                    {
                        haptic.performHapticFeedback(if (i == steps.lastIndex) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
                        if (i == steps.lastIndex) onDone() else onIndex(i + 1)
                    },
                    kind = ButtonKind.Primary, wide = true,
                )
            }
        }
    }
}
