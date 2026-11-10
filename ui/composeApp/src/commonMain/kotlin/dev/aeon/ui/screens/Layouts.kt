package dev.aeon.ui.screens

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.Connection
import dev.aeon.ui.state.UiState

enum class Destination(val title: String) { Tasks("Tasks"), Approvals("Approvals"), Settings("Settings") }

/** Chooses between pairing and the app proper. Each platform supplies its own shell for the paired state. */
@Composable
fun AppRoot(model: AppModel, shell: @Composable (UiState, AppModel) -> Unit) {
    val state by model.state.collectAsState()
    AeonTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.connection is Connection.Unpaired) ConnectScreen(state, model) else shell(state, model)
        }
    }
}

@Composable
fun ConnectionStrip(state: UiState, model: AppModel) {
    val c = state.connection
    val (text, color) = when (c) {
        is Connection.Connected -> "Connected to ${c.serverName}" to Color(0xFF2E7D32)
        is Connection.Connecting -> "Connecting…" to Color(0xFF5B6478)
        is Connection.Offline -> "Server unreachable: ${c.reason}" to Color(0xFFC62828)
        Connection.Unpaired -> "Not paired" to Color(0xFF5B6478)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 2)
    }
    state.message?.let {
        Spacer(Modifier.height(8.dp))
        Banner(it, onDismiss = model::dismissMessage, isError = state.connection is Connection.Offline)
    }
}

@Composable
private fun Content(dest: Destination, state: UiState, model: AppModel, modifier: Modifier = Modifier, onOpenTask: (String) -> Unit = {}) {
    when (dest) {
        Destination.Tasks -> TaskList(state, model, modifier, onOpen = onOpenTask)
        Destination.Approvals -> ApprovalsScreen(state, model, modifier)
        Destination.Settings -> SettingsScreen(state, model, modifier)
    }
}

/**
 * Wide layout (Windows app, wide browser windows): permanent sidebar, task list and task detail side by side.
 */
@Composable
fun WideLayout(state: UiState, model: AppModel) {
    var dest by remember { mutableStateOf(Destination.Tasks) }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(220.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Aeon", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Destination.entries.forEach { d ->
                val badge = if (d == Destination.Approvals && state.approvals.isNotEmpty()) "  (${state.approvals.size})" else ""
                val selected = d == dest
                Text(
                    d.title + badge,
                    Modifier.fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { dest = d }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Spacer(Modifier.weight(1f))
            ConnectionStrip(state, model)
        }
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (dest == Destination.Tasks) {
                Content(dest, state, model, Modifier.width(380.dp))
                TaskDetailPane(state, model, Modifier.weight(1f))
            } else {
                Content(dest, state, model, Modifier.weight(1f))
            }
        }
    }
}

/**
 * Narrow layout (phones and narrow browser windows): bottom navigation, one pane at a time, back to the list from a task.
 */
@Composable
fun NarrowLayout(state: UiState, model: AppModel) {
    var dest by remember { mutableStateOf(Destination.Tasks) }
    var showDetail by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ConnectionStrip(state, model) }
        Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            if (dest == Destination.Tasks && showDetail) {
                Column {
                    TextButton(onClick = { showDetail = false }) { Text("← Tasks") }
                    TaskDetailPane(state, model, Modifier.weight(1f, fill = false))
                }
            } else {
                Content(dest, state, model, onOpenTask = { showDetail = true })
            }
        }
        NavigationBar {
            Destination.entries.forEach { d ->
                NavigationBarItem(
                    selected = d == dest,
                    onClick = { dest = d; showDetail = false },
                    icon = { Text(if (d == Destination.Approvals && state.approvals.isNotEmpty()) "${state.approvals.size}" else d.title.take(1), fontWeight = FontWeight.Bold) },
                    label = { Text(d.title) },
                )
            }
        }
    }
}

/** Browser shell: picks the layout from the window width, so the same page works on a PC and on a phone. */
@Composable
fun AdaptiveLayout(state: UiState, model: AppModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 900.dp) WideLayout(state, model) else NarrowLayout(state, model)
    }
}
