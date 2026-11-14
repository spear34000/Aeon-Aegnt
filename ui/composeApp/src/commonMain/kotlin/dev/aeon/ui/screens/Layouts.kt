package dev.aeon.ui.screens

import dev.aeon.ui.design.Glyph
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.aeon.ui.state.SetupStep
import dev.aeon.ui.platform.PlatformBackHandler
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonTheme
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.Connection
import dev.aeon.ui.state.Section
import dev.aeon.ui.state.SettingsPage
import dev.aeon.ui.state.UiState

private enum class RootScreen { Splash, Intro, Welcome, Terms, Where, Guide, Device, Connect, Model, Profile, Main }

/**
 * Decides what fills the window: the splash while state is restored, the first-run steps in order, the pairing
 * screen whenever this device is not paired, and otherwise the app itself. Each platform only supplies the shell.
 */
@Composable
fun AppRoot(model: AppModel, shell: @Composable (UiState, AppModel) -> Unit) {
    val state by model.state.collectAsState()
    var splashDone by rememberSaveable { mutableStateOf(false) }
    val coach = remember { CoachRegistry() }
    AeonTheme(dark = when (state.themeMode) {
        dev.aeon.ui.state.ThemeMode.System -> null
        dev.aeon.ui.state.ThemeMode.Light -> false
        dev.aeon.ui.state.ThemeMode.Dark -> true
    }) {
        CompositionLocalProvider(LocalCoach provides coach) {
            // The background fills the whole window, under the system bars; the content keeps clear of them.
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
              Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val setup = state.setup
                val screen = when {
                    !splashDone || !setup.loaded -> RootScreen.Splash
                    setup.step == SetupStep.Intro -> RootScreen.Intro
                    setup.step == SetupStep.Welcome -> RootScreen.Welcome
                    setup.step == SetupStep.Terms -> RootScreen.Terms
                    setup.step == SetupStep.Where -> RootScreen.Where
                    state.connection is Connection.Unpaired || (setup.step == SetupStep.Connect && state.connection !is Connection.Connected) ->
                        // Before the address and code form, each way has its own page: the guide for another device, or what is possible on this one.
                        if (setup.step == SetupStep.Connect && setup.where == "device") RootScreen.Device
                        else if (setup.step == SetupStep.Connect && setup.where == "remote" && !setup.guideDone && state.pairPrefill == null) RootScreen.Guide
                        else RootScreen.Connect
                    setup.step == SetupStep.Model -> RootScreen.Model
                    setup.step == SetupStep.Profile -> RootScreen.Profile
                    else -> RootScreen.Main
                }
                val coaching = screen == RootScreen.Main && (setup.step == SetupStep.Coach || setup.replayCoach)
                val canGoBack = when (screen) {
                    RootScreen.Intro -> setup.introPage > 0 || !setup.replayIntro
                    RootScreen.Terms, RootScreen.Profile, RootScreen.Where, RootScreen.Guide, RootScreen.Device, RootScreen.Model -> true
                    RootScreen.Connect -> setup.step == SetupStep.Connect
                    RootScreen.Main -> coaching && setup.coachIndex > 0
                    RootScreen.Splash, RootScreen.Welcome -> false
                }
                PlatformBackHandler(enabled = canGoBack) { if (coaching) model.setCoachIndex(setup.coachIndex - 1) else model.setupBack() }
                AnimatedContent(
                    screen,
                    transitionSpec = { (fadeIn(tween(280)) + slideInHorizontally(tween(280)) { it / 10 }) togetherWith fadeOut(tween(160)) },
                    label = "root",
                ) { which ->
                    when (which) {
                        RootScreen.Splash -> SplashScreen(ready = setup.loaded) { splashDone = true }
                        RootScreen.Intro -> IntroScreen(state, model)
                        RootScreen.Welcome -> WelcomeScreen(model)
                        RootScreen.Terms -> TermsScreen(state, model)
                        RootScreen.Where -> WhereScreen(state, model)
                        RootScreen.Guide -> GuideScreen(state, model)
                        RootScreen.Device -> DeviceScreen(state, model)
                        RootScreen.Model -> Box(Modifier.fillMaxSize()) {
                            ModelStepScreen(state, model)
                            ModelSheetHost(state, model)
                        }
                        RootScreen.Connect -> ConnectScreen(state, model, onBack = if (setup.step == SetupStep.Connect) ({ model.setupBack() }) else null)
                        RootScreen.Profile -> ProfileSetupScreen(state, model)
                        RootScreen.Main -> Box(Modifier.fillMaxSize()) {
                            shell(state, model)
                            ModelSheetHost(state, model)
                            if (coaching) CoachOverlay(setup.coachIndex, model::setCoachIndex, model::finishCoach)
                        }
                    }
                }
              }
            }
        }
    }
}

/** Browser and desktop: wide layout from 1100dp, phone layout below. */
@Composable
fun AdaptiveLayout(state: UiState, model: AppModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 1100.dp) WideLayout(state, model) else PhoneLayout(state, model)
    }
}

/**
 * Wide layout (design 09): sidebar 220 + list 380 + conversation or task detail. Between 1100 and 1279dp the
 * sidebar collapses to an icon rail (72) so the conversation keeps its width.
 */
@Composable
fun WideLayout(state: UiState, model: AppModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = maxWidth < 1280.dp
        Row(Modifier.fillMaxSize()) {
            Sidebar(state, model, rail)
            when (state.section) {
                Section.Chat, Section.Tasks -> {
                    Box(Modifier.width(380.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
                        TaskList(state, model)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (state.showDetail && state.selectedTaskId != null) {
                            TaskDetailPanel(state, model, onBack = { model.closeDetail() })
                        } else {
                            ConversationScreen(state, model, wide = true)
                        }
                    }
                }
                Section.Approvals -> Box(Modifier.weight(1f).fillMaxHeight()) { ApprovalsPanel(state, model) }
                Section.Settings, Section.Agent -> Box(Modifier.weight(1f).fillMaxHeight()) { SettingsScreen(state, model) }
                Section.Feed, Section.Explore -> Box(Modifier.weight(1f).fillMaxHeight()) { FeedScreen(state, model) }
            }
        }
    }
}

@Composable
private fun Sidebar(state: UiState, model: AppModel, rail: Boolean) {
    val s = LocalAeonStatus.current
    val width = if (rail) 72.dp else 220.dp
    Column(
        Modifier.width(width).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant).padding(if (rail) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = if (rail) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (!rail) Text("Æ Aeon", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        else Text("Æ", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(6.dp))
        AeonButton(if (rail) "+" else "＋ 새 대화", { model.newConversation() }, kind = ButtonKind.Primary, wide = true, modifier = Modifier.fillMaxWidth())
        NavItem(if (rail) "대" else "대화", state.section == Section.Chat, rail) { model.go(Section.Chat); model.newConversation() }
        NavItem(if (rail) "승" else if (state.pendingCount > 0) "승인 대기 ${state.pendingCount}" else "승인 대기", state.section == Section.Approvals, rail) { model.go(Section.Approvals) }
        NavItem(if (rail) "기" else "기기", state.section == Section.Settings && state.settingsPage == SettingsPage.Devices, rail) { model.openSettings(SettingsPage.Devices) }
        NavItem(if (rail) "설" else "설정", state.section == Section.Settings && state.settingsPage != SettingsPage.Devices, rail) { model.openSettings(SettingsPage.Models) }
        Spacer(Modifier.weight(1f))
        if (!rail) {
            val (dot, text) = when (val c = state.connection) {
                is Connection.Connected -> s.ok to "온라인 · ${c.serverName}"
                is Connection.Connecting -> s.fg3 to "연결 중…"
                is Connection.Offline -> s.danger to "오프라인"
                Connection.Unpaired -> s.fg3 to "연결 안 됨"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = s.fg3)
            }
        } else {
            Box(Modifier.size(8.dp).background(if (state.connection is Connection.Connected) s.ok else s.danger, CircleShape))
        }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, rail: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) LocalAeonStatus.current.onAccentSoft else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = if (rail) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}

