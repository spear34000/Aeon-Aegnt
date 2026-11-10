package dev.aeon.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.aeon.ui.api.AeonClient
import dev.aeon.ui.platform.createDeviceCrypto
import dev.aeon.ui.platform.createKeyValueStore
import dev.aeon.ui.platform.pageOrigin
import dev.aeon.ui.screens.AdaptiveLayout
import dev.aeon.ui.screens.AppRoot
import dev.aeon.ui.state.AppModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Browser entry point. The UI is served by Aeon itself, so the API address defaults to the page's own origin. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val crypto = createDeviceCrypto()
    val store = createKeyValueStore()
    val origin = pageOrigin()
    if (store.get("serverUrl") == null) store.put("serverUrl", origin)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val model = AppModel(AeonClient(origin, crypto, HttpClient()), store, crypto, scope)
    model.start()
    ComposeViewport {
        AppRoot(model) { s, m -> AdaptiveLayout(s, m) }
    }
}
