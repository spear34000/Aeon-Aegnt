package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.ProviderRow
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.Tone
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.UiState

private fun effortName(e: String) = when (e) { "low" -> "낮음"; "medium" -> "보통"; "high" -> "높음"; "max" -> "최대"; else -> e }

/** Bottom sheet over the screen: provider list, then that provider's models, key entry and reasoning effort. */
@Composable
fun ModelSheetHost(state: UiState, model: AppModel) {
    if (!state.models.open) return
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { model.closeModelSheet() },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(topStart = AeonShape.SHEET.dp, topEnd = AeonShape.SHEET.dp)).background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {}.padding(horizontal = 20.dp),
        ) {
            Box(Modifier.padding(top = 10.dp, bottom = 12.dp).align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
            ModelSheetBody(state, model)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ModelSheetBody(state: UiState, model: AppModel) {
    val m = state.models
    val scheme = MaterialTheme.colorScheme
    val provider = m.providers.firstOrNull { it.id == m.providerId }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (provider != null) Box(Modifier.size(40.dp).clickable { model.showProvider(null) }, contentAlignment = Alignment.Center) { Icon(AeonIcons.Back, contentDescription = "제공자 목록", modifier = Modifier.size(22.dp)) }
        Text(provider?.name ?: "모델", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Box(Modifier.size(40.dp).clickable { model.closeModelSheet() }, contentAlignment = Alignment.Center) { Icon(AeonIcons.Close, contentDescription = "닫기", modifier = Modifier.size(22.dp)) }
    }
    if (!state.isAdmin) Banner("이 기기는 설정을 바꿀 수 없어요.", Tone.Warn, "⚠")
    m.error?.let { Box(Modifier.padding(top = 8.dp)) { Banner(it, Tone.Danger, "✕") } }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (provider == null) ProviderList(state, model) else ProviderDetail(state, model, provider)
    }
}

@Composable
private fun ProviderList(state: UiState, model: AppModel) {
    val m = state.models
    m.catalogError?.let { Banner("최신 목록을 가져오지 못해 저장된 목록을 보여 줘요.", Tone.Warn, "⚠") }
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(false) }
    AeonTextField(query, { query = it }, "제공자 찾기", placeholder = "OpenAI, Groq, Upstage…")
    val ranked = m.providers.sortedWith(compareByDescending<ProviderRow> { it.id == m.current?.provider }.thenByDescending { it.hasKey || it.local }.thenBy { it.name.lowercase() })
    val mine = ranked.filter { it.id == m.current?.provider || it.hasKey || it.local }
    val shown = when {
        query.isNotBlank() -> ranked.filter { it.name.contains(query, true) || it.id.contains(query, true) }
        all -> ranked
        else -> mine
    }
    ProviderRows(shown, m.current?.provider, model)
    if (query.isBlank() && !all && ranked.size > mine.size) {
        AeonButton("제공자 ${ranked.size - mine.size}개 더 보기", { all = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
    }
}

@Composable
private fun ProviderRows(list: List<ProviderRow>, currentId: String?, model: AppModel) {
    val scheme = MaterialTheme.colorScheme
    if (list.isEmpty()) {
        Text("찾는 제공자가 없어요.", Modifier.padding(4.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        return
    }
    ListSurface {
        list.forEachIndexed { i, p ->
            if (i > 0) RowDivider(16.dp)
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clickable { model.showProvider(p.id) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                when {
                    p.id == currentId -> Chip("사용 중", Tone.Accent)
                    p.hasKey || p.local -> Chip("연결됨", Tone.Ok)
                }
                Icon(AeonIcons.Chevron, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ProviderDetail(state: UiState, model: AppModel, p: ProviderRow) {
    val m = state.models
    val scheme = MaterialTheme.colorScheme
    val list = m.models[p.id]
    val inUse = p.id == m.current?.provider
    var consent by remember(p.id) { mutableStateOf(p.consented) }
    var key by remember(p.id) { mutableStateOf("") }
    var typed by remember(p.id) { mutableStateOf("") }
    val needsKey = !p.local && !p.hasKey && p.keyName != null

    if (needsKey) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CARD.dp)).background(scheme.background).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonTextField(key, { key = it }, "API 키", secret = true, mono = true)
            AeonButton("저장", { model.saveProviderKey(p.keyName!!, key); key = "" }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = key.isNotBlank() && !m.loading && state.isAdmin)
        }
    }
    if (!p.local && !p.consented) {
        Row(Modifier.fillMaxWidth().clickable { consent = !consent }, verticalAlignment = Alignment.CenterVertically) {
            CheckBox(consent, "전송 동의")
            Text("${p.name}(${p.baseUrl.removePrefix("https://").substringBefore('/')})로 요청과 읽은 파일 내용을 보내는 데 동의해요.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
    val ready = state.isAdmin && !m.loading && !needsKey && (p.local || p.consented || consent)
    when {
        list == null && m.loading -> Text("불러오는 중…", style = MaterialTheme.typography.bodyMedium)
        list.isNullOrEmpty() -> {
            AeonTextField(typed, { typed = it }, "모델 이름", mono = true, placeholder = "예: exaone3.5:7.8b")
            AeonButton("사용", { model.pickModel(p.id, typed.trim(), null, consent) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = ready && typed.isNotBlank())
        }
        else -> ListSurface {
            list.forEachIndexed { i, mo ->
                if (i > 0) RowDivider(16.dp)
                val on = inUse && mo.id == m.current?.model
                val effortAvailable = (mo.reasoning || p.id == "exaone") && p.efforts.isNotEmpty()
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clickable(enabled = ready) { model.pickModel(p.id, mo.id, if (effortAvailable) m.current?.reasoningEffort?.takeIf { inUse && it in p.efforts } else null, consent) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(mo.name, style = MaterialTheme.typography.titleMedium)
                            mo.context?.let { Text("문맥 ${it / 1000}K", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant) }
                        }
                        if (on) Icon(AeonIcons.Check, contentDescription = "사용 중", tint = scheme.primary, modifier = Modifier.size(20.dp))
                    }
                    // The effort levels only appear for the model in use, and only if that model has a reasoning mode.
                    if (on && effortAvailable) {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("추론 강도", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                            Segmented(listOf<Pair<String?, String>>(null to "자동") + p.efforts.map { it to effortName(it) }, m.current?.reasoningEffort?.takeIf { it in p.efforts }) { e ->
                                model.pickModel(p.id, mo.id, e, consent)
                            }
                        }
                    }
                }
            }
        }
    }
}
