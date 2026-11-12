package dev.aeon.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.aeon.ui.api.AeonClient
import dev.aeon.ui.platform.AppContext
import dev.aeon.ui.platform.createDeviceCrypto
import dev.aeon.ui.platform.createKeyValueStore
import dev.aeon.ui.screens.AppRoot
import dev.aeon.ui.screens.PhoneLayout
import dev.aeon.ui.state.AppModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the single AppModel so it survives configuration changes such as rotation. */
class AeonApplication : Application() {
    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
        val crypto = createDeviceCrypto()
        model = AppModel(
            AeonClient("http://invalid.local", crypto, HttpClient()), // replaced when the user pairs or a saved pairing is restored
            createKeyValueStore(),
            crypto,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            // 127.0.0.1 on a phone is the phone itself, so there is no meaningful default: the user enters the PC's address.
            defaultServerUrl = "",
        )
    }
}

/**
 * Phone shell: bottom navigation and one pane at a time. Polling runs only while the app is visible, so an idle
 * app does not wake the radio or drain the battery (push notifications arrive with Phase 6).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // the app draws its own background under the status and navigation bars
        val model = (application as AeonApplication).model
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = model.start()
            override fun onStop(owner: LifecycleOwner) = model.stop()
        })
        openFrom(intent)
        setContent {
            AppRoot(model) { s, m -> PhoneLayout(s, m) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** A tap on the approval notification lands on 피드. */
    private fun openFrom(intent: android.content.Intent?) {
        val model = (application as AeonApplication).model
        if (intent?.getStringExtra(dev.aeon.ui.platform.EXTRA_OPEN) == "feed") model.go(dev.aeon.ui.state.Section.Feed)
        if (intent?.action == android.content.Intent.ACTION_VIEW && intent.data?.scheme == "aeon") {
            model.receivePairLink(intent.data.toString())
            intent.data = null // handled: a rotation must not apply the same link again
        }
    }
}
