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

/** kind "image": base64 [data] of [mime]. kind "path": one SVG path [d] on a 24x24 grid in brand [color]. */
@Serializable
data class IconPayload(val kind: String, val mime: String? = null, val data: String? = null, val d: String? = null, val color: String? = null)

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
