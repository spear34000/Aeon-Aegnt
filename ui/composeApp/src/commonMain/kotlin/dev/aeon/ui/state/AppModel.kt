package dev.aeon.ui.state

import dev.aeon.ui.api.AeonClient
import dev.aeon.ui.api.AeonConfig
import dev.aeon.ui.api.AeonException
import dev.aeon.ui.api.Approval
import dev.aeon.ui.api.CatalogCollection
import dev.aeon.ui.api.ConnectionItem
import dev.aeon.ui.api.IconPayload
import dev.aeon.ui.api.CatalogModel
import dev.aeon.ui.api.CurrentModel
import dev.aeon.ui.api.McpServer
import dev.aeon.ui.api.Profile
import dev.aeon.ui.api.ProviderRow
import dev.aeon.ui.platform.PickedFile
import dev.aeon.ui.api.DeviceInfo
import dev.aeon.ui.api.ServerStatus
import dev.aeon.ui.api.TaskDetail
import dev.aeon.ui.api.TaskSummary
import dev.aeon.ui.api.UsageRecord
import dev.aeon.ui.api.parseCheck
import dev.aeon.ui.platform.DeviceCrypto
import dev.aeon.ui.platform.KeyValueStore
import dev.aeon.ui.platform.LOCAL_AGENT_URL
import dev.aeon.ui.platform.LocalAgentHost
import dev.aeon.ui.platform.localAgentHost
import dev.aeon.ui.platform.defaultDeviceName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed interface Connection {
    data object Unpaired : Connection
    data object Connecting : Connection
    data class Connected(val serverName: String) : Connection
    /** Paired earlier, but the server is not reachable right now. */
    data class Offline(val reason: String) : Connection
}

/** Which main area is visible. The conversation is the default place (design: chat-first). */
enum class Section { Chat, Feed, Explore, Tasks, Agent, Approvals, Settings }

enum class SettingsPage { Personal, Models, Keys, Devices, Usage, Legal, About }

enum class ThemeMode(val label: String) { System("시스템 설정"), Light("라이트"), Dark("다크") }

/** A file already stored in the workspace, waiting to be mentioned in the next message. */
data class Attachment(val name: String, val path: String, val size: Long)

/** Model picker. The provider and model lists are whatever the server's catalogue reports. */
data class ModelState(
    val open: Boolean = false,
    val providers: List<ProviderRow> = emptyList(),
    val current: CurrentModel? = null,
    val catalogError: String? = null,
    /** Provider whose models are shown. */
    val providerId: String? = null,
    val models: Map<String, List<CatalogModel>> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * What a scanned pairing link suggested: addresses to try and a code. [address] is the first one that answered a
 * hello request. Nothing has been sent with the code: the owner still taps connect.
 */
data class PairPrefill(val addresses: List<String>, val code: String, val address: String? = null, val serverName: String? = null, val probing: Boolean = false, val probed: Boolean = false)

/** The skill being written or changed. [folder] is null for a new one. */
/** A skill being looked at or corrected. [folder] is also its name (Agent Skills format). */
data class SkillDraft(
    val folder: String,
    val description: String = "",
    val body: String = "",
    val license: String? = null,
    val compatibility: String? = null,
    val files: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    /** Only the owner's skills ("user") can be corrected here; a project skill is read-only. */
    val scope: String = "user",
)

/** The pages of the skills manager, pushed one over the other. */
enum class SkillScreen { List, Add, Found, Review, Detail, Read, Update }

/** Adding a skill: the source being typed, what was found, and the choices made before installing. */
data class SkillAddState(
    val source: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val preview: dev.aeon.ui.api.SkillPreview? = null,
    /** Name of the skill picked from the preview. */
    val pick: String? = null,
    val scope: String = "user",
    val rename: String = "",
    /** The same name exists: the sheet asking what to do is open. */
    val conflict: Boolean = false,
    val installing: Boolean = false,
    /** Scripts that pass the check run in Docker without a question each time. On unless the owner switches it off. */
    val autoRun: Boolean = true,
)

/** One skill open on its detail page, and what is being done to it. */
data class SkillDetailState(val skill: dev.aeon.ui.api.SkillItem, val busy: Boolean = false, val error: String? = null)

/** The text of a skill laid out for reading before it is approved: the instructions and every file. */
data class SkillReadState(
    val skill: dev.aeon.ui.api.SkillItem,
    /** File path to its text; null while loading, "" text for a file that is not text. */
    val files: Map<String, String?> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
)

/** A newer version of an installed skill, with what would change, waiting for the owner. */
data class SkillUpdateState(
    val folder: String,
    val scope: String,
    val preview: dev.aeon.ui.api.SkillPreview,
    val install: dev.aeon.ui.api.SkillInstallHint,
    val diff: dev.aeon.ui.api.SkillFileDiff,
    val busy: Boolean = false,
    val error: String? = null,
)

/** The feed of the owner's interests (headlines of the last refresh, and the pictures decoded so far). */
data class FeedState(
    val data: dev.aeon.ui.api.FeedData? = null,
    val interests: List<String> = emptyList(),
    val online: Boolean = true,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** One interest shown alone, or null for all of them. */
    val filter: String? = null,
    /** Pictures by item id: absent while not asked for, null when the server has none or it could not be read. */
    val images: Map<String, androidx.compose.ui.graphics.ImageBitmap?> = emptyMap(),
)

data class SkillsState(
    val list: List<dev.aeon.ui.api.SkillItem> = emptyList(),
    val dir: String = "",
    val projectDir: String? = null,
    val canEdit: Boolean = false,
    val loaded: Boolean = false,
    val editing: SkillDraft? = null,
    /** The page of the skills manager that is open, or null when it is closed. */
    val screen: SkillScreen? = null,
    val add: SkillAddState = SkillAddState(),
    val detail: SkillDetailState? = null,
    val read: SkillReadState? = null,
    val update: SkillUpdateState? = null,
    /** What the last look for newer versions found (empty until looked). */
    val updates: List<dev.aeon.ui.api.SkillUpdateStatus> = emptyList(),
    val checking: Boolean = false,
    val checkedOnce: Boolean = false,
    /** Opened from 둘러보기 instead of the list: back closes the manager. */
    val fromOutside: Boolean = false,
)

/** A pairing code shown on this device so another device can be let in. */
data class NewDevice(val code: String, val expiresAt: Long, val admin: Boolean, val addresses: List<String>)

/** 둘러보기. Everything here comes from the server: nothing is listed that the server did not report. */
data class ExploreState(
    val installed: List<ConnectionItem> = emptyList(),
    /** Adding and removing servers is only accepted from the machine that runs Aeon. */
    val canEdit: Boolean = false,
    val skillsDir: String = "",
    val collections: List<CatalogCollection> = emptyList(),
    val collectionId: String? = null,
    val query: String = "",
    val catalog: List<ConnectionItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Icon path -> payload. A key with a null value means "asked, and there is none". */
    val icons: Map<String, IconPayload?> = emptyMap(),
    val detail: ConnectionItem? = null,
)

data class UiState(
    val connection: Connection = Connection.Unpaired,
    val serverUrl: String = "http://127.0.0.1:7421",
    val section: Section = Section.Chat,
    val settingsPage: SettingsPage = SettingsPage.Models,
    /** Phone: a settings page is open over the 내 에이전트 list. */
    val settingsOpen: Boolean = false,
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
    val themeMode: ThemeMode = ThemeMode.System,
    val explore: ExploreState = ExploreState(),
    val models: ModelState = ModelState(),
    val attachments: List<Attachment> = emptyList(),
    val uploading: Boolean = false,
    val newDevice: NewDevice? = null,
    /** False for a pairing made before the server had a sealing key: keys cannot be sent from this device until it pairs again. */
    val canSeal: Boolean = false,
    val setup: SetupState = SetupState(),
    val pairPrefill: PairPrefill? = null,
    val skills: SkillsState = SkillsState(),
    val feed: FeedState = FeedState(),
) {
    /** This device may change settings, keys, connections and devices. */
    val isAdmin: Boolean get() = status?.admin == true
    /** What the server holds; before the settings have been read (or while offline), what was typed on this device. */
    val profile: Profile get() = if (config != null) config.profile ?: Profile() else setup.profileDraft

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
    private val localAgent: LocalAgentHost = localAgentHost(),
) {
    private val _state = MutableStateFlow(UiState(serverUrl = defaultServerUrl))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var poller: Job? = null

    /** Restores a previous pairing, if any, and starts polling. */
    fun start() {
        if (!_state.value.setup.loaded) loadSetup()
        store.get(KEY_THEME)?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved }?.let { m -> _state.update { it.copy(themeMode = m) } } }
        val url = store.get(KEY_URL)
        val id = store.get(KEY_DEVICE)
        if (url != null) {
            client.baseUrl = url
            _state.update { it.copy(serverUrl = url) }
        }
        if (url != null && id != null) {
            client.deviceId = id
            client.serverKey = store.get(KEY_SERVER_KEY).orEmpty()
            _state.update { it.copy(canSeal = client.serverKey.isNotEmpty()) }
            _state.update { it.copy(connection = Connection.Connecting) }
            // The agent that runs inside this app stops with the app: bring it back before asking it anything.
            if (localAgent.available && dev.aeon.ui.api.PairLink.isThisDevice(url)) scope.launch {
                localAgent.start()
                startPolling()
            } else startPolling()
        }
    }

    /** True when this build of the app contains the agent, so "이 기기에서 바로 쓰기" is possible. */
    val canRunOnDevice: Boolean get() = localAgent.available

    /**
     * Starts the agent inside this app and pairs with it, with nothing typed: the app asks the agent for a one-time code
     * through a file only this app can read. Progress and the reason for a failure are in [SetupState.deviceStarting] and
     * [SetupState.deviceError].
     */
    fun startOnDevice() {
        if (_state.value.setup.deviceStarting) return
        setup { it.copy(deviceStarting = true, deviceError = null) }
        scope.launch {
            val failure = localAgent.start() ?: localAgent.pairingCode()?.let { code ->
                pairNow(LOCAL_AGENT_URL, code)
                if (_state.value.connection is Connection.Connected) null else _state.value.message ?: "이 기기의 에이전트와 연결하지 못했어요."
            } ?: "이 기기의 에이전트에서 연결 코드를 받지 못했어요."
            setup { it.copy(deviceStarting = false, deviceError = failure) }
        }
    }

    fun stop() {
        poller?.cancel()
        poller = null
    }

    fun go(section: Section) = _state.update { it.copy(section = section, showDetail = false, drawerOpen = false, settingsOpen = false) }

    fun closeSettings() = _state.update { it.copy(settingsOpen = false) }

    fun openDrawer() = _state.update { it.copy(drawerOpen = true) }
    fun closeDrawer() = _state.update { it.copy(drawerOpen = false) }

    /** Starts a new conversation with [text] already typed, so the owner can edit it before sending. */
    fun startWith(text: String) = _state.update { it.copy(section = Section.Chat, selectedTaskId = null, selectedTask = null, showDetail = false, drawerOpen = false, prefill = text) }

    fun consumePrefill() = _state.update { it.copy(prefill = null) }

    fun openSettings(page: SettingsPage) {
        _state.update { it.copy(section = Section.Settings, settingsPage = page, showDetail = false, settingsOpen = true) }
        if (page == SettingsPage.Devices) scope.launch { runCatching { client.devices() }.onSuccess { d -> _state.update { it.copy(devices = d) } } }
        if (page == SettingsPage.Usage) scope.launch { runCatching { client.usage() }.onSuccess { u -> _state.update { it.copy(usage = u) } } }
    }

    /** Clears the current selection and shows the new-conversation composer. */
    fun newConversation() = _state.update { it.copy(section = Section.Chat, selectedTaskId = null, selectedTask = null, showDetail = false, drawerOpen = false) }

    fun pair(serverUrl: String, code: String) {
        scope.launch { pairNow(serverUrl, code) }
    }

    private suspend fun pairNow(serverUrl: String, code: String) {
        run {
            // Never let signed requests for the current pairing go to the address being tried: stop the poller,
            // remember the current identity, and only switch after the server has proven it knew the code.
            val previousUrl = client.baseUrl
            val previousDevice = client.deviceId
            val previousKey = client.serverKey
            val previousConnection = _state.value.connection
            stop()
            _state.update { it.copy(busy = true, message = null, connection = Connection.Connecting) }
            try {
                client.deviceId = ""
                client.baseUrl = serverUrl.trim()
                val res = client.pair(code, defaultDeviceName())
                store.put(KEY_URL, client.baseUrl)
                store.put(KEY_DEVICE, res.deviceId)
                if (client.serverKey.isNotEmpty()) store.put(KEY_SERVER_KEY, client.serverKey) else store.remove(KEY_SERVER_KEY)
                _state.update { it.copy(serverUrl = client.baseUrl, connection = Connection.Connected(res.serverName), busy = false, canSeal = client.serverKey.isNotEmpty()) }
                _state.update { it.copy(pairPrefill = null) }
                if (_state.value.setup.step == SetupStep.Connect) goStep(SetupStep.Model)
                if (dev.aeon.ui.platform.supportsBackgroundCheck && _state.value.setup.consents.background) runCatching { dev.aeon.ui.platform.setBackgroundApprovalCheck(true) }
                startPolling()
            } catch (e: Exception) {
                client.baseUrl = previousUrl
                client.deviceId = previousDevice
                client.serverKey = previousKey
                val stillPaired = previousDevice.isNotEmpty()
                _state.update {
                    it.copy(
                        connection = if (stillPaired) previousConnection else Connection.Unpaired,
                        serverUrl = previousUrl,
                        busy = false,
                        message = when {
                            e is AeonException && e.status == 403 -> "코드가 맞지 않거나 만료됐어요. 서버에서 새 코드를 받으세요."
                            e is AeonException && e.status == 429 -> "시도가 너무 많아요. 잠시 뒤에 다시 해 주세요."
                            else -> describe(e)
                        },
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
            store.remove(KEY_SERVER_KEY)
            client.deviceId = ""
            client.serverKey = ""
            crypto.reset()
            runCatching { dev.aeon.ui.platform.setBackgroundApprovalCheck(false) }
            _state.update { UiState(serverUrl = it.serverUrl, setup = it.setup, themeMode = it.themeMode) }
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
            if (_state.value.setup.step == SetupStep.Connect) goStep(SetupStep.Model)
            syncSetup(status.admin)
            // The greeting and the settings need the saved profile; read it once after connecting.
            if (_state.value.config == null) runCatching { client.config() }.onSuccess { cfg -> _state.update { it.copy(config = cfg, draft = it.draft ?: cfg) } }
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
        action(null) {
            val criteria = checks.filter { it.isNotBlank() }.map { parseCheck(it) ?: throw IllegalArgumentException("Check format: file_exists:PATH or file_contains:PATH:TEXT") }
            // Attached files are already in the workspace; the message only tells the agent where they are.
            val files = _state.value.attachments
            val text = if (files.isEmpty()) goal.trim() else goal.trim() + "\n\n첨부 파일 (작업 폴더 기준 경로):\n" + files.joinToString("\n") { "- ${it.path}" }
            val t = client.createTask(text, criteria)
            _state.update { it.copy(selectedTaskId = t.id, selectedTask = null, attachments = emptyList()) }
        }
    }

    /** Sends a chosen file to the workspace (`uploads/`) and keeps it as an attachment for the next message. */
    fun attach(file: PickedFile?, problem: String?) {
        if (file == null) {
            if (problem != null) _state.update { it.copy(message = problem) }
            return
        }
        scope.launch {
            _state.update { it.copy(uploading = true) }
            try {
                val r = client.upload(file.name, file.bytes)
                _state.update { it.copy(uploading = false, attachments = it.attachments + Attachment(file.name, r.path, r.size)) }
            } catch (e: Exception) {
                _state.update { it.copy(uploading = false, message = "파일을 올리지 못했습니다: ${describe(e)}") }
            }
        }
    }

    fun removeAttachment(path: String) = _state.update { s -> s.copy(attachments = s.attachments.filter { it.path != path }) }

    // ---------------------------------------------------------------- 모델

    private fun models(change: (ModelState) -> ModelState) = _state.update { it.copy(models = change(it.models)) }

    /** Reads the provider list and the model in use. Cheap enough to call when the composer appears. */
    fun loadModels() {
        scope.launch {
            try {
                val r = client.providers()
                models { it.copy(providers = r.providers, current = r.current, catalogError = r.catalogError, error = null) }
            } catch (e: Exception) {
                models { it.copy(error = describe(e)) }
            }
        }
    }

    fun openModelSheet() {
        models { it.copy(open = true, providerId = it.current?.provider, error = null) }
        loadModels()
        _state.value.models.current?.provider?.let(::showProvider)
    }

    fun closeModelSheet() = models { it.copy(open = false) }

    /** Shows one provider's models (null goes back to the provider list). */
    fun showProvider(id: String?) {
        models { it.copy(providerId = id, error = null) }
        if (id == null || _state.value.models.models.containsKey(id)) return
        scope.launch {
            models { it.copy(loading = true) }
            try {
                val list = client.providerModels(id)
                models { it.copy(loading = false, models = it.models + (id to list)) }
            } catch (e: Exception) {
                models { it.copy(loading = false, error = describe(e)) }
            }
        }
    }

    /** Saves an API key for a provider (sealed for the server), then refreshes the list. */
    fun saveProviderKey(keyName: String, value: String) {
        scope.launch {
            models { it.copy(loading = true, error = null) }
            try {
                client.setSecret(keyName, value.trim())
                val r = client.providers()
                models { it.copy(loading = false, providers = r.providers, current = r.current) }
                runCatching { client.secretNames() }.onSuccess { sec -> _state.update { it.copy(secretNames = sec.names, secretsBackend = sec.backend) } }
                refresh()
            } catch (e: Exception) {
                models { it.copy(loading = false, error = keyProblem(e)) }
            }
        }
    }

    /** Makes a model the one new tasks use. [consent] is the owner's yes to sending prompts to that provider. */
    fun pickModel(provider: String, model: String, effort: String?, consent: Boolean, customName: String? = null, customBaseUrl: String? = null, needsKey: Boolean = true) {
        scope.launch {
            models { it.copy(loading = true, error = null) }
            try {
                val r = client.selectModel(provider, model, effort, consent, customName, customBaseUrl, needsKey)
                _state.update { it.copy(config = r.config, draft = r.config) }
                val list = runCatching { client.providers() }.getOrNull()
                models { it.copy(loading = false, current = r.current ?: it.current, providers = list?.providers ?: it.providers, open = false) }
                refresh()
            } catch (e: Exception) {
                models { it.copy(loading = false, error = describe(e)) }
            }
        }
    }

    private fun keyProblem(e: Exception): String = when {
        !_state.value.canSeal -> "이 기기는 예전 방식으로 연결되어 키를 보낼 수 없습니다. 내 에이전트 › 정보에서 연결을 해제한 뒤 다시 연결하세요."
        e is AeonException && e.status == 403 -> "이 기기는 설정을 바꿀 수 없게 되어 있습니다. 설정을 바꿀 수 있는 기기에서 허용해 주세요."
        else -> describe(e)
    }

    // ---------------------------------------------------------------- 최초 설정

    private fun setup(change: (SetupState) -> SetupState) = _state.update { it.copy(setup = change(it.setup)) }

    /** Writes to this device's store. A failure never stops the flow: progress stays in memory and the UI says so. */
    private fun save(key: String, value: String?) {
        try {
            if (value == null) store.remove(key) else store.put(key, value)
        } catch (e: Exception) {
            setup { it.copy(storageError = true) }
        }
    }

    private fun read(key: String): String? = try {
        store.get(key)
    } catch (e: Exception) {
        null
    }

    /** Restores where the owner left off. A device that was paired before this flow existed is treated as done. */
    private fun loadSetup() {
        val saved = SetupStep.entries.firstOrNull { it.name == read(KEY_SETUP_STEP) }
        val step = saved ?: if (read(KEY_DEVICE) != null) SetupStep.Done else SetupStep.Intro
        val draft = try {
            read(KEY_SETUP_PROFILE)?.let { dev.aeon.ui.api.ApiJson.decodeFromString(Profile.serializer(), it) }
        } catch (e: Exception) {
            null
        }
        val consents = Consents.decode(read(KEY_SETUP_CONSENTS))
        setup {
            SetupState(
                loaded = true,
                step = step,
                introPage = read(KEY_SETUP_INTRO)?.toIntOrNull()?.coerceIn(0, INTRO_PAGES - 1) ?: 0,
                consents = consents,
                profileDraft = draft ?: Profile(),
                profilePage = read(KEY_SETUP_PROFILE_PAGE)?.toIntOrNull()?.coerceIn(0, PROFILE_PAGES - 1) ?: 0,
                where = read(KEY_SETUP_WHERE)?.takeIf { it == "device" || it == "remote" },
                profilePending = read(KEY_SETUP_PENDING) == "1",
                onlinePending = read(KEY_SETUP_ONLINE_PENDING) == "1",
            )
        }
        if (saved == null) save(KEY_SETUP_STEP, step.name)
        if (dev.aeon.ui.platform.supportsBackgroundCheck) runCatching { dev.aeon.ui.platform.setBackgroundApprovalCheck(consents.background && read(KEY_DEVICE) != null) }
    }

    private fun goStep(step: SetupStep) {
        save(KEY_SETUP_STEP, step.name)
        if (step == SetupStep.Done) save(KEY_SETUP_COMPLETED, "1")
        setup { it.copy(step = step, error = null, saving = false) }
        if (step == SetupStep.Coach) _state.update { it.copy(section = Section.Chat, selectedTaskId = null, selectedTask = null, showDetail = false, drawerOpen = false) }
    }

    fun setIntroPage(page: Int) {
        val p = page.coerceIn(0, INTRO_PAGES - 1)
        if (!_state.value.setup.replayIntro) save(KEY_SETUP_INTRO, p.toString())
        setup { it.copy(introPage = p) }
    }

    /** Leaves the intro, whether it was read to the end or skipped. */
    fun finishIntro() {
        if (_state.value.setup.replayIntro) setup { it.copy(replayIntro = false, step = SetupStep.entries.firstOrNull { s -> s.name == read(KEY_SETUP_STEP) } ?: SetupStep.Done) }
        else goStep(SetupStep.Welcome)
    }

    /** Shows the intro again. From the settings it returns to where the owner was; nothing saved is changed. */
    fun replayIntro() {
        val s = _state.value.setup
        if (s.done) setup { it.copy(replayIntro = true, step = SetupStep.Intro, introPage = 0) }
        else {
            setIntroPage(0)
            goStep(SetupStep.Intro)
        }
    }

    fun beginSetup() = goStep(SetupStep.Terms)

    fun setConsents(c: Consents) {
        save(KEY_SETUP_CONSENTS, c.encode())
        setup { it.copy(consents = c) }
    }

    /** Records the agreement and moves on. Only possible with both required items agreed. */
    fun acceptTerms() {
        val c = _state.value.setup.consents
        if (!c.requiredOk) return
        setConsents(c.copy(acceptedAt = dev.aeon.ui.platform.nowMillis()))
        save(KEY_SETUP_ONLINE_PENDING, "1")
        setup { it.copy(onlinePending = true) }
        // Agreeing again after taking the agreement back does not repeat the tour and the profile.
        goStep(
            when {
                // A scanned link already says which way: another device, with the address and code ready.
                _state.value.connection is Connection.Unpaired && _state.value.pairPrefill != null -> {
                    chooseWhere("remote")
                    setup { it.copy(guideDone = true) }
                    SetupStep.Connect
                }
                _state.value.connection is Connection.Unpaired -> SetupStep.Where
                read(KEY_SETUP_COMPLETED) == "1" -> SetupStep.Done
                else -> SetupStep.Coach
            },
        )
    }

    /** Where the agent runs: "device" or "remote". Kept on this device so closing the app resumes here. */
    fun chooseWhere(w: String) {
        save(KEY_SETUP_WHERE, w)
        setup { it.copy(where = w) }
    }

    /** Leaves the choice: another device starts with its guide; this device shows what is possible now. */
    fun whereContinue() {
        setup { it.copy(guideDone = false) }
        goStep(SetupStep.Connect)
        if (_state.value.setup.where == "device" && localAgent.available) startOnDevice()
    }

    /** The guide for another device is read; the address and code form comes next. */
    fun guideContinue() = setup { it.copy(guideDone = true) }

    /** Ends the model step (a model was chosen, the agent already had one, or the owner chose to do it later). */
    fun finishModelStep() {
        if (_state.value.setup.step != SetupStep.Model) return
        goStep(if (read(KEY_SETUP_COMPLETED) == "1") SetupStep.Done else SetupStep.Coach)
    }

    /** Taking back a required agreement returns the app to the terms; nothing else is deleted. */
    fun withdrawTerms() {
        val c = _state.value.setup.consents
        setConsents(c.copy(service = false, data = false, acceptedAt = 0))
        goStep(SetupStep.Terms)
    }

    /** Per-device choice: check for waiting approvals while the app is closed. */
    fun setBackgroundConsent(on: Boolean) {
        setConsents(_state.value.setup.consents.copy(background = on))
        if (dev.aeon.ui.platform.supportsBackgroundCheck) runCatching { dev.aeon.ui.platform.setBackgroundApprovalCheck(on && client.deviceId.isNotEmpty()) }
    }

    /** Server-wide choice: may the server machine look up lists and logos on the internet. Needs the right to change settings. */
    fun setOnlineLookups(on: Boolean) = action(null) {
        val cfg = client.config()
        val saved = client.saveConfig(cfg.copy(onlineLookups = if (on) null else false))
        setConsents(_state.value.setup.consents.copy(online = on))
        _state.update { it.copy(config = saved, draft = saved) }
    }

    /** One step back inside the first-run flow. Returns false when there is nothing to go back to. */
    fun setupBack(): Boolean {
        val s = _state.value.setup
        when (s.step) {
            SetupStep.Intro -> if (s.introPage > 0) setIntroPage(s.introPage - 1) else return false
            SetupStep.Welcome -> {
                setIntroPage(INTRO_PAGES - 1)
                goStep(SetupStep.Intro)
            }
            SetupStep.Terms -> goStep(SetupStep.Welcome)
            SetupStep.Where -> goStep(SetupStep.Terms)
            SetupStep.Connect -> if (s.where == "remote" && s.guideDone) setup { it.copy(guideDone = false) } else goStep(SetupStep.Where)
            SetupStep.Model -> goStep(SetupStep.Where)
            SetupStep.Coach -> if (s.coachIndex > 0) setup { it.copy(coachIndex = it.coachIndex - 1) } else return false
            SetupStep.Profile -> if (s.profilePage > 0) setProfilePage(s.profilePage - 1) else goStep(SetupStep.Coach)
            SetupStep.Done -> return false
        }
        return true
    }

    fun setCoachIndex(i: Int) = setup { it.copy(coachIndex = i.coerceAtLeast(0)) }

    /** Ends the coach marks (finished or skipped). */
    fun finishCoach() {
        if (_state.value.setup.replayCoach) setup { it.copy(replayCoach = false, coachIndex = 0) }
        else {
            setup { it.copy(coachIndex = 0) }
            goStep(SetupStep.Profile)
        }
    }

    fun replayCoach() {
        _state.update { it.copy(section = Section.Chat, selectedTaskId = null, selectedTask = null, showDetail = false, drawerOpen = false) }
        setup { it.copy(replayCoach = true, coachIndex = 0) }
    }

    /** Keeps the profile being typed on this device, so closing the app loses nothing. */
    fun editSetupProfile(p: Profile) {
        save(KEY_SETUP_PROFILE, try { dev.aeon.ui.api.ApiJson.encodeToString(Profile.serializer(), p) } catch (e: Exception) { null })
        setup { it.copy(profileDraft = p, error = null) }
    }

    fun setProfilePage(page: Int) {
        val p = page.coerceIn(0, PROFILE_PAGES - 1)
        save(KEY_SETUP_PROFILE_PAGE, p.toString())
        setup { it.copy(profilePage = p) }
    }

    /** Starts from what the server already knows, when nothing was typed here yet. */
    fun prefillSetupProfile() {
        scope.launch {
            val cfg = _state.value.config ?: runCatching { client.config() }.getOrNull() ?: return@launch
            _state.update { it.copy(config = cfg) }
            val server = cfg.profile ?: return@launch
            if (_state.value.setup.profileDraft == Profile()) editSetupProfile(server)
        }
    }

    private fun cleanProfile(p: Profile): Profile = p.copy(
        userName = p.userName?.trim()?.ifEmpty { null },
        agentName = p.agentName?.trim()?.ifEmpty { null },
        about = p.about?.trim()?.ifEmpty { null },
        interests = p.interests?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.take(12)?.ifEmpty { null },
    )

    private suspend fun pushProfile(p: Profile) {
        val cfg = client.config()
        val saved = client.saveConfig(cfg.copy(profile = cleanProfile(p)))
        _state.update { it.copy(config = saved, draft = saved) }
        save(KEY_SETUP_PENDING, null)
        setup { it.copy(profilePending = false) }
    }

    /** Sends the profile to the server so the agent uses it, then opens the main screen. On failure the owner chooses. */
    fun finishProfile() {
        val draft = cleanProfile(_state.value.setup.profileDraft)
        if (draft == Profile()) return goStep(SetupStep.Done)
        scope.launch {
            setup { it.copy(saving = true, error = null) }
            try {
                pushProfile(draft)
                goStep(SetupStep.Done)
            } catch (e: Exception) {
                setup { it.copy(saving = false, error = "서버에 저장하지 못했습니다. ${describe(e)}") }
            }
        }
    }

    /** Continues without waiting for the server: the profile stays on this device and is sent when the server answers. */
    fun finishProfileLater() {
        save(KEY_SETUP_PENDING, "1")
        setup { it.copy(profilePending = true) }
        goStep(SetupStep.Done)
    }

    /** Leaves the profile for later. What was typed stays on this device and can be finished in the settings. */
    fun skipProfile() = goStep(SetupStep.Done)

    /** Runs after each successful poll: sends what could not be sent during setup. */
    private suspend fun syncSetup(admin: Boolean) {
        val s = _state.value.setup
        if (s.profilePending) runCatching { pushProfile(s.profileDraft) }
        if (s.onlinePending && s.consents.acceptedAt > 0) {
            // The owner's choice is applied once. A device that may not change settings leaves the server as it is.
            if (admin) {
                val ok = runCatching {
                    val cfg = client.config()
                    val wanted = if (s.consents.online) null else false
                    val saved = if (cfg.onlineLookups != wanted) client.saveConfig(cfg.copy(onlineLookups = wanted)) else cfg
                    _state.update { it.copy(config = saved) }
                }.isSuccess
                if (!ok) return
            }
            save(KEY_SETUP_ONLINE_PENDING, null)
            setup { it.copy(onlinePending = false) }
        }
    }

    // ---------------------------------------------------------------- 개인화

    /** Saves how the agent addresses the owner. Allowed from every paired device. */
    fun saveProfile(profile: Profile) = action("저장했어요") {
        val cfg = client.config()
        val saved = client.saveConfig(cfg.copy(profile = cleanProfile(profile)))
        _state.update { it.copy(config = saved, draft = saved) }
        editSetupProfile(saved.profile ?: Profile())
    }

    // ---------------------------------------------------------------- 기기

    fun loadDevices() {
        scope.launch { runCatching { client.devices() }.onSuccess { d -> _state.update { it.copy(devices = d) } } }
    }

    /** Shows a one-time code another device can type. [admin] decides whether that device may change settings. */
    fun newPairingCode(admin: Boolean) {
        scope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val (code, r) = client.newPairingCode(admin)
                _state.update { it.copy(busy = false, newDevice = NewDevice(code, r.expiresAt, r.admin, r.addresses)) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = keyProblem(e)) }
            }
        }
    }

    /**
     * Handles `aeon://pair?...` (a scanned QR code). Any app can send this link, so it is parsed strictly and only
     * pre-fills the connect screen. Before the first-run terms are agreed the flow jumps to them, so the short-lived
     * code is still valid when the connect step is reached.
     */
    fun receivePairLink(raw: String) {
        val link = dev.aeon.ui.api.PairLink.parse(raw)
        if (link == null) {
            _state.update { it.copy(message = "읽을 수 없는 QR 코드예요.") }
            return
        }
        if (_state.value.connection !is Connection.Unpaired) {
            _state.update { it.copy(message = "이미 서버에 연결되어 있어요. 다른 서버에 연결하려면 먼저 연결을 끊으세요.") }
            return
        }
        // Nothing is contacted yet: the addresses are only remembered, and the connect screen shows them for the owner to check.
        _state.update { it.copy(pairPrefill = PairPrefill(link.addresses, link.code)) }
        val step = _state.value.setup.step
        if (step == SetupStep.Intro || step == SetupStep.Welcome) goStep(SetupStep.Terms)
    }

    /**
     * Called by the connect screen once it is showing and the terms are agreed: asks the suggested addresses that are
     * on a local network whether an Aeon server answers (a plain hello, nothing secret), and picks the first that does.
     */
    fun probePrefill() {
        val p = _state.value.pairPrefill ?: return
        if (p.probed || _state.value.connection !is Connection.Unpaired || !_state.value.setup.consents.requiredOk) return
        _state.update { it.copy(pairPrefill = p.copy(probing = true, probed = true)) }
        scope.launch {
            var found: Pair<String, String>? = null
            for (a in p.addresses) {
                if (!dev.aeon.ui.api.PairLink.isLanAddress(a)) continue
                val name = client.probe(a) ?: continue
                found = a to name
                break
            }
            _state.update { s -> s.copy(pairPrefill = s.pairPrefill?.takeIf { it.code == p.code }?.copy(address = found?.first, serverName = found?.second, probing = false) ?: s.pairPrefill) }
        }
    }

    fun clearPairPrefill() = _state.update { it.copy(pairPrefill = null) }

    fun dismissPairingCode() {
        _state.update { it.copy(newDevice = null) }
        loadDevices()
    }

    fun renameDevice(id: String, name: String) = action("이름을 바꿨어요") {
        client.updateDevice(id, name = name.trim())
        _state.update { it.copy(devices = client.devices()) }
    }

    fun setDeviceAdmin(id: String, admin: Boolean) = action(null) {
        client.updateDevice(id, admin = admin)
        _state.update { it.copy(devices = client.devices()) }
    }

    fun decide(approvalId: String, approve: Boolean) = action(null) { client.decide(approvalId, approve) }
    fun cancel(id: String) = action("멈췄어요") { client.cancel(id) }
    fun reverify(id: String) = action("다시 확인했어요") { client.reverify(id) }
    fun confirm(id: String) = action("확인했어요") { client.confirm(id) }

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
        action("저장했어요") {
            val saved = client.saveConfig(d)
            _state.update { it.copy(config = saved, draft = saved) }
        }
    }

    fun setSecret(name: String, value: String) = action("키를 저장했어요") {
        client.setSecret(name, value)
        val sec = client.secretNames()
        _state.update { it.copy(secretNames = sec.names, secretsBackend = sec.backend) }
    }

    fun deleteSecret(name: String) = action("키를 지웠어요") {
        client.deleteSecret(name)
        val sec = client.secretNames()
        _state.update { it.copy(secretNames = sec.names) }
    }

    fun revokeDevice(id: String) = action("기기를 끊었어요") {
        client.revokeDevice(id)
        _state.update { it.copy(devices = client.devices()) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** System -> Light -> Dark -> System. Remembered on this device only. */
    fun cycleTheme() {
        val next = ThemeMode.entries[(_state.value.themeMode.ordinal + 1) % ThemeMode.entries.size]
        store.put(KEY_THEME, next.name)
        _state.update { it.copy(themeMode = next) }
    }

    fun loadUsage() {
        scope.launch { runCatching { client.usage() }.onSuccess { u -> _state.update { it.copy(usage = u) } } }
    }

    // ---------------------------------------------------------------- 둘러보기

    private var catalogJob: Job? = null
    private val iconRequests = mutableSetOf<String>()
    private val iconGate = Semaphore(4)

    private fun explore(change: (ExploreState) -> ExploreState) = _state.update { it.copy(explore = change(it.explore)) }

    /** Reads what is installed and which collections exist, then fills the "can be added" list. */
    fun loadExplore() {
        scope.launch {
            try {
                val installed = client.connections()
                val collections = runCatching { client.collections() }.getOrDefault(emptyList())
                explore { it.copy(installed = installed.connections, canEdit = installed.canEdit, skillsDir = installed.skillsDir, collections = collections, error = null) }
                val e = _state.value.explore
                when {
                    e.query.isNotBlank() -> loadCatalog(query = e.query)
                    else -> (e.collectionId ?: collections.firstOrNull()?.id)?.let { loadCatalog(collection = it) }
                }
            } catch (e: Exception) {
                explore { it.copy(loading = false, error = describe(e)) }
            }
        }
    }

    /** Typing searches the registry after a short pause; clearing the field returns to the collections. */
    fun searchCatalog(query: String) {
        explore { it.copy(query = query) }
        catalogJob?.cancel()
        catalogJob = scope.launch {
            delay(400)
            val e = _state.value.explore
            if (query.isBlank()) (e.collectionId ?: e.collections.firstOrNull()?.id)?.let { loadCatalog(collection = it) } else loadCatalog(query = query)
        }
    }

    fun openCollection(id: String) {
        explore { it.copy(query = "", collectionId = id) }
        catalogJob?.cancel()
        catalogJob = scope.launch { loadCatalog(collection = id) }
    }

    private suspend fun loadCatalog(query: String = "", collection: String? = null) {
        explore { it.copy(loading = true, error = null, collectionId = collection ?: it.collectionId) }
        try {
            val r = client.catalog(query.trim(), collection)
            explore { it.copy(catalog = r.connections, loading = false) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val text = if (e is AeonException && e.status == 502) "목록을 가져오지 못했습니다. Aeon 서버의 인터넷 연결을 확인하세요." else describe(e)
            explore { it.copy(catalog = emptyList(), loading = false, error = text) }
        }
    }

    /** Asks the server for an icon once per path. The server looks it up and caches it; the device never contacts other hosts. */
    fun requestIcon(path: String) {
        if (!iconRequests.add(path)) return
        scope.launch {
            val payload = iconGate.withPermit { runCatching { client.icon(path) }.getOrNull() }
            explore { it.copy(icons = it.icons + (path to payload)) }
        }
    }

    fun showConnection(c: ConnectionItem?) = explore { it.copy(detail = c) }

    // ---------------------------------------------------------------- 피드

    private fun feed(change: (FeedState) -> FeedState) = _state.update { it.copy(feed = change(it.feed)) }

    private val feedImageRequests = mutableSetOf<String>()

    /** Shows what the server has, and refreshes when it is old (needs interests in the profile and internet lookups on). */
    fun loadFeed() {
        scope.launch {
            runCatching { client.feed() }
                .onSuccess { r ->
                    feed { it.copy(data = r.feed, interests = r.interests, online = r.online, loaded = true, error = null, filter = it.filter?.takeIf { f -> f in r.interests }) }
                    if (r.stale && r.online && r.interests.isNotEmpty()) refreshFeed()
                }
                .onFailure { e -> feed { it.copy(loaded = true, error = describe(e as? Exception ?: Exception(e.message))) } }
        }
    }

    fun refreshFeed() {
        if (_state.value.feed.refreshing) return
        scope.launch {
            feed { it.copy(refreshing = true, error = null) }
            try {
                val r = client.refreshFeed()
                feed { it.copy(data = r.feed, interests = r.interests, online = true, refreshing = false, loaded = true) }
            } catch (e: Exception) {
                val msg = when {
                    e is AeonException && e.status == 403 -> null
                    e is AeonException && e.status == 400 -> null
                    else -> "새 소식을 가져오지 못했어요. 잠시 뒤에 다시 해 보세요."
                }
                feed { it.copy(refreshing = false, online = if (e is AeonException && e.status == 403) false else it.online, error = msg) }
            }
        }
    }

    fun setFeedFilter(interest: String?) = feed { it.copy(filter = interest) }

    /** Fetches and decodes the picture of one item, at most three at a time. */
    fun loadFeedImage(id: String) {
        if (!feedImageRequests.add(id)) return
        scope.launch {
            val img = iconGate.withPermit { runCatching { client.feedImage(id) }.getOrNull()?.let { dev.aeon.ui.platform.decodeImage(it) } }
            feed { it.copy(images = it.images + (id to img)) }
        }
    }

    fun openArticle(url: String) = dev.aeon.ui.platform.openUrl(url)

    /** Article pictures in the feed on or off (server-wide). Off also deletes the copies the server kept. */
    fun setFeedImages(on: Boolean) = action(null) {
        val cfg = client.config()
        val saved = client.saveConfig(cfg.copy(feedImages = if (on) null else false))
        _state.update { it.copy(config = saved, draft = saved) }
        feedImageRequests.clear()
        feed { it.copy(images = emptyMap()) }
        refreshFeed()
    }

    // ---------------------------------------------------------------- 스킬

    private fun skills(change: (SkillsState) -> SkillsState) = _state.update { it.copy(skills = change(it.skills)) }

    fun loadSkills() {
        scope.launch {
            runCatching { client.skills() }.onSuccess { r -> skills { it.copy(list = r.skills, dir = r.dir, projectDir = r.projectDir, canEdit = r.canEdit, loaded = true) } }
        }
    }

    /** Opens a skill to read and, if needed, correct. New skills are made by the agent, not here. */
    fun editSkill(folder: String, skillScope: String = "user") {
        scope.launch {
            runCatching { client.skill(folder, skillScope) }.onSuccess { s ->
                skills { it.copy(editing = SkillDraft(s.folder, s.description, s.body, s.license, s.compatibility, s.files, s.problems, scope = s.scope)) }
            }.onFailure { e -> _state.update { it.copy(message = describe(e as? Exception ?: Exception(e.message))) } }
        }
    }

    /** Starts a conversation that asks the agent to make a skill; the owner finishes the sentence. */
    fun askForSkill() = startWith("이런 일을 할 때 쓸 스킬을 만들어 줘: ")

    fun changeSkillDraft(change: (SkillDraft) -> SkillDraft) = skills { s -> s.copy(editing = s.editing?.let { change(it).copy(error = null) }) }

    fun closeSkillEditor() = skills { it.copy(editing = null) }

    fun saveSkill() {
        val d = _state.value.skills.editing ?: return
        if (d.description.isBlank() || d.saving) return
        scope.launch {
            skills { s -> s.copy(editing = s.editing?.copy(saving = true, error = null)) }
            try {
                client.saveSkill(d.folder, d.description.trim(), d.body)
                val r = client.skills()
                skills { it.copy(list = r.skills, dir = r.dir, editing = null) }
                loadExplore()
            } catch (e: Exception) {
                skills { s -> s.copy(editing = s.editing?.copy(saving = false, error = if (e is AeonException && e.status == 403) "이 기기는 스킬을 바꿀 수 없어요." else describe(e))) }
            }
        }
    }

    fun deleteSkill(folder: String, skillScope: String = "user") {
        scope.launch {
            try {
                client.deleteSkill(folder, skillScope)
                val r = client.skills()
                // Deleting from the detail page returns to where it was opened from; from the editor it just closes.
                skills { it.copy(list = r.skills, editing = null, detail = null, read = null, screen = if (it.screen == null) null else if (it.fromOutside) null else SkillScreen.List) }
                loadExplore()
            } catch (e: Exception) {
                val msg = if (e is AeonException && e.status == 403) "이 기기는 스킬을 바꿀 수 없어요." else describe(e)
                skills { s -> s.copy(editing = s.editing?.copy(error = msg), detail = s.detail?.copy(busy = false, error = msg)) }
            }
        }
    }

    // ---- the skills manager: list, add from a source, detail, read before approving, update

    private fun skillMessage(e: Exception): String = when {
        e is AeonException && e.status == 403 -> "이 기기는 스킬을 바꿀 수 없어요."
        e is AeonException && e.status == 409 -> e.message ?: "이미 있거나 바뀐 스킬이에요."
        else -> describe(e)
    }

    /** Opens the skills manager at its list. */
    fun openSkills() {
        skills { it.copy(screen = SkillScreen.List, fromOutside = false, detail = null, read = null, update = null) }
        loadSkills()
        // Looking for newer versions asks GitHub: once per run, and only when the owner can act on the answer.
        if (!_state.value.skills.checkedOnce) checkSkillUpdates(quiet = true)
    }

    fun closeSkills() = skills { it.copy(screen = null, detail = null, read = null, update = null, add = SkillAddState()) }

    /** One step back inside the skills manager. */
    fun skillsBack() {
        val s = _state.value.skills
        when (s.screen) {
            null -> {}
            SkillScreen.List -> closeSkills()
            SkillScreen.Add -> skills { it.copy(screen = SkillScreen.List) }
            SkillScreen.Found -> skills { it.copy(screen = SkillScreen.Add, add = it.add.copy(preview = null, pick = null, error = null, conflict = false)) }
            SkillScreen.Review -> skills { it.copy(screen = if ((it.add.preview?.candidates?.size ?: 0) > 1) SkillScreen.Found else SkillScreen.Add, add = it.add.copy(conflict = false, error = null)) }
            SkillScreen.Detail -> if (s.fromOutside) closeSkills() else skills { it.copy(screen = SkillScreen.List, detail = null) }
            SkillScreen.Read -> skills { it.copy(screen = SkillScreen.Detail, read = null) }
            SkillScreen.Update -> skills { it.copy(screen = SkillScreen.Detail, update = null) }
        }
    }

    /** Opens one skill's page. [fromOutside] is true when it was opened from 둘러보기, so back leaves the manager. */
    fun openSkill(folder: String, skillScope: String = "user", fromOutside: Boolean = false) {
        scope.launch {
            runCatching { client.skill(folder, skillScope) }
                .onSuccess { s -> skills { it.copy(screen = SkillScreen.Detail, detail = SkillDetailState(s), fromOutside = fromOutside || (it.screen == null)) } }
                .onFailure { e -> _state.update { it.copy(message = describe(e as? Exception ?: Exception(e.message))) } }
        }
    }

    private suspend fun reloadSkill(folder: String, skillScope: String) {
        runCatching { client.skills() }.onSuccess { r -> skills { it.copy(list = r.skills, dir = r.dir, projectDir = r.projectDir, canEdit = r.canEdit, loaded = true) } }
        runCatching { client.skill(folder, skillScope) }.onSuccess { s -> skills { it.copy(detail = it.detail?.copy(skill = s, busy = false, error = null)) } }
    }

    fun setSkillEnabled(on: Boolean) {
        val d = _state.value.skills.detail ?: return
        scope.launch {
            skills { it.copy(detail = it.detail?.copy(busy = true, error = null)) }
            try {
                client.setSkillEnabled(d.skill.folder, d.skill.scope, on)
                reloadSkill(d.skill.folder, d.skill.scope)
            } catch (e: Exception) {
                skills { it.copy(detail = it.detail?.copy(busy = false, error = skillMessage(e))) }
            }
        }
    }

    /** Opens the text of the skill (instructions and every file) so the owner reads what they are approving. */
    fun readSkill() {
        val d = _state.value.skills.detail ?: return
        val sk = d.skill
        skills { it.copy(screen = SkillScreen.Read, read = SkillReadState(sk, files = sk.files.associateWith { null }, busy = true)) }
        scope.launch {
            for (path in sk.files.take(20)) {
                val text = runCatching { client.skillFile(sk.folder, sk.scope, path) }.getOrNull()
                val shown = if (text == null) "(읽지 못했어요)" else if (text.binary) "(글이 아닌 파일이에요 · ${text.size}바이트)" else text.text ?: ""
                skills { st -> st.copy(read = st.read?.let { r -> r.copy(files = r.files + (path to shown)) }) }
            }
            skills { it.copy(read = it.read?.copy(busy = false)) }
        }
    }

    /** Approves the skill as it was when the page was opened: the hash shown goes back, so a skill that changed since is refused. */
    fun trustSkill() {
        val s = _state.value.skills
        val sk = s.read?.skill ?: s.detail?.skill ?: return
        scope.launch {
            skills { it.copy(read = it.read?.copy(busy = true, error = null), detail = it.detail?.copy(busy = true, error = null)) }
            try {
                client.trustSkill(sk.folder, sk.scope, sk.hash)
                reloadSkill(sk.folder, sk.scope)
                skills { it.copy(screen = SkillScreen.Detail, read = null) }
            } catch (e: Exception) {
                val msg = if (e is AeonException && e.status == 409) "읽는 동안 스킬이 바뀌었어요. 다시 열어 새 내용을 읽어 주세요." else skillMessage(e)
                skills { it.copy(read = it.read?.copy(busy = false, error = msg), detail = it.detail?.copy(busy = false, error = msg)) }
            }
        }
    }

    // ---- adding from a source

    fun openAddSkill() = skills { it.copy(screen = SkillScreen.Add, add = SkillAddState(scope = if (it.projectDir != null) it.add.scope else "user")) }

    fun changeSkillSource(text: String) = skills { it.copy(add = it.add.copy(source = text.take(300), error = null)) }

    fun inspectSkillSource() {
        val src = _state.value.skills.add.source.trim()
        if (src.isEmpty() || _state.value.skills.add.busy) return
        scope.launch {
            skills { it.copy(add = it.add.copy(busy = true, error = null)) }
            try {
                val p = client.inspectSkillSource(src)
                val usable = p.candidates.filter { it.problems.isEmpty() }
                // One skill that follows the format goes straight to its review; otherwise the owner picks.
                val single = if (p.candidates.size == 1 && usable.size == 1) usable.first().name else null
                skills { it.copy(screen = if (single != null) SkillScreen.Review else SkillScreen.Found, add = it.add.copy(busy = false, preview = p, pick = single, rename = single ?: "")) }
            } catch (e: Exception) {
                skills { it.copy(add = it.add.copy(busy = false, error = if (e is AeonException && e.status == 403) "이 기기는 스킬을 가져올 수 없어요." else skillMessage(e))) }
            }
        }
    }

    fun pickSkillCandidate(name: String) = skills { it.copy(screen = SkillScreen.Review, add = it.add.copy(pick = name, rename = name, error = null, conflict = false)) }

    fun setSkillAutoRunDraft(on: Boolean) = skills { it.copy(add = it.add.copy(autoRun = on)) }

    fun setSkillAutoRun(on: Boolean) {
        val d = _state.value.skills.detail ?: return
        scope.launch {
            skills { it.copy(detail = it.detail?.copy(busy = true, error = null)) }
            try {
                client.setSkillAutoRun(d.skill.folder, d.skill.scope, on)
                reloadSkill(d.skill.folder, d.skill.scope)
            } catch (e: Exception) {
                skills { it.copy(detail = it.detail?.copy(busy = false, error = skillMessage(e))) }
            }
        }
    }

    fun setSkillInstallScope(scopeName: String) = skills { it.copy(add = it.add.copy(scope = scopeName, error = null)) }

    fun changeSkillRename(text: String) = skills { it.copy(add = it.add.copy(rename = text.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' }.take(64), error = null)) }

    fun closeSkillConflict() = skills { it.copy(add = it.add.copy(conflict = false, error = null)) }

    /** Installs the picked skill. [replace] overwrites a skill of the same name; a new [rename] installs it next to it. */
    fun installSkill(replace: Boolean = false, renamed: Boolean = false) {
        val a = _state.value.skills.add
        val pick = a.pick ?: return
        val p = a.preview ?: return
        if (a.installing) return
        val rename = if (renamed && a.rename.isNotBlank() && a.rename != pick) a.rename else null
        scope.launch {
            skills { it.copy(add = it.add.copy(installing = true, error = null)) }
            try {
                val s = client.installSkill(p.id, pick, a.scope, rename, replace, a.autoRun)
                val r = client.skills()
                skills { it.copy(list = r.skills, dir = r.dir, projectDir = r.projectDir, add = SkillAddState(scope = a.scope), screen = SkillScreen.Detail, detail = SkillDetailState(s), fromOutside = false) }
                loadExplore()
            } catch (e: Exception) {
                if (e is AeonException && e.status == 409 && !replace && rename == null) {
                    skills { it.copy(add = it.add.copy(installing = false, conflict = true, rename = suggestSkillName(pick, it.list.map { s -> s.folder }))) }
                } else {
                    skills { it.copy(add = it.add.copy(installing = false, conflict = if (e is AeonException && e.status == 409) it.add.conflict else false, error = skillMessage(e))) }
                }
            }
        }
    }

    private fun suggestSkillName(base: String, taken: List<String>): String {
        var i = 2
        while ("$base-$i" in taken) i++
        return "$base-$i".take(64)
    }

    // ---- newer versions

    /** Asks GitHub whether the skills that came from it have newer versions. [quiet] hides a failure (offline, lookups off). */
    fun checkSkillUpdates(quiet: Boolean = false) {
        if (_state.value.skills.checking) return
        scope.launch {
            skills { it.copy(checking = true) }
            try {
                val u = client.skillUpdates()
                skills { it.copy(updates = u, checking = false, checkedOnce = true) }
            } catch (e: Exception) {
                skills { it.copy(checking = false, checkedOnce = true) }
                if (!quiet) _state.update { it.copy(message = skillMessage(e)) }
            }
        }
    }

    fun previewSkillUpdate() {
        val d = _state.value.skills.detail ?: return
        scope.launch {
            skills { it.copy(detail = it.detail?.copy(busy = true, error = null)) }
            try {
                val u = client.skillUpdatePreview(d.skill.folder, d.skill.scope)
                skills { it.copy(screen = SkillScreen.Update, update = SkillUpdateState(d.skill.folder, d.skill.scope, u.preview, u.install, u.diff), detail = it.detail?.copy(busy = false)) }
            } catch (e: Exception) {
                skills { it.copy(detail = it.detail?.copy(busy = false, error = skillMessage(e))) }
            }
        }
    }

    fun applySkillUpdate() {
        val u = _state.value.skills.update ?: return
        scope.launch {
            skills { it.copy(update = it.update?.copy(busy = true, error = null)) }
            try {
                val s = client.installSkill(u.preview.id, u.install.name, u.scope, u.install.rename, replace = true)
                val r = client.skills()
                skills { it.copy(list = r.skills, update = null, screen = SkillScreen.Detail, detail = SkillDetailState(s), updates = it.updates.filterNot { x -> x.name == u.folder && x.scope == u.scope }) }
            } catch (e: Exception) {
                skills { it.copy(update = it.update?.copy(busy = false, error = skillMessage(e))) }
            }
        }
    }

    /** Saves a remote server into the settings. The server refuses this from a device that may not change settings. */
    fun addConnection(c: ConnectionItem) = action("추가했어요") {
        val url = c.remoteUrl ?: throw IllegalStateException("원격 주소가 없는 항목은 아직 추가할 수 없습니다.")
        val name = c.id.removePrefix("mcp:")
        val cfg = client.config()
        val server = McpServer(name = name, title = c.title, url = url, websiteUrl = c.websiteUrl?.takeIf { it.startsWith("https://") }, requires = c.requires.ifEmpty { null })
        client.saveConfig(cfg.copy(mcpServers = (cfg.mcpServers ?: emptyList()).filter { it.name != name } + server))
        explore { it.copy(detail = null) }
        loadExplore()
    }

    fun removeConnection(c: ConnectionItem) = action("삭제했어요") {
        val name = c.id.removePrefix("mcp:")
        val cfg = client.config()
        client.saveConfig(cfg.copy(mcpServers = (cfg.mcpServers ?: emptyList()).filter { it.name != name }))
        explore { it.copy(detail = null) }
        loadExplore()
    }

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
        const val KEY_THEME = "themeMode"
        const val KEY_SERVER_KEY = "serverKey"
        const val KEY_SETUP_COMPLETED = "setup.completed"
        const val KEY_SETUP_STEP = "setup.step"
        const val KEY_SETUP_INTRO = "setup.introPage"
        const val KEY_SETUP_CONSENTS = "setup.consents"
        const val KEY_SETUP_PROFILE = "setup.profile"
        const val KEY_SETUP_PROFILE_PAGE = "setup.profilePage"
        const val KEY_SETUP_WHERE = "setup.where"
        const val KEY_SETUP_PENDING = "setup.profilePending"
        const val KEY_SETUP_ONLINE_PENDING = "setup.onlinePending"
    }
}
