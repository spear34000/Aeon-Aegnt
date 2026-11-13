package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonIcons
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Chip
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.UiState

/**
 * Whether this build can run the agent on the device itself. It cannot yet (decision D19): the agent is TypeScript that
 * needs Node 24, and no way to run it inside the app exists. The choice is shown anyway so nobody has to guess, and it
 * says so. When a way exists this becomes true and the same path continues to the model step.
 */
internal const val CAN_RUN_ON_THIS_DEVICE = false

private val GUIDE_COMMANDS = listOf("git clone https://github.com/spear34000/Aeon-Aegnt", "cd Aeon-Aegnt", "node src/node/cli.ts serve --host 0.0.0.0")

@Composable
private fun WhereOption(icon: ImageVector, title: String, points: List<String>, selected: Boolean, chip: String? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val s = LocalAeonStatus.current
    val shape = RoundedCornerShape(AeonShape.CARD.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(scheme.surface).border(2.dp, if (selected) scheme.primary else androidx.compose.ui.graphics.Color.Transparent, shape)
            .clickable(onClick = onClick).semantics { contentDescription = "$title, ${if (selected) "선택됨" else "선택 안 됨"}" }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(scheme.surfaceVariant, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(24.dp))
            }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (chip != null) Chip(chip, Tone.Warn)
        }
        points.forEach { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(AeonIcons.Check, contentDescription = null, tint = s.ok, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Text(p, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** 어디서 실행할까요?: the agent on this device, or on a computer (or another device) this one connects to. */
@Composable
fun WhereScreen(state: UiState, model: AppModel) {
    val haptic = LocalHapticFeedback.current
    val choice = state.setup.where
    SetupFrame(
        null, null, onBack = { model.setupBack() }, onSkip = null,
        footer = {
            AeonButton("계속", { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); model.whereContinue() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = choice != null)
        },
    ) {
        StepHeading("어디서 실행할까요?", "나중에 설정에서 바꿀 수 있어요.")
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WhereOption(
                AeonIcons.Phone, "이 기기에서 바로 쓰기",
                listOf("컴퓨터 없이 이 기기 하나로 설정해요", "AI 모델 연결부터 프로필까지 이 앱 안에서 끝나요", "기록과 파일은 이 기기에만 있어요"),
                choice == "device", chip = if (CAN_RUN_ON_THIS_DEVICE) null else "준비 중",
            ) { model.chooseWhere("device") }
            WhereOption(
                AeonIcons.Terminal, "내 컴퓨터에 연결",
                listOf("컴퓨터에서 Aeon을 켜 두고 이 기기로 시키고 승인해요", "다음 화면에서 설치부터 연결까지 안내해요", "여러 기기를 한 에이전트에 연결할 수 있어요"),
                choice == "remote",
            ) { model.chooseWhere("remote") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun GuideStep(n: Int, title: String, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(30.dp).background(scheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text(n.toString(), color = scheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** 컴퓨터에 연결하기: install and start Aeon on the computer, find the code, connect. Everything needed is on this page. */
@Suppress("DEPRECATION")
@Composable
fun GuideScreen(state: UiState, model: AppModel) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    SetupFrame(
        null, null, onBack = { model.setupBack() }, onSkip = null,
        footer = { AeonButton("주소와 코드 입력하기", { model.guideContinue() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary) },
    ) {
        StepHeading("컴퓨터에 연결하기", "세 단계면 돼요. 컴퓨터와 이 기기는 같은 Wi-Fi에 있어야 해요.")
        GuideStep(1, "컴퓨터에서 Aeon 켜기") {
            Body("Node.js 24 이상이 필요해요. 터미널을 열고 아래를 차례로 실행하세요.")
            Column(Modifier.fillMaxWidth().background(scheme.onSurface, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GUIDE_COMMANDS.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = scheme.background) }
            }
            Text(
                if (copied) "복사했어요" else "복사", Modifier.clip(CircleShape).background(scheme.surfaceVariant)
                    .clickable { clipboard.setText(AnnotatedString(GUIDE_COMMANDS.joinToString("\n"))); copied = true; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        GuideStep(2, "코드와 QR 확인") {
            Body("실행하면 화면에 연결 코드와 QR이 나와요. 코드는 5분 동안 한 번만 쓸 수 있어요. 지났으면 터미널에 pair를 입력해 새로 받아요.")
        }
        GuideStep(3, "이 기기로 연결") {
            Body("QR은 이 기기의 카메라 앱으로 스캔하면 Aeon이 열리고 주소와 코드가 채워져요. 직접 입력해도 돼요.")
        }
        Text(
            if (help) "접기" else "연결이 안 되나요?", Modifier.padding(top = 24.dp).clip(RoundedCornerShape(8.dp)).clickable { help = !help }.padding(4.dp),
            style = MaterialTheme.typography.labelLarge, color = scheme.primary,
        )
        if (help) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Help("같은 Wi-Fi인가요?", "컴퓨터와 이 기기가 같은 공유기에 연결돼 있어야 해요. 게스트 Wi-Fi는 기기끼리 막는 경우가 있어요.")
                Help("주소가 맞나요?", "컴퓨터 화면에 Other devices connect to로 나온 주소를 써요. 여러 개면 192.168로 시작하는 것부터 해 보세요.")
                Help("방화벽이 막고 있나요?", "윈도우는 처음 실행할 때 나오는 네트워크 허용 창에서 ‘허용’을 눌러 주세요. 7421번 포트를 열어야 해요.")
                Help("코드가 안 받아져요", "코드는 5분 동안 한 번만 써요. 터미널에 pair를 입력하면 새 코드가 나와요.")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Help(q: String, a: String) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(AeonShape.CARD.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(q, style = MaterialTheme.typography.titleMedium)
        Body(a)
    }
}

/** What "이 기기에서 바로 쓰기" says while the agent cannot run on the device yet: the truth, and the way that works now. */
@Composable
fun DeviceScreen(state: UiState, model: AppModel) {
    SetupFrame(
        null, null, onBack = { model.setupBack() }, onSkip = null,
        footer = { AeonButton("컴퓨터에 연결하기", { model.chooseWhere("remote"); model.whereContinue() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary) },
    ) {
        StepHeading("이 기기에서 실행", "아직 만들고 있는 기능이에요.")
        Column(Modifier.padding(top = 22.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(AeonShape.CARD.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("지금은 이렇게 쓸 수 있어요", style = MaterialTheme.typography.titleMedium)
            Body("다른 컴퓨터에서 Aeon을 켜고 이 기기로 연결하면 이 기기에서 시키고 승인할 수 있어요. 이 기기만으로 쓰는 방법은 준비되는 대로 여기에 나와요.")
        }
    }
}

/**
 * AI 고르기: shown right after connecting when the agent has no model yet (a new computer, or the agent on this
 * device). Pick a provider with its logo, give the key, choose the model: the same sheet as in the app. Skipped
 * silently when the agent already has a working model.
 */
@Composable
fun ModelStepScreen(state: UiState, model: AppModel) {
    val cur = state.models.current
    val ready = state.status != null && state.status.setupProblem == null && cur != null
    LaunchedEffect(Unit) { model.loadModels() }
    LaunchedEffect(ready) { if (ready && !state.models.open) model.finishModelStep() }
    SetupFrame(
        null, null, onBack = { model.setupBack() }, onSkip = { model.finishModelStep() },
        footer = {
            AeonButton("AI 고르기", { model.openModelSheet() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
            AeonButton("나중에 하기", { model.finishModelStep() }, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
        },
    ) {
        StepHeading("어떤 AI를 쓸까요?", "에이전트가 쓸 모델을 골라요. 키는 이 기기와 에이전트 사이에서만 암호화해서 보내요.")
        state.status?.setupProblem?.let { Text(it, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = LocalAeonStatus.current.fg3) }
        Spacer(Modifier.height(16.dp))
    }
}
