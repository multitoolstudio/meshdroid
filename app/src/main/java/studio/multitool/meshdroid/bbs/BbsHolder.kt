package studio.multitool.meshdroid.bbs

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import studio.multitool.meshdroid.DaemonState
import studio.multitool.meshdroid.NodeStatus
import studio.multitool.meshdroid.Profile
import studio.multitool.meshdroid.Settings

/**
 * Process-wide owner of the BBS.
 *
 * The engine follows three things: the setting, whether the node is running, and whether
 * the active profile can carry a BBS at all. It has no node to talk to when the daemon is
 * down, and reconnecting forever against a stopped daemon would be pointless work on
 * battery.
 *
 * The profile matters as much as the other two. The engine's link is Meshtastic's TCP API
 * on 4403, so under MeshCore it would retry against a port nothing is listening on, with
 * the BBS tab hidden and no way for the operator to stop it. Hiding a tab keeps a user off
 * a screen; it does not stop the object behind it running.
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
            combine(DaemonState.status, Profile.selected) { status, profile -> status to profile }
                .collect { (status, profile) ->
                    apply(e, ctx, status, profile)
                }
        }
    }

    /** Called when the switch changes, since neither flow may emit again. */
    fun refresh(ctx: Context) {
        apply(engine(ctx), ctx, DaemonState.status.value, Profile.selected.value)
    }

    private fun apply(e: BbsEngine, ctx: Context, status: NodeStatus, profile: Profile) {
        val shouldRun = status == NodeStatus.RUNNING && profile.supportsBbs && Settings.bbsEnabled(ctx)
        if (shouldRun && !e.running.value) e.start()
        if (!shouldRun && e.running.value) e.stop()
    }
}
