package studio.multitool.meshdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Publishes the phone's battery state to the daemon.
 *
 * meshtasticd on a Linux box has no battery, so it reports itself as USB powered and the
 * mesh sees no charge level for the node. On a phone the battery is the interesting number:
 * other nodes can see how long this one is likely to last.
 *
 * The state goes through a file rather than the API, because the daemon reads it at its own
 * pace and nothing has to stay connected. Firmware patch 0005 adds a battery source that
 * reads the path in MESHTASTICD_BATTERY_FILE. The format is one line of key=value pairs:
 *
 *     pct=87 mv=3980 charging=1 powered=1 ts=1757390000
 *
 * ACTION_BATTERY_CHANGED is a sticky broadcast that fires on every percent change and on
 * every plug or unplug, so writes are rare. The heartbeat exists only to refresh ts: the
 * daemon treats anything older than three minutes as "no battery", which is how it notices
 * that this app has stopped or crashed rather than reporting a stale level to the mesh.
 */
class BatteryReporter(private val ctx: Context, private val file: File) {

    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "battery-heartbeat").apply { isDaemon = true }
    }
    private var heartbeat: ScheduledFuture<*>? = null
    @Volatile private var last: Reading? = null

    data class Reading(val percent: Int, val millivolts: Int, val charging: Boolean, val powered: Boolean)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) write(read(intent))
        }
    }

    fun start() {
        // registerReceiver returns the sticky ACTION_BATTERY_CHANGED intent, so the first
        // reading is available without waiting for the next change.
        val sticky = ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) write(read(sticky))
        heartbeat = timer.scheduleWithFixedDelay({ last?.let { write(it) } }, HEARTBEAT_S, HEARTBEAT_S, TimeUnit.SECONDS)
    }

    /**
     * Stops publishing and removes the file, so a daemon that outlives this reporter falls
     * back to reporting itself as USB powered instead of holding the last known level.
     */
    fun stop() {
        heartbeat?.cancel(false); heartbeat = null
        timer.shutdownNow()
        runCatching { ctx.unregisterReceiver(receiver) }
        runCatching { file.delete() }
        DaemonState.setBattery(null)
    }

    private fun read(intent: Intent): Reading {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        // EXTRA_VOLTAGE is millivolts on most phones, volts on a few, and absent on some.
        // Anything outside a single cell's range is dropped and the daemon substitutes a
        // mid-charge value; the percentage is what gets published either way.
        val raw = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val mv = when {
            raw in 2500..5000 -> raw
            raw in 3..5 -> raw * 1000
            else -> 0
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val powered = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return Reading(percent, mv, charging, powered)
    }

    // The broadcast receiver runs on the main thread and the heartbeat on its own, and
    // writeAtomically stages through one fixed temporary name. Two writers at once means
    // the second rename finds its source already moved, so writes are serialised here.
    private val writeLock = Any()

    private fun write(r: Reading) {
        if (r.percent < 0) return
        last = r
        val line = "pct=${r.percent} mv=${r.millivolts} charging=${if (r.charging) 1 else 0} " +
            "powered=${if (r.powered) 1 else 0} ts=${System.currentTimeMillis() / 1000}\n"
        synchronized(writeLock) {
            runCatching { NodeFiles.writeAtomically(file, line.toByteArray()) }
                .onFailure { DaemonState.appendLog("[meshdroid] could not write the battery file: ${it.message}") }
        }
        DaemonState.setBattery(r)
    }

    companion object {
        private const val HEARTBEAT_S = 30L
        fun fileFor(ctx: Context): File = File(ctx.filesDir, "battery")
    }
}
