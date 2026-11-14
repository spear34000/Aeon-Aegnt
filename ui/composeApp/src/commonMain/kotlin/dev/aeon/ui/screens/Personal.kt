package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.UiState

private const val MAX_INTERESTS = 12

/**
 * 개인화: the same choices as the profile step of the first run (name, interests, voice, notes), on one page with a
 * save bar that stays in view. They only change the wording and focus of what the agent writes; they never change what
 * it may do or what needs approval. Saved on the server, so every paired device sees the same agent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonalPage(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    val saved = state.profile
    var draft by remember(state.config?.profile) { mutableStateOf(saved) }
    LaunchedEffect(Unit) { if (state.config == null) model.loadSettings() }
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val interests = draft.interests.orEmpty()
    val dirty = draft != saved
    var typed by remember { mutableStateOf("") }

    fun toggle(label: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val on = label in interests
        draft = draft.copy(interests = (if (on) interests - label else if (interests.size < MAX_INTERESTS) interests + label else interests).ifEmpty { null })
    }

    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
            IdentityPreview(draft, Modifier.padding(top = 8.dp, bottom = 4.dp))

            GroupHeader("이름")
            AeonTextField(draft.userName.orEmpty(), { draft = draft.copy(userName = it.replace('\n', ' ').take(40)) }, "나를 부르는 이름", placeholder = "예: 지우")
            Spacer(Modifier.height(12.dp))
            AeonTextField(draft.agentName.orEmpty(), { draft = draft.copy(agentName = it.replace('\n', ' ').take(40)) }, "에이전트 이름", placeholder = "Aeon")

            GroupHeader("관심사", "${interests.size}/$MAX_INTERESTS")
            INTEREST_GROUPS.forEach { (name, items) ->
                Text(name, Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { InterestTile(it, it.label in interests, Modifier.weight(1f)) { toggle(it.label) } }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            val known = INTEREST_GROUPS.flatMap { g -> g.second.map { it.label } }
            val own = interests.filter { it !in known }
            if (own.isNotEmpty()) {
                Text("직접 추가", Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    own.forEach { InterestPill(it, true) { toggle(it) } }
                }
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AeonTextField(typed, { typed = it.replace('\n', ' ').take(30) }, "직접 입력", Modifier.weight(1f), placeholder = "예: 러닝")
                AeonButton("추가", { val t = typed.trim(); if (t.isNotEmpty() && t !in interests && interests.size < MAX_INTERESTS) draft = draft.copy(interests = interests + t); typed = "" }, kind = ButtonKind.Secondary, enabled = typed.isNotBlank() && interests.size < MAX_INTERESTS)
            }

            GroupHeader("피드")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.surface).padding(horizontal = 16.dp)) {
                dev.aeon.ui.design.AeonSwitch(state.config?.feedImages != false, { model.setFeedImages(it) }, "기사 사진 보여 주기")
            }
            Text("켜면 글을 만들 때 기사 사진을 같이 가져와서 데이터를 더 써요. 기사 하나를 열어 보는 데 1MB쯤 들어요. 끄면 사진 없이 보여 주고, 가져온 사진은 지워요.", Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)

            GroupHeader("말투")
            Text("언어", Modifier.padding(start = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Segmented(listOf<Pair<String?, String>>(null to "자동", "ko" to "한국어", "en" to "English"), draft.language) { draft = draft.copy(language = it) }
            Text("말투", Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val tone = draft.tone ?: "polite"
                OptionCard("존댓말", "파일 3개를 찾았어요.", tone == "polite") { draft = draft.copy(tone = "polite") }
                OptionCard("반말", "파일 3개 찾았어.", tone == "casual") { draft = draft.copy(tone = "casual") }
            }
            Text("답변 길이", Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Segmented(listOf<Pair<String?, String>>("brief" to "짧게", null to "기본", "detailed" to "자세히"), draft.detail) { draft = draft.copy(detail = it) }
            ChatPreview(draft, null, profilePreview(draft), Modifier.padding(top = 18.dp))

            GroupHeader("알아 둘 것")
            AeonTextField(
                draft.about.orEmpty(), { draft = draft.copy(about = it.replace('\n', ' ').take(1000)) }, "내용", singleLine = false, minHeight = 140.dp,
                placeholder = "예: 개발자입니다. 코드 주석은 영어로 써 주세요.", help = "고른 모델 제공자에게 전달돼요 · ${draft.about.orEmpty().length}/1000",
            )
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ABOUT_STARTERS.forEach { label ->
                    Text(
                        label, Modifier.clip(CircleShape).background(scheme.surfaceVariant).clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val now = draft.about.orEmpty().trimEnd()
                            val next = (if (now.isEmpty()) "" else "$now ") + "$label: "
                            if (next.length <= 1000) draft = draft.copy(about = next)
                        }.padding(horizontal = 14.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Row(Modifier.fillMaxWidth().background(scheme.background).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("되돌리기", { draft = saved }, kind = ButtonKind.Secondary, enabled = dirty)
            AeonButton("저장", { model.saveProfile(draft) }, Modifier.weight(1f), kind = ButtonKind.Primary, enabled = dirty && !state.busy)
        }
    }
}
