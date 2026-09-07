package studio.multitool.meshdroid

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class NodeStatus { STOPPED, STARTING, RUNNING, ERROR }

/**
 * Process-wide state shared between DaemonService and the Compose UI.
 *
 * The service writes here (status, log lines); screens collect the StateFlows.
 * A plain object is used instead of a ViewModel because the state must outlive
 * any single screen and the app is a single small module.
 */
object DaemonState {
    private val _status = MutableStateFlow(NodeStatus.STOPPED)
    val status: StateFlow<NodeStatus> = _status

    private val _detail = MutableStateFlow("No radio attached.")
    val detail: StateFlow<String> = _detail

    /** Rolling daemon log, newest line last, capped at LOG_MAX lines. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private const val LOG_MAX = 3000

    /** Values parsed out of the daemon log as it boots; cleared when the node stops. */
    private val _nodeId = MutableStateFlow<String?>(null)
    val nodeId: StateFlow<String?> = _nodeId
    private val _firmware = MutableStateFlow<String?>(null)
    val firmware: StateFlow<String?> = _firmware
    private val _radioSerial = MutableStateFlow<String?>(null)
    val radioSerial: StateFlow<String?> = _radioSerial

    /**
     * True while the daemon reports its LoRa region as UNSET. Firmware 2.8 and later
     * will not mint identity keys, transmit, or write config.proto until a region is set.
     */
    private val _regionUnset = MutableStateFlow(false)
    val regionUnset: StateFlow<Boolean> = _regionUnset

    fun setStatus(s: NodeStatus, detail: String? = null) {
        _status.value = s
        if (detail != null) _detail.value = detail
        if (s == NodeStatus.STOPPED) { _nodeId.value = null; _firmware.value = null; _radioSerial.value = null; _regionUnset.value = false }
    }

    fun appendLog(line: String) {
        _log.update { l -> if (l.size >= LOG_MAX) l.drop(l.size - LOG_MAX + 1) + line else l + line }
        // Pattern-match the handful of log lines the UI surfaces. Order matters only
        // where two patterns could hit the same line ("init result").
        when {
            "Use nodenum 0x" in line -> Regex("nodenum 0x([0-9a-f]+)").find(line)?.let { _nodeId.value = "!" + it.groupValues[1] }
            line.contains("S:B:") -> Regex("S:B:\\d+,([^,]+),").find(line)?.let { _firmware.value = it.groupValues[1] }
            "Using pre-opened CH341 fd" in line -> Regex("serial ([0-9A-Za-z]+)").find(line)?.let { _radioSerial.value = it.groupValues[1] }
            "init result 0" in line && _status.value == NodeStatus.STARTING -> setStatus(NodeStatus.RUNNING, "Radio initialised.")
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

    fun clearLog() { _log.value = emptyList() }
}

/** App settings persisted in SharedPreferences. Read at daemon launch, not live. */
object Settings {
    const val API_PORT = 4403
    private const val PREFS = "meshdroid"

    fun lanEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).getBoolean("lan", false)
    fun setLanEnabled(ctx: Context, v: Boolean) = ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("lan", v).apply()

    fun verbose(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).getBoolean("verbose", false)
    fun setVerbose(ctx: Context, v: Boolean) = ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("verbose", v).apply()

    fun autoStart(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).getBoolean("autostart", true)
    fun setAutoStart(ctx: Context, v: Boolean) = ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("autostart", v).apply()
}
