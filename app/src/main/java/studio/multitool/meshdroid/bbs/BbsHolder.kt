package studio.multitool.meshdroid.bbs

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import studio.multitool.meshdroid.DaemonState
import studio.multitool.meshdroid.NodeStatus
import studio.multitool.meshdroid.Settings

/**
 * Process-wide owner of the BBS.
 *
 * The engine follows two things: the setting, and whether the node is running. It has no
 * node to talk to when the daemon is down, and reconnecting forever against a stopped
 * daemon would be pointless work on battery.
 */
object BbsHolder {
    @Volatile private var engine: BbsEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watching = false

    fun engine(ctx: Context): BbsEngine =
        engine ?: synchronized(this) {
            engine ?: BbsEngine(ctx.applicationContext).also { engine = it }
        }

    fun init(ctx: Context) {
        val e = engine(ctx)
        if (watching) return
        watching = true
        scope.launch {
            DaemonState.status.collect { status ->
                val shouldRun = status == NodeStatus.RUNNING && Settings.bbsEnabled(ctx)
                if (shouldRun && !e.running.value) e.start()
                if (!shouldRun && e.running.value) e.stop()
            }
        }
    }

    /** Called when the switch changes, since the status flow may not emit again. */
    fun refresh(ctx: Context) {
        val e = engine(ctx)
        val shouldRun = DaemonState.status.value == NodeStatus.RUNNING && Settings.bbsEnabled(ctx)
        if (shouldRun && !e.running.value) e.start()
        if (!shouldRun && e.running.value) e.stop()
    }
}
