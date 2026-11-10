package dev.aeon.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aeon.ui.api.AeonConfig
import dev.aeon.ui.api.Approval
import dev.aeon.ui.api.TaskDetail
import dev.aeon.ui.api.TaskSummary
import dev.aeon.ui.platform.formatDateTime
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.Connection
import dev.aeon.ui.state.UiState

// ------------------------------------------------------------------ small pieces

@Composable
fun StatusChip(status: String) {
    val c = StatusStyle.color(status)
    Row(
        Modifier.background(c.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(c, CircleShape))
        Spacer(Modifier.size(6.dp))
        Text(StatusStyle.label(status), color = c, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun Banner(text: String, onDismiss: (() -> Unit)? = null, isError: Boolean = false) {
    val bg = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    Row(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 14.sp)
        if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

// ------------------------------------------------------------------ pairing

@Composable
fun ConnectScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    var url by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var code by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth(0.98f).widthLimit(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect to Aeon", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Start the agent on your PC with “node src/node/cli.ts serve”. It prints a one-time pairing code. " +
                    "Enter the server address and the code below. The code is never sent over the network; both sides prove they know it.",
                color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedTextField(url, { url = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it }, label = { Text("Pairing code (e.g. ABCDE-FGHIJ-KLMNO-PQRS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!url.startsWith("https://") && !url.contains("127.0.0.1") && !url.contains("localhost")) {
                Banner("This address uses plain HTTP over your network. Requests are signed, but task contents are not encrypted. Use it only on a network you trust.", isError = true)
            }
            state.message?.let { Banner(it, onDismiss = model::dismissMessage, isError = true) }
            Button(onClick = { model.pair(url, code) }, enabled = !state.busy && code.isNotBlank() && url.isNotBlank()) {
                Text(if (state.connection is Connection.Connecting) "Connecting…" else "Pair this device")
            }
        }
    }
}

private fun Modifier.widthLimit(): Modifier = this.then(Modifier.fillMaxWidth())

// ------------------------------------------------------------------ tasks

@Composable
fun TaskRow(t: TaskSummary, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
    Column(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(t.goal, maxLines = 2, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(t.status)
            val v = t.verification?.status
            if (t.status == "COMPLETED" && v != null) Text(StatusStyle.verificationLabel(v), fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        }
        Text(formatDateTime(t.updatedAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
fun TaskList(state: UiState, model: AppModel, modifier: Modifier = Modifier, onOpen: (String) -> Unit = {}) {
    Column(modifier.fillMaxSize()) {
        NewTaskCard(model, state.busy)
        Spacer(Modifier.height(8.dp))
        if (state.tasks.isEmpty()) {
            Text("No tasks yet. Describe something above and Aeon will plan it, ask before anything risky, and check the result.", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(8.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.tasks, key = { it.id }) { t ->
                TaskRow(t, selected = t.id == state.selectedTaskId) {
                    model.selectTask(t.id)
                    onOpen(t.id)
                }
            }
        }
    }
}

@Composable
fun NewTaskCard(model: AppModel, busy: Boolean) {
    var goal by remember { mutableStateOf("") }
    var check by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(goal, { goal = it }, label = { Text("What should Aeon do?") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
            if (advanced) {
                OutlinedTextField(
                    check, { check = it },
                    label = { Text("How to verify (one per line): file_exists:PATH or file_contains:PATH:TEXT") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { model.createTask(goal, check.lines()); goal = ""; check = "" }, enabled = goal.isNotBlank() && !busy) { Text("Start task") }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide verification" else "Add verification") }
            }
        }
    }
}

@Composable
fun TaskDetailPane(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    val summary = state.tasks.firstOrNull { it.id == state.selectedTaskId }
    val d = state.selectedTask?.takeIf { it.id == state.selectedTaskId }
    if (summary == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Select a task", color = MaterialTheme.colorScheme.secondary) }
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(summary.goal, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(summary.status)
            Text(StatusStyle.verificationLabel(summary.verification?.status), fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
        }
        summary.note?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        summary.error?.let { Banner(it, isError = true) }
        summary.result?.let {
            SectionTitle("Agent's report")
            Text(it)
            Text("This is what the agent says it did. Only the verification below is checked against reality.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        }

        val pending = state.approvals.firstOrNull { it.id == summary.pendingApprovalId }
        if (pending != null) ApprovalCard(pending, model)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val running = summary.status !in setOf("COMPLETED", "FAILED", "CANCELLED")
            if (running) OutlinedButton(onClick = { model.cancel(summary.id) }) { Text("Cancel") }
            if (summary.status == "COMPLETED" || summary.status == "FAILED") {
                OutlinedButton(onClick = { model.reverify(summary.id) }) { Text("Re-check now") }
            }
            if (summary.status == "COMPLETED" && summary.verification?.status == "unverified") {
                Button(onClick = { model.confirm(summary.id) }) { Text("I checked it myself") }
            }
        }

        if (d != null) DetailBody(d)
    }
}

@Composable
private fun DetailBody(d: TaskDetail) {
    if (d.plan.isNotEmpty()) {
        SectionTitle("Plan")
        d.plan.forEachIndexed { i, p -> Text("${i + 1}. $p") }
    }
    d.verificationDetail?.let { v ->
        SectionTitle("Verification: ${StatusStyle.verificationLabel(v.status)}")
        if (v.evidence.isEmpty()) Text(v.note ?: "No objective check was defined.", color = MaterialTheme.colorScheme.secondary)
        v.evidence.forEach { e ->
            Text("${if (e.passed) "✔" else "✘"}  ${e.criterion}  →  ${e.observed}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (e.passed) StatusStyle.color("COMPLETED") else StatusStyle.color("FAILED"))
        }
        if (!v.userDefined && v.evidence.isNotEmpty()) Text("These checks were proposed by the model, not by you.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
    }
    if (d.steps.isNotEmpty()) {
        SectionTitle("Steps")
        d.steps.forEach { s ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${s.index + 1}. ${s.tool}  ${if (s.ok) "ok" else "failed"}", fontWeight = FontWeight.Medium)
                    SelectionContainer { Text(s.args.toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    HorizontalDivider()
                    SelectionContainer { Text(s.output.take(600), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ approvals

@Composable
fun ApprovalCard(a: Approval, model: AppModel) {
    Card(colors = CardDefaults.cardColors(containerColor = StatusStyle.color("WAITING_FOR_APPROVAL").copy(alpha = 0.10f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Aeon asks permission", fontWeight = FontWeight.Bold)
            SelectionContainer { Text(a.summary, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
            Text("Risk: ${a.risk}. Expires ${formatDateTime(a.expiresAt)}. Approving allows exactly this action, once.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.decide(a.id, true) }) { Text("Approve") }
                OutlinedButton(onClick = { model.decide(a.id, false) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Deny") }
            }
        }
    }
}

@Composable
fun ApprovalsScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Waiting for you")
        if (state.approvals.isEmpty()) Text("Nothing is waiting for your approval.", color = MaterialTheme.colorScheme.secondary)
        state.approvals.forEach { a ->
            val goal = state.tasks.firstOrNull { it.id == a.taskId }?.goal
            if (goal != null) Text("Task: $goal", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            ApprovalCard(a, model)
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
fun SettingsScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { model.loadSettings() }
    val cfg = state.config
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Connection")
        Text("Server: ${state.serverUrl}")
        state.status?.let { Text("Keys stored in: ${it.secretsBackend}", color = MaterialTheme.colorScheme.secondary) }
        state.status?.setupProblem?.let { Banner("Setup needed: $it", isError = true) }
        OutlinedButton(onClick = model::unpair) { Text("Forget this device") }

        if (cfg != null) {
            SectionTitle("Models and privacy")
            ConfigEditor(cfg, model)
        }

        SectionTitle("API keys")
        KeyEditor(state, model)

        if (state.usage.isNotEmpty()) {
            SectionTitle("Model usage")
            val ok = state.usage.filter { it.ok }
            Text("${ok.size} calls, ${ok.sumOf { it.inputTokens + it.outputTokens }} tokens (reasoning: ${ok.sumOf { it.reasoningTokens }}).")
        }
    }
}

private val KNOWN_PROVIDERS = listOf("exaone", "ollama", "llamacpp", "openai-compatible")

@Composable
private fun ConfigEditor(cfg: AeonConfig, model: AppModel) {
    var providers by remember(cfg) { mutableStateOf(cfg.providers) }
    var consent by remember(cfg) { mutableStateOf(cfg.dataConsent) }
    var fallback by remember(cfg) { mutableStateOf(cfg.fallbackAllowed) }
    var ollamaModel by remember(cfg) { mutableStateOf(cfg.ollamaModel.orEmpty()) }
    Text("Providers in priority order. The first is used; later ones only if you allow automatic fallback.", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
    KNOWN_PROVIDERS.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(p in providers, { on -> providers = if (on) providers + p else providers - p; if (!on) consent = consent - p })
            Text(p, Modifier.weight(1f))
            if (p == "exaone" || p == "openai-compatible") {
                Checkbox(p in consent, { on -> consent = if (on) consent + p else consent - p }, enabled = p in providers)
                Text("send my prompts there", fontSize = 12.sp)
            } else Text("local", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(fallback, { fallback = it })
        Text("Allow automatic switch to the next provider when one is down or rate limited")
    }
    OutlinedTextField(ollamaModel, { ollamaModel = it }, label = { Text("Ollama model name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("Changing providers or consent works only on the PC running Aeon.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
    Button(onClick = { model.saveConfig(cfg.copy(providers = providers, dataConsent = consent, fallbackAllowed = fallback, ollamaModel = ollamaModel.ifBlank { null })) }) { Text("Save") }
}

@Composable
private fun KeyEditor(state: UiState, model: AppModel) {
    var name by remember { mutableStateOf("EXAONE_API_KEY") }
    var value by remember { mutableStateOf("") }
    Text("Keys are write-only: Aeon never shows them again. They can be changed only on the PC running Aeon.", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
    state.secretNames.forEach { n ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$n  ✔ stored", Modifier.weight(1f))
            TextButton(onClick = { model.deleteSecret(n) }) { Text("Remove") }
        }
    }
    OutlinedTextField(name, { name = it.uppercase() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value, { value = it }, label = { Text("Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Button(onClick = { model.setSecret(name, value); value = "" }, enabled = value.isNotBlank() && name.isNotBlank()) { Text("Store key") }
}
