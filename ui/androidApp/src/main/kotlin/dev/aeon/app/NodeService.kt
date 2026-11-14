package dev.aeon.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import dev.aeon.ui.platform.NodeHost
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the on-device agent (Node.js for Mobile) in its own process, `:node`. Node can end its whole process (exit, a
 * fatal error, a native crash), and when it shared the app's process that took the screen down with it. Here only this
 * process goes; the app notices that the agent does not answer and says so. Node can be started once per process, so
 * when it returns this process ends too and the next start gets a fresh one.
 */
class NodeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val args = intent?.getStringArrayExtra("args")
        val tmp = intent?.getStringExtra("tmp")
        if (args != null && tmp != null && started.compareAndSet(false, true)) {
            // Node needs far more stack than a default Java thread (1 MB): 8 MB is safe.
            Thread(null, {
                try {
                    // The native library is loaded by touching this (it is only loaded on first use, and this process is new).
                    check(NodeHost.loaded) { "libaeonnode.so could not be loaded" }
                    val code = NodeHost.start(args, tmp)
                    Log.e("AeonNode", "node returned $code")
                } catch (t: Throwable) {
                    Log.e("AeonNode", "node stopped", t)
                } finally {
                    Process.killProcess(Process.myPid())
                }
            }, "aeon-node", 8L * 1024 * 1024).start()
        }
        return START_NOT_STICKY
    }

    private companion object {
        val started = AtomicBoolean(false)
    }
}
