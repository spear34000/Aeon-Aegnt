package dev.aeon.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.TaskSummary
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.EmptyState
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.koreanClock
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.Connection
import dev.aeon.ui.state.Section
import dev.aeon.ui.state.SettingsPage
import dev.aeon.ui.state.UiState

private data class PhoneTab(val section: Section, val icon: ImageVector, val label: String)

private val TABS = listOf(
    PhoneTab(Section.Chat, AeonIcons.Chat, "대화"),
    PhoneTab(Section.Feed, AeonIcons.Feed, "피드"),
    PhoneTab(Section.Explore, AeonIcons.Explore, "둘러보기"),
    PhoneTab(Section.Tasks, AeonIcons.Tasks, "작업"),
    PhoneTab(Section.Agent, AeonIcons.Agent, "내 에이전트"),
)

/** Sections reached from elsewhere (wide layout) map onto the nearest phone tab. */
private fun tabFor(s: Section): Section = when (s) {
    Section.Approvals -> Section.Feed
    Section.Settings -> Section.Agent
    else -> s
}

/**
 * Phone layout (mockup "Aeon 모바일"): the conversation has a centred identity with a live status line, a menu button
 * that slides the chat list in over 70% of the width, and a new-chat button. The other tabs open with a large title.
 * The bottom bar has five icon-only tabs: 대화, 피드, 둘러보기, 작업, 내 에이전트.
 */
@Composable
fun PhoneLayout(state: UiState, model: AppModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = maxWidth * 0.7f
        val current = tabFor(state.section)
        // System back closes whatever is open on top, innermost first, before it leaves the app.
        val detailOpen = state.showDetail && state.selectedTaskId != null
        dev.aeon.ui.platform.PlatformBackHandler(enabled = state.models.open || state.explore.detail != null || state.drawerOpen || detailOpen) {
            when {
                state.models.open -> model.closeModelSheet()
                state.explore.detail != null -> model.showConnection(null)
                state.drawerOpen -> model.closeDrawer()
                else -> model.closeDetail()
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (!(state.showDetail && state.selectedTaskId != null)) {
                if (current == Section.Chat) ChatTopBar(state, model) else TitleBar(TABS.first { it.section == current }.label)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.showDetail && state.selectedTaskId != null -> TaskDetailPanel(state, model, onBack = { model.closeDetail() }, modifier = Modifier.fillMaxSize())
                    current == Section.Chat -> ConversationScreen(state, model, wide = false, modifier = Modifier.fillMaxSize())
                    current == Section.Feed -> FeedScreen(state, model, Modifier.fillMaxSize())
                    current == Section.Explore -> ExploreScreen(state, model, Modifier.fillMaxSize())
                    current == Section.Tasks -> TasksScreen(state, model, Modifier.fillMaxSize())
                    else -> AgentHubScreen(state, model, Modifier.fillMaxSize())
                }
            }
            PhoneBottomBar(current, state.pendingCount, model::go)
        }

        // Dimmed area to the right of the drawer; tapping it closes the drawer.
        AnimatedVisibility(visible = state.drawerOpen, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = model::closeDrawer),
            )
        }
        AnimatedVisibility(
            visible = state.drawerOpen,
            enter = slideInHorizontally(tween(260)) { -it },
            exit = slideOutHorizontally(tween(200)) { -it },
        ) {
            ChatDrawer(state, model, Modifier.width(drawerWidth).fillMaxHeight())
        }
    }
}

/** 44dp soft circle with a 48dp touch target. */
@Composable
internal fun CircleButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun StatusDot(color: Color, size: Int = 7) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

@Composable
private fun ChatTopBar(state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    val busy = state.tasks.any { it.status == "RUNNING" || it.status == "PENDING" || it.status == "RETRY_WAIT" }
    val (dot, text) = when {
        state.connection !is Connection.Connected -> s.danger to "서버에 연결되지 않음"
        state.pendingCount > 0 -> s.warn to "승인을 기다리는 중"
        busy -> MaterialTheme.colorScheme.primary to "작업 중"
        else -> s.ok to "서버와 연결됨"
    }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircleButton(AeonIcons.Menu, "대화 목록 열기", model::openDrawer)
        Column(
            Modifier.weight(1f).clickable(enabled = state.pendingCount > 0, interactionSource = remember { MutableInteractionSource() }, indication = null) { model.go(Section.Feed) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Aeon", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(dot)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        CircleButton(AeonIcons.Plus, "새 대화", model::newConversation)
    }
}

@Composable
private fun TitleBar(title: String) {
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

/** Icon-only bottom bar. Each tab has a spoken label for screen readers instead of visible text. */
@Composable
private fun PhoneBottomBar(current: Section, pending: Int, onSelect: (Section) -> Unit) {
    val s = LocalAeonStatus.current
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(scheme.background)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TABS.forEach { t ->
                val selected = t.section == current
                Box(
                    Modifier.weight(1f).height(48.dp).coachTarget("nav.${t.section.name.lowercase()}")
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(t.section) }
                        .semantics { contentDescription = t.label },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(width = 60.dp, height = 40.dp).background(if (selected) scheme.surfaceVariant else Color.Transparent, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                        Icon(t.icon, contentDescription = null, tint = if (selected) scheme.onSurface else scheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    }
                    if (t.section == Section.Feed && pending > 0 && !selected) {
                        Box(Modifier.align(Alignment.Center).padding(start = 22.dp, bottom = 20.dp).size(9.dp).background(s.danger, CircleShape))
                    }
                }
            }
        }
    }
}

/** One word per task state, with the colour of its dot. The colour is never the only signal. */
@Composable
internal fun taskStatusLine(t: TaskSummary): Pair<Color, String> {
    val s = LocalAeonStatus.current
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    return when (t.status) {
        "WAITING_FOR_APPROVAL" -> s.warn to "승인 대기"
        "RUNNING", "PENDING" -> MaterialTheme.colorScheme.primary to "진행 중"
        "RETRY_WAIT" -> s.warn to "다시 시도 중"
        "WAITING_FOR_QUOTA" -> s.warn to "한도 대기"
        "WAITING_FOR_DEVICE" -> s.warn to "기기 대기"
        "FAILED" -> s.danger to "실패"
        "CANCELLED" -> neutral to "중단됨"
        "COMPLETED" -> if (t.verification?.status == "failed") s.danger to "완료 · 확인 실패" else s.ok to "완료"
        else -> neutral to t.status
    }
}

/** Chat list that slides in over the left 70% of the screen. */
@Composable
private fun ChatDrawer(state: UiState, model: AppModel, modifier: Modifier) {
    val s = LocalAeonStatus.current
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(topEnd = AeonShape.SHEET.dp, bottomEnd = AeonShape.SHEET.dp)).background(scheme.background)
            .safeDrawingPadding().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Row(Modifier.padding(start = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AeonIcons.Mark, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(30.dp))
            Text("Aeon", style = MaterialTheme.typography.titleLarge)
        }
        AeonButton("새 대화", { model.newConversation() }, Modifier.fillMaxWidth(), kind = ButtonKind.Strong)
        if (state.tasks.isEmpty()) {
            Text("아직 대화가 없습니다.", Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f).padding(top = 12.dp), contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(state.tasks, key = { it.id }) { t ->
                val (dot, _) = taskStatusLine(t)
                val selected = t.id == state.selectedTaskId
                val quiet = t.status == "COMPLETED" && t.verification?.status != "failed" || t.status == "CANCELLED"
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CONTROL.dp)).background(if (selected) scheme.surface else Color.Transparent)
                        .clickable { model.selectTask(t.id) }.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(t.goal, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (quiet && !selected) scheme.onSurfaceVariant else scheme.onSurface)
                    if (!quiet) StatusDot(dot, 9)
                }
            }
        }
        val connected = state.connection is Connection.Connected
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CONTROL.dp)).background(scheme.surface).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(if (connected) s.ok else s.danger, 9)
            Column {
                Text(if (connected) "서버와 연결됨" else "서버에 연결되지 않음", style = MaterialTheme.typography.labelLarge)
                Text(state.serverUrl.removePrefix("http://").removePrefix("https://"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** 피드: what is waiting for your answer first, then recent activity, newest first. */
@Composable
fun FeedScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        if (state.approvals.isNotEmpty()) {
            GroupHeader("승인 대기", state.approvals.size.toString())
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.approvals.forEach { a ->
                    val goal = state.tasks.firstOrNull { it.id == a.taskId }?.goal ?: "작업"
                    ApprovalCard(a, state.approvalSeenAt[a.id], goal, { model.decide(a.id, it) })
                }
            }
        }
        GroupHeader("활동")
        if (state.tasks.isEmpty()) {
            EmptyState("아직 활동이 없습니다", "새 대화에서 목표를 말하면 여기에 쌓입니다.")
        } else {
            ListSurface {
                state.tasks.forEachIndexed { i, t ->
                    if (i > 0) RowDivider(88.dp)
                    val (dot, text) = taskStatusLine(t)
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable { model.selectTask(t.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(koreanClock(t.updatedAt), Modifier.width(60.dp).padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(t.goal, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusDot(dot)
                                Text(text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(scheme.surfaceVariant, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(20.dp))
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        if (value.isNotEmpty()) Text(value, Modifier.widthIn(max = 140.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(AeonIcons.Chevron, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(AeonShape.CARD.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.displaySmall)
    }
}

/** 내 에이전트: who the agent is and whether it is reachable, what it has used, then the settings. */
@Composable
fun AgentHubScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var inSettings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        model.loadSettings()
        model.loadUsage()
        if (state.explore.installed.isEmpty()) model.loadExplore()
    }
    if (inSettings) {
        Column(modifier.fillMaxSize()) {
            Row(Modifier.clickable { inSettings = false }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(AeonIcons.Back, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                Text("내 에이전트", color = scheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            SettingsScreen(state, model, Modifier.weight(1f), title = "설정")
        }
        return
    }
    val ok = state.usage.filter { it.ok }
    val connected = state.connection is Connection.Connected
    val open: (SettingsPage) -> Unit = { model.openSettings(it); inSettings = true }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CARD.dp)).background(scheme.surface).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(56.dp).background(scheme.primary, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(AeonIcons.Mark, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(state.status?.name ?: "Aeon", style = MaterialTheme.typography.titleLarge)
                Text(
                    listOfNotNull(state.config?.providers?.firstOrNull(), state.serverUrl.removePrefix("http://").removePrefix("https://")).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Chip(if (connected) "연결됨" else "끊김", if (connected) Tone.Ok else Tone.Danger)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("모델 호출", "${ok.size}번", Modifier.weight(1f))
            StatCard("입력+출력 토큰", "${ok.sumOf { it.inputTokens + it.outputTokens }}", Modifier.weight(1f))
        }
        GroupHeader("설정")
        ListSurface {
            SettingRow(AeonIcons.Agent, "개인화", state.profile.userName ?: "") { open(SettingsPage.Personal) }
            RowDivider(64.dp)
            SettingRow(AeonIcons.Cpu, "모델", state.config?.providers?.firstOrNull() ?: "") { open(SettingsPage.Models) }
            RowDivider(64.dp)
            SettingRow(AeonIcons.Key, "API 키", if (state.secretNames.isEmpty()) "없음" else "${state.secretNames.size}개 보관") { open(SettingsPage.Keys) }
            RowDivider(64.dp)
            SettingRow(AeonIcons.Phone, "연결된 기기", if (state.devices.isEmpty()) "" else "${state.devices.count { !it.revoked }}대") { open(SettingsPage.Devices) }
            RowDivider(64.dp)
            SettingRow(AeonIcons.Plug, "연결과 도구", if (state.explore.installed.isEmpty()) "" else "${state.explore.installed.size}개") { model.go(Section.Explore) }
            RowDivider(64.dp)
            SettingRow(AeonIcons.Chart, "사용량", "") { open(SettingsPage.Usage) }
            RowDivider(64.dp)
            SettingRow(AeonIcons.Moon, "화면 테마", state.themeMode.label) { model.cycleTheme() }
            RowDivider(64.dp)
            SettingRow(AeonIcons.Shield, "약관 · 권한", "") { open(SettingsPage.Legal) }
            RowDivider(64.dp)
            SettingRow(AeonIcons.Info, "정보", "") { open(SettingsPage.About) }
        }
    }
}


@Composable
private fun TaskTile(t: TaskSummary) {
    val s = LocalAeonStatus.current
    val scheme = MaterialTheme.colorScheme
    val (icon, bg, fg) = when {
        t.status == "FAILED" || (t.status == "COMPLETED" && t.verification?.status == "failed") -> Triple(AeonIcons.Close, s.dangerSoft, s.danger)
        t.status == "COMPLETED" -> Triple(AeonIcons.Check, s.okSoft, s.ok)
        t.status.startsWith("WAITING") || t.status == "RETRY_WAIT" -> Triple(AeonIcons.Pause, s.warnSoft, s.warn)
        t.status == "PENDING" && t.runAfter > dev.aeon.ui.platform.nowMillis() -> Triple(AeonIcons.Clock, scheme.surfaceVariant, scheme.onSurface)
        t.status == "RUNNING" || t.status == "PENDING" -> Triple(AeonIcons.Send, s.accentSoft, s.onAccentSoft)
        else -> Triple(AeonIcons.Dash, scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
    Box(Modifier.size(44.dp).background(bg, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
    }
}

/** 작업: grouped by what the owner needs to know first: waiting for approval, running, scheduled, then past work. */
@Composable
fun TasksScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val now = dev.aeon.ui.platform.nowMillis()
    val past = setOf("COMPLETED", "FAILED", "CANCELLED")
    val waiting = state.tasks.filter { it.status == "WAITING_FOR_APPROVAL" }
    val scheduled = state.tasks.filter { it.status == "PENDING" && it.runAfter > now }
    val finished = state.tasks.filter { it.status in past }
    val active = state.tasks.filter { it.status !in past && it !in waiting && it !in scheduled }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        if (state.tasks.isEmpty()) EmptyState("아직 작업이 없습니다", "대화에서 일을 맡기면 여기에 쌓입니다.")
        listOf("승인 대기" to waiting, "진행 중" to active, "예약됨" to scheduled, "지난 작업" to finished).forEach { (title, list) ->
            if (list.isEmpty()) return@forEach
            GroupHeader(title, list.size.toString())
            ListSurface {
                list.forEachIndexed { i, t ->
                    if (i > 0) RowDivider()
                    val (dot, text) = taskStatusLine(t)
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable { model.selectTask(t.id, asDetail = t.status in past) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TaskTile(t)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t.goal, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusDot(dot)
                                Text(if (t in scheduled) "${koreanClock(t.runAfter)}에 시작" else text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        Text(koreanClock(t.updatedAt), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
