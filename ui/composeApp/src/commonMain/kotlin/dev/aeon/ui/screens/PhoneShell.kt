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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Card
import dev.aeon.ui.design.EmptyState
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.SectionLabel
import dev.aeon.ui.design.TabIcons
import dev.aeon.ui.design.TaskStatusChip
import dev.aeon.ui.platform.koreanClock
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.Connection
import dev.aeon.ui.state.Section
import dev.aeon.ui.state.UiState

private data class PhoneTab(val section: Section, val icon: ImageVector, val label: String)

private val TABS = listOf(
    PhoneTab(Section.Chat, TabIcons.Chat, "대화"),
    PhoneTab(Section.Feed, TabIcons.Feed, "피드"),
    PhoneTab(Section.Explore, TabIcons.Explore, "둘러보기"),
    PhoneTab(Section.Tasks, TabIcons.Tasks, "작업"),
    PhoneTab(Section.Agent, TabIcons.Agent, "내 에이전트"),
)

/** Sections reached from elsewhere (wide layout) map onto the nearest phone tab. */
private fun tabFor(s: Section): Section = when (s) {
    Section.Approvals -> Section.Feed
    Section.Settings -> Section.Agent
    else -> s
}

/**
 * Phone layout: hamburger at the top left opens the chat list from the left edge (70% of the width, the rest dimmed),
 * and an icon-only bottom bar with five tabs: 대화, 피드, 둘러보기, 작업, 내 에이전트.
 */
@Composable
fun PhoneLayout(state: UiState, model: AppModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = maxWidth * 0.7f
        val current = tabFor(state.section)
        Column(Modifier.fillMaxSize()) {
            if (!(state.showDetail && state.selectedTaskId != null)) {
                PhoneTopBar(title = TABS.first { it.section == current }.label, pending = state.pendingCount, onMenu = model::openDrawer, onPending = { model.go(Section.Feed) })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.showDetail && state.selectedTaskId != null -> TaskDetailPanel(state, model, onBack = { model.closeDetail() }, modifier = Modifier.fillMaxSize())
                    current == Section.Chat -> ConversationScreen(state, model, wide = false, modifier = Modifier.fillMaxSize())
                    current == Section.Feed -> FeedScreen(state, model, Modifier.fillMaxSize())
                    current == Section.Explore -> ExploreScreen(model, Modifier.fillMaxSize())
                    current == Section.Tasks -> TaskList(state, model, Modifier.fillMaxSize())
                    else -> SettingsScreen(state, model, Modifier.fillMaxSize(), title = "내 에이전트")
                }
            }
            PhoneBottomBar(current, state.pendingCount, model::go)
        }

        // Dimmed area to the right of the drawer; tapping it closes the drawer.
        AnimatedVisibility(visible = state.drawerOpen, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
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

@Composable
private fun PhoneTopBar(title: String, pending: Int, onMenu: () -> Unit, onPending: () -> Unit) {
    val s = LocalAeonStatus.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clickable(onClick = onMenu).semantics { contentDescription = "채팅 목록 열기" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Menu, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
        if (pending > 0) {
            Box(
                Modifier.padding(end = 12.dp).background(s.warnSoft, RoundedCornerShape(50)).clickable(onClick = onPending).padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("승인 $pending", color = s.warn, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** Icon-only bottom bar. Each tab has a spoken label for screen readers instead of visible text. */
@Composable
private fun PhoneBottomBar(current: Section, pending: Int, onSelect: (Section) -> Unit) {
    val s = LocalAeonStatus.current
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TABS.forEach { t ->
            val selected = t.section == current
            Box(
                Modifier.size(width = 64.dp, height = 40.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(t.section) }
                    .semantics { contentDescription = t.label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(width = 56.dp, height = 32.dp)
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(t.icon, contentDescription = null, tint = if (selected) s.onAccentSoft else s.fg3, modifier = Modifier.size(24.dp))
                }
                if (t.section == Section.Feed && pending > 0) {
                    Box(Modifier.align(Alignment.TopEnd).padding(end = 4.dp).size(10.dp).background(s.warn, CircleShape))
                }
            }
        }
    }
}

/** Chat list that slides in over the left 70% of the screen. */
@Composable
private fun ChatDrawer(state: UiState, model: AppModel, modifier: Modifier) {
    val s = LocalAeonStatus.current
    Column(
        modifier.background(MaterialTheme.colorScheme.surface).safeDrawingPadding().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Æ Aeon", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        }
        AeonButton("＋ 새 대화", { model.newConversation() }, kind = ButtonKind.Primary, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth())
        SectionLabel("대화", Modifier.padding(horizontal = 16.dp))
        if (state.tasks.isEmpty()) {
            Text("아직 대화가 없습니다.", style = MaterialTheme.typography.bodyMedium, color = s.fg3, modifier = Modifier.padding(horizontal = 16.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.tasks, key = { it.id }) { t -> TaskRow(t, selected = t.id == state.selectedTaskId) { model.selectTask(t.id) } }
        }
        val connected = state.connection is Connection.Connected
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (connected) s.ok else s.danger, CircleShape))
            Spacer(Modifier.size(8.dp))
            Text(if (connected) "온라인" else "오프라인", style = MaterialTheme.typography.bodySmall, color = s.fg3)
        }
    }
}

/** 피드: what needs you now (approvals), then recent activity, newest first. */
@Composable
fun FeedScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    val s = LocalAeonStatus.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.approvals.isNotEmpty()) {
            SectionLabel("승인 대기")
            state.approvals.forEach { a ->
                val goal = state.tasks.firstOrNull { it.id == a.taskId }?.goal ?: "작업"
                ApprovalCard(a, state.approvalSeenAt[a.id], "$goal · ${a.tool}", { model.decide(a.id, it) })
            }
        }
        SectionLabel("최근 활동")
        if (state.tasks.isEmpty()) {
            EmptyState("아직 활동이 없습니다", "새 대화에서 목표를 말하면 여기에 쌓입니다.")
        }
        state.tasks.forEach { t ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(AeonShape.CARD.dp)).clickable { model.selectTask(t.id) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.goal, maxLines = 2, style = MaterialTheme.typography.bodyLarge)
                    TaskStatusChip(t.status)
                }
                Text(koreanClock(t.updatedAt), style = MaterialTheme.typography.bodySmall, color = s.fg3)
            }
        }
    }
}

private data class Example(val title: String, val text: String)

private val EXAMPLES = listOf(
    Example("파일 만들기와 확인", "hello.txt 파일을 만들고 내용에 안녕하세요를 써 줘"),
    Example("폴더 정리", "다운로드 폴더에서 지난달 영수증만 찾아 2025 영수증 폴더로 복사해 줘"),
    Example("문서 요약", "회의록 폴더의 파일을 읽고 요약 문서를 새로 만들어 줘"),
    Example("중복 파일 찾기", "작업 폴더에서 내용이 같은 파일을 찾아 목록으로 알려 줘"),
)

/** 둘러보기: example goals. Tapping one opens a new conversation with the text typed in, ready to edit. */
@Composable
fun ExploreScreen(model: AppModel, modifier: Modifier = Modifier) {
    val s = LocalAeonStatus.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("이런 일을 시켜 볼 수 있어요", style = MaterialTheme.typography.titleMedium)
        Text("예시 문구입니다. 눌러서 고친 뒤 보내세요. 삭제나 명령 실행은 먼저 물어봅니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
        EXAMPLES.forEach { e ->
            Card(Modifier.clickable { model.startWith(e.text) }) {
                Text(e.title, style = MaterialTheme.typography.titleMedium)
                Text(e.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
