package studio.multitool.meshdroid

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import studio.multitool.meshdroid.mesh.LinkStatus
import studio.multitool.meshdroid.mesh.MeshLink
import java.util.concurrent.TimeUnit

/**
 * Publishes phone sensor readings to the mesh as environment telemetry.
 *
 * This goes over the mesh link rather than through a firmware patch, which is why it costs
 * nothing on a firmware bump: the app builds the same Telemetry packet a sensor-equipped
 * node would send. The battery path predates the link and still uses patch 0005, because
 * there was no other route at the time.
 *
 * The consequence, until the multiplexing proxy exists, is that publishing only happens
 * while the Mesh tab link is connected, and the link holds the daemon's single client slot.
 * Turning this on does not connect the link by itself.
 */
class EnvironmentReporter(private val ctx: Context, private val link: MeshLink) {

    data class Published(val pressureHpa: Float?, val lux: Float?, val atSeconds: Long)

    private val _last = MutableStateFlow<Published?>(null)
    val last: StateFlow<Published?> = _last

    /**
     * Publishes on the configured interval for as long as the scope lives. A cycle where
     * the link is down, or nothing is enabled, or no sensor answers, sends nothing and
     * waits for the next one.
     */
    fun run(scope: CoroutineScope) = scope.launch {
        while (true) {
            val intervalMs = TimeUnit.MINUTES.toMillis(Settings.envIntervalMinutes(ctx).toLong())
            delay(intervalMs)
            if (link.status.value != LinkStatus.CONNECTED) continue

            val pressure = if (Settings.envPressure(ctx)) PhoneSensors.readOnce(ctx, PhoneSensors.Kind.PRESSURE) else null
            val lux = if (Settings.envLux(ctx)) PhoneSensors.readOnce(ctx, PhoneSensors.Kind.LIGHT) else null
            if (pressure == null && lux == null) continue

            runCatching { link.sendEnvironment(pressureHpa = pressure, lux = lux) }
                .onSuccess {
                    _last.value = Published(pressure, lux, System.currentTimeMillis() / 1000)
                    DaemonState.appendLog(
                        "[meshdroid] published environment telemetry" +
                            (pressure?.let { " pressure=%.1f hPa".format(it) } ?: "") +
                            (lux?.let { " lux=%.0f".format(it) } ?: "")
                    )
                }
                .onFailure { DaemonState.appendLog("[meshdroid] could not publish telemetry: ${it.message}") }
        }
    }
}
