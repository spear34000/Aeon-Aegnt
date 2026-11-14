package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonSwitch
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Card
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.PermissionController
import dev.aeon.ui.platform.PermissionStatus
import dev.aeon.ui.platform.rememberPermissionController
import dev.aeon.ui.platform.supportsBackgroundCheck
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.UiState

/** One thing the owner is asked to agree to. Every sentence describes what the software really does. */
internal class Term(val id: String, val required: Boolean, val title: String, val summary: String, val body: String)

internal val TERMS = listOf(
    Term(
        "service", true, "이용 안내", "승인한 일의 결과는 내 책임이에요",
        "1. Aeon은 Apache-2.0 라이선스의 오픈소스 소프트웨어이며, 어떤 보증도 없이 있는 그대로 제공됩니다.\n\n" +
            "2. 에이전트는 서버를 시작할 때 정한 작업 폴더 안에서만 파일을 다룹니다. 삭제, 명령 실행, 외부로 보내기처럼 되돌릴 수 없거나 위험한 동작은 내가 승인한 뒤에만 실행합니다.\n\n" +
            "3. 승인하면 그 동작은 실제로 실행됩니다. Aeon에는 실행한 동작을 되돌리는 기능이 없습니다. 승인한 동작의 결과는 내 책임입니다.\n\n" +
            "4. 모델의 답과 계획은 틀릴 수 있습니다. ‘확인됨’ 표시는 실제 파일을 검사한 결과만 뜻하고, 에이전트의 ‘완료’ 보고는 에이전트의 말입니다.",
    ),
    Term(
        "data", true, "데이터 처리 안내", "기록은 내 기기에, 요청은 고른 제공자에게",
        "1. 대화, 작업 기록, 설정은 Aeon 서버를 실행하는 내 기기에 저장됩니다. Aeon 개발자에게는 아무것도 보내지 않습니다.\n\n" +
            "2. 내 요청과 에이전트가 읽은 파일 내용은 내가 고른 모델 제공자에게 전송됩니다. 내 기기 밖의 제공자는 처음 고를 때 한 번 동의를 받습니다. 개인화에 적은 내용도 함께 전송됩니다.\n\n" +
            "3. 이 앱과 서버 사이 연결은 요청마다 서명되지만, HTTP 주소로 연결하면 내용은 암호화되지 않습니다. 같은 네트워크에 있는 사람이 대화 내용을 볼 수 있으니 믿을 수 있는 네트워크에서만 쓰세요. API 키와 연결 코드는 따로 암호화해서 보냅니다.\n\n" +
            "4. API 키는 서버 기기의 보안 저장소에 저장되고 다시 표시되지 않습니다.",
    ),
    Term(
        "online", false, "온라인 목록·로고 조회", "제공자·연결 목록과 로고를 인터넷에서 가져옵니다",
        "동의하면 서버 기기가 다음 사이트에 요청을 보냅니다.\n\n" +
            "· models.dev: 모델 제공자와 모델 목록\n· MCP 공식 레지스트리: 추가할 수 있는 연결 목록(검색어 포함)\n· jsDelivr, 각 서비스의 웹사이트, GitHub: 로고\n\n" +
            "이 사이트들은 서버 기기의 IP 주소와 요청 내용을 알게 됩니다. 동의하지 않으면 기본 제공 항목과 이미 받아 둔 목록만 보입니다.\n\n" +
            "이 설정은 서버 전체에 적용되고, 설정을 바꿀 수 있는 기기에서만 바뀝니다.",
    ),
    Term(
        "background", false, "백그라운드 승인 확인", "앱을 닫아도 15분쯤마다 승인 대기를 확인합니다",
        "동의하면 이 기기가 앱을 닫은 동안에도 15분쯤마다 서버에 승인을 기다리는 일이 있는지 묻고, 새로 생기면 알림을 보냅니다.\n\n" +
            "알림에는 건수만 표시합니다. 내용은 앱을 열어야 보입니다. 배터리와 데이터를 조금 씁니다. 서버와 같은 네트워크에 있을 때만 동작합니다.\n\n" +
            "알림을 보려면 알림 권한도 허용해야 합니다.",
    ),
)

/** Terms that exist on this platform (the background check is Android only). */
internal fun termsHere(): List<Term> = TERMS.filter { it.id != "background" || supportsBackgroundCheck }

@Composable
internal fun CheckBox(checked: Boolean, label: String, modifier: Modifier = Modifier, onChange: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.size(44.dp).then(if (onChange != null) Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onChange) else Modifier)
            .semantics { role = Role.Checkbox; contentDescription = "$label, ${if (checked) "선택됨" else "선택 안 됨"}" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (checked) scheme.primary else scheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (checked) Icon(AeonIcons.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun TermDialog(term: Term, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(term.title) },
        text = { Text(term.body, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}

/** Permission rows: why it is wanted, its state, and one button that asks or opens the system settings. */
@Composable
internal fun PermissionRows(controller: PermissionController) {
    val scheme = MaterialTheme.colorScheme
    if (controller.items.isEmpty()) {
        Text("이 기기에서는 허용할 권한이 없어요.", Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        return
    }
    ListSurface {
        controller.items.forEachIndexed { i, item ->
            if (i > 0) RowDivider(16.dp)
            val status = controller.status(item.id)
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(item.reason, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    if (status == PermissionStatus.Blocked) Text("시스템 설정에서만 켤 수 있습니다.", style = MaterialTheme.typography.labelMedium, color = LocalAeonStatus.current.warn)
                }
                when (status) {
                    PermissionStatus.Granted -> Chip("허용됨", Tone.Ok)
                    PermissionStatus.Denied -> AeonButton("허용", { controller.request(item.id) }, kind = ButtonKind.Secondary, wide = true)
                    PermissionStatus.Blocked -> AeonButton("설정 열기", { controller.openSettings() }, kind = ButtonKind.Secondary, wide = true)
                }
            }
        }
    }
}

/** 내 에이전트 › 약관·권한: everything from the first run can be seen and changed again here. */
@Composable
fun LegalPage(state: UiState, model: AppModel) {
    val s = LocalAeonStatus.current
    val controller = rememberPermissionController()
    var open by remember { mutableStateOf<Term?>(null) }
    var withdraw by remember { mutableStateOf(false) }
    val consents = state.setup.consents
    val online = state.config?.onlineLookups != false

    Text("권한", style = MaterialTheme.typography.labelLarge, color = s.fg3)
    PermissionRows(controller)

    Text("공유와 알림", style = MaterialTheme.typography.labelLarge, color = s.fg3)
    Card {
        AeonSwitch(online, { model.setOnlineLookups(it) }, "온라인 목록·로고 조회", enabled = state.isAdmin && !state.busy)
        Text(if (state.isAdmin) "서버 전체에 적용됩니다." else "설정을 바꿀 수 있는 기기에서만 바뀝니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
        AeonButton("내용 보기", { open = TERMS.first { it.id == "online" } }, kind = ButtonKind.Ghost, wide = true)
    }
    if (supportsBackgroundCheck) {
        Card {
            AeonSwitch(consents.background, { model.setBackgroundConsent(it) }, "백그라운드 승인 확인")
            Text("이 기기에만 적용됩니다. 알림 권한이 꺼져 있으면 알림은 오지 않습니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
            AeonButton("내용 보기", { open = TERMS.first { it.id == "background" } }, kind = ButtonKind.Ghost, wide = true)
        }
    }

    Text("약관", style = MaterialTheme.typography.labelLarge, color = s.fg3)
    Card {
        TERMS.filter { it.required }.forEach { t ->
            Row(Modifier.fillMaxWidth().clickable { open = t }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                    Text(t.summary, style = MaterialTheme.typography.labelMedium, color = s.fg3)
                }
                Icon(AeonIcons.Chevron, contentDescription = null, tint = s.fg3, modifier = Modifier.size(18.dp))
            }
        }
        Text(if (consents.acceptedAt > 0) "동의한 날: ${dev.aeon.ui.platform.formatDateTime(consents.acceptedAt).take(10)}" else "이 기기는 최초 설정이 생기기 전에 연결되어 동의 기록이 없습니다.", style = MaterialTheme.typography.bodySmall, color = s.fg3)
        AeonButton("동의 철회", { withdraw = true }, kind = ButtonKind.Destructive, wide = true)
    }

    Text("다시 보기", style = MaterialTheme.typography.labelLarge, color = s.fg3)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AeonButton("앱 소개", { model.replayIntro() }, kind = ButtonKind.Secondary, wide = true)
        AeonButton("사용법 안내", { model.replayCoach() }, kind = ButtonKind.Secondary, wide = true)
    }

    open?.let { TermDialog(it) { open = null } }
    if (withdraw) {
        AlertDialog(
            onDismissRequest = { withdraw = false },
            title = { Text("동의를 철회할까요?") },
            text = { Text("철회하면 약관 화면으로 돌아가고, 다시 동의하기 전까지 쓸 수 없어요. 대화와 설정은 지워지지 않아요.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { withdraw = false; model.withdrawTerms() }) { Text("철회", color = s.danger) } },
            dismissButton = { TextButton(onClick = { withdraw = false }) { Text("취소") } },
        )
    }
}
