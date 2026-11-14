package dev.aeon.ui.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Wire models for the local API (docs/API.md). Unknown fields are ignored so older UIs keep working. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class VerificationBrief(val status: String, val checkedAt: Long)

@Serializable
data class TaskSummary(
    val id: String,
    val goal: String,
    val status: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val runAfter: Long = 0,
    val attempt: Int = 0,
    val note: String? = null,
    val error: String? = null,
    val result: String? = null,
    val verification: VerificationBrief? = null,
    val pendingApprovalId: String? = null,
    val stepCount: Int = 0,
)

@Serializable
data class Step(
    val index: Int,
    val tool: String,
    val args: JsonElement? = null,
    val output: String = "",
    val ok: Boolean = false,
    val at: Long = 0,
    val executed: Boolean? = null,
)

@Serializable
data class Evidence(val criterion: JsonElement? = null, val passed: Boolean = false, val observed: String = "", val at: Long = 0)

@Serializable
data class VerificationDetail(
    val status: String,
    val evidence: List<Evidence> = emptyList(),
    val userDefined: Boolean = false,
    val checkedAt: Long = 0,
    val note: String? = null,
)

@Serializable
data class TaskDetail(
    val id: String,
    val goal: String,
    val status: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val note: String? = null,
    val error: String? = null,
    val result: String? = null,
    val plan: List<String> = emptyList(),
    val steps: List<Step> = emptyList(),
    val verificationDetail: VerificationDetail? = null,
    val pendingApprovalId: String? = null,
)

@Serializable
data class Approval(
    val id: String,
    val taskId: String,
    val tool: String,
    val summary: String,
    val risk: String,
    val resources: List<String> = emptyList(),
    val irreversible: Boolean = false,
    /** Longer text to read before deciding (the instructions of a new skill). */
    val detail: String? = null,
    /** "question": the agent asks something in words ([summary] is the question, [resources] are suggested answers). */
    val kind: String? = null,
    val answer: String? = null,
    val status: String,
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val decidedAt: Long? = null,
)

@Serializable
data class ProviderHealth(val provider: String, val blockedUntil: Long? = null)

@Serializable
data class ServerStatus(
    val name: String = "Aeon",
    val device: String = "",
    val tasks: Map<String, Int> = emptyMap(),
    val secretsBackend: String = "",
    val providers: List<ProviderHealth> = emptyList(),
    val setupProblem: String? = null,
    val deviceName: String = "",
    /** This device may change settings, keys, connections and devices. */
    val admin: Boolean = false,
    val host: HostInfo? = null,
    /** Addresses another device can type to reach the server. Empty when it listens on its own machine only. */
    val addresses: List<String> = emptyList(),
    /** The server can receive sealed values (it has a sealing key). */
    val sealing: Boolean = false,
)

@Serializable
data class HostInfo(val platform: String = "", val arch: String = "")

@Serializable
data class AeonConfig(
    val providers: List<String> = listOf("exaone"),
    val dataConsent: List<String> = emptyList(),
    val fallbackAllowed: Boolean = false,
    val ollamaModel: String? = null,
    val ollamaBaseUrl: String? = null,
    val openaiCompatBaseUrl: String? = null,
    val openaiCompatModel: String? = null,
    /** Kept in the model so saving other settings never drops the servers the owner added. */
    val mcpServers: List<McpServer>? = null,
    val endpoints: List<Endpoint>? = null,
    val exaoneReasoning: String? = null,
    val profile: Profile? = null,
    /** false = the owner declined internet lookups for lists and logos. Absent means allowed. */
    val onlineLookups: Boolean? = null,
    /** Article images in the feed. On unless false: each costs about a megabyte of data when the feed refreshes. */
    val feedImages: Boolean? = null,
)

@Serializable
data class Endpoint(val id: String, val name: String? = null, val baseUrl: String, val model: String, val keyName: String? = null, val reasoningEffort: String? = null)

/** How the agent addresses the owner. Wording only. */
@Serializable
data class Profile(
    val userName: String? = null,
    val agentName: String? = null,
    val tone: String? = null,
    val detail: String? = null,
    val language: String? = null,
    val about: String? = null,
    val interests: List<String>? = null,
)

@Serializable
data class ProviderRow(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val keyName: String? = null,
    val hasKey: Boolean = false,
    val local: Boolean = false,
    val doc: String? = null,
    val efforts: List<String> = emptyList(),
    val modelCount: Int = 0,
    val inUse: Boolean = false,
    val consented: Boolean = false,
    /** API path of the provider's logo; the logo is fetched once with [dev.aeon.ui.state.AppModel.requestIcon]. */
    val icon: String? = null,
)

@Serializable
data class CurrentModel(val provider: String, val providerName: String = "", val model: String = "", val reasoningEffort: String? = null)

@Serializable
data class CatalogModel(val id: String, val name: String = "", val reasoning: Boolean = false, val attachment: Boolean = false, val context: Long? = null, val released: String? = null)

@Serializable
data class McpServer(val name: String, val title: String? = null, val url: String, val websiteUrl: String? = null, val requires: List<String>? = null)

/** One row of 둘러보기: a built-in tool group, a remote MCP server ("integration"), a packaged MCP server ("mcp") or a skill. */
@Serializable
data class ConnectionItem(
    val id: String,
    val kind: String,
    /** connected, added (saved, not executed yet) or available. */
    val state: String,
    val title: String,
    val description: String = "",
    val publisher: String? = null,
    val version: String? = null,
    val websiteUrl: String? = null,
    val remoteUrl: String? = null,
    val runtime: String? = null,
    val requires: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val icon: String? = null,
)

/**
 * kind "image": base64 [data] of [mime]. kind "path": one SVG path [d] on a square grid of [size] (24 when absent) in
 * brand [color], or in the text colour when [color] is "currentColor"; [rule] "evenodd" fills with the even-odd rule.
 */
@Serializable
data class IconPayload(val kind: String, val mime: String? = null, val data: String? = null, val d: String? = null, val color: String? = null, val size: Int? = null, val rule: String? = null)

@Serializable
data class CatalogCollection(val id: String, val title: Map<String, String> = emptyMap(), val queries: List<String> = emptyList())

@Serializable
data class DeviceInfo(val id: String, val name: String, val createdAt: Long = 0, val lastSeenAt: Long = 0, val revoked: Boolean = false, val admin: Boolean = true, val current: Boolean = false)

@Serializable
data class UsageRecord(
    val at: Long,
    val provider: String,
    val model: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val ok: Boolean = true,
)

// Response envelopes
@Serializable data class TasksResponse(val tasks: List<TaskSummary>)
@Serializable data class TaskResponse(val task: TaskSummary)
@Serializable data class TaskDetailResponse(val task: TaskDetail)
@Serializable data class ApprovalsResponse(val approvals: List<Approval>)
@Serializable data class ApprovalResponse(val approval: Approval)
@Serializable data class ConfigResponse(val config: AeonConfig)
@Serializable data class SecretsResponse(val backend: String = "", val names: List<String> = emptyList())
@Serializable data class DevicesResponse(val devices: List<DeviceInfo>)
@Serializable data class UsageResponse(val usage: List<UsageRecord>)
@Serializable data class ConnectionsResponse(val connections: List<ConnectionItem> = emptyList(), val skillsDir: String = "", val canEdit: Boolean = false)
@Serializable data class CatalogResponse(val connections: List<ConnectionItem> = emptyList(), val nextCursor: String? = null, val disabled: Boolean = false)
@Serializable data class CollectionsResponse(val collections: List<CatalogCollection> = emptyList())
@Serializable data class IconResponse(val icon: IconPayload)

/** A skill: a folder with a SKILL.md. [folder] is the name on disk. */
@Serializable
data class SkillItem(
    val folder: String,
    val name: String,
    val description: String = "",
    val body: String = "",
    val license: String? = null,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    /** What is wrong with the SKILL.md (the Agent Skills format). Empty when it is fine. */
    val problems: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val size: Long = 0,
    val updatedAt: Double = 0.0,
    /** "user" (the owner's skills) or "project" (this workspace's `.agents/skills`). */
    val scope: String = "user",
    /** "tracked": exactly what the owner approved. "modified": changed since. "untracked": never approved. */
    val status: String = "tracked",
    val enabled: Boolean = false,
    /** Offered to the model right now. */
    val active: Boolean = false,
    val inactiveBecause: String = "",
    val shadowedBy: String? = null,
    val source: SkillSourceInfo? = null,
    /** The content hash. Approving a skill sends back the hash that was shown. */
    val hash: String = "",
    val version: String? = null,
    val scripts: Boolean = false,
    val warnings: List<String> = emptyList(),
    /** Scripts run in Docker without a question each time (only for an approved skill whose scripts passed the check). */
    val autoRun: Boolean = false,
    val safety: SkillSafety? = null,
)

/** The install-time check of a skill's scripts: a fixed list of rules, not a model's opinion. */
@Serializable data class SkillFinding(val file: String = "", val line: Int = 0, val severity: String = "note", val what: String = "")
@Serializable data class SkillSafety(val level: String = "clean", val findings: List<SkillFinding> = emptyList(), val scanned: Int = 0)

/** Where a skill came from: `agent`, `legacy`, `local` (path) or `github` (owner, repo, ref, path, commit). */
@Serializable
data class SkillSourceInfo(val type: String = "", val owner: String? = null, val repo: String? = null, val ref: String? = null, val path: String? = null, val commit: String? = null)

@Serializable data class SkillFileInfo(val path: String, val size: Long = 0)
@Serializable data class SkillScriptText(val path: String, val text: String = "", val cut: Boolean = false)
@Serializable data class SkillExisting(val scope: String, val status: String = "", val source: SkillSourceInfo? = null)

/** One skill found in a source, with everything the owner should read before it is installed. */
@Serializable
data class SkillCandidate(
    val name: String,
    val dir: String = "",
    val description: String = "",
    val problems: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val files: List<SkillFileInfo> = emptyList(),
    val scripts: Boolean = false,
    val ignored: List<String> = emptyList(),
    val hash: String = "",
    val bytes: Long = 0,
    val skillMd: String = "",
    val scriptTexts: List<SkillScriptText> = emptyList(),
    val existing: List<SkillExisting> = emptyList(),
    val safety: SkillSafety = SkillSafety(),
)

@Serializable
data class SkillPreview(val id: String, val source: String = "", val commit: String? = null, val skipped: List<String> = emptyList(), val candidates: List<SkillCandidate> = emptyList())

@Serializable data class SkillPreviewResponse(val preview: SkillPreview)
@Serializable data class SkillUpdateStatus(val scope: String, val name: String, val status: String, val message: String? = null, val remoteCommit: String? = null)
@Serializable data class SkillUpdatesResponse(val updates: List<SkillUpdateStatus> = emptyList())
@Serializable data class SkillFileDiff(val added: List<String> = emptyList(), val removed: List<String> = emptyList(), val changed: List<String> = emptyList())
@Serializable data class SkillInstallHint(val name: String, val rename: String? = null)
@Serializable data class SkillUpdatePreview(val preview: SkillPreview, val install: SkillInstallHint, val diff: SkillFileDiff = SkillFileDiff())
@Serializable data class SkillFileResponse(val path: String = "", val text: String? = null, val binary: Boolean = false, val size: Long = 0)

/** Where a post's facts come from. The address was filled in by the server from the articles it read, never typed by a model. */
@Serializable data class PostSource(val title: String = "", val outlet: String = "", val url: String = "", val publishedAt: Double = 0.0)

/** One post of the feed. [body] is empty and [written] false when it is only the outlet's headline. */
@Serializable
data class FeedPost(
    val id: String,
    val kicker: String = "",
    val title: String,
    val body: String = "",
    val category: String = "",
    val sources: List<PostSource> = emptyList(),
    val reason: String = "",
    val written: Boolean = true,
    val image: Boolean = false,
    val createdAt: Double = 0.0,
    val deadline: Double? = null,
    val actionable: Boolean = false,
    val seenAt: Double? = null,
    val liked: Boolean = false,
)

@Serializable data class FeedSettings(val brief: String = "", val auto: Boolean = true, val intervalMinutes: Int = 360, val dailyRuns: Int = 4, val dailyPosts: Int = 8)
@Serializable data class FeedToday(val runs: Int = 0, val posts: Int = 0, val lookups: Int = 0)
@Serializable data class FeedCaps(val runs: Int = 4, val posts: Int = 8)
@Serializable data class FeedRejected(val title: String = "", val why: String = "")

/** What one run of the pipeline did: [status] made, nothing or skipped; [mode] written by a model or plain headlines. */
@Serializable
data class RunReport(
    val at: Double = 0.0,
    val status: String = "",
    val reason: String? = null,
    val message: String? = null,
    val made: Int = 0,
    val candidates: Int = 0,
    val lookups: Int = 0,
    val mode: String = "written",
    val rejected: List<FeedRejected> = emptyList(),
)

@Serializable data class FeedStatus(val today: FeedToday = FeedToday(), val caps: FeedCaps = FeedCaps(), val lastRunAt: Double = 0.0, val lastRun: RunReport? = null, val nextRunAt: Double? = null)
@Serializable data class FeedQuery(val interest: String = "", val query: String = "")
@Serializable data class FeedTaste(val text: String = "", val updatedAt: Double = 0.0, val source: String = "auto")

/** Everything the feed screen needs, in the order the server wants the posts shown. [report] is only there after a manual run. */
@Serializable
data class FeedResponse(
    val posts: List<FeedPost> = emptyList(),
    val settings: FeedSettings = FeedSettings(),
    val status: FeedStatus = FeedStatus(),
    val interests: List<String> = emptyList(),
    val queries: List<FeedQuery> = emptyList(),
    val taste: FeedTaste? = null,
    val online: Boolean = true,
    val report: RunReport? = null,
)

/** Who made a memory: the owner typed it, or the agent proposed it in a task and the owner approved those exact words. */
@Serializable data class MemorySource(val type: String = "owner", val taskId: String? = null)
/** An earlier wording of a memory. It is deleted together with the memory. */
@Serializable data class MemoryHistory(val text: String = "", val at: Double = 0.0)
@Serializable
data class MemoryItem(
    val id: String,
    val text: String,
    val kind: String = "fact",
    val source: MemorySource = MemorySource(),
    val createdAt: Double = 0.0,
    val lastUsedAt: Double? = null,
    val uses: Int = 0,
    /** After this time the memory is not used any more (it stays in the list as past for 30 days). */
    val expiresAt: Double? = null,
    val history: List<MemoryHistory> = emptyList(),
)

/** What a finished task suggests keeping. It waits for the owner; [replaces] is the kept memory it would correct. */
@Serializable data class MemoryProposal(val id: String, val text: String, val kind: String = "fact", val taskId: String = "", val createdAt: Double = 0.0, val replaces: String? = null)

/** The memory page: what is in use, what ran out, what is suggested. [item] is the one the last change was about. */
@Serializable
data class MemoryResponse(
    val items: List<MemoryItem> = emptyList(),
    val past: List<MemoryItem> = emptyList(),
    val proposals: List<MemoryProposal> = emptyList(),
    val max: Int = 200,
    val textMax: Int = 300,
    val item: MemoryItem? = null,
)
@Serializable data class MemoryItemResponse(val item: MemoryItem)

@Serializable data class SkillsResponse(val skills: List<SkillItem> = emptyList(), val dir: String = "", val projectDir: String? = null, val canEdit: Boolean = false)
@Serializable data class SkillResponse(val skill: SkillItem)
@Serializable data class HelloResponse(val name: String = "Aeon", val api: Int = 1, val pairingRequired: Boolean = true)
@Serializable data class PairResponse(val deviceId: String, val serverProof: String, val serverName: String = "Aeon", val admin: Boolean = true, val serverKey: String? = null, val serverKeyProof: String? = null)
@Serializable data class ProvidersResponse(val providers: List<ProviderRow> = emptyList(), val current: CurrentModel? = null, val catalogError: String? = null, val online: Boolean = true)
@Serializable data class ProviderModelsResponse(val provider: String = "", val models: List<CatalogModel> = emptyList())
@Serializable data class SelectModelResponse(val config: AeonConfig, val current: CurrentModel? = null, val setupProblem: String? = null)
@Serializable data class UploadResponse(val path: String, val size: Long = 0)
@Serializable data class PairingCodeResponse(val expiresAt: Long = 0, val admin: Boolean = true, val addresses: List<String> = emptyList(), val code: String? = null, val sealed: SealedReply? = null)
@Serializable data class DeviceResponse(val device: DeviceInfo)

@Serializable
data class PairRequest(val deviceName: String, val publicKey: String, val clientNonce: String, val proof: String)
