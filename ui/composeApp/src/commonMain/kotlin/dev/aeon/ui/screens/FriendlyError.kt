package dev.aeon.ui.screens

/**
 * What the engine reports when a task stops, in words an ordinary user can act on. The engine writes these in English for
 * logs and other tools; known ones are shown in Korean, and anything else is shown as it is under a plain sentence.
 */
internal fun friendlyError(raw: String): String {
    val t = raw.trim()
    return when {
        t.startsWith("The agent reported success but verification failed") -> "에이전트가 끝냈다고 했지만, 결과를 확인해 보니 맞지 않았어요."
        t.startsWith("model did not produce a valid plan") -> "작업 계획을 세우지 못했어요. 다시 시도해 보세요."
        t.startsWith("model repeatedly produced invalid actions") -> "에이전트가 계속 알아들을 수 없는 답을 해서 멈췄어요."
        t.startsWith("the agent kept repeating") -> "같은 동작을 계속 되풀이해서 멈췄어요."
        t.startsWith("step budget exhausted") -> "정해 둔 단계 수를 다 써서 멈췄어요."
        t.startsWith("runner crashed") -> "작업을 하다가 문제가 생겨 멈췄어요."
        t.any { it in '가'..'힣' } -> t
        else -> "문제가 생겨서 멈췄어요. 자세한 내용: $t"
    }
}
