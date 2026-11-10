package dev.aeon.ui.state

import dev.aeon.ui.api.AeonClient
import dev.aeon.ui.api.AeonConfig
import dev.aeon.ui.api.AeonException
import dev.aeon.ui.api.Approval
import dev.aeon.ui.api.DeviceInfo
import dev.aeon.ui.api.ServerStatus
import dev.aeon.ui.api.TaskDetail
import dev.aeon.ui.api.TaskSummary
import dev.aeon.ui.api.UsageRecord
import dev.aeon.ui.api.parseCheck
import dev.aeon.ui.platform.DeviceCrypto
import dev.aeon.ui.platform.KeyValueStore
import dev.aeon.ui.platform.defaultDeviceName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Connection {
    data object Unpaired : Connection
    data object Connecting : Connection
    data class Connected(val serverName: String) : Connection
    /** Paired earlier, but the server is not reachable right now. */
    data class Offline(val reason: String) : Connection
}

/** Which main area is visible. The conversation is the default place (design: chat-first). */
enum class Section { Chat, Feed, Explore, Tasks, Agent, Approvals, Settings }

enum class SettingsPage { Models, Keys, Devices, Usage, About }

data class UiState(
    val connection: Connection = Connection.Unpaired,
    val serverUrl: String = "http://127.0.0.1:7421",
    val section: Section = Section.Chat,
    val settingsPage: SettingsPage = SettingsPage.Models,
    val tasks: List<TaskSummary> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val recentApprovals: List<Approval> = emptyList(),
    val status: ServerStatus? = null,
    /** null = "new conversation" composer. */
    val selectedTaskId: String? = null,
    val selectedTask: TaskDetail? = null,
    /** Phone: the task detail is shown on top of the conversation/list. */
    val showDetail: Boolean = false,
    val config: AeonConfig? = null,
    /** Unsaved edits to the model settings. null when nothing is being edited. */
    val draft: AeonConfig? = null,
    val secretNames: List<String> = emptyList(),
    val secretsBackend: String = "",
    val devices: List<DeviceInfo> = emptyList(),
    val usage: List<UsageRecord> = emptyList(),
    /** Last user-visible problem (cleared on the next successful action). */
    val message: String? = null,
    val busy: Boolean = false,
    /** Monotonic time (ms) when the approval list was last seen with a new card; used for the 1 s allow delay. */
    val approvalSeenAt: Map<String, Long> = emptyMap(),
    /** Phone: the chat list slides in from the left over the screen. */
    val drawerOpen: Boolean = false,
    /** Text to put in the composer when a new conversation starts from an example. */
    val prefill: String? = null,
) {
    val pendingCount: Int get() = approvals.count { it.status == "pending" }

    /** How many model settings differ from the saved ones. */
    val dirtyCount: Int
        get() {
            val c = config ?: return 0
            val d = draft ?: return 0
            var n = 0
            if (c.providers != d.providers) n++
            if (c.dataConsent != d.dataConsent) n++
            if (c.fallbackAllowed != d.fallbackAllowed) n++
            if (c.ollamaModel != d.ollamaModel) n++
            return n
        }
}

/**
 * Screen-independent state and actions shared by the Windows, web and Android UIs.
 * It polls the local API. The UI holds no business logic beyond calling the API and reporting failures.
 */
class AppModel(
    private val client: AeonClient,
    private val store: KeyValueStore,
    private val crypto: DeviceCrypto,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 2000,
    defaultServerUrl: String = "http://127.0.0.1:7421",
) {
    private val _state = MutableStateFlow(UiState(serverUrl = defaultServerUrl))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var poller: Job? = null

    /** Restores a previous pairing, if any, and starts polling. */
    fun start() {
        val url = store.get(KEY_URL)
        val id = store.get(KEY_DEVICE)
        if (url != null) {
            client.baseUrl = url
            _state.update { it.copy(serverUrl = url) }
        }
        if (url != null && id != null) {
            client.deviceId = id
            _state.update { it.copy(connection = Connection.Connecting) }
            startPolling()
        }
    }

    fun stop() {
        poller?.cancel()
        poller = null
    }

    fun go(section: Section) = _state.update { it.copy(section = section, showDetail = false, drawerOpen = false) }

    fun openDrawer() = _state.update { it.copy(drawerOpen = true) }
    fun closeDrawer() = _state.update { it.copy(drawerOpen = false) }

    /** Starts a new conversation with [text] already typed, so the owner can edit it before sending. */
    fun startWith(text: String) = _state.update { it.copy(section = Section.Chat, selectedTaskId = null, selectedTask = null, showDetail = false, drawerOpen = false, prefill = text) }

    fun consumePrefill() = _state.update { it.copy(prefill = null) }

    fun openSettings(page: SettingsPage) {
        _state.update { it.copy(section = Section.Settings, settingsPage = page, showDetail = false) }
        if (page == SettingsPage.Devices) scope.launch { runCatching { client.devices() }.onSuccess { d -> _state.update { it.copy(devices = d) } } }
        if (page == SettingsPage.Usage) scope.launch { runCatching { client.usage() }.onSuccess { u -> _state.update { it.copy(usage = u) } } }
    }

    /** Clears the current selection and shows the new-conversation composer. */
    fun newConversation() = _state.update { it.copy(section = Section.Chat, selectedTaskId = null, selectedTask = null, showDetail = false, drawerOpen = false) }

    fun pair(serverUrl: String, code: String) {
        scope.launch {
            // Never let signed requests for the current pairing go to the address being tried: stop the poller,
            // remember the current identity, and only switch after the server has proven it knew the code.
            val previousUrl = client.baseUrl
            val previousDevice = client.deviceId
            val previousConnection = _state.value.connection
            stop()
            _state.update { it.copy(busy = true, message = null, connection = Connection.Connecting) }
            try {
                client.deviceId = ""
                client.baseUrl = serverUrl.trim()
                val res = client.pair(code, defaultDeviceName())
                store.put(KEY_URL, client.baseUrl)
                store.put(KEY_DEVICE, res.deviceId)
                _state.update { it.copy(serverUrl = client.baseUrl, connection = Connection.Connected(res.serverName), busy = false) }
                startPolling()
            } catch (e: Exception) {
                client.baseUrl = previousUrl
                client.deviceId = previousDevice
                val stillPaired = previousDevice.isNotEmpty()
                _state.update {
                    it.copy(
                        connection = if (stillPaired) previousConnection else Connection.Unpaired,
                        serverUrl = previousUrl,
                        busy = false,
                        message = describe(e),
                    )
                }
                if (stillPaired) startPolling()
            }
        }
    }

    /** Forget this pairing on this device (the server keeps the device record until it is revoked there). */
    fun unpair() {
        scope.launch {
            stop()
            store.remove(KEY_DEVICE)
            client.deviceId = ""
            crypto.reset()
            _state.update { UiState(serverUrl = it.serverUrl) }
        }
    }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            while (true) {
                refresh()
                delay(pollMillis)
            }
        }
    }

    suspend fun refresh() {
        try {
            val status = client.status()
            val tasks = client.tasks()
            val approvals = client.approvals("pending")
            val recent = client.approvals(null).filter { it.status != "pending" }.takeLast(20).reversed()
            val selectedId = _state.value.selectedTaskId
            val detail = if (selectedId != null) runCatching { client.task(selectedId) }.getOrNull() else null
            val now = dev.aeon.ui.platform.nowMillis()
            _state.update { s ->
                val seen = s.approvalSeenAt.toMutableMap()
                for (a in approvals) if (!seen.containsKey(a.id)) seen[a.id] = now
                s.copy(
                    connection = Connection.Connected(status.name),
                    status = status,
                    tasks = tasks,
                    approvals = approvals,
                    recentApprovals = recent,
                    selectedTask = detail ?: s.selectedTask,
                    approvalSeenAt = seen,
                )
            }
        } catch (e: AeonException) {
            if (e.status == 401) {
                // Revoked or clock skew: stop hammering the server and ask the user to pair again.
                stop()
                _state.update { it.copy(connection = Connection.Unpaired, message = "This device is no longer authorised. Pair it again.") }
                store.remove(KEY_DEVICE)
            } else {
                _state.update { it.copy(connection = Connection.Offline(describe(e))) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(connection = Connection.Offline(describe(e))) }
        }
    }

    fun selectTask(id: String?, asDetail: Boolean = false) {
        _state.update { it.copy(selectedTaskId = id, selectedTask = null, showDetail = asDetail, section = Section.Chat, drawerOpen = false) }
        if (id != null) scope.launch { runCatching { client.task(id) }.onSuccess { d -> _state.update { it.copy(selectedTask = d) } } }
    }

    fun closeDetail() = _state.update { it.copy(showDetail = false) }

    fun createTask(goal: String, checks: List<String>) {
        action("Task started") {
            val criteria = checks.filter { it.isNotBlank() }.map { parseCheck(it) ?: throw IllegalArgumentException("Check format: file_exists:PATH or file_contains:PATH:TEXT") }
            val t = client.createTask(goal.trim(), criteria)
            _state.update { it.copy(selectedTaskId = t.id, selectedTask = null) }
        }
    }

    fun decide(approvalId: String, approve: Boolean) = action(if (approve) "Allowed once" else "Denied") { client.decide(approvalId, approve) }
    fun cancel(id: String) = action("Stopping") { client.cancel(id) }
    fun reverify(id: String) = action("Checked again") { client.reverify(id) }
    fun confirm(id: String) = action("Marked as checked by you") { client.confirm(id) }

    fun loadSettings() {
        action(null) {
            val cfg = client.config()
            val sec = client.secretNames()
            _state.update { it.copy(config = cfg, draft = it.draft ?: cfg, secretNames = sec.names, secretsBackend = sec.backend) }
        }
    }

    fun editDraft(change: (AeonConfig) -> AeonConfig) = _state.update { s -> s.copy(draft = change(s.draft ?: s.config ?: AeonConfig())) }

    fun revertDraft() = _state.update { it.copy(draft = it.config) }

    fun saveDraft() {
        val d = _state.value.draft ?: return
        action("Saved") {
            val saved = client.saveConfig(d)
            _state.update { it.copy(config = saved, draft = saved) }
        }
    }

    fun setSecret(name: String, value: String) = action("Key saved") {
        client.setSecret(name, value)
        val sec = client.secretNames()
        _state.update { it.copy(secretNames = sec.names, secretsBackend = sec.backend) }
    }

    fun deleteSecret(name: String) = action("Key removed") {
        client.deleteSecret(name)
        val sec = client.secretNames()
        _state.update { it.copy(secretNames = sec.names) }
    }

    fun revokeDevice(id: String) = action("Device removed") {
        client.revokeDevice(id)
        _state.update { it.copy(devices = client.devices()) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun action(success: String?, block: suspend () -> Unit) {
        scope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
                _state.update { it.copy(message = success, busy = false) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(message = describe(e), busy = false) }
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is AeonException -> if (e.status == 0) e.message ?: "error" else "${e.message} (HTTP ${e.status})"
        else -> e.message ?: e::class.simpleName ?: "error"
    }

    private companion object {
        const val KEY_URL = "serverUrl"
        const val KEY_DEVICE = "deviceId"
    }
}
