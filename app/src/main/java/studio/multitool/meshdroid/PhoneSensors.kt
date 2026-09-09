package studio.multitool.meshdroid

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The phone's environment sensors, probed at runtime.
 *
 * Which of these a phone has varies enormously. A barometer is common on mid-range and
 * better hardware, an ambient light sensor is close to universal, and ambient temperature
 * and humidity are on almost nothing made after about 2015. So the app asks the phone what
 * it has rather than assuming, and offers only what came back.
 *
 * Readings are taken on demand: register a listener, take the first value, unregister. A
 * standing registration would keep the sensor hub awake between publications, which is a
 * poor trade for a value sent every fifteen minutes.
 */
object PhoneSensors {

    enum class Kind(val sensorType: Int, val label: String, val unit: String) {
        PRESSURE(Sensor.TYPE_PRESSURE, "Barometric pressure", "hPa"),
        LIGHT(Sensor.TYPE_LIGHT, "Ambient light", "lx"),
        TEMPERATURE(Sensor.TYPE_AMBIENT_TEMPERATURE, "Ambient temperature", "\u00b0C"),
        HUMIDITY(Sensor.TYPE_RELATIVE_HUMIDITY, "Relative humidity", "%")
    }

    /** The kinds this phone actually has, in the order they are shown. */
    fun available(ctx: Context): List<Kind> {
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return emptyList()
        return Kind.entries.filter { sm.getDefaultSensor(it.sensorType) != null }
    }

    /** Vendor and model of the underlying sensor, for the capability list. */
    fun describe(ctx: Context, kind: Kind): String? {
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return null
        val s = sm.getDefaultSensor(kind.sensorType) ?: return null
        return listOfNotNull(s.vendor?.ifBlank { null }, s.name?.ifBlank { null }).joinToString(" ")
    }

    /**
     * One reading, or null if the phone has no such sensor or it does not report within
     * the timeout. A sensor that is present but never fires is a real case on some
     * hardware, which is why this is bounded rather than suspending forever.
     */
    suspend fun readOnce(ctx: Context, kind: Kind, timeoutMs: Long = 5000): Float? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val sm = ctx.getSystemService(SensorManager::class.java)
                val sensor = sm?.getDefaultSensor(kind.sensorType)
                if (sensor == null) { cont.resume(null); return@suspendCancellableCoroutine }
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values.firstOrNull())
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
}
