package dev.aeon.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.ConnectionItem
import dev.aeon.ui.api.IconPayload
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.ExploreState
import dev.aeon.ui.state.UiState
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

private val KINDS = listOf<Pair<String?, String>>(null to "전체", "integration" to "연동", "mcp" to "MCP 서버", "skill" to "스킬", "builtin" to "기본 도구")

/** Korean names for Aeon's own tool namespaces. Anything else shows the id the server sent. */
private fun builtinTitle(id: String): String = when (id) {
    "fs" -> "파일"
    "http" -> "웹 요청"
    "sandbox" -> "격리 실행"
    else -> id
}

internal fun connectionTitle(c: ConnectionItem): String = if (c.kind == "builtin") builtinTitle(c.title) else c.title

private fun kindLabel(kind: String): String = KINDS.firstOrNull { it.first == kind }?.second ?: kind

private val TOOL_VERBS = mapOf("read" to "읽기", "write" to "쓰기", "list" to "목록", "delete" to "삭제", "mkdir" to "폴더 만들기", "move" to "옮기기", "copy" to "복사", "get" to "가져오기", "fetch" to "가져오기", "exec" to "실행", "run" to "실행", "stat" to "정보")

/** One short line under a name: what kind it is, and for built-in tools what they can do. No ids or host names. */
private fun subtitle(c: ConnectionItem): String = when (c.kind) {
    "builtin" -> {
        val verbs = c.tools.mapNotNull { TOOL_VERBS[it.substringAfterLast('.')] }.distinct().take(3)
        if (verbs.isEmpty()) "기본 도구" else "기본 도구 · ${verbs.joinToString(", ")}"
    }
    "skill" -> c.description.ifBlank { "스킬" }
    "integration" -> "연동"
    else -> "MCP 서버"
}

@Composable
internal fun GroupHeader(text: String, count: String? = null, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (count != null) Text(" $count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A card that holds rows, separated by hairlines that start where the row text starts. */
@Composable
internal fun ListSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CARD.dp)).background(MaterialTheme.colorScheme.surface)) { content() }
}

@Composable
internal fun RowDivider(inset: Dp = 72.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
internal fun PillChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.defaultMinSize(minHeight = 36.dp).clip(shape)
            .background(if (selected) scheme.onSurface else scheme.surface)
            .then(if (selected) Modifier else Modifier.border(1.dp, scheme.outlineVariant, shape))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) scheme.background else scheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge) }
}

private fun parseHex(hex: String?): Color {
    val v = hex?.removePrefix("#")?.toLongOrNull(16) ?: return Color(0xFF0B0B0F)
    return Color(0xFF000000 or v)
}

/**
 * Logo tile. The server resolves the icon (publisher's manifest, a brand mark matching the title, the publisher's
 * site icon, or its GitHub avatar) and the tile only draws what came back. Without one it shows the first letter.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalResourceApi::class)
@Composable
internal fun ConnectionTile(c: ConnectionItem, payload: IconPayload?, size: Dp = 44.dp) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(size * 0.27f)
    val glyph: ImageVector? = when (c.kind) {
        "builtin" -> when (c.title) {
            "fs" -> AeonIcons.File
            "http" -> AeonIcons.Globe
            "sandbox" -> AeonIcons.Terminal
            else -> AeonIcons.Plug
        }
        "skill" -> AeonIcons.Book
        else -> null
    }
    val bitmap = remember(payload?.data) { payload?.takeIf { it.kind == "image" }?.data?.let { runCatching { Base64.decode(it).decodeToImageBitmap() }.getOrNull() } }
    val path = remember(payload?.d) { payload?.takeIf { it.kind == "path" }?.d?.let { runCatching { PathParser().parsePathString(it).toPath() }.getOrNull() } }
    val logo = bitmap != null || path != null
    Box(
        Modifier.size(size).clip(shape).background(if (logo) Color.White else scheme.surfaceVariant)
            .then(if (logo) Modifier.border(1.dp, scheme.outlineVariant, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(bitmap, contentDescription = null, modifier = Modifier.size(size * 0.66f).clip(RoundedCornerShape(6.dp)))
            path != null -> {
                val color = parseHex(payload?.color)
                Canvas(Modifier.size(size * 0.55f)) { scale(this.size.width / 24f, this.size.height / 24f, pivot = Offset.Zero) { drawPath(path, color) } }
            }
            glyph != null -> Icon(glyph, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(size * 0.5f))
            else -> Text(connectionTitle(c).take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectionRow(c: ConnectionItem, payload: IconPayload?, installed: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionTile(c, payload)
        Column(Modifier.weight(1f)) {
            Text(connectionTitle(c), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle(c), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            installed -> Icon(AeonIcons.Chevron, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            c.state == "added" -> Chip("추가됨", Tone.Ok)
            else -> Box(Modifier.clip(RoundedCornerShape(50)).background(scheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("추가", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            }
        }
    }
}

@Composable
private fun ConnectionList(items: List<ConnectionItem>, e: ExploreState, model: AppModel, installed: Boolean) {
    ListSurface {
        items.forEachIndexed { i, c ->
            if (i > 0) RowDivider()
            c.icon?.let { path -> LaunchedEffect(path) { model.requestIcon(path) } }
            ConnectionRow(c, c.icon?.let { e.icons[it] }, installed) { if (c.kind == "skill") { model.loadSkills(); model.editSkill(c.id.removePrefix("skill:")) } else model.showConnection(c) }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(shape).background(scheme.surface).border(1.dp, scheme.outlineVariant, shape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(AeonIcons.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("도구, 연동, 스킬 검색", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "도구, 연동, 스킬 검색" },
            )
        }
        if (value.isNotEmpty()) {
            Icon(AeonIcons.Close, contentDescription = "검색어 지우기", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable { onChange("") })
        }
    }
}

/**
 * 둘러보기: what is connected and what can be added. Every row comes from the server at this moment:
 * built-in tools from the running tool registry, added servers from the settings, skills from the skills folder,
 * and "can be added" from a live search of the official MCP registry made by the PC.
 */
@Composable
fun ExploreScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    val e = state.explore
    var kind by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { model.loadExplore() }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Box(Modifier.padding(horizontal = 16.dp)) { SearchField(e.query, model::searchCatalog) }
            Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KINDS.forEach { (k, label) -> PillChoice(label, kind == k) { kind = k } }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                state.message?.let { Box(Modifier.padding(top = 12.dp)) { Banner(it, Tone.Neutral, "·", model::dismissMessage) } }

                val q = e.query.trim()
                val installed = e.installed.filter { c -> (kind == null || c.kind == kind) && (q.isEmpty() || connectionTitle(c).contains(q, true) || c.title.contains(q, true)) }
                if (kind == "skill") {
                    SkillsSection(state, model, q)
                } else {
                    GroupHeader("연결됨", installed.size.toString())
                    if (installed.isEmpty()) {
                        Hint(
                            when {
                                q.isNotEmpty() -> "연결된 항목에는 없어요."
                                kind == "integration" -> "추가한 연동이 없어요."
                                kind == "mcp" -> "추가한 MCP 서버가 없어요."
                                else -> "불러오는 중이에요."
                            },
                        )
                    } else {
                        ConnectionList(installed, e, model, installed = true)
                    }
                }

                if (kind != "skill" && kind != "builtin") {
                    val catalog = e.catalog.filter { c -> (kind == null || c.kind == kind) && e.installed.none { it.id == c.id } }
                    GroupHeader("추가할 수 있음")
                    if (e.query.isBlank() && e.collections.isNotEmpty()) {
                        Row(Modifier.padding(bottom = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            e.collections.forEach { c -> PillChoice(c.title["ko"] ?: c.title["en"] ?: c.id, e.collectionId == c.id) { model.openCollection(c.id) } }
                        }
                    }
                    when {
                        e.error != null -> Banner(e.error, Tone.Warn, "⚠")
                        e.loading && catalog.isEmpty() -> Hint("찾는 중이에요.")
                        catalog.isEmpty() -> Hint(if (e.query.isBlank()) "항목이 없어요." else "찾는 서버가 없어요. 영문 이름으로 검색해 보세요.")
                        else -> ConnectionList(catalog, e, model, installed = false)
                    }
                }
            }
        }

        AnimatedVisibility(visible = e.detail != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { model.showConnection(null) },
            )
        }
        AnimatedVisibility(
            visible = e.detail != null,
            enter = slideInVertically(tween(240)) { it },
            exit = slideOutVertically(tween(180)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // Keep drawing the last item while the sheet slides out.
            var last by remember { mutableStateOf(e.detail) }
            if (e.detail != null) last = e.detail
            last?.let { ConnectionSheet(it, state, model) }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Note(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(AeonIcons.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectionSheet(c: ConnectionItem, state: UiState, model: AppModel) {
    val e = state.explore
    val scheme = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    val isAdded = c.state == "added" || (c.kind != "builtin" && c.kind != "skill" && e.installed.any { it.id == c.id })
    Column(
        Modifier.fillMaxWidth().fillMaxHeight(0.86f).clip(RoundedCornerShape(topStart = AeonShape.SHEET.dp, topEnd = AeonShape.SHEET.dp)).background(scheme.surface)
            .pointerInput(Unit) {} // taps on the sheet must not reach the dimmed area behind it
            .padding(horizontal = 20.dp),
    ) {
        Box(Modifier.padding(top = 10.dp, bottom = 16.dp).align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).background(scheme.outlineVariant, CircleShape))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ConnectionTile(c, c.icon?.let { e.icons[it] }, 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(connectionTitle(c), style = MaterialTheme.typography.titleLarge)
                    Text(listOfNotNull(c.publisher, c.version).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(kindLabel(c.kind), Tone.Accent)
                if (isAdded) Chip("추가됨", Tone.Ok)
            }
            if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodyLarge)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.background).padding(horizontal = 14.dp, vertical = 8.dp)) {
                c.remoteUrl?.let { InfoLine(AeonIcons.Link, "원격 주소", it.removePrefix("https://")) }
                c.runtime?.let { InfoLine(AeonIcons.Box, "실행 방식", it) }
                if (c.requires.isNotEmpty()) InfoLine(AeonIcons.Key, "필요한 키", c.requires.joinToString(", "))
                if (c.tools.isNotEmpty()) InfoLine(AeonIcons.Plug, "도구", c.tools.joinToString(", "))
                if (c.kind == "skill") InfoLine(AeonIcons.Book, "폴더", c.id.removePrefix("skill:"))
            }
            if (c.kind == "integration") Note("에이전트가 이 서버를 쓰는 기능은 아직 준비 중이에요.")
            if (c.kind == "integration" && !e.canEdit) Note("이 기기는 설정을 바꿀 수 없어요.")
        }
        Column(Modifier.padding(top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                c.kind == "builtin" || c.kind == "skill" -> {}
                isAdded -> AeonButton("삭제", { model.removeConnection(c) }, Modifier.fillMaxWidth(), kind = ButtonKind.Destructive, enabled = e.canEdit && !state.busy)
                c.kind == "integration" -> AeonButton("추가", { model.addConnection(c) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = e.canEdit && !state.busy)
                else -> AeonButton("추가", {}, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = false)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                c.websiteUrl?.takeIf { it.startsWith("https://") }?.let { url ->
                    AeonButton("자세히", { runCatching { uri.openUri(url) } }, Modifier.weight(1f), kind = ButtonKind.Ghost)
                }
                AeonButton("닫기", { model.showConnection(null) }, Modifier.weight(1f), kind = ButtonKind.Ghost)
            }
        }
    }
}


/** The skills list with a button to write a new one. Rows open the editor. */
@Composable
private fun SkillsSection(state: UiState, model: AppModel, query: String) {
    val sk = state.skills
    LaunchedEffect(Unit) { model.loadSkills() }
    val list = sk.list.filter { query.isEmpty() || it.name.contains(query, true) || it.description.contains(query, true) }
    GroupHeader("내 스킬", list.size.toString())
    if (sk.canEdit) AeonButton("스킬 만들기", { model.editSkill(null) }, Modifier.fillMaxWidth().padding(bottom = 10.dp), kind = ButtonKind.Primary)
    if (list.isEmpty()) {
        Hint(if (!sk.loaded) "불러오는 중이에요." else if (query.isNotEmpty()) "찾는 스킬이 없어요." else "스킬이 없어요.")
    } else {
        ListSurface {
            list.forEachIndexed { i, k ->
                if (i > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable { model.editSkill(k.folder) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Icon(AeonIcons.Book, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(k.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (k.description.isNotBlank()) Text(k.description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(AeonIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    if (sk.dir.isNotBlank()) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text("저장 위치 ${sk.dir}", Modifier.padding(start = 4.dp, top = 12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Hint("에이전트가 스킬을 쓰는 기능은 아직 준비 중이에요.")
}
