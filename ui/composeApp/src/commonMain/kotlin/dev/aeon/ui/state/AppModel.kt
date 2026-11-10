package dev.aeon.ui.state

import dev.aeon.ui.api.AeonClient
import dev.aeon.ui.api.AeonConfig
import dev.aeon.ui.api.AeonException
import dev.aeon.ui.api.Approval
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

data class UiState(
    val connection: Connection = Connection.Unpaired,
    val serverUrl: String = "http://127.0.0.1:7421",
    val tasks: List<TaskSummary> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val status: ServerStatus? = null,
    val selectedTaskId: String? = null,
    val selectedTask: TaskDetail? = null,
    val config: AeonConfig? = null,
    val secretNames: List<String> = emptyList(),
    val secretsBackend: String = "",
    val usage: List<UsageRecord> = emptyList(),
    /** Last user-visible problem (cleared on the next successful action). */
    val message: String? = null,
    val busy: Boolean = false,
)

/**
 * Screen-independent state and actions shared by the Windows, web (and later Android) UIs.
 * It polls the local API; there is no business logic here beyond calling the API and reporting failures.
 */
class AppModel(
    private val client: AeonClient,
    private val store: KeyValueStore,
    private val crypto: DeviceCrypto,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 2000,
) {
    private val _state = MutableStateFlow(UiState())
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

    fun pair(serverUrl: String, code: String) {
        scope.launch {
            _state.update { it.copy(busy = true, message = null, connection = Connection.Connecting) }
            try {
                client.baseUrl = serverUrl.trim()
                val res = client.pair(code, defaultDeviceName())
                store.put(KEY_URL, client.baseUrl)
                store.put(KEY_DEVICE, res.deviceId)
                _state.update { it.copy(serverUrl = client.baseUrl, connection = Connection.Connected(res.serverName), busy = false) }
                startPolling()
            } catch (e: Exception) {
                _state.update { it.copy(connection = Connection.Unpaired, busy = false, message = describe(e)) }
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
            val selectedId = _state.value.selectedTaskId
            val detail = if (selectedId != null) runCatching { client.task(selectedId) }.getOrNull() else null
            _state.update {
                it.copy(
                    connection = Connection.Connected(status.name),
                    status = status,
                    tasks = tasks,
                    approvals = approvals,
                    selectedTask = detail ?: it.selectedTask,
                )
            }
        } catch (e: AeonException) {
            if (e.status == 401) {
                // Revoked or clock skew: stop hammering the server and ask the user to pair again.
                stop()
                _state.update { it.copy(connection = Connection.Unpaired, message = "This device is no longer authorised (revoked, or the clock differs by more than a minute). Pair it again.") }
                store.remove(KEY_DEVICE)
            } else {
                _state.update { it.copy(connection = Connection.Offline(describe(e))) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(connection = Connection.Offline(describe(e))) }
        }
    }

    fun selectTask(id: String?) {
        _state.update { it.copy(selectedTaskId = id, selectedTask = if (id == _state.value.selectedTaskId) it.selectedTask else null) }
        if (id != null) scope.launch { runCatching { client.task(id) }.onSuccess { d -> _state.update { it.copy(selectedTask = d) } } }
    }

    fun createTask(goal: String, checks: List<String>) {
        action("Task created") {
            val criteria = checks.filter { it.isNotBlank() }.map { parseCheck(it) ?: throw IllegalArgumentException("Bad check: $it (use file_exists:PATH or file_contains:PATH:TEXT)") }
            val t = client.createTask(goal.trim(), criteria)
            _state.update { it.copy(selectedTaskId = t.id) }
        }
    }

    fun decide(approvalId: String, approve: Boolean) = action(if (approve) "Approved" else "Denied") { client.decide(approvalId, approve) }
    fun cancel(id: String) = action("Cancel requested") { client.cancel(id) }
    fun reverify(id: String) = action("Re-verified against the real files") { client.reverify(id) }
    fun confirm(id: String) = action("Confirmed by you") { client.confirm(id) }

    fun loadSettings() {
        action(null) {
            val cfg = client.config()
            val sec = client.secretNames()
            val usage = client.usage()
            _state.update { it.copy(config = cfg, secretNames = sec.names, secretsBackend = sec.backend, usage = usage) }
        }
    }

    fun saveConfig(cfg: AeonConfig) = action("Settings saved") {
        val saved = client.saveConfig(cfg)
        _state.update { it.copy(config = saved) }
    }

    fun setSecret(name: String, value: String) = action("Key stored") {
        client.setSecret(name, value)
        val sec = client.secretNames()
        _state.update { it.copy(secretNames = sec.names, secretsBackend = sec.backend) }
    }

    fun deleteSecret(name: String) = action("Key removed") {
        client.deleteSecret(name)
        val sec = client.secretNames()
        _state.update { it.copy(secretNames = sec.names) }
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
