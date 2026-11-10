package dev.aeon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.Signing
import dev.aeon.ui.design.AeonButton
import dev.aeon.ui.design.AeonShape
import dev.aeon.ui.design.AeonTextField
import dev.aeon.ui.design.Banner
import dev.aeon.ui.design.ButtonKind
import dev.aeon.ui.design.Card
import dev.aeon.ui.design.LocalAeonStatus
import dev.aeon.ui.design.Tone
import dev.aeon.ui.platform.pageAllowsSigning
import dev.aeon.ui.platform.pageHostLabel
import dev.aeon.ui.state.AppModel
import dev.aeon.ui.state.Connection
import dev.aeon.ui.state.UiState

const val PAIR_CODE_LEN = 16

private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

/**
 * True when the address is plain HTTP to a host other than this PC. Parsed with a real URL parser (not substring
 * matching). Anything that does not parse is treated as remote and plain, which is the safe side.
 */
fun isPlainRemoteHttp(raw: String): Boolean {
    val parsed = runCatching { io.ktor.http.Url(raw.trim()) }.getOrNull() ?: return true
    val plain = parsed.protocol == io.ktor.http.URLProtocol.HTTP
    return plain && parsed.host.lowercase() !in LOCAL_HOSTS
}

/** Groups the pairing code as XXXX-XXXX-XXXX-XXXX while typing; ignores dashes, spaces and case. */
fun formatPairCode(raw: String): String {
    val n = Signing.normalizeCode(raw).take(PAIR_CODE_LEN)
    return n.chunked(4).joinToString("-")
}

/**
 * 연결 (pairing), design 04 + 09-2. Three steps; step 1 (run the server) is shown as instructions here,
 * and step 3 (model key) is handled by the setup banner after pairing.
 */
@Composable
fun ConnectScreen(state: UiState, model: AppModel, modifier: Modifier = Modifier) {
    if (!pageAllowsSigning()) {
        WebBlocked(modifier)
        return
    }
    var url by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var code by remember { mutableStateOf("") }
    var acceptHttp by remember { mutableStateOf(false) }
    val s = LocalAeonStatus.current
    val plainHttp = isPlainRemoteHttp(url)
    val codeReady = Signing.normalizeCode(code).length == PAIR_CODE_LEN
    val canConnect = codeReady && (!plainHttp || acceptHttp) && state.connection !is Connection.Connecting && !state.busy

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("2 / 3 · PC에 연결", style = MaterialTheme.typography.labelMedium, color = s.fg3)
            Text("PC의 Aeon 서버에 연결", style = MaterialTheme.typography.displaySmall)
            Text(
                "PC에서 서버를 켜세요: node src/node/cli.ts serve. 서버 창에 보이는 주소와 코드를 입력하세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AeonTextField(
                value = url,
                onValueChange = { url = it },
                label = "서버 주소",
                placeholder = "http://192.168.0.12:7421",
                help = "PC 화면의 Aeon 서버 창에 표시됩니다.",
                error = if (url.isNotBlank() && !url.startsWith("http")) "주소 앞에 http:// 를 붙여 주세요." else null,
                enabled = state.connection !is Connection.Connecting,
            )
            if (plainHttp) {
                Banner("암호화되지 않은 연결입니다. 집처럼 믿을 수 있는 와이파이에서만 쓰세요.", Tone.Warn, "⚠")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { acceptHttp = !acceptHttp }) {
                    Box(
                        Modifier.size(20.dp).border(1.5.dp, if (acceptHttp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                            .background(if (acceptHttp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (acceptHttp) Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text("위험을 알고 계속합니다", style = MaterialTheme.typography.bodyMedium)
                }
            }
            AeonTextField(
                value = code,
                onValueChange = { code = Signing.normalizeCode(it).take(PAIR_CODE_LEN) },
                label = "일회용 코드",
                placeholder = "XXXX-XXXX-XXXX-XXXX",
                help = "코드는 한 번만 쓸 수 있습니다. 5분 뒤 만료됩니다. 하이픈은 자동으로 들어갑니다. 붙여 넣기 가능.",
                mono = true,
                group = 4,
                enabled = state.connection !is Connection.Connecting,
            )
            state.message?.let { Banner(it, Tone.Danger, "✕", model::dismissMessage) }
            AeonButton(
                if (state.connection is Connection.Connecting || state.busy) "연결 중…" else "연결",
                onClick = { model.pair(url, code) },
                kind = ButtonKind.Primary,
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Shown when the page is loaded from an address that cannot sign requests (design: 웹 UI는 PC 주소에서만). */
@Composable
private fun WebBlocked(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Card(Modifier.widthIn(max = 440.dp)) {
            Text("이 주소에서는 브라우저로 연결할 수 없습니다.", style = MaterialTheme.typography.titleMedium)
            Text(
                "현재 주소: ${pageHostLabel()}. 브라우저 보안 때문에 PC 자신(127.0.0.1)이나 https 주소에서만 연결할 수 있습니다. 폰에서는 Aeon Android 앱을 쓰세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("서버 주소: 127.0.0.1:7421", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
