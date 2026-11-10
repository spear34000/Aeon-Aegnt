package dev.aeon.ui.api

import dev.aeon.ui.platform.DeviceCrypto
import dev.aeon.ui.platform.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AeonException(val status: Int, message: String) : Exception(message)

/**
 * Client for docs/API.md. Every request after pairing is signed over the exact bytes sent:
 * the path+query string is built here (never re-encoded by the HTTP layer) and the body is serialised
 * to bytes first, hashed, signed, and then sent as those same bytes.
 */
class AeonClient(
    baseUrl: String,
    private val crypto: DeviceCrypto,
    private val http: HttpClient = HttpClient(),
    private val clock: () -> Long = ::nowMillis,
) {
    var baseUrl: String = baseUrl.trimEnd('/')
        set(value) {
            field = value.trimEnd('/')
        }
    var deviceId: String = ""

    suspend fun hello(): HelloResponse = ApiJson.decodeFromString(plain(HttpMethod.Get, "/v1/hello", null))

    /** Pairs this device. Verifies the server's proof that it knew the code before trusting the result. */
    suspend fun pair(code: String, deviceName: String): PairResponse {
        val normalized = Signing.normalizeCode(code)
        val publicKey = crypto.publicKeyB64()
        val clientNonce = crypto.randomBytes(16).toBase64()
        val proof = crypto.hmacSha256(normalized.encodeToByteArray(), Signing.clientProofMessage(deviceName, publicKey, clientNonce).encodeToByteArray()).toBase64()
        val body = ApiJson.encodeToString(PairRequest.serializer(), PairRequest(deviceName, publicKey, clientNonce, proof))
        val res: PairResponse = ApiJson.decodeFromString(plain(HttpMethod.Post, "/v1/pair", body))
        val expected = crypto.hmacSha256(normalized.encodeToByteArray(), Signing.serverProofMessage(res.deviceId, clientNonce, publicKey).encodeToByteArray()).toBase64()
        if (expected != res.serverProof) throw AeonException(0, "The server could not prove it knows the pairing code. Do not trust this server.")
        deviceId = res.deviceId
        return res
    }

    suspend fun status(): ServerStatus = ApiJson.decodeFromString(signed(HttpMethod.Get, "/v1/status"))

    suspend fun tasks(): List<TaskSummary> = ApiJson.decodeFromString<TasksResponse>(signed(HttpMethod.Get, "/v1/tasks")).tasks

    suspend fun task(id: String): TaskDetail = ApiJson.decodeFromString<TaskDetailResponse>(signed(HttpMethod.Get, "/v1/tasks/$id")).task

    suspend fun createTask(goal: String, criteria: List<JsonObject> = emptyList(), runAfter: Long? = null): TaskSummary {
        val body = buildJsonObject {
            put("goal", goal)
            if (criteria.isNotEmpty()) put("criteria", buildJsonArray { criteria.forEach { add(it) } })
            if (runAfter != null) put("runAfter", runAfter)
        }
        return ApiJson.decodeFromString<TaskResponse>(signed(HttpMethod.Post, "/v1/tasks", body.toString())).task
    }

    suspend fun cancel(id: String): TaskSummary = ApiJson.decodeFromString<TaskResponse>(signed(HttpMethod.Post, "/v1/tasks/$id/cancel")).task
    suspend fun reverify(id: String): TaskSummary = ApiJson.decodeFromString<TaskResponse>(signed(HttpMethod.Post, "/v1/tasks/$id/verify")).task
    suspend fun confirm(id: String): TaskSummary = ApiJson.decodeFromString<TaskResponse>(signed(HttpMethod.Post, "/v1/tasks/$id/confirm")).task

    suspend fun approvals(status: String? = "pending"): List<Approval> {
        val q = if (status != null) "?status=$status" else ""
        return ApiJson.decodeFromString<ApprovalsResponse>(signed(HttpMethod.Get, "/v1/approvals$q")).approvals
    }

    suspend fun decide(approvalId: String, approve: Boolean): Approval {
        val body = buildJsonObject { put("decision", if (approve) "approved" else "denied") }
        return ApiJson.decodeFromString<ApprovalResponse>(signed(HttpMethod.Post, "/v1/approvals/$approvalId/decide", body.toString())).approval
    }

    suspend fun usage(): List<UsageRecord> = ApiJson.decodeFromString<UsageResponse>(signed(HttpMethod.Get, "/v1/usage")).usage

    suspend fun config(): AeonConfig = ApiJson.decodeFromString<ConfigResponse>(signed(HttpMethod.Get, "/v1/config")).config

    suspend fun saveConfig(config: AeonConfig): AeonConfig =
        ApiJson.decodeFromString<ConfigResponse>(signed(HttpMethod.Put, "/v1/config", ApiJson.encodeToString(AeonConfig.serializer(), config))).config

    suspend fun secretNames(): SecretsResponse = ApiJson.decodeFromString(signed(HttpMethod.Get, "/v1/secrets"))

    /** Write-only. The server accepts this only from the machine it runs on. */
    suspend fun setSecret(name: String, value: String) {
        signed(HttpMethod.Put, "/v1/secrets/$name", buildJsonObject { put("value", JsonPrimitive(value)) }.toString())
    }

    suspend fun deleteSecret(name: String) {
        signed(HttpMethod.Delete, "/v1/secrets/$name")
    }

    suspend fun devices(): List<DeviceInfo> = ApiJson.decodeFromString<DevicesResponse>(signed(HttpMethod.Get, "/v1/devices")).devices

    suspend fun revokeDevice(id: String) {
        signed(HttpMethod.Delete, "/v1/devices/$id")
    }

    private suspend fun plain(method: HttpMethod, pathAndQuery: String, body: String?): String =
        send(method, pathAndQuery, body, emptyMap())

    private suspend fun signed(method: HttpMethod, pathAndQuery: String, body: String? = null): String {
        check(deviceId.isNotEmpty()) { "device is not paired" }
        val bytes = body?.encodeToByteArray() ?: ByteArray(0)
        val timestamp = clock().toString()
        val nonce = crypto.randomBytes(12).toHex()
        val bodyHash = crypto.sha256(bytes).toHex()
        val signature = crypto.sign(Signing.canonicalRequest(method.value, pathAndQuery, timestamp, nonce, bodyHash).encodeToByteArray())
        return send(
            method,
            pathAndQuery,
            body,
            mapOf("x-aeon-device" to deviceId, "x-aeon-timestamp" to timestamp, "x-aeon-nonce" to nonce, "x-aeon-signature" to signature),
        )
    }

    private suspend fun send(method: HttpMethod, pathAndQuery: String, body: String?, headers: Map<String, String>): String {
        val response = http.request(baseUrl + pathAndQuery) {
            this.method = method
            headers.forEach { (k, v) -> header(k, v) }
            if (body != null) {
                header("content-type", "application/json")
                setBody(body.encodeToByteArray()) // exactly the bytes that were hashed and signed
            }
        }
        val text = response.bodyAsText()
        val code = response.status.value
        if (code >= 400) {
            val message = runCatching { (ApiJson.parseToJsonElement(text) as? JsonObject)?.get("error")?.let { (it as? JsonPrimitive)?.content } }.getOrNull()
            throw AeonException(code, message ?: "HTTP $code")
        }
        return text
    }
}

/** "file_exists:out.txt" / "file_contains:out.txt:hello" -> API criterion object, or null if malformed. */
fun parseCheck(spec: String): JsonObject? {
    val parts = spec.trim().split(":", limit = 3)
    return when {
        parts.size == 2 && parts[0] == "file_exists" && parts[1].isNotBlank() -> buildJsonObject { put("kind", "file_exists"); put("path", parts[1].trim()) }
        parts.size == 3 && parts[0] == "file_contains" && parts[1].isNotBlank() -> buildJsonObject { put("kind", "file_contains"); put("path", parts[1].trim()); put("text", parts[2]) }
        else -> null
    }
}

@Suppress("unused")
private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
