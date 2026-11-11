package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
        Text(provider?.name ?: "모델 선택", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Box(Modifier.size(40.dp).clickable { model.closeModelSheet() }, contentAlignment = Alignment.Center) { Icon(AeonIcons.Close, contentDescription = "닫기", modifier = Modifier.size(22.dp)) }
    }
    if (!state.isAdmin) Banner("이 기기는 설정을 바꿀 수 없게 되어 있습니다. 설정을 바꿀 수 있는 기기에서 허용해 주세요.", Tone.Warn, "⚠")
    m.error?.let { Box(Modifier.padding(top = 8.dp)) { Banner(it, Tone.Danger, "✕") } }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (provider == null) ProviderList(state, model) else ProviderDetail(state, model, provider)
    }
}

@Composable
private fun ProviderList(state: UiState, model: AppModel) {
    val m = state.models
    val scheme = MaterialTheme.colorScheme
    m.catalogError?.let { Banner("최신 제공자 목록을 가져오지 못했습니다. 저장된 목록을 보여 줍니다.", Tone.Warn, "⚠") }
    var query by remember { mutableStateOf("") }
    AeonTextField(query, { query = it }, "제공자 찾기", placeholder = "예: openai, groq, upstage")
    val shown = m.providers.filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }
        .sortedWith(compareByDescending<ProviderRow> { it.id == m.current?.provider }.thenByDescending { it.hasKey && !it.local }.thenBy { it.name.lowercase() })
    ListSurface {
        shown.forEachIndexed { i, p ->
            if (i > 0) RowDivider(16.dp)
            Row(Modifier.fillMaxWidth().clickable { model.showProvider(p.id) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (p.local) "이 기기에서 실행 · 키 필요 없음" else if (p.hasKey) "키 있음" else "키 필요", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
                if (p.id == m.current?.provider) Chip("사용 중", Tone.Accent)
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
    var effort by remember(p.id) { mutableStateOf(if (p.id == m.current?.provider) m.current.reasoningEffort else null) }
    var consent by remember(p.id) { mutableStateOf(p.consented) }
    var key by remember(p.id) { mutableStateOf("") }
    var typed by remember(p.id) { mutableStateOf("") }
    Text("주소 ${p.baseUrl.removePrefix("https://").removePrefix("http://")}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)

    if (!p.local && !p.hasKey && p.keyName != null) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CARD.dp)).background(scheme.background).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("API 키를 넣어 주세요", style = MaterialTheme.typography.titleMedium)
            Text("키는 암호화해서 Aeon이 실행 중인 기기의 보안 저장소에만 둡니다. 이 기기에는 남지 않습니다.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            AeonTextField(key, { key = it }, "API 키", secret = true, mono = true)
            AeonButton("키 저장", { model.saveProviderKey(p.keyName, key); key = "" }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = key.isNotBlank() && !m.loading && state.isAdmin)
        }
    }
    if (p.efforts.isNotEmpty()) {
        Text("추론 강도", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillChoice("자동", effort == null) { effort = null }
            p.efforts.forEach { e -> PillChoice(effortName(e), effort == e) { effort = e } }
        }
        Text("추론 강도를 지원하지 않는 모델에서는 자동으로 빠집니다.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
    if (!p.local && !p.consented) {
        Row(Modifier.fillMaxWidth().clickable { consent = !consent }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (consent) scheme.primary else scheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (consent) Icon(AeonIcons.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
            }
            Text("${p.name}(${p.baseUrl.removePrefix("https://").substringBefore('/')})로 내 요청과 읽은 파일 내용이 전송되는 것에 동의합니다.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
    val ready = state.isAdmin && !m.loading && (p.local || p.hasKey || p.keyName == null) && (p.local || p.consented || consent)
    Text("모델", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    when {
        list == null && m.loading -> Text("불러오는 중…", style = MaterialTheme.typography.bodyMedium)
        list.isNullOrEmpty() -> {
            Text("목록에 모델이 없습니다. 모델 이름을 직접 입력하세요.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            AeonTextField(typed, { typed = it }, "모델 이름", mono = true, placeholder = "예: exaone3.5:7.8b")
            AeonButton("이 모델 사용", { model.pickModel(p.id, typed.trim(), effort, consent) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = ready && typed.isNotBlank())
        }
        else -> ListSurface {
            list.forEachIndexed { i, mo ->
                if (i > 0) RowDivider(16.dp)
                val on = p.id == m.current?.provider && mo.id == m.current.model
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = ready) { model.pickModel(p.id, mo.id, if (mo.reasoning || p.id == "exaone") effort else null, consent) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(mo.name, style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(mo.id.takeIf { it != mo.name }, if (mo.reasoning) "추론" else null, mo.context?.let { "문맥 ${it / 1000}K" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (on) Chip("사용 중", Tone.Accent)
                }
            }
        }
    }
}
