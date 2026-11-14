package dev.aeon.ui.screens

/** What a person reads when a task stopped, and whether the fix is on the AI settings page. */
internal data class Problem(val text: String, val fixInSettings: Boolean = false)

/**
 * What the engine reports when a task stops, in words an ordinary user can act on. The engine writes these in English for
 * logs and other tools; known ones are shown in Korean, and anything else is shown as it is under a plain sentence.
 *
 * Problems with the AI service arrive as `<service>: <kind>: <what the service said>` (for example
 * `exaone: auth: HTTP 401: {...}`). The kind decides the sentence; what the service said is never shown, because it is
 * English or JSON and cannot be acted on.
 */
internal fun friendlyProblem(raw: String): Problem {
    val t = raw.trim()
    val kind = MODEL_ERROR.find(t)?.groupValues?.get(1)
    return when {
        kind == "auth" -> Problem("AI 서비스가 내 키를 받아 주지 않아요. 키가 맞는지, 기간이 지나지 않았는지 확인해 주세요.", fixInSettings = true)
        kind == "consent_required" -> Problem("이 AI에 내용을 보내도 되는지 아직 허락을 받지 못했어요. AI 설정에서 허락해 주세요.", fixInSettings = true)
        kind == "bad_request" -> Problem("AI 서비스가 요청을 받아들이지 않았어요. 고른 AI의 이름이나 설정을 확인해 주세요.", fixInSettings = true)
        kind == "network" -> Problem("인터넷에 연결되지 않아 AI 서비스에 닿지 못했어요. 연결을 확인하고 다시 해 보세요.")
        kind == "timeout" -> Problem("AI 서비스가 너무 오래 답하지 않아요. 잠시 뒤에 다시 해 보세요.")
        kind == "server" || kind == "unavailable" -> Problem("AI 서비스에 지금 문제가 있어요. 몇 분 뒤에 다시 해 보세요.")
        kind == "invalid_response" || kind == "truncated" -> Problem("AI 서비스가 읽을 수 없는 답을 보냈어요. 다시 해 보세요.")
        kind == "rate_limited" || kind == "quota_exhausted" -> Problem("AI 서비스가 지금 너무 붐벼요. 잠시 뒤에 다시 해 보세요.")
        t.startsWith("The agent reported success but verification failed") -> Problem("에이전트가 끝냈다고 했지만, 결과를 확인해 보니 맞지 않았어요.")
        t.startsWith("model did not produce a valid plan") -> Problem("작업 계획을 세우지 못했어요. 다시 시도해 보세요.")
        t.startsWith("model repeatedly produced invalid actions") -> Problem("에이전트가 계속 알아들을 수 없는 답을 해서 멈췄어요.")
        t.startsWith("the agent kept repeating") -> Problem("같은 동작을 계속 되풀이해서 멈췄어요.")
        t.startsWith("step budget exhausted") -> Problem("정해 둔 단계 수를 다 써서 멈췄어요.")
        t.startsWith("runner crashed") -> Problem("작업을 하다가 문제가 생겨 멈췄어요.")
        t.any { it in '가'..'힣' } -> Problem(t)
        else -> Problem("문제가 생겨서 멈췄어요. 자세한 내용: $t")
    }
}

internal fun friendlyError(raw: String): String = friendlyProblem(raw).text

private val MODEL_ERROR = Regex("^[A-Za-z0-9._-]+: (auth|consent_required|bad_request|network|timeout|server|unavailable|invalid_response|truncated|rate_limited|quota_exhausted): ")
