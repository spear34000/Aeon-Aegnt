package dev.aeon.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.aeon.ui.api.AeonClient
import dev.aeon.ui.platform.createDeviceCrypto
import dev.aeon.ui.platform.createKeyValueStore
import dev.aeon.ui.screens.AppRoot
import dev.aeon.ui.screens.AdaptiveLayout
import dev.aeon.ui.state.AppModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Windows (and other desktop) entry point: always the wide layout with a permanent sidebar. */
fun main() = application {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val model = remember {
        val crypto = createDeviceCrypto()
        AppModel(AeonClient("http://127.0.0.1:7421", crypto, HttpClient()), createKeyValueStore(), crypto, scope)
    }
    DisposableEffect(Unit) {
        model.start()
        onDispose { model.stop(); scope.cancel() }
    }
    val state by model.state.collectAsState()
    val waiting = state.approvals.size
    Window(
        onCloseRequest = ::exitApplication,
        title = if (waiting > 0) "Aeon — $waiting waiting for your approval" else "Aeon",
        state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
    ) {
        window.minimumSize = java.awt.Dimension(900, 560)
        AppRoot(model) { s, m -> AdaptiveLayout(s, m) }
    }
}
