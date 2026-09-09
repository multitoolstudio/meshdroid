package studio.multitool.meshdroid.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.content.Context
import studio.multitool.meshdroid.DaemonState
import studio.multitool.meshdroid.EnvironmentReporter
import studio.multitool.meshdroid.NodeStatus

/**
 * Process-wide owner of the mesh link, so the connection survives tab switches and
 * configuration changes rather than living inside a composable.
 *
 * Also keeps a capped history of what the link has seen. The Mesh tab renders that
 * history; the BBS will subscribe to the link's event flow directly rather than reading
 * from here, since it needs every event and not just the recent ones.
 */
object MeshLinkHolder {
    private const val HISTORY_MAX = 500

    val link: MeshLink = MeshtasticTcpLink()

    private val _history = MutableStateFlow<List<MeshEvent>>(emptyList())
    val history: StateFlow<List<MeshEvent>> = _history

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    /** Set once init() has an application context to read sensors and settings with. */
    var environment: EnvironmentReporter? = null
        private set

    /**
     * Collects events into the history, and drops the link when the node stops. Without
     * that second part the reconnect loop would sit there widening its backoff against a
     * daemon that is not coming back until the user starts it again.
     */
    fun init(ctx: Context) {
        if (started) return
        started = true
        environment = EnvironmentReporter(ctx.applicationContext, link).also { it.run(scope) }
        scope.launch {
            link.events.collect { e ->
                _history.value = (_history.value + e).let { if (it.size > HISTORY_MAX) it.drop(it.size - HISTORY_MAX) else it }
            }
        }
        scope.launch {
            DaemonState.status.collect { s ->
                if (s != NodeStatus.RUNNING && link.status.value != LinkStatus.DISCONNECTED) link.stop()
            }
        }
    }

    fun connect() = link.start()
    fun disconnect() = link.stop()
    fun clearHistory() { _history.value = emptyList() }
}
