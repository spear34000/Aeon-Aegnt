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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.unit.sp
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
    topBar: (@Composable () -> Unit)? = null,
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
            topBar?.invoke()
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
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).size(width = 120.dp, height = 4.dp).clip(CircleShape).background(scheme.surfaceVariant)) {
            Box(Modifier.size(width = 48.dp, height = 4.dp).graphicsLayer { translationX = (sweep * 168.dp.toPx()) - 48.dp.toPx() }.background(scheme.primary, CircleShape))
        }
    }
}

// ---------------------------------------------------------------------------------------------- intro

private class IntroPage(val title: String, val body: String, val art: @Composable ColumnScope.() -> Unit)

/**
 * The picture of an intro page: a tinted panel that fills the rest of the screen, with a cut-off excerpt of the real
 * screen sitting in it, the way a store screenshot does. The excerpt is taller than the panel on purpose.
 */
@Composable
private fun ArtPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(scheme.primaryContainer)) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(start = 20.dp, end = 20.dp, top = 28.dp).fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true).heightIn(min = 640.dp)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(scheme.surface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
        )
    }
}

@Composable
private fun ArtRow(icon: ImageVector, text: String, trailing: String? = null, ok: Boolean = false) {
    val s = LocalAeonStatus.current
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(38.dp).background(if (ok) s.okSoft else scheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (ok) s.ok else scheme.onSurface, modifier = Modifier.size(20.dp))
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = if (ok) s.ok else scheme.onSurfaceVariant)
    }
}

@Composable
private fun ArtLabel(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ArtFile(name: String, folder: String) {
    Column {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text(folder, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val INTRO = listOf(
    IntroPage("한 일은\n기록으로 남겨요", "단계마다 기록하고, 결과는 실제 파일로 확인해요.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("회의록 요약 문서 만들기", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Chip("완료", Tone.Ok)
        }
        ArtRow(AeonIcons.Check, "폴더 살펴보기", ok = true)
        ArtRow(AeonIcons.Check, "회의록 4개 읽기", ok = true)
        ArtRow(AeonIcons.Check, "결정 사항 추리기", ok = true)
        ArtRow(AeonIcons.Check, "요약 문서 쓰기", "확인됨", ok = true)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.background).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("에이전트 보고", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("회의록 4개에서 결정 사항 7개를 모아 요약.md에 정리했어요.", style = MaterialTheme.typography.bodyLarge)
        }
        ArtLabel("어제")
        ArtRow(AeonIcons.Check, "중복 파일 찾기", "완료", ok = true)
        ArtRow(AeonIcons.Check, "hello.txt 만들기", "완료", ok = true)
        ArtRow(AeonIcons.Check, "old.txt 삭제", "완료", ok = true)
    },
    IntroPage("되돌릴 수 없는 일은\n먼저 물어요", "삭제나 명령 실행은 허락하기 전에는 하지 않아요.") {
        Column {
            Text("파일 3개를 삭제할까요?", style = MaterialTheme.typography.titleLarge)
            Text("다운로드 정리", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.background).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ArtFile("setup-old.exe", "C:\\Users\\jiwoo\\Downloads")
            ArtFile("driver_2023.msi", "C:\\Users\\jiwoo\\Downloads")
            ArtFile("tool-installer.exe", "C:\\Users\\jiwoo\\Downloads")
        }
        Text("되돌릴 수 없어요", style = MaterialTheme.typography.labelLarge, color = LocalAeonStatus.current.danger)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(48.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape), contentAlignment = Alignment.Center) {
                Text("거부", color = MaterialTheme.colorScheme.background, style = MaterialTheme.typography.labelLarge)
            }
            Box(Modifier.weight(1f).height(48.dp).border(1.5.dp, LocalAeonStatus.current.danger, CircleShape), contentAlignment = Alignment.Center) {
                Text("허용", color = LocalAeonStatus.current.danger, style = MaterialTheme.typography.labelLarge)
            }
        }
        ArtLabel("오늘")
        ArtRow(AeonIcons.Pause, "다운로드 정리", "승인 대기")
        ArtRow(AeonIcons.Check, "회의록 요약 문서 만들기", "완료", ok = true)
        ArtRow(AeonIcons.Clock, "백업 폴더 점검", "내일 오전 9:00")
    },
    IntroPage("내 기기에서\n일해요", "에이전트와 기록은 내 컴퓨터에 있고, 이 앱은 리모컨이에요.") {
        Text("연결된 기기", style = MaterialTheme.typography.titleLarge)
        ArtRow(AeonIcons.Terminal, "내 컴퓨터", "에이전트 실행 중", ok = true)
        ArtRow(AeonIcons.Phone, "Galaxy S24", "이 기기")
        ArtRow(AeonIcons.Globe, "Chrome 웹", "3일 전")
        ArtRow(AeonIcons.Phone, "거실 태블릿", "대화·승인만")
        Box(Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text("새 기기 연결", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
        ArtLabel("내 컴퓨터")
        ArtRow(AeonIcons.File, "작업 폴더", "내 문서")
        ArtRow(AeonIcons.Key, "API 키", "암호화 보관")
        ArtRow(AeonIcons.Shield, "기록", "내 컴퓨터에만")
    },
    IntroPage("모델과 도구는\n내가 골라요", "쓸 모델과 연동, 스킬을 직접 고르고 만들어요.") {
        Text("모델", style = MaterialTheme.typography.titleLarge)
        ArtRow(AeonIcons.Cpu, "Solar Pro 4", "쓰는 중", ok = true)
        Segmented(listOf<Pair<String?, String>>("auto" to "자동", "low" to "낮음", "mid" to "보통", "high" to "높음"), "high") {}
        ArtRow(AeonIcons.Cpu, "Solar Mini 4")
        ArtRow(AeonIcons.Plug, "GitHub", "연동")
        ArtRow(AeonIcons.Book, "회의록 정리", "스킬")
        ArtRow(AeonIcons.Plug, "Notion", "연동")
        ArtRow(AeonIcons.Book, "PR 리뷰 체크리스트", "스킬")
        ArtRow(AeonIcons.Cpu, "solar-pro3")
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
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    Text(p.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, lineHeight = 40.sp))
                    Text(p.body, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ArtPanel(Modifier.weight(1f).padding(top = 28.dp, bottom = 8.dp), p.art)
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Dots(INTRO_PAGES, pager.currentPage, Modifier.padding(bottom = 18.dp))
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
            }
            AeonButton("시작하기", { haptic.performHapticFeedback(HapticFeedbackType.LongPress); model.beginSetup() }, Modifier.padding(bottom = 24.dp).fillMaxWidth(), kind = ButtonKind.Primary)
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
            AeonButton("동의하고 계속", { haptic.performHapticFeedback(HapticFeedbackType.LongPress); model.acceptTerms() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = consents.requiredOk)
            AeonButton("동의하지 않음", { decline = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
        },
    ) {
        if (state.setup.storageError) Banner("진행 상태를 이 기기에 저장하지 못했어요. 앱을 닫으면 처음부터 해야 할 수 있어요.", Tone.Warn, "⚠")
        GroupHeader("권한")
        PermissionRows(controller)
        GroupHeader("약관")
        Row(Modifier.fillMaxWidth().clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); model.setConsents(consents.copy(service = !all, data = !all, online = !all, background = !all && terms.any { it.id == "background" })) }.padding(start = 6.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            CheckBox(all, "전체 동의")
            Text("전체 동의", style = MaterialTheme.typography.titleMedium)
        }
        listOf(terms.filter { it.required }, terms.filter { !it.required }).filter { it.isNotEmpty() }.forEachIndexed { g, group ->
            ListSurface(Modifier.padding(top = if (g == 0) 0.dp else 10.dp)) {
                group.forEachIndexed { i, t ->
                    if (i > 0) RowDivider(16.dp)
                    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp).padding(start = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CheckBox(value(t.id), t.title) { set(t.id, !value(t.id)) }
                        Column(Modifier.weight(1f).clickable { set(t.id, !value(t.id)) }.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            Text(t.summary, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        }
                        Box(Modifier.size(44.dp).clickable { open = t }.semantics { contentDescription = "${t.title} 전문 보기" }, contentAlignment = Alignment.Center) {
                            Icon(AeonIcons.Chevron, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    open?.let { TermDialog(it) { open = null } }
    if (decline) {
        AlertDialog(
            onDismissRequest = { decline = false },
            title = { Text("동의가 필요해요") },
            text = { Text("이용 안내와 데이터 처리 안내에 동의해야 Aeon을 쓸 수 있어요. 앱을 다시 열면 여기서 이어져요.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { decline = false }) { Text("다시 읽어 보기") } },
            dismissButton = { TextButton(onClick = { decline = false; model.setupBack() }) { Text("이전 화면으로") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------- profile

internal val INTEREST_SUGGESTIONS = listOf("개발", "문서 정리", "일정", "공부", "쇼핑", "여행", "재테크", "건강", "요리", "게임", "음악", "영화")

/** Equal-width segments in one soft pill: for a choice of two or three short options. */
@Composable
internal fun Segmented(options: List<Pair<String?, String>>, value: String?, onPick: (String?) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(scheme.surfaceVariant).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (v, label) ->
            val on = value == v
            Box(
                Modifier.weight(1f).defaultMinSize(minHeight = 40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) scheme.surface else Color.Transparent)
                    .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(v) }.semantics { contentDescription = "$label, ${if (on) "선택됨" else "선택 안 됨"}" },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = if (on) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium, color = if (on) scheme.onSurface else scheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun ChoiceRow(title: String, options: List<Pair<String?, String>>, value: String?, onPick: (String?) -> Unit) {
    Text(title, Modifier.padding(top = 18.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Segmented(options, value, onPick)
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

private val INTEREST_GROUPS = listOf(
    "일" to listOf("개발", "문서 정리", "일정", "공부", "재테크"),
    "생활" to listOf("쇼핑", "여행", "건강", "요리"),
    "취미" to listOf("게임", "음악", "영화", "사진"),
)

private val ABOUT_STARTERS = listOf("직업", "사는 곳", "쓰는 도구", "피하고 싶은 것")

private fun greetingPreview(p: Profile): String {
    val me = p.userName?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        p.language == "en" -> "Hi${me?.let { " $it" } ?: ""}. What can I help with?"
        p.tone == "casual" -> "${me?.let { "$it, " } ?: ""}안녕. 뭘 도와줄까?"
        else -> "${me?.let { "${it}님, " } ?: ""}안녕하세요. 무엇을 도와드릴까요?"
    }
}

@Composable
private fun AgentAvatar() {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(36.dp).background(scheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(AeonIcons.Mark, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
    }
}

/** A small conversation that changes with what is typed, so each choice shows its effect at once. */
@Composable
private fun ChatPreview(p: Profile, mine: String?, reply: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(scheme.primaryContainer).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("미리보기 · ${p.agentName?.trim()?.takeIf { it.isNotEmpty() } ?: "Aeon"}", style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer)
        if (mine != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(mine, Modifier.background(scheme.onSurface, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).padding(horizontal = 14.dp, vertical = 10.dp), color = scheme.background, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AgentAvatar()
            Text(reply, Modifier.weight(1f, fill = false).background(scheme.surface, RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)).padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun StepHeading(title: String, sub: String) {
    Text(title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 38.sp))
    Text(sub, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The bar under the top row: one segment per step, filled up to the current one. */
@Composable
private fun StepBar(step: Int, count: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).semantics { contentDescription = "${step + 1} / $count" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i -> Box(Modifier.weight(1f).height(4.dp).background(if (i <= step) scheme.onSurface else scheme.surfaceVariant, CircleShape)) }
    }
}

/** A big pill for one interest: filled with a check when chosen. */
@Composable
private fun InterestPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.height(44.dp).clip(CircleShape).background(if (selected) scheme.primary else scheme.surface).clickable(onClick = onClick)
            .semantics { contentDescription = "$text, ${if (selected) "선택됨" else "선택 안 됨"}" }.padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) Icon(AeonIcons.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (selected) scheme.onPrimary else scheme.onSurface)
    }
}

/** A choice with a sample line under it; the chosen one has an accent outline. */
@Composable
private fun OptionCard(title: String, sample: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AeonShape.CARD.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(scheme.surface).border(2.dp, if (selected) scheme.primary else Color.Transparent, shape)
            .clickable(onClick = onClick).semantics { contentDescription = "$title, ${if (selected) "선택됨" else "선택 안 됨"}" }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(sample, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

private fun toneLabel(t: String?) = if (t == "casual") "반말" else "존댓말"
private fun detailLabel(d: String?) = when (d) { "brief" -> "짧게"; "detailed" -> "자세히"; else -> "기본" }
private fun languageLabel(l: String?) = when (l) { "ko" -> "한국어"; "en" -> "English"; else -> "자동" }

@Composable
private fun SummaryRow(label: String, onEdit: () -> Unit, last: Boolean = false, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Text("수정", Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onEdit).padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
        content()
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
}

/**
 * 프로필 설정: five steps (name, interests, voice, what to know, a summary). Every field is optional and each step
 * can be skipped. What is typed is kept on this device as it is typed, and sent to the server at the end so the
 * agent's system prompt carries it. New kinds of information are added as new steps here and new fields in `Profile`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileSetupScreen(state: UiState, model: AppModel) {
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val setup = state.setup
    val p = setup.profileDraft
    val page = setup.profilePage.coerceIn(0, PROFILE_PAGES - 1)
    val last = page == PROFILE_PAGES - 1
    val interests = p.interests.orEmpty()
    LaunchedEffect(Unit) { model.prefillSetupProfile() }
    SetupFrame(
        null, null, onBack = { model.setupBack() },
        onSkip = if (last) null else ({ model.setProfilePage(page + 1) }),
        topBar = { StepBar(page, PROFILE_PAGES) },
        footer = {
            setup.error?.let {
                Banner(it, Tone.Danger, "✕")
                AeonButton("나중에 저장하고 계속", { model.finishProfileLater() }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
            }
            if (page == 1) Text("${interests.size}개 선택됨 · 최대 12개", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            AeonButton(
                when {
                    setup.saving -> "저장하는 중…"
                    setup.error != null -> "다시 시도"
                    last -> "시작하기"
                    else -> "다음"
                },
                { haptic.performHapticFeedback(if (last) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove); if (last) model.finishProfile() else model.setProfilePage(page + 1) },
                Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !setup.saving,
            )
        },
    ) {
        AnimatedContent(page, transitionSpec = { (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { if (targetState > initialState) it / 6 else -it / 6 }) togetherWith (fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { if (targetState > initialState) -it / 6 else it / 6 }) }, label = "profile") { pg ->
            Column(Modifier.padding(top = 18.dp)) {
                when (pg) {
                    0 -> {
                        StepHeading("어떻게 부를까요?", "대화에서 쓸 이름이에요. 나중에 바꿀 수 있어요.")
                        ChatPreview(p, if (p.language == "en") "Hello?" else "안녕?", greetingPreview(p), Modifier.padding(top = 22.dp))
                        Spacer(Modifier.height(18.dp))
                        AeonTextField(p.userName.orEmpty(), { model.editSetupProfile(p.copy(userName = it.replace('\n', ' ').take(40))) }, "나를 부르는 이름", placeholder = "예: 지우")
                        Spacer(Modifier.height(14.dp))
                        AeonTextField(p.agentName.orEmpty(), { model.editSetupProfile(p.copy(agentName = it.replace('\n', ' ').take(40))) }, "에이전트 이름", placeholder = "Aeon")
                    }
                    1 -> {
                        StepHeading("무엇에 관심 있나요?", "고른 만큼 에이전트가 더 맞춰 드려요.")
                        fun toggle(label: String) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val on = label in interests
                            model.editSetupProfile(p.copy(interests = (if (on) interests - label else if (interests.size < 12) interests + label else interests).ifEmpty { null }))
                        }
                        val known = INTEREST_GROUPS.flatMap { it.second }
                        val own = interests.filter { it !in known }
                        (INTEREST_GROUPS + (if (own.isEmpty()) emptyList() else listOf("직접 추가" to own))).forEach { (name, items) ->
                            Text(name, Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items.forEach { InterestPill(it, it in interests) { toggle(it) } }
                            }
                        }
                        var typed by remember { mutableStateOf("") }
                        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AeonTextField(typed, { typed = it.replace('\n', ' ').take(30) }, "직접 입력", Modifier.weight(1f), placeholder = "예: 러닝")
                            AeonButton("추가", { val t = typed.trim(); if (t.isNotEmpty() && t !in interests && interests.size < 12) model.editSetupProfile(p.copy(interests = interests + t)); typed = "" }, kind = ButtonKind.Secondary, enabled = typed.isNotBlank() && interests.size < 12)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    2 -> {
                        StepHeading("어떻게 말할까요?", "답변 말투와 길이를 정해요.")
                        ChoiceRow("언어", listOf(null to "자동", "ko" to "한국어", "en" to "English"), p.language) { model.editSetupProfile(p.copy(language = it)) }
                        Text("말투", Modifier.padding(top = 18.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val tone = p.tone ?: "polite"
                            OptionCard("존댓말", "파일 3개를 찾았어요.", tone == "polite") { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); model.editSetupProfile(p.copy(tone = "polite")) }
                            OptionCard("반말", "파일 3개 찾았어.", tone == "casual") { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); model.editSetupProfile(p.copy(tone = "casual")) }
                        }
                        ChoiceRow("답변 스타일", listOf("brief" to "짧게", null to "기본", "detailed" to "자세히"), p.detail) { model.editSetupProfile(p.copy(detail = it)) }
                        ChatPreview(p, null, profilePreview(p), Modifier.padding(top = 20.dp))
                        Spacer(Modifier.height(8.dp))
                    }
                    3 -> {
                        StepHeading("에이전트가 알아 둘 것", "직업이나 쓰는 도구처럼, 답을 맞추는 데 도움이 되는 것을 적어 보세요.")
                        Spacer(Modifier.height(20.dp))
                        AeonTextField(
                            p.about.orEmpty(), { model.editSetupProfile(p.copy(about = it.replace('\n', ' ').take(1000))) }, "알아 둘 것", singleLine = false, minHeight = 180.dp,
                            placeholder = "예: 개발자입니다. 코드 주석은 영어로 써 주세요.", help = "고른 모델 제공자에게 전달돼요 · ${p.about.orEmpty().length}/1000",
                        )
                        Text("이런 것을 적어 보세요", Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ABOUT_STARTERS.forEach { label ->
                                Text(
                                    label, Modifier.clip(CircleShape).background(scheme.surfaceVariant).clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val now = p.about.orEmpty().trimEnd()
                                        val next = (if (now.isEmpty()) "" else "$now ") + "$label: "
                                        if (next.length <= 1000) model.editSetupProfile(p.copy(about = next))
                                    }.padding(horizontal = 14.dp, vertical = 9.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    else -> {
                        StepHeading("이렇게 기억할게요", "설정에서 언제든 바꿀 수 있어요.")
                        Column(Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(AeonShape.CARD.dp)).background(scheme.surface).padding(horizontal = 18.dp, vertical = 4.dp)) {
                            SummaryRow("이름", { model.setProfilePage(0) }) {
                                Text("${p.userName?.trim()?.takeIf { it.isNotEmpty() } ?: "정하지 않음"} · 에이전트 ${p.agentName?.trim()?.takeIf { it.isNotEmpty() } ?: "Aeon"}", style = MaterialTheme.typography.bodyLarge)
                            }
                            SummaryRow("관심사", { model.setProfilePage(1) }) {
                                if (interests.isEmpty()) Text("정하지 않음", style = MaterialTheme.typography.bodyLarge)
                                else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    interests.forEach { Text(it, Modifier.clip(CircleShape).background(scheme.primaryContainer).padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer) }
                                }
                            }
                            SummaryRow("말투", { model.setProfilePage(2) }) {
                                Text("${languageLabel(p.language)} · ${toneLabel(p.tone)} · ${detailLabel(p.detail)}", style = MaterialTheme.typography.bodyLarge)
                            }
                            SummaryRow("알아 둘 것", { model.setProfilePage(3) }, last = true) {
                                Text(p.about?.trim()?.takeIf { it.isNotEmpty() } ?: "정하지 않음", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
