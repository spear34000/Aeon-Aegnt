package dev.aeon.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.Profile
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.rememberPermissionController
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.INTRO_PAGES
import dev.aeon.ui.state.PROFILE_PAGES
import dev.aeon.ui.state.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Common frame of the first-run screens: back, title with progress, skip, scrolling body and a fixed footer. */
@Composable
private fun SetupFrame(
    title: String?,
    progress: String?,
    onBack: (() -> Unit)?,
    onSkip: (() -> Unit)?,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) CircleButton(AeonIcons.Back, "이전", onBack) else Spacer(Modifier.width(48.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
                    if (progress != null) Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onSkip != null) AeonButton("건너뛰기", onSkip, kind = ButtonKind.Ghost, wide = true) else Spacer(Modifier.width(48.dp))
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), content = content)
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = footer)
        }
    }
}

@Composable
private fun Dots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.semantics { contentDescription = "${current + 1} / $count" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 20.dp else 6.dp, tween(220), label = "dot")
            Box(Modifier.size(width = w, height = 6.dp).background(if (i == current) scheme.onSurface else scheme.outlineVariant, CircleShape))
        }
    }
}

/**
 * Splash: the mark settles into place while saved progress and the pairing are restored. It leaves as soon as
 * both the animation and the restore are finished, so a slow store never shows a half-ready screen.
 */
@Composable
fun SplashScreen(ready: Boolean, onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val appear = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    val sweep by rememberInfiniteTransition(label = "load").animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        delay(350)
        shown = true
    }
    LaunchedEffect(ready, shown) { if (ready && shown) onDone() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                AeonIcons.Mark, contentDescription = "Aeon", tint = scheme.primary,
                modifier = Modifier.size(96.dp).graphicsLayer {
                    val v = appear.value
                    scaleX = 0.72f + 0.28f * v
                    scaleY = 0.72f + 0.28f * v
                    alpha = v
                    rotationZ = (1f - v) * -45f
                },
            )
            Text("Aeon", Modifier.padding(top = 18.dp).graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 24f }, style = MaterialTheme.typography.headlineMedium)
            Text("한 일은 기록으로, 위험한 일은 먼저 묻고", Modifier.padding(top = 6.dp).graphicsLayer { alpha = appear.value }, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(width = 120.dp, height = 4.dp).clip(CircleShape).background(scheme.surfaceVariant)) {
                Box(Modifier.size(width = 48.dp, height = 4.dp).graphicsLayer { translationX = (sweep * 168.dp.toPx()) - 48.dp.toPx() }.background(scheme.primary, CircleShape))
            }
            Text("준비하는 중", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------- intro

private class IntroPage(val title: String, val body: String, val art: @Composable () -> Unit)

@Composable
private fun ArtCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CARD.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

@Composable
private fun ArtRow(icon: ImageVector, text: String, trailing: String? = null, ok: Boolean = false) {
    val s = LocalAeonStatus.current
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).background(if (ok) s.okSoft else scheme.surfaceVariant, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (ok) s.ok else scheme.onSurface, modifier = Modifier.size(18.dp))
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = if (ok) s.ok else scheme.onSurfaceVariant)
    }
}

private val INTRO = listOf(
    IntroPage("한 일은 기록으로 남깁니다", "어떤 파일을 읽고 무엇을 바꿨는지 단계마다 남기고, 결과는 실제 파일을 열어 확인합니다. ‘완료’라는 말만 믿지 않습니다.") {
        ArtCard {
            ArtRow(AeonIcons.Check, "폴더 살펴보기", ok = true)
            ArtRow(AeonIcons.Check, "회의록 4개 읽기", ok = true)
            ArtRow(AeonIcons.Check, "요약 문서 쓰기", "확인됨 · 파일이 있음", ok = true)
        }
    },
    IntroPage("되돌릴 수 없는 일은\n먼저 묻습니다", "삭제, 명령 실행, 외부 전송은 승인 없이는 실행하지 않습니다. 답하지 않으면 실행하지 않고, 허용은 그 한 번에만 적용됩니다.") {
        ArtCard {
            Chip("되돌릴 수 없음", Tone.Danger)
            Text("파일 3개를 영구 삭제할까요?", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(44.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape), contentAlignment = Alignment.Center) {
                    Text("거부", color = MaterialTheme.colorScheme.background, style = MaterialTheme.typography.labelLarge)
                }
                Box(Modifier.weight(1f).height(44.dp).background(LocalAeonStatus.current.dangerSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Text("이 한 번만 허용", color = LocalAeonStatus.current.danger, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    },
    IntroPage("내 기기에서 일합니다", "에이전트는 내 컴퓨터에서 실행되고 기록도 거기에 남습니다. 이 앱은 그 컴퓨터에 서명된 요청을 보내는 리모컨입니다. API 키는 암호화해서 보내고 다시 보여 주지 않습니다.") {
        ArtCard {
            ArtRow(AeonIcons.Phone, "이 기기", "요청에 서명")
            ArtRow(AeonIcons.Terminal, "Aeon이 실행되는 내 컴퓨터", "기록 보관")
            ArtRow(AeonIcons.Key, "API 키", "암호화 보관")
        }
    },
    IntroPage("모델과 도구는\n내가 고릅니다", "쓸 모델 제공자와 모델, 추론 강도를 직접 고르고, 필요한 연동과 MCP 서버, 스킬만 연결합니다. 내 기기 밖으로 요청을 보내는 제공자는 처음에 동의를 받습니다.") {
        ArtCard {
            ArtRow(AeonIcons.Cpu, "모델", "제공자 · 모델 · 추론 강도")
            ArtRow(AeonIcons.Plug, "연동과 MCP 서버", "필요한 것만")
            ArtRow(AeonIcons.Book, "스킬", "폴더로 추가")
        }
    },
)

/** 앱 소개: four pages that can be swiped, skipped at any point, and read again later from the settings. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IntroScreen(state: UiState, model: AppModel) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = state.setup.introPage.coerceIn(0, INTRO_PAGES - 1)) { INTRO_PAGES }
    // Swipes are saved so the intro reopens on the same page; "이전" from the model moves the pager.
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage != state.setup.introPage) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            model.setIntroPage(pager.currentPage)
        }
    }
    LaunchedEffect(state.setup.introPage) { if (pager.currentPage != state.setup.introPage) pager.animateScrollToPage(state.setup.introPage) }
    val last = pager.currentPage == INTRO_PAGES - 1
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                AeonButton("건너뛰기", { model.finishIntro() }, kind = ButtonKind.Ghost, wide = true)
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                val p = INTRO[i]
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    p.art()
                    Text(p.title, Modifier.padding(top = 32.dp), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text(p.body, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Dots(INTRO_PAGES, pager.currentPage, Modifier.padding(bottom = 20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pager.currentPage > 0) AeonButton("이전", { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, kind = ButtonKind.Secondary)
                    AeonButton(
                        if (last) "계속" else "다음",
                        { if (last) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); model.finishIntro() } else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        Modifier.weight(1f), kind = ButtonKind.Primary,
                    )
                }
            }
        }
    }
}

/** 웰컴: the brand, one line of what it is, and the button that starts the setup. */
@Composable
fun WelcomeScreen(model: AppModel) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize().padding(horizontal = 24.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(
                    Modifier.size(104.dp).graphicsLayer { scaleX = 0.8f + 0.2f * appear.value; scaleY = 0.8f + 0.2f * appear.value; alpha = appear.value }
                        .background(scheme.primary, RoundedCornerShape(30.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(AeonIcons.Mark, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(60.dp)) }
                Text("Aeon", Modifier.padding(top = 28.dp), style = MaterialTheme.typography.headlineMedium)
                Text("내 기기에서 일하는 내 에이전트", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text("설정은 2분쯤 걸립니다. 중간에 닫아도 이어서 할 수 있습니다.", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AeonButton("시작하기", { haptic.performHapticFeedback(HapticFeedbackType.LongPress); model.beginSetup() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
                AeonButton("소개 다시 보기", { model.replayIntro() }, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
            }
        }
    }
}

/** 권한과 약관: each permission is asked on its own with its reason; required and optional terms are separate. */
@Composable
fun TermsScreen(state: UiState, model: AppModel) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val controller = rememberPermissionController()
    val consents = state.setup.consents
    val terms = termsHere()
    var open by remember { mutableStateOf<Term?>(null) }
    var decline by remember { mutableStateOf(false) }
    fun value(id: String) = when (id) {
        "service" -> consents.service
        "data" -> consents.data
        "online" -> consents.online
        else -> consents.background
    }
    fun set(id: String, on: Boolean) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        model.setConsents(
            when (id) {
                "service" -> consents.copy(service = on)
                "data" -> consents.copy(data = on)
                "online" -> consents.copy(online = on)
                else -> consents.copy(background = on)
            },
        )
    }
    val all = terms.all { value(it.id) }
    SetupFrame(
        "권한과 약관", null, onBack = { model.setupBack() }, onSkip = null,
        footer = {
            if (!consents.requiredOk) Text("필수 항목 두 가지에 동의해야 계속할 수 있습니다.", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            AeonButton("동의하고 계속", { haptic.performHapticFeedback(HapticFeedbackType.LongPress); model.acceptTerms() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = consents.requiredOk)
            AeonButton("동의하지 않음", { decline = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
        },
    ) {
        if (state.setup.storageError) Banner("이 기기에 진행 상태를 저장하지 못했습니다. 앱을 닫으면 처음부터 다시 해야 할 수 있습니다.", Tone.Warn, "⚠")
        GroupHeader("권한")
        PermissionRows(controller)
        GroupHeader("약관")
        ListSurface {
            Row(Modifier.fillMaxWidth().clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); model.setConsents(consents.copy(service = !all, data = !all, online = !all, background = !all && terms.any { it.id == "background" })) }.padding(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CheckBox(all, "전체 동의")
                Text("전체 동의", style = MaterialTheme.typography.titleMedium)
            }
            terms.forEach { t ->
                RowDivider(16.dp)
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp).padding(start = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CheckBox(value(t.id), t.title) { set(t.id, !value(t.id)) }
                    Column(Modifier.weight(1f).clickable { set(t.id, !value(t.id)) }.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip(if (t.required) "필수" else "선택", if (t.required) Tone.Accent else Tone.Neutral)
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(t.summary, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                    Box(Modifier.size(44.dp).clickable { open = t }.semantics { contentDescription = "${t.title} 전문 보기" }, contentAlignment = Alignment.Center) {
                        Icon(AeonIcons.Chevron, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Text("선택 항목은 동의하지 않아도 쓸 수 있고, 내 에이전트 › 약관·권한에서 언제든 바꿀 수 있습니다.", Modifier.padding(horizontal = 4.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
    open?.let { TermDialog(it) { open = null } }
    if (decline) {
        AlertDialog(
            onDismissRequest = { decline = false },
            title = { Text("필수 항목에 동의하지 않으면 쓸 수 없습니다") },
            text = { Text("이용 안내와 데이터 처리 안내는 Aeon이 실제로 하는 일을 설명한 것입니다. 동의하지 않으면 여기서 멈춥니다. 나중에 앱을 다시 열면 이 화면부터 이어집니다.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { decline = false }) { Text("다시 읽어 보기") } },
            dismissButton = { TextButton(onClick = { decline = false; model.setupBack() }) { Text("이전 화면으로") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------- profile

internal val INTEREST_SUGGESTIONS = listOf("개발", "문서 정리", "일정", "공부", "쇼핑", "여행", "재테크", "건강", "요리", "게임", "음악", "영화")

@Composable
internal fun ChoiceRow(title: String, options: List<Pair<String?, String>>, value: String?, onPick: (String?) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Text(title, Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) -> PillChoice(label, value == v) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(v) } }
    }
}

/** Interests as chips: tap a suggestion to toggle it, or type one. Up to 12. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InterestsEditor(value: List<String>, onChange: (List<String>) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var typed by remember { mutableStateOf("") }
    val all = (INTEREST_SUGGESTIONS + value).distinct()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        all.forEach { label ->
            val on = label in value
            PillChoice(label, on) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onChange(if (on) value - label else if (value.size < 12) value + label else value)
            }
        }
    }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AeonTextField(typed, { typed = it.replace('\n', ' ').take(30) }, "직접 입력", Modifier.weight(1f), placeholder = "예: 사진")
        AeonButton("추가", { val t = typed.trim(); if (t.isNotEmpty() && t !in value && value.size < 12) onChange(value + t); typed = "" }, kind = ButtonKind.Secondary, enabled = typed.isNotBlank() && value.size < 12)
    }
    if (value.size >= 12) Text("관심사는 12개까지 넣을 수 있습니다.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun profilePreview(p: Profile): String {
    val me = p.userName?.trim()?.takeIf { it.isNotEmpty() }
    val brief = p.detail == "brief"
    return when {
        p.language == "en" -> "${me?.let { "$it, " } ?: ""}I found 3 files.${if (brief) "" else " They are installers older than 30 days."} Shall I go ahead?"
        p.tone == "casual" -> "${me?.let { "$it, " } ?: ""}파일 3개 찾았어.${if (brief) "" else " 30일 넘은 설치 파일이야."} 진행할까?"
        else -> "${me?.let { "${it}님, " } ?: ""}파일 3개를 찾았어요.${if (brief) "" else " 30일이 넘은 설치 파일입니다."} 진행할까요?"
    }
}

@Composable
internal fun PreviewCard(p: Profile) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).clip(RoundedCornerShape(AeonShape.CARD.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("미리보기 · ${p.agentName?.trim()?.takeIf { it.isNotEmpty() } ?: "Aeon"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(profilePreview(p), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 프로필 설정: three short pages. Every field is optional and each page can be skipped. What is typed is kept on
 * this device as it is typed, and sent to the server at the end so the agent's system prompt carries it.
 */
@Composable
fun ProfileSetupScreen(state: UiState, model: AppModel) {
    val haptic = LocalHapticFeedback.current
    val setup = state.setup
    val p = setup.profileDraft
    val page = setup.profilePage.coerceIn(0, PROFILE_PAGES - 1)
    val last = page == PROFILE_PAGES - 1
    LaunchedEffect(Unit) { model.prefillSetupProfile() }
    SetupFrame(
        "프로필", "${page + 1} / $PROFILE_PAGES", onBack = { model.setupBack() },
        onSkip = { if (last) model.finishProfile() else model.setProfilePage(page + 1) },
        footer = {
            setup.error?.let {
                Banner(it, Tone.Danger, "✕")
                AeonButton("나중에 저장하고 계속", { model.finishProfileLater() }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
            }
            Dots(PROFILE_PAGES, page, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp))
            AeonButton(
                when {
                    setup.saving -> "저장하는 중…"
                    setup.error != null -> "다시 시도"
                    last -> "완료"
                    else -> "다음"
                },
                { haptic.performHapticFeedback(if (last) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove); if (last) model.finishProfile() else model.setProfilePage(page + 1) },
                Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !setup.saving,
            )
        },
    ) {
        AnimatedContent(page, transitionSpec = { (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { if (targetState > initialState) it / 6 else -it / 6 }) togetherWith (fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { if (targetState > initialState) -it / 6 else it / 6 }) }, label = "profile") { pg ->
            Column {
                when (pg) {
                    0 -> {
                        Text("어떻게 부를까요?", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineMedium)
                        Text("에이전트가 답할 때 쓰는 이름입니다. 비워 두어도 됩니다.", Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AeonTextField(p.userName.orEmpty(), { model.editSetupProfile(p.copy(userName = it.replace('\n', ' ').take(40))) }, "나를 부르는 이름 (닉네임)", placeholder = "예: 지우")
                        Spacer(Modifier.height(14.dp))
                        AeonTextField(p.agentName.orEmpty(), { model.editSetupProfile(p.copy(agentName = it.replace('\n', ' ').take(40))) }, "에이전트 이름", placeholder = "Aeon", help = "비워 두면 Aeon이라고 합니다.")
                        PreviewCard(p)
                    }
                    1 -> {
                        Text("어떻게 말할까요?", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineMedium)
                        Text("말투만 바뀝니다. 할 수 있는 일과 승인 규칙은 그대로입니다.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ChoiceRow("언어", listOf(null to "자동", "ko" to "한국어", "en" to "English"), p.language) { model.editSetupProfile(p.copy(language = it)) }
                        ChoiceRow("말투", listOf("polite" to "존댓말", "casual" to "반말"), p.tone ?: "polite") { model.editSetupProfile(p.copy(tone = it)) }
                        ChoiceRow("답변 스타일", listOf("brief" to "짧게", null to "기본", "detailed" to "자세히"), p.detail) { model.editSetupProfile(p.copy(detail = it)) }
                        PreviewCard(p)
                    }
                    else -> {
                        Text("나에 대해 알려 주세요", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineMedium)
                        Text("에이전트가 답을 고를 때 참고합니다. 여기 적은 내용은 고른 모델 제공자에게 전달됩니다.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("관심사", Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        InterestsEditor(p.interests.orEmpty()) { model.editSetupProfile(p.copy(interests = it.ifEmpty { null })) }
                        Spacer(Modifier.height(16.dp))
                        AeonTextField(
                            p.about.orEmpty(), { model.editSetupProfile(p.copy(about = it.replace('\n', ' ').take(1000))) }, "에이전트가 알아 둘 것", singleLine = false,
                            placeholder = "예: 개발자입니다. 코드 주석은 영어로 써 주세요.", help = "${p.about.orEmpty().length}/1000",
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
