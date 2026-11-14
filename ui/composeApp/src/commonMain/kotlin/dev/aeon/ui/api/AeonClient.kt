package dev.aeon.ui.api

import dev.aeon.ui.platform.DeviceCrypto
import dev.aeon.ui.platform.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    /** The server's sealing key (SPKI, base64), pinned while pairing. Empty for a pairing made before sealing existed. */
    var serverKey: String = ""

    suspend fun hello(): HelloResponse = ApiJson.decodeFromString(plain(HttpMethod.Get, "/v1/hello", null))

    /** The name of the Aeon server answering at [url], or null if nothing answers within a few seconds. Sends nothing secret. */
    suspend fun probe(url: String): String? = kotlinx.coroutines.withTimeoutOrNull(3_000) {
        runCatching {
            val r = http.request(url.trimEnd('/') + "/v1/hello") { this.method = HttpMethod.Get }
            if (r.status.value == 200) ApiJson.decodeFromString<HelloResponse>(r.bodyAsText()).name else null
        }.getOrNull()
    }

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
        // Pin the sealing key only if the same pairing code vouches for it.
        val key = res.serverKey
        val keyOk = key != null && res.serverKeyProof == crypto.hmacSha256(normalized.encodeToByteArray(), Sealing.serverKeyProofMessage(key, clientNonce, res.deviceId).encodeToByteArray()).toBase64()
        // Fail closed: away from this machine the sealing key is required, and it must be vouched for by the code.
        if (!keyOk && !isLoopback(baseUrl)) throw AeonException(0, "서버가 암호화 키를 증명하지 못했습니다. 이 서버를 믿지 마세요.")
        serverKey = if (keyOk) key!! else ""
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

    /** [answer] is the owner's words when the approval is a question from the agent. */
    suspend fun decide(approvalId: String, approve: Boolean, answer: String? = null): Approval {
        val body = buildJsonObject {
            put("decision", if (approve) "approved" else "denied")
            if (answer != null) put("answer", answer)
        }
        return ApiJson.decodeFromString<ApprovalResponse>(signed(HttpMethod.Post, "/v1/approvals/$approvalId/decide", body.toString())).approval
    }

    suspend fun usage(): List<UsageRecord> = ApiJson.decodeFromString<UsageResponse>(signed(HttpMethod.Get, "/v1/usage")).usage

    suspend fun config(): AeonConfig = ApiJson.decodeFromString<ConfigResponse>(signed(HttpMethod.Get, "/v1/config")).config

    suspend fun saveConfig(config: AeonConfig): AeonConfig =
        ApiJson.decodeFromString<ConfigResponse>(signed(HttpMethod.Put, "/v1/config", ApiJson.encodeToString(AeonConfig.serializer(), config))).config

    suspend fun secretNames(): SecretsResponse = ApiJson.decodeFromString(signed(HttpMethod.Get, "/v1/secrets"))

    /**
     * Write-only. The value is sealed for the server's key, so it can be typed on any paired device even though the
     * connection is plain HTTP. A device paired before sealing existed can only do this on the server's own machine.
     */
    suspend fun setSecret(name: String, value: String) {
        if (serverKey.isEmpty() && !isLoopback(baseUrl)) throw AeonException(0, "이 기기는 암호화 키 없이 연결되어 있습니다. 연결을 해제한 뒤 다시 연결하세요.")
        val body = if (serverKey.isNotEmpty()) {
            val box = Sealing.seal(crypto, serverKey, value.encodeToByteArray(), "secret|$deviceId|$name")
            buildJsonObject { put("sealed", ApiJson.encodeToJsonElement(SealedBox.serializer(), box)) }
        } else {
            buildJsonObject { put("value", JsonPrimitive(value)) }
        }
        signed(HttpMethod.Put, "/v1/secrets/$name", body.toString())
    }

    suspend fun providers(): ProvidersResponse = ApiJson.decodeFromString(signed(HttpMethod.Get, "/v1/providers"))

    suspend fun providerModels(id: String): List<CatalogModel> =
        ApiJson.decodeFromString<ProviderModelsResponse>(signed(HttpMethod.Get, "/v1/providers/$id/models")).models

    /** Makes [model] of [provider] the model new tasks use. [consent] is the owner's yes to sending prompts there. */
    suspend fun selectModel(provider: String, model: String, reasoningEffort: String?, consent: Boolean, customName: String? = null, customBaseUrl: String? = null, needsKey: Boolean = true): SelectModelResponse {
        val body = buildJsonObject {
            put("provider", provider)
            put("model", model)
            if (reasoningEffort != null) put("reasoningEffort", reasoningEffort)
            if (consent) put("consent", true)
            if (customBaseUrl != null) put("custom", buildJsonObject {
                put("baseUrl", customBaseUrl)
                if (customName != null) put("name", customName)
                if (!needsKey) put("needsKey", false)
            })
        }
        return ApiJson.decodeFromString(signed(HttpMethod.Post, "/v1/model", body.toString()))
    }

    /** Stores a file under `uploads/` in the workspace and returns its path there. */
    suspend fun upload(name: String, bytes: ByteArray): UploadResponse =
        ApiJson.decodeFromString(signedBytes(HttpMethod.Post, "/v1/files?name=${encodeQueryValue(name)}", bytes, "application/octet-stream"))

    /** Asks for a one-time pairing code for another device. Over the network it comes back sealed for this device. */
    suspend fun newPairingCode(admin: Boolean): Pair<String, PairingCodeResponse> {
        if (serverKey.isEmpty()) {
            if (!isLoopback(baseUrl)) throw AeonException(0, "이 기기는 암호화 키 없이 연결되어 있습니다. 연결을 해제한 뒤 다시 연결하세요.")
            val r: PairingCodeResponse = ApiJson.decodeFromString(signed(HttpMethod.Post, "/v1/pairing-codes", buildJsonObject { put("admin", admin) }.toString()))
            return (r.code ?: throw AeonException(0, "이 기기를 다시 연결해야 다른 기기를 추가할 수 있습니다.")) to r
        }
        val ex = Sealing.exchange(crypto, serverKey)
        val r: PairingCodeResponse = ApiJson.decodeFromString(signed(HttpMethod.Post, "/v1/pairing-codes", buildJsonObject { put("admin", admin); put("epk", ex.epk) }.toString()))
        val sealed = r.sealed ?: throw AeonException(0, "서버가 코드를 암호화하지 않았습니다. 사용하지 않습니다.")
        return ex.open(sealed, "pairing-code|$deviceId").decodeToString() to r
    }

    suspend fun updateDevice(id: String, name: String? = null, admin: Boolean? = null): DeviceInfo {
        val body = buildJsonObject {
            if (name != null) put("name", name)
            if (admin != null) put("admin", admin)
        }
        return ApiJson.decodeFromString<DeviceResponse>(signed(HttpMethod.Patch, "/v1/devices/$id", body.toString())).device
    }

    suspend fun deleteSecret(name: String) {
        signed(HttpMethod.Delete, "/v1/secrets/$name")
    }

    suspend fun devices(): List<DeviceInfo> = ApiJson.decodeFromString<DevicesResponse>(signed(HttpMethod.Get, "/v1/devices")).devices

    suspend fun revokeDevice(id: String) {
        signed(HttpMethod.Delete, "/v1/devices/$id")
    }

    /** Built-in tools, added MCP servers and skills, as the server sees them right now. */
    suspend fun connections(): ConnectionsResponse = ApiJson.decodeFromString(signed(HttpMethod.Get, "/v1/connections"))

    suspend fun collections(): List<CatalogCollection> = ApiJson.decodeFromString<CollectionsResponse>(signed(HttpMethod.Get, "/v1/catalog/collections")).collections

    /** Live registry search made by the PC. Pass either free text or a collection id. */
    suspend fun catalog(query: String = "", collection: String? = null): CatalogResponse {
        val q = if (collection != null) "collection=${encodeQueryValue(collection)}" else "q=${encodeQueryValue(query)}"
        return ApiJson.decodeFromString(signed(HttpMethod.Get, "/v1/catalog?$q"))
    }

    /** The stored feed of the profile's interests, and whether it should be refreshed. */
    suspend fun feed(): FeedResponse = ApiJson.decodeFromString(signed(HttpMethod.Get, "/v1/feed"))

    /** One run of the pipeline now. A run that may not start (limit, gap, internet off) comes back as a report with status "skipped", not as an error. */
    suspend fun generateFeed(): FeedResponse = ApiJson.decodeFromString(signed(HttpMethod.Post, "/v1/feed/generate", "{}"))

    suspend fun saveFeedSettings(brief: String? = null, auto: Boolean? = null, intervalMinutes: Int? = null, dailyRuns: Int? = null, dailyPosts: Int? = null): FeedResponse {
        val json = buildJsonObject {
            if (brief != null) put("brief", brief)
            if (auto != null) put("auto", auto)
            if (intervalMinutes != null) put("intervalMinutes", intervalMinutes)
            if (dailyRuns != null) put("dailyRuns", dailyRuns)
            if (dailyPosts != null) put("dailyPosts", dailyPosts)
        }
        return ApiJson.decodeFromString(signed(HttpMethod.Put, "/v1/feed/settings", json.toString()))
    }

    suspend fun markPostSeen(id: String): FeedResponse = ApiJson.decodeFromString(signed(HttpMethod.Post, "/v1/feed/posts/$id/seen", "{}"))

    /** [reaction] is "like", "unlike" or "dismiss" (which removes the post). */
    suspend fun reactToPost(id: String, reaction: String): FeedResponse {
        val json = buildJsonObject { put("reaction", reaction) }
        return ApiJson.decodeFromString(signed(HttpMethod.Post, "/v1/feed/posts/$id/react", json.toString()))
    }

    /** [to] is "top" or "bottom": for today only. */
    suspend fun movePost(id: String, to: String): FeedResponse {
        val json = buildJsonObject { put("to", to) }
        return ApiJson.decodeFromString(signed(HttpMethod.Post, "/v1/feed/posts/$id/move", json.toString()))
    }

    suspend fun saveFeedTaste(text: String): FeedResponse {
        val json = buildJsonObject { put("text", text) }
        return ApiJson.decodeFromString(signed(HttpMethod.Put, "/v1/feed/taste", json.toString()))
    }

    suspend fun clearFeedTaste(): FeedResponse = ApiJson.decodeFromString(signed(HttpMethod.Delete, "/v1/feed/taste"))

    /** The picture the server kept for a feed item: encoded JPEG, PNG or WebP bytes. */
    suspend fun feedImage(id: String): ByteArray = signedRaw(HttpMethod.Get, "/v1/feed/image/$id", null, "application/json")

    /** What the owner wants remembered across tasks, newest first. */
    suspend fun memory(): MemoryResponse = ApiJson.decodeFromString(signed(HttpMethod.Get, "/v1/memory"))

    /** [days] is how long to remember it, counted from today; null means without an end. */
    suspend fun addMemory(text: String, kind: String, days: Int? = null): MemoryItem {
        val json = buildJsonObject {
            put("text", text)
            put("kind", kind)
            if (days != null) put("expiresInDays", days)
        }
        return ApiJson.decodeFromString<MemoryItemResponse>(signed(HttpMethod.Post, "/v1/memory", json.toString())).item
    }

    /** Changes a memory. [periodChanged] says whether [days] (null: no end) is to be applied; otherwise the period is left as it is. */
    suspend fun editMemory(id: String, text: String, periodChanged: Boolean, days: Int?): MemoryResponse {
        val json = buildJsonObject {
            put("text", text)
            if (periodChanged) { if (days == null) put("expires", JsonNull) else put("expiresInDays", days) }
        }
        return ApiJson.decodeFromString(signed(HttpMethod.Patch, "/v1/memory/$id", json.toString()))
    }

    suspend fun acceptMemoryProposal(id: String, text: String, periodChanged: Boolean, days: Int?): MemoryResponse {
        val json = buildJsonObject {
            put("text", text)
            if (periodChanged && days != null) put("expiresInDays", days)
        }
        return ApiJson.decodeFromString(signed(HttpMethod.Post, "/v1/memory/proposals/$id/accept", json.toString()))
    }

    suspend fun ignoreMemoryProposal(id: String): MemoryResponse = ApiJson.decodeFromString(signed(HttpMethod.Delete, "/v1/memory/proposals/$id"))

    suspend fun deleteMemory(id: String) {
        signed(HttpMethod.Delete, "/v1/memory/$id")
    }

    suspend fun skills(): SkillsResponse = ApiJson.decodeFromString(signed(HttpMethod.Get, "/v1/skills"))

    suspend fun skill(folder: String, scope: String? = null): SkillItem =
        ApiJson.decodeFromString<SkillResponse>(signed(HttpMethod.Get, "/v1/skills/$folder" + if (scope != null) "?scope=$scope" else "")).skill

    /** One file of a skill as text, for reading before approving it. [binary] files come back without text. */
    suspend fun skillFile(folder: String, scope: String, path: String): SkillFileResponse =
        ApiJson.decodeFromString(signed(HttpMethod.Get, "/v1/skills/$folder/file?scope=$scope&path=$path"))

    /** Reads a source (a GitHub repository or a folder on the machine that runs Aeon). Installs nothing. */
    suspend fun inspectSkillSource(source: String): SkillPreview {
        val json = buildJsonObject { put("source", source) }
        return ApiJson.decodeFromString<SkillPreviewResponse>(signed(HttpMethod.Post, "/v1/skill-sources/inspect", json.toString())).preview
    }

    suspend fun installSkill(previewId: String, name: String, scope: String, rename: String?, replace: Boolean, autoRun: Boolean = true): SkillItem {
        val json = buildJsonObject {
            put("previewId", previewId)
            put("name", name)
            put("scope", scope)
            if (rename != null) put("rename", rename)
            if (replace) put("replace", true)
            if (!autoRun) put("autoRun", false)
        }
        return ApiJson.decodeFromString<SkillResponse>(signed(HttpMethod.Post, "/v1/skill-sources/install", json.toString())).skill
    }

    /** Lets the scripts of an approved skill run in Docker without a question each time (only after the install-time check passed), or asks again. */
    suspend fun setSkillAutoRun(folder: String, scope: String, enabled: Boolean): SkillItem {
        val json = buildJsonObject {
            put("scope", scope)
            put("enabled", enabled)
        }
        return ApiJson.decodeFromString<SkillResponse>(signed(HttpMethod.Post, "/v1/skills/$folder/auto-run", json.toString())).skill
    }

    suspend fun setSkillEnabled(folder: String, scope: String, enabled: Boolean): SkillItem {
        val json = buildJsonObject {
            put("scope", scope)
            put("enabled", enabled)
        }
        return ApiJson.decodeFromString<SkillResponse>(signed(HttpMethod.Post, "/v1/skills/$folder/enable", json.toString())).skill
    }

    /** Approves a skill as it is. [hash] is the hash of what the owner was shown; the server refuses if the folder changed since. */
    suspend fun trustSkill(folder: String, scope: String, hash: String): SkillItem {
        val json = buildJsonObject {
            put("scope", scope)
            put("hash", hash)
        }
        return ApiJson.decodeFromString<SkillResponse>(signed(HttpMethod.Post, "/v1/skills/$folder/trust", json.toString())).skill
    }

    suspend fun skillUpdates(): List<SkillUpdateStatus> = ApiJson.decodeFromString<SkillUpdatesResponse>(signed(HttpMethod.Get, "/v1/skill-sources/updates")).updates

    suspend fun skillUpdatePreview(folder: String, scope: String): SkillUpdatePreview {
        val json = buildJsonObject { put("scope", scope) }
        return ApiJson.decodeFromString(signed(HttpMethod.Post, "/v1/skills/$folder/update-preview", json.toString()))
    }

    /** Corrects an existing skill (the agent makes new ones). The name stays the folder name, as the Agent Skills format requires. */
    suspend fun saveSkill(folder: String, description: String, body: String): SkillItem {
        val json = buildJsonObject {
            put("description", description)
            put("body", body)
        }
        return ApiJson.decodeFromString<SkillResponse>(signed(HttpMethod.Put, "/v1/skills/$folder", json.toString())).skill
    }

    suspend fun deleteSkill(folder: String, scope: String = "user") {
        signed(HttpMethod.Delete, "/v1/skills/$folder?scope=$scope")
    }

    /** [path] is the `icon` field of a connection. Returns null when the server found no icon. */
    suspend fun icon(path: String): IconPayload? = try {
        ApiJson.decodeFromString<IconResponse>(signed(HttpMethod.Get, path)).icon
    } catch (e: AeonException) {
        if (e.status == 404) null else throw e
    }

    private suspend fun plain(method: HttpMethod, pathAndQuery: String, body: String?): String =
        send(method, pathAndQuery, body?.encodeToByteArray(), emptyMap())

    private suspend fun signed(method: HttpMethod, pathAndQuery: String, body: String? = null): String =
        signedBytes(method, pathAndQuery, body?.encodeToByteArray(), "application/json")

    private suspend fun signedBytes(method: HttpMethod, pathAndQuery: String, body: ByteArray?, contentType: String): String =
        signedRaw(method, pathAndQuery, body, contentType).decodeToString()

    private suspend fun signedRaw(method: HttpMethod, pathAndQuery: String, body: ByteArray?, contentType: String): ByteArray {
        check(deviceId.isNotEmpty()) { "device is not paired" }
        val bytes = body ?: ByteArray(0)
        val timestamp = clock().toString()
        val nonce = crypto.randomBytes(12).toHex()
        val bodyHash = crypto.sha256(bytes).toHex()
        val signature = crypto.sign(Signing.canonicalRequest(method.value, pathAndQuery, timestamp, nonce, bodyHash).encodeToByteArray())
        return sendRaw(
            method,
            pathAndQuery,
            body,
            mapOf("x-aeon-device" to deviceId, "x-aeon-timestamp" to timestamp, "x-aeon-nonce" to nonce, "x-aeon-signature" to signature),
            contentType,
        )
    }

    private suspend fun send(method: HttpMethod, pathAndQuery: String, body: ByteArray?, headers: Map<String, String>, contentType: String = "application/json"): String =
        sendRaw(method, pathAndQuery, body, headers, contentType).decodeToString()

    private suspend fun sendRaw(method: HttpMethod, pathAndQuery: String, body: ByteArray?, headers: Map<String, String>, contentType: String = "application/json"): ByteArray {
        val response = http.request(baseUrl + pathAndQuery) {
            this.method = method
            headers.forEach { (k, v) -> header(k, v) }
            if (body != null) {
                header("content-type", contentType)
                setBody(body) // exactly the bytes that were hashed and signed
            }
        }
        val data = response.bodyAsBytes()
        val code = response.status.value
        if (code >= 400) {
            val text = data.decodeToString()
            val message = runCatching { (ApiJson.parseToJsonElement(text) as? JsonObject)?.get("error")?.let { (it as? JsonPrimitive)?.content } }.getOrNull()
            throw AeonException(code, message ?: "HTTP $code")
        }
        return data
    }
}

/** True when [url] points at the machine this app runs on. */
fun isLoopback(url: String): Boolean = LOOPBACK.matches(url.trim())

/** Exactly scheme, one of three fixed hosts, an optional port, then a path or nothing: no userinfo, `\`, `?` or `#` can follow the host. */
private val LOOPBACK = Regex("""(?i)https?://(localhost|127\.0\.0\.1|\[::1\])(:[0-9]{1,5})?(/.*)?""")

/** Percent-encodes everything except RFC 3986 unreserved characters, so the signed text is exactly what is sent. */
fun encodeQueryValue(value: String): String {
    val hex = "0123456789ABCDEF"
    val out = StringBuilder()
    for (b in value.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') out.append(ch)
        else out.append('%').append(hex[c shr 4]).append(hex[c and 15])
    }
    return out.toString()
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
