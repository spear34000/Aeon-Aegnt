package dev.aeon.ui

import dev.aeon.ui.screens.friendlyError
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
