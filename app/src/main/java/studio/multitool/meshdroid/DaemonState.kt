package studio.multitool.meshdroid

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class NodeStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

/**
 * Process-wide state shared between DaemonService and the Compose UI.
 *
 * The service writes here (status, log lines); screens collect the StateFlows.
 * A plain object is used instead of a ViewModel because the state must outlive
 * any single screen and the app is a single small module.
 *
 * Log lines arrive on the daemon's stdout reader thread, one at a time, and are
 * held in an ArrayDeque. The StateFlow is refreshed from that deque at most once
 * every FLUSH_MS, so a burst of verbose output costs one list copy per interval
 * instead of one per line.
 */
object DaemonState {
    private val _status = MutableStateFlow(NodeStatus.STOPPED)
    val status: StateFlow<NodeStatus> = _status

    private val _detail = MutableStateFlow("No radio attached.")
    val detail: StateFlow<String> = _detail

    /** True while DaemonService exists. MainActivity waits for false before it finishes. */
    private val _serviceAlive = MutableStateFlow(false)
    val serviceAlive: StateFlow<Boolean> = _serviceAlive

    /** Rolling daemon log, newest line last, capped at LOG_MAX lines. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private const val LOG_MAX = 3000
    private const val FLUSH_MS = 120L

    private val buffer = ArrayDeque<String>()
    private val bufferLock = Any()
    private val flushPending = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Values parsed out of the daemon log as it boots; cleared when the node stops. */
    private val _nodeId = MutableStateFlow<String?>(null)
    val nodeId: StateFlow<String?> = _nodeId
    private val _firmware = MutableStateFlow<String?>(null)
    val firmware: StateFlow<String?> = _firmware
    private val _radioSerial = MutableStateFlow<String?>(null)
    val radioSerial: StateFlow<String?> = _radioSerial

    /**
     * Address the API server bound to, from the line patch 0002 writes. Null means
     * the line has not appeared. A build without patch 0002 never prints it and
     * binds every interface, which DaemonService reports as a warning.
     */
    private val _bindAddr = MutableStateFlow<String?>(null)
    val bindAddr: StateFlow<String?> = _bindAddr

    /**
     * True while the daemon reports its LoRa region as UNSET. Firmware 2.8 and later
     * will not mint identity keys, transmit, or write config.proto until a region is set.
     */
    private val _regionUnset = MutableStateFlow(false)
    val regionUnset: StateFlow<Boolean> = _regionUnset

    /** Last phone battery reading published to the daemon, or null when reporting is off. */
    private val _battery = MutableStateFlow<BatteryReporter.Reading?>(null)
    val battery: StateFlow<BatteryReporter.Reading?> = _battery

    fun setServiceAlive(alive: Boolean) { _serviceAlive.value = alive }

    fun setBattery(r: BatteryReporter.Reading?) { _battery.value = r }

    /** Clients attached to the proxy, or zero when the proxy is off. */
    private val _proxyClients = MutableStateFlow(0)
    val proxyClients: StateFlow<Int> = _proxyClients

    fun setProxyClients(n: Int) { _proxyClients.value = n }

    fun setStatus(s: NodeStatus, detail: String? = null) {
        _status.value = s
        if (detail != null) _detail.value = detail
        if (s == NodeStatus.STOPPED || s == NodeStatus.ERROR) {
            _nodeId.value = null; _firmware.value = null; _radioSerial.value = null
            _regionUnset.value = false; _bindAddr.value = null
            flushNow()   // the tail of a failed session is the part worth reading
        }
    }

    /**
     * Marks the start of a daemon session in the log. The previous session's lines
     * are kept so that pressing Start after a failure does not erase the reason.
     */
    fun beginSession(header: String) {
        if (synchronized(bufferLock) { buffer.isNotEmpty() }) appendLog("")
        appendLog("[meshdroid] ---- $header ----")
    }

    fun appendLog(line: String) {
        synchronized(bufferLock) {
            buffer.addLast(line)
            while (buffer.size > LOG_MAX) buffer.removeFirst()
        }
        parse(line)
        scheduleFlush()
    }

    fun clearLog() {
        synchronized(bufferLock) { buffer.clear() }
        _log.value = emptyList()
    }

    private fun scheduleFlush() {
        if (flushPending.compareAndSet(false, true)) scope.launch {
            delay(FLUSH_MS)
            flushPending.set(false)
            flushNow()
        }
    }

    private fun flushNow() {
        _log.value = synchronized(bufferLock) { buffer.toList() }
    }

    /**
     * Pattern-matches the handful of log lines the UI surfaces. Order matters only
     * where two patterns could hit the same line ("init result"). Every string here
     * comes from the firmware or from patch 0001 or 0002; check them on each bump.
     */
    private fun parse(line: String) {
        when {
            "Use nodenum 0x" in line -> Regex("nodenum 0x([0-9a-fA-F]+)").find(line)?.let { _nodeId.value = "!" + it.groupValues[1].lowercase() }
            line.contains("S:B:") -> Regex("S:B:\\d+,([^,]+),").find(line)?.let { _firmware.value = it.groupValues[1] }
            "Using pre-opened CH341 fd" in line -> Regex("serial ([0-9A-Za-z]+)").find(line)?.let { _radioSerial.value = it.groupValues[1] }
            "init result 0" in line && _status.value == NodeStatus.STARTING -> setStatus(NodeStatus.RUNNING, "Radio initialised.")
            "API server binding" in line -> Regex("binding ([0-9.]+):").find(line)?.let { _bindAddr.value = it.groupValues[1] }
            "API server listen on TCP port" in line -> _detail.value = "Running. API on port ${Regex("port (\\d+)").find(line)?.groupValues?.get(1) ?: Settings.API_PORT}"
            "using UNSET" in line -> _regionUnset.value = true
            "Generate new PKI keys" in line || ("Set radio: region=" in line && "region=UNSET" !in line) -> _regionUnset.value = false
            "Couldn't open LoRa USB device" in line || "Could not receive USB fd" in line -> setStatus(NodeStatus.ERROR, "Could not open the USB radio.")
            "init result" in line && "result 0" !in line -> setStatus(NodeStatus.ERROR, line.trim().substringAfter("] "))
            line.startsWith("Portduino notneg") || line.startsWith("Portduino checkzero") ->
                setStatus(NodeStatus.ERROR, line.substringAfter(": ").trim() +
                    if ("bind" in line) ". Port ${Settings.API_PORT} is taken by another process." else "")
        }
    }
}

/**
 * App settings persisted in SharedPreferences. Read at daemon launch, not live.
 *
 * The SharedPreferences instance is cached after the first call. warm() does that
 * first call on a background thread; everything after it is served from memory.
 */
object Settings {
    /** Port clients connect to. The proxy owns it when enabled, the daemon when not. */
    const val API_PORT = 4403

    /** Loopback port the daemon listens on while the proxy owns API_PORT. */
    const val DAEMON_PORT = 4404
    /** Seconds to wait for "init result 0" before telling the user to look at the log. */
    const val START_TIMEOUT_S = 30L
    /** Seconds the daemon gets to exit after SIGTERM before it is killed. */
    const val STOP_TIMEOUT_S = 4L
    private const val PREFS = "meshdroid"

    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences =
        cached ?: ctx.applicationContext.getSharedPreferences(PREFS, 0).also { cached = it }

    /** Call once off the main thread to keep the first read off it. */
    fun warm(ctx: Context) { prefs(ctx) }

    fun lanEnabled(ctx: Context) = prefs(ctx).getBoolean("lan", false)
    fun setLanEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("lan", v).apply()

    fun verbose(ctx: Context) = prefs(ctx).getBoolean("verbose", false)
    fun setVerbose(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("verbose", v).apply()

    fun autoStart(ctx: Context) = prefs(ctx).getBoolean("autostart", true)
    fun setAutoStart(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("autostart", v).apply()

    // With the proxy on, several clients share the node's single API connection. Off hands
    // port 4403 straight back to the daemon and restores the one-client behaviour.
    fun proxyEnabled(ctx: Context) = prefs(ctx).getBoolean("proxy", true)
    fun setProxyEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("proxy", v).apply()

    fun batteryReport(ctx: Context) = prefs(ctx).getBoolean("battery", true)
    fun setBatteryReport(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("battery", v).apply()

    // Phone sensor telemetry. Off by default: it costs airtime on a shared channel, and it
    // only publishes while the mesh link is connected.
    fun envPressure(ctx: Context) = prefs(ctx).getBoolean("env_pressure", false)
    fun setEnvPressure(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("env_pressure", v).apply()

    fun envLux(ctx: Context) = prefs(ctx).getBoolean("env_lux", false)
    fun setEnvLux(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("env_lux", v).apply()

    /** Minutes between publications. Matches the firmware's own default cadence. */
    fun envIntervalMinutes(ctx: Context) = prefs(ctx).getInt("env_interval", 30)
    fun setEnvIntervalMinutes(ctx: Context, v: Int) = prefs(ctx).edit().putInt("env_interval", v).apply()
}
