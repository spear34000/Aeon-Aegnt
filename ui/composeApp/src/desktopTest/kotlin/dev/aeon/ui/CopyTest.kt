package dev.aeon.ui

import dev.aeon.ui.design.quotaWaitWord
import dev.aeon.ui.screens.GOAL_LIMIT
import dev.aeon.ui.screens.friendlyError
import dev.aeon.ui.screens.goalTooLong
import dev.aeon.ui.screens.friendlyProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The words an ordinary user reads when something stops: Korean, plain, and no raw English from the engine. */
class CopyTest {
    @Test
    fun taskErrorsAreShownInPlainKorean() {
        assertEquals("에이전트가 끝냈다고 했지만, 결과를 확인해 보니 맞지 않았어요.", friendlyError("The agent reported success but verification failed."))
        assertEquals("같은 동작을 계속 되풀이해서 멈췄어요.", friendlyError("the agent kept repeating fs.read with the same result"))
        assertEquals("정해 둔 단계 수를 다 써서 멈췄어요.", friendlyError("step budget exhausted (10 steps)"))
        assertEquals("작업 계획을 세우지 못했어요. 다시 시도해 보세요.", friendlyError("model did not produce a valid plan"))
        assertEquals("모델에 연결하지 못했어요.", friendlyError("모델에 연결하지 못했어요."), "a Korean message is kept as it is")
        val unknown = friendlyError("EACCES: permission denied, open 'x'")
        assertTrue(unknown.startsWith("문제가 생겨서 멈췄어요."), unknown)
        assertTrue(unknown.endsWith("EACCES: permission denied, open 'x'"), "an unknown error is not hidden: the plain sentence comes first, the original follows")
    }

    @Test
    fun problemsWithTheAiServiceSayWhatToDoAndHideWhatTheServiceSaid() {
        val auth = friendlyProblem("exaone: auth: HTTP 401: {\"error\":{\"message\":\"invalid api key format\",\"code\":\"invalid_api_key\"}}")
        assertTrue(auth.fixInSettings, "a wrong key is fixed in the AI settings")
        assertTrue("키" in auth.text && "401" !in auth.text && "{" !in auth.text, auth.text)
        assertTrue(friendlyProblem("router: consent_required: no model provider is permitted: exaone").fixInSettings)
        assertTrue(friendlyProblem("exaone: bad_request: HTTP 422: x").fixInSettings)
        for (kind in listOf("network: network error: fetch failed", "timeout: request timed out", "server: HTTP 503: <html>", "invalid_response: response was not valid JSON", "rate_limited: HTTP 429", "unavailable: x")) {
            val p = friendlyProblem("exaone-proxy: $kind")
            assertFalse(p.fixInSettings, kind)
            assertTrue(p.text.any { it in '가'..'힣' } && "HTTP" !in p.text && "<html>" !in p.text && "JSON" !in p.text, "$kind -> ${p.text}")
        }
        assertEquals("인터넷에 연결되지 않아 AI 서비스에 닿지 못했어요. 연결을 확인하고 다시 해 보세요.", friendlyError("exaone: network: network error: fetch failed"))
        assertTrue(friendlyError("exaone: weird: x").startsWith("문제가 생겨서 멈췄어요."), "an unknown kind is not mistaken for a known one")
    }

    @Test
    fun aMessageTooLongForTheServerIsStoppedBeforeSending() {
        assertEquals(4000, GOAL_LIMIT, "the same limit as the server's goal")
        assertFalse(goalTooLong("가".repeat(4000)))
        assertTrue(goalTooLong("가".repeat(4001)))
        assertFalse(goalTooLong("  " + "가".repeat(4000) + "  "), "spaces around it do not count, the server trims them too")
    }

    @Test
    fun theWaitForTheAiServiceNamesItsReason() {
        assertEquals("AI가 붐벼요", quotaWaitWord("busy: the model service is busy; trying again soon"))
        assertEquals("AI가 붐벼요", quotaWaitWord(null), "an older server's note still reads as a short wait")
        assertEquals("오늘 사용량 가득", quotaWaitWord("limit: the model's allowance is used up for now"))
    }
}
