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
)

@Serializable
data class AeonConfig(
    val providers: List<String> = listOf("exaone"),
    val dataConsent: List<String> = emptyList(),
    val fallbackAllowed: Boolean = false,
    val ollamaModel: String? = null,
    val ollamaBaseUrl: String? = null,
    val openaiCompatBaseUrl: String? = null,
    val openaiCompatModel: String? = null,
)

@Serializable
data class DeviceInfo(val id: String, val name: String, val createdAt: Long = 0, val lastSeenAt: Long = 0, val revoked: Boolean = false)

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
@Serializable data class HelloResponse(val name: String = "Aeon", val api: Int = 1, val pairingRequired: Boolean = true)
@Serializable data class PairResponse(val deviceId: String, val serverProof: String, val serverName: String = "Aeon")

@Serializable
data class PairRequest(val deviceName: String, val publicKey: String, val clientNonce: String, val proof: String)
