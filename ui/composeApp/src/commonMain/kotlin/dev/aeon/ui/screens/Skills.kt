package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.SkillCandidate
import dev.aeon.ui.api.SkillItem
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.AeonSwitch
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.PlatformBackHandler
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.SkillScreen
import dev.aeon.ui.state.UiState

// ---------------------------------------------------------------- what a skill is called on screen

internal fun scopeLabel(scope: String): String = if (scope == "project") "이 프로젝트" else "내 스킬"

internal fun sourceLabel(k: SkillItem): String {
    val src = k.source
    return when (src?.type) {
        "github" -> "GitHub · ${src.owner}/${src.repo}"
        "agent" -> "에이전트가 만들었어요"
        "local" -> "이 컴퓨터의 폴더"
        "legacy" -> "이전부터 있던 스킬"
        else -> if (k.scope == "project") "저장소에 있던 스킬" else "승인한 적 없는 스킬"
    }
}

/** A source that is a path on the machine (not owner/repo): starts with a slash, a dot, a tilde or a drive letter. */
private fun sourceIsFolder(src: String): Boolean = src.startsWith("/") || src.startsWith(".") || src.startsWith("~") || (src.length > 1 && src[1] == ':')

private fun shortCommit(c: String?): String = c?.take(7) ?: ""

/** The one word that says what the owner has to know about a skill: needs approval, changed, off, hidden, or has scripts. */
@Composable
internal fun SkillStateChip(k: SkillItem) {
    when {
        k.problems.isNotEmpty() -> Chip("형식 오류", Tone.Danger)
        k.status == "untracked" -> Chip("승인 안 됨", Tone.Warn)
        k.status == "modified" -> Chip("바뀜", Tone.Danger)
        !k.enabled -> Chip("꺼짐", Tone.Neutral)
        k.shadowedBy != null -> Chip("가려짐", Tone.Neutral)
        k.scripts -> Chip("스크립트 ${k.files.count { it.startsWith("scripts/") }}", Tone.Neutral)
    }
}

private val SkillItem.needsLook: Boolean get() = status != "tracked" || problems.isNotEmpty()

// ---------------------------------------------------------------- shared page frame

@Composable
private fun SkillPage(
    title: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) { detectTapGestures { } }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(AeonIcons.Back, "이전", onBack)
            Text(title, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing?.invoke()
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        if (footer != null) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
    }
}

@Composable
private fun Heading(title: String, sub: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Label(text: String) = Text(text, Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SmallLink(text: String, onClick: () -> Unit) = Text(text, Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

/** A block of code or instructions: monospace, selectable, cut to a height until the owner asks for all of it. */
@Composable
private fun TextBlock(text: String, expanded: Boolean, maxHeight: androidx.compose.ui.unit.Dp = 150.dp) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CONTROL.dp)).background(scheme.surfaceVariant).then(if (expanded) Modifier else Modifier.heightIn(max = maxHeight)).padding(12.dp),
    ) {
        SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SkillTile(warn: Boolean, big: Boolean = false) {
    val s = LocalAeonStatus.current
    val size = if (big) 56.dp else 44.dp
    Box(Modifier.size(size).background(if (warn) s.warnSoft else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(if (big) 16.dp else 12.dp)), contentAlignment = Alignment.Center) {
        Icon(if (warn) AeonIcons.Shield else AeonIcons.Book, contentDescription = null, tint = if (warn) s.warn else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(if (big) 28.dp else 22.dp))
    }
}

@Composable
private fun SheetPanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(scheme.scrim.copy(alpha = 0.45f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = AeonShape.SHEET.dp, topEnd = AeonShape.SHEET.dp)).background(scheme.surface)
                .pointerInput(Unit) { detectTapGestures { } }.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
        )
    }
}

// ---------------------------------------------------------------- the manager

/** The skills manager: pages pushed over the whole screen, innermost on top. Back goes one page back. */
@Composable
fun SkillsHost(state: UiState, model: AppModel) {
    val sk = state.skills
    val screen = sk.screen ?: return
    // The editor of a skill's text has its own back handling and sits above these pages.
    PlatformBackHandler(enabled = sk.editing == null) { model.skillsBack() }
    when (screen) {
        SkillScreen.List -> SkillsListPage(state, model)
        SkillScreen.Add -> SkillAddPage(state, model)
        SkillScreen.Found -> SkillFoundPage(state, model)
        SkillScreen.Review -> SkillReviewPage(state, model)
        SkillScreen.Detail -> SkillDetailPage(state, model)
        SkillScreen.Read -> SkillReadPage(state, model)
        SkillScreen.Update -> SkillUpdatePage(state, model)
    }
}

// ---------------------------------------------------------------- list

@Composable
private fun SkillRow(k: SkillItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkillTile(k.needsLook)
        Column(Modifier.weight(1f)) {
            Text(k.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${scopeLabel(k.scope)} · ${sourceLabel(k)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SkillStateChip(k)
        Icon(AeonIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SkillGroup(title: String, items: List<SkillItem>, model: AppModel) {
    if (items.isEmpty()) return
    GroupHeader(title, items.size.toString())
    ListSurface {
        items.forEachIndexed { i, k ->
            if (i > 0) RowDivider(68.dp)
            SkillRow(k) { model.openSkill(k.folder, k.scope) }
        }
    }
}

@Composable
private fun SkillsListPage(state: UiState, model: AppModel) {
    val sk = state.skills
    val newer = sk.updates.filter { it.status == "update-available" }
    SkillPage(
        "스킬", onBack = model::skillsBack,
        trailing = { if (sk.canEdit) CircleButton(AeonIcons.Plus, "스킬 추가", model::openAddSkill) },
    ) {
        if (newer.isNotEmpty()) {
            val first = newer.first()
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CARD.dp)).background(LocalAeonStatus.current.accentSoft).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(AeonIcons.Refresh, contentDescription = null, tint = LocalAeonStatus.current.onAccentSoft, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text("새 버전이 있어요", style = MaterialTheme.typography.labelLarge, color = LocalAeonStatus.current.onAccentSoft)
                    Text("스킬 ${newer.size}개를 업데이트할 수 있어요", style = MaterialTheme.typography.bodySmall, color = LocalAeonStatus.current.onAccentSoft)
                }
                AeonButton("확인", { model.openSkill(first.name, first.scope) }, kind = ButtonKind.Primary, wide = true)
            }
        }
        if (!sk.canEdit && sk.loaded) Banner("이 기기는 스킬을 바꿀 수 없어요. 목록만 볼 수 있어요.", Tone.Warn, "⚠")
        if (sk.list.isEmpty()) {
            Text(
                if (!sk.loaded) "불러오는 중이에요." else "아직 스킬이 없어요. 자주 시키는 일을 에이전트에게 말하면 스킬로 만들어 줘요. GitHub나 폴더에서 가져올 수도 있어요.",
                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SkillGroup("확인이 필요해요", sk.list.filter { it.needsLook }, model)
        SkillGroup("쓰고 있어요", sk.list.filter { !it.needsLook && it.enabled }, model)
        SkillGroup("꺼져 있어요", sk.list.filter { !it.needsLook && !it.enabled }, model)
        Text("에이전트는 내가 승인한 스킬만 써요.", Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sk.checking) Text("새 버전을 찾는 중이에요…", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- add

@Composable
private fun SkillAddPage(state: UiState, model: AppModel) {
    val sk = state.skills
    val a = sk.add
    SkillPage(
        "스킬 추가", onBack = model::skillsBack,
        footer = {
            AeonButton(if (a.busy) "찾는 중…" else "찾아보기", model::inspectSkillSource, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = a.source.isNotBlank() && !a.busy && sk.canEdit)
        },
    ) {
        Heading("어디서 가져올까요?", "GitHub 저장소나 이 컴퓨터의 폴더에서 스킬을 가져와요.")
        if (!sk.canEdit) Banner("이 기기는 스킬을 가져올 수 없어요.", Tone.Warn, "⚠")
        AeonTextField(a.source, model::changeSkillSource, "주소 또는 폴더", Modifier.padding(top = 10.dp), placeholder = "anthropics/skills", mono = true, error = a.error, enabled = !a.busy)
        Text("이런 주소를 쓸 수 있어요", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("owner/repo", Tone.Neutral)
            Chip("GitHub 주소", Tone.Neutral)
            Chip("이 컴퓨터의 폴더", Tone.Neutral)
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(AeonShape.CARD.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                "찾아보기만 해서는 아무것도 설치되지 않아요. 지침과 스크립트를 전부 보여 드려요.",
                "공개 GitHub 저장소만 돼요. 개인 저장소는 지원하지 않아요.",
                "폴더는 에이전트가 켜져 있는 컴퓨터에 있는 폴더예요.",
                "설치한 스킬은 내가 승인한 내용 그대로만 쓰여요. 나중에 바뀌면 다시 물어봐요.",
            ).forEach { t ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(AeonIcons.Check, contentDescription = null, tint = LocalAeonStatus.current.ok, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SkillFoundPage(state: UiState, model: AppModel) {
    val p = state.skills.add.preview ?: return
    val s = LocalAeonStatus.current
    SkillPage("찾은 스킬", onBack = model::skillsBack) {
        Heading("찾은 스킬 ${p.candidates.size}개", null)
        Text(listOfNotNull(p.source, p.commit?.let { shortCommit(it) }).joinToString(" · "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ListSurface(Modifier.padding(top = 6.dp)) {
            p.candidates.forEachIndexed { i, c ->
                if (i > 0) RowDivider(68.dp)
                val usable = c.problems.isEmpty()
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).then(if (usable) Modifier.clickable { model.pickSkillCandidate(c.name) } else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SkillTile(!usable)
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (usable) Text(c.description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        else Text("규격에 맞지 않아 가져올 수 없어요 · ${c.problems.first()}", style = MaterialTheme.typography.labelMedium, color = s.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (usable && c.scripts) Chip("스크립트 ${c.files.count { it.path.startsWith("scripts/") }}", Tone.Neutral)
                    if (usable) Icon(AeonIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        Text("고르면 지침과 스크립트를 전부 보여 드려요. 아직 아무것도 설치되지 않았어요.", Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- review before installing

@Composable
private fun SkillReviewPage(state: UiState, model: AppModel) {
    val sk = state.skills
    val a = sk.add
    val c: SkillCandidate = a.preview?.candidates?.firstOrNull { it.name == a.pick } ?: return
    val s = LocalAeonStatus.current
    var fullMd by remember(c.name) { mutableStateOf(false) }
    var fullCode by remember(c.name) { mutableStateOf(false) }
    val clash = c.existing.any { it.scope == a.scope }
    val projectOk = sk.projectDir != null
    SkillPage(
        "설치 전 확인", onBack = model::skillsBack,
        footer = {
            a.error?.let { Banner(it, Tone.Danger, "✕") }
            AeonButton(if (a.installing) "설치하는 중…" else "설치", { model.installSkill() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !a.installing && sk.canEdit)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SkillTile(false, big = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(c.name, style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(if (sourceIsFolder(a.preview?.source ?: "")) "폴더" else "GitHub", Tone.Neutral)
                    if (c.scripts) Chip("스크립트 ${c.files.count { it.path.startsWith("scripts/") }}", Tone.Warn)
                }
            }
        }
        Text(c.description, style = MaterialTheme.typography.bodyLarge)
        c.warnings.forEach { Banner(it, Tone.Warn, "⚠") }
        Label("에이전트가 읽을 지침")
        TextBlock(c.skillMd, fullMd, 130.dp)
        SmallLink(if (fullMd) "접기" else "지침 전체 보기") { fullMd = !fullMd }
        if (c.files.size > 1) {
            Label("파일 ${c.files.size - 1}개")
            Text(c.files.filter { it.path != "SKILL.md" }.joinToString("\n") { "${it.path}  ·  ${it.size}바이트" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (c.scripts) {
            Label("스크립트 ${c.files.count { it.path.startsWith("scripts/") }}개")
            Banner("이 스킬에는 스크립트가 있어요. 설치하기 전에 코드를 읽어 보세요. 설치해도 에이전트가 쓰려 할 때만 실행돼요.", Tone.Warn, "⚠")
            c.scriptTexts.forEach { t ->
                Text(t.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                TextBlock(t.text + if (t.cut) "\n… (앞부분만 보여 줘요)" else "", fullCode, 120.dp)
            }
            if (c.scriptTexts.isNotEmpty()) SmallLink(if (fullCode) "접기" else "코드 전체 보기") { fullCode = !fullCode }
        }
        if (c.scripts) {
            Label("안전 검사")
            if (c.safety.level == "clean") {
                Banner("스크립트 ${c.safety.scanned}개를 검사했고 위험해 보이는 점을 찾지 못했어요. 정해 둔 규칙으로 찾은 결과라서, 코드를 직접 읽는 것을 대신하지는 않아요.", Tone.Ok, "✓")
                c.safety.findings.take(4).forEach { f -> Text("· ${f.file}:${f.line} ${f.what}", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CARD.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp)) {
                    AeonSwitch(a.autoRun, model::setSkillAutoRunDraft, "묻지 않고 실행 (Docker 안에서만)")
                }
                Text("Docker는 인터넷이 없고 읽기만 되는 상자예요. 이 컴퓨터에서 직접 돌릴 때는 계속 물어봐요.", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Banner("읽어 봐야 할 부분을 찾았어요. 이 스크립트는 실행할 때마다 물어봐요.", Tone.Warn, "⚠")
                c.safety.findings.filter { it.severity == "block" }.take(8).forEach { f -> Text("· ${f.file}:${f.line} ${f.what}", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = LocalAeonStatus.current.warn) }
            }
        }
        Label("설치 위치")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillChoice("내 스킬", a.scope == "user") { model.setSkillInstallScope("user") }
            if (projectOk) PillChoice("이 프로젝트", a.scope == "project") { model.setSkillInstallScope("project") }
        }
        Text("내 스킬은 모든 프로젝트에서, 이 프로젝트는 이 폴더에서만 써요.", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (clash) Banner("같은 이름의 스킬이 ${scopeLabel(a.scope)}에 이미 있어요. 설치하면 어떻게 할지 물어볼게요.", Tone.Warn, "⚠")
    }
    if (a.conflict) {
        val taken = sk.list.filter { it.scope == a.scope }.map { it.folder }
        SheetPanel(onDismiss = model::closeSkillConflict) {
            Text("같은 이름이 있어요", style = MaterialTheme.typography.titleLarge)
            Text("${c.name}은(는) 이미 ${scopeLabel(a.scope)}에 있어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AeonTextField(a.rename, model::changeSkillRename, "새 이름", Modifier.padding(top = 6.dp), mono = true, help = "소문자, 숫자, 하이픈만 쓸 수 있어요.", error = if (a.rename in taken) "이 이름도 이미 있어요." else null)
            AeonButton("이 이름으로 설치", { model.installSkill(renamed = true) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = a.rename.isNotBlank() && a.rename != c.name && a.rename !in taken && !a.installing)
            AeonButton("기존 스킬 바꿔 쓰기", { model.installSkill(replace = true) }, Modifier.fillMaxWidth(), kind = ButtonKind.Destructive, enabled = !a.installing)
            Text("바꿔 쓰면 지금 있는 ${c.name}은(는) 지워져요.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
            AeonButton("취소", model::closeSkillConflict, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        }
    }
}

// ---------------------------------------------------------------- detail

@Composable
private fun KeyValue(k: String, v: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun SkillDetailPage(state: UiState, model: AppModel) {
    val sk = state.skills
    val d = sk.detail ?: return
    val k = d.skill
    val canEdit = sk.canEdit && !d.busy
    var confirm by remember(k.folder, k.scope) { mutableStateOf(false) }
    val newer = sk.updates.any { it.name == k.folder && it.scope == k.scope && it.status == "update-available" }
    val isGithub = k.source?.type == "github"
    SkillPage(
        "스킬", onBack = model::skillsBack,
        footer = {
            if (canEdit && k.status != "tracked" && k.problems.isEmpty()) AeonButton(if (k.status == "modified") "바뀐 내용 보고 다시 승인" else "내용 보고 승인", model::readSkill, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
            if (sk.canEdit) AeonButton("삭제", { confirm = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Destructive, enabled = !d.busy)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SkillTile(k.needsLook, big = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(k.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        k.status == "tracked" -> Chip("승인됨", Tone.Ok, "✓")
                        k.status == "modified" -> Chip("바뀜", Tone.Danger)
                        else -> Chip("승인 안 됨", Tone.Warn)
                    }
                    Chip(scopeLabel(k.scope), Tone.Neutral)
                }
            }
        }
        d.error?.let { Banner(it, Tone.Danger, "✕") }
        if (k.description.isNotBlank()) Text(k.description, style = MaterialTheme.typography.bodyMedium)
        when {
            k.problems.isNotEmpty() -> Banner("스킬 형식에 맞지 않아 에이전트가 쓰지 않아요. " + k.problems.joinToString(" "), Tone.Warn, "⚠")
            k.status == "untracked" -> Banner(
                if (k.scope == "project") "이 저장소에 들어 있던 스킬이에요. 내가 읽고 승인하기 전에는 에이전트가 쓰지 않아요." else "내가 승인한 적 없는 스킬이에요. 읽고 승인하기 전에는 에이전트가 쓰지 않아요.",
                Tone.Warn, "⚠",
            )
            k.status == "modified" -> Banner("승인한 뒤에 바뀌었어요. 다시 승인하기 전에는 에이전트가 쓰지 않아요. 직접 고쳤거나, 에이전트가 만든 파일이 섞였거나, 다른 프로그램이 바꿨을 수 있어요.", Tone.Danger, "⚠")
        }
        if (k.status == "tracked") {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CARD.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp)) {
                AeonSwitch(k.enabled, { model.setSkillEnabled(it) }, "에이전트가 쓰기", enabled = canEdit)
            }
            if (k.scripts) {
                val sf = k.safety
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CARD.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp)) {
                    AeonSwitch(k.autoRun, { model.setSkillAutoRun(it) }, "묻지 않고 실행 (Docker 안에서만)", enabled = canEdit && sf?.level == "clean")
                }
                if (sf != null && sf.level != "clean") {
                    Text("안전 검사에서 읽어 볼 점이 있어서 실행할 때마다 물어봐요.", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = LocalAeonStatus.current.warn)
                    sf.findings.filter { it.severity == "block" }.take(6).forEach { f -> Text("· ${f.file}:${f.line} ${f.what}", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else if (k.autoRun) {
                    Text("안전 검사를 통과해서 Docker 안에서는 묻지 않고 실행돼요. 이 컴퓨터에서 직접 돌릴 때는 계속 물어봐요.", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (k.shadowedBy != null) Text("같은 이름의 ${scopeLabel(k.shadowedBy)} 스킬이 대신 쓰여요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (newer) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CARD.dp)).background(LocalAeonStatus.current.accentSoft).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("새 버전이 있어요", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = LocalAeonStatus.current.onAccentSoft)
                AeonButton("보기", model::previewSkillUpdate, kind = ButtonKind.Primary, wide = true, enabled = canEdit)
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AeonShape.CARD.dp)).background(MaterialTheme.colorScheme.surface).padding(vertical = 4.dp)) {
            KeyValue("출처", sourceLabel(k))
            k.source?.takeIf { it.type == "github" }?.commit?.let { KeyValue("커밋", shortCommit(it), mono = true) }
            k.version?.let { KeyValue("버전", it) }
            KeyValue("위치", scopeLabel(k.scope))
            KeyValue("파일", "${k.files.size + 1}개")
            if (k.hash.isNotEmpty()) KeyValue("내용 지문", k.hash.take(6) + "…" + k.hash.takeLast(4), mono = true)
        }
        if (k.scripts && !k.autoRun) Banner("스크립트 ${k.files.count { it.startsWith("scripts/") }}개 · 에이전트가 쓰려 할 때마다 코드를 보여 드리고 물어봐요", Tone.Warn, "⚠")
        k.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AeonButton("지침 보기", model::readSkill, Modifier.weight(1f), kind = ButtonKind.Secondary)
            if (isGithub && sk.canEdit) AeonButton("업데이트 확인", model::previewSkillUpdate, Modifier.weight(1f), kind = ButtonKind.Secondary, enabled = !d.busy)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("\"${k.name}\" 스킬을 삭제할까요?") },
            text = { Text("스킬과 안에 있는 파일이 지워져요. 에이전트는 더 이상 이 스킬을 쓰지 않아요.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { confirm = false; model.deleteSkill(k.folder, k.scope) }) { Text("삭제", color = LocalAeonStatus.current.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
        )
    }
}

// ---------------------------------------------------------------- read before approving

@Composable
private fun SkillReadPage(state: UiState, model: AppModel) {
    val sk = state.skills
    val r = sk.read ?: return
    val k = r.skill
    var full by remember(k.folder) { mutableStateOf(false) }
    val needsApproval = k.status != "tracked" && k.problems.isEmpty()
    SkillPage(
        "내용 읽기", onBack = model::skillsBack,
        footer = {
            r.error?.let { Banner(it, Tone.Danger, "✕") }
            if (needsApproval && sk.canEdit) {
                AeonButton(if (r.busy) "읽는 중…" else "이 내용으로 승인", model::trustSkill, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !r.busy)
                Text("읽은 그대로만 승인돼요. 그 사이 바뀌면 다시 읽어야 해요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (k.scope == "user" && sk.canEdit && k.status == "tracked") {
                AeonButton("고치기", { model.editSkill(k.folder, k.scope) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
            }
        },
    ) {
        Text(k.name, style = MaterialTheme.typography.titleLarge)
        Text(k.description, style = MaterialTheme.typography.bodyMedium)
        Label("에이전트가 읽을 지침")
        TextBlock(k.body.ifBlank { "(비어 있어요)" }, full, 220.dp)
        SmallLink(if (full) "접기" else "지침 전체 보기") { full = !full }
        if (k.files.isNotEmpty()) Label("파일 ${k.files.size}개")
        k.files.forEach { path ->
            val text = r.files[path]
            Text((if (path.startsWith("scripts/")) "스크립트 · " else "") + path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = if (path.startsWith("scripts/")) LocalAeonStatus.current.warn else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            TextBlock(text ?: "읽는 중…", expanded = false, maxHeight = 220.dp)
        }
        if (k.files.size > 20) Text("파일이 많아서 앞의 20개만 보여 줘요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- update

@Composable
private fun DiffRow(kind: String, path: String) {
    val tone = when (kind) { "추가" -> Tone.Ok; "삭제" -> Tone.Danger; else -> Tone.Warn }
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip(kind, tone)
        Text(path, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SkillUpdatePage(state: UiState, model: AppModel) {
    val u = state.skills.update ?: return
    val old = state.skills.detail?.skill
    var code by remember(u.preview.id) { mutableStateOf(false) }
    val cand = u.preview.candidates.firstOrNull()
    val scriptChanged = (u.diff.added + u.diff.changed).any { it.startsWith("scripts/") }
    val nothing = u.diff.added.isEmpty() && u.diff.removed.isEmpty() && u.diff.changed.isEmpty()
    SkillPage(
        "업데이트", onBack = model::skillsBack,
        footer = {
            u.error?.let { Banner(it, Tone.Danger, "✕") }
            AeonButton(if (u.busy) "업데이트하는 중…" else "업데이트", model::applySkillUpdate, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !u.busy && !nothing)
            AeonButton("나중에", model::skillsBack, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        },
    ) {
        Heading("${u.folder} 새 버전", if (nothing) "바뀐 파일이 없어요. 이미 최신이에요." else "지금 쓰는 것에서 이렇게 바뀌어요.")
        val from = shortCommit(old?.source?.commit)
        val to = shortCommit(u.preview.commit)
        if (from.isNotEmpty() || to.isNotEmpty()) Text("$from → $to · ${u.preview.source}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!nothing) {
            ListSurface(Modifier.padding(top = 6.dp)) {
                val rows = u.diff.changed.map { "바뀜" to it } + u.diff.added.map { "추가" to it } + u.diff.removed.map { "삭제" to it }
                rows.forEachIndexed { i, (kind, path) ->
                    if (i > 0) RowDivider(16.dp)
                    DiffRow(kind, path)
                }
            }
        }
        if (scriptChanged) {
            Banner("스크립트가 바뀌었어요. 코드를 읽어 본 뒤에 업데이트하세요.", Tone.Warn, "⚠")
            cand?.scriptTexts?.forEach { t ->
                Text(t.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                TextBlock(t.text + if (t.cut) "\n… (앞부분만 보여 줘요)" else "", code, 120.dp)
            }
            SmallLink(if (code) "접기" else "바뀐 코드 보기") { code = !code }
        }
    }
}
