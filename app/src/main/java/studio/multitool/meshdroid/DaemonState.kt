package studio.multitool.meshdroid

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class NodeStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

/**
 * What a fault line says about the USB link to the radio.
 *
 * PIPE: the dongle is still on the bus and still answering, but the host and the
 * device disagree about what is in flight. libch341 cancels its own transfers on
 * an error and does nothing about the device, which answers the command it
 * already accepted; the reply then lands in the next transfer, whose buffer is a
 * different size. A six byte status reply read into a four byte SPI read is
 * LIBUSB_TRANSFER_OVERFLOW, which is the usual first symptom. Nothing in the
 * library corrects the offset afterwards.
 *
 * GONE: the dongle has left the bus. The descriptor the daemon holds is dead and
 * nothing in this process can bring it back.
 */
enum class RadioFault { PIPE, GONE }

/**
 * Process-wide state shared between DaemonService and the Compose UI, for
 * whichever daemon the active profile names.
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

    /**
     * Transport faults, for DaemonService to act on. A SharedFlow rather than a
     * StateFlow because each one is an event and the count is what matters: a
     * single failed transfer is noise, and faults that keep arriving are a pipe
     * that has stopped working.
     */
    private val _radioFault = MutableSharedFlow<RadioFault>(extraBufferCapacity = 32)
    val radioFault: SharedFlow<RadioFault> = _radioFault

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

    /**
     * The profile whose output is being parsed. Set by DaemonService at the start
     * of a session, because the two daemons share no log lines at all and a parser
     * reading meshtasticd's patterns in meshcored's output would leave the UI on
     * "Starting..." forever.
     */
    @Volatile var profile: Profile = Profile.DEFAULT
        private set

    fun setProfile(p: Profile) { profile = p }

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
        // Shared first: both daemons reach the radio through the same patched
        // libch341, so these lines come from patch 0001/0002 rather than from
        // either firmware.
        when {
            "Using pre-opened CH341 fd" in line ->
                Regex("serial ([0-9A-Za-z]+)").find(line)?.let { _radioSerial.value = it.groupValues[1] }
            "Could not receive USB fd" in line ->
                setStatus(NodeStatus.ERROR, "Could not receive the USB descriptor from the app.")
        }
        // Transport faults come from libch341 and from the CH341 HAL above it, so
        // both daemons produce them and neither recovers on its own. Reported to
        // the service rather than only logged.
        radioFaultIn(line)?.let { _radioFault.tryEmit(it) }

        if (profile == Profile.MESHCORE) { parseMeshCore(line); return }
        // The CH341 lines are handled above, for both profiles, and are not
        // repeated here.
        when {
            "Use nodenum 0x" in line -> Regex("nodenum 0x([0-9a-fA-F]+)").find(line)?.let { _nodeId.value = "!" + it.groupValues[1].lowercase() }
            line.contains("S:B:") -> Regex("S:B:\\d+,([^,]+),").find(line)?.let { _firmware.value = it.groupValues[1] }
            "init result 0" in line && _status.value == NodeStatus.STARTING -> setStatus(NodeStatus.RUNNING, "Radio initialised.")
            "API server binding" in line -> Regex("binding ([0-9.]+):").find(line)?.let { _bindAddr.value = it.groupValues[1] }
            "API server listen on TCP port" in line -> _detail.value = "Running. API on port ${Regex("port (\\d+)").find(line)?.groupValues?.get(1) ?: Settings.API_PORT}"
            "using UNSET" in line -> _regionUnset.value = true
            "Generate new PKI keys" in line || ("Set radio: region=" in line && "region=UNSET" !in line) -> _regionUnset.value = false
            "Couldn't open LoRa USB device" in line -> setStatus(NodeStatus.ERROR, "Could not open the USB radio.")
            "init result" in line && "result 0" !in line -> setStatus(NodeStatus.ERROR, line.trim().substringAfter("] "))
            line.startsWith("Portduino notneg") || line.startsWith("Portduino checkzero") ->
                setStatus(NodeStatus.ERROR, line.substringAfter(": ").trim() +
                    if ("bind" in line) ". Port ${Settings.API_PORT} is taken by another process." else "")
        }
    }

    /**
     * Classifies a line as a USB transport fault, or not one.
     *
     * The first three patterns are libch341's own: its transfer callbacks print
     * the libusb status, and its error path prints the failed transfer. The
     * USBHal lines are the CH341 RadioLib HAL reporting the return value above
     * that. LIBUSB_TRANSFER_CANCELLED is acknowledged silently and never
     * printed, so matching the prefix does not catch an ordinary cancellation.
     */
    private fun radioFaultIn(line: String): RadioFault? = when {
        "LIBUSB_ERROR_NO_DEVICE" in line -> RadioFault.GONE
        "LIBUSB_TRANSFER_" in line -> RadioFault.PIPE
        "Could not perform SPI transfer" in line -> RadioFault.PIPE
        "Could not get input pins" in line -> RadioFault.PIPE
        "USBHal digitalWrite: cannot write" in line -> RadioFault.PIPE
        "USBHal digitalRead: cannot read" in line -> RadioFault.PIPE
        "libch341 in_error detected" in line -> RadioFault.PIPE
        else -> null
    }

    /**
     * meshcored's log lines, which share nothing with meshtasticd's.
     *
     * There is no "init result 0": MeshCore's setup() halts if radio_init() fails,
     * so reaching the point where the companion socket is listening means the radio
     * came up and the transport is ready. That line is therefore the RUNNING signal.
     *
     * Every string here comes from MeshCore, from framework-portduino, or from
     * variant/SocketSerialInterface.h and variant/target.cpp. Check them on a
     * MeshCore version bump, the same as the meshtasticd patterns above.
     */
    private fun parseMeshCore(line: String) {
        when {
            "companion: listening on" in line -> {
                val port = Regex(":(\\d+)").find(line)?.groupValues?.get(1)
                if (_status.value == NodeStatus.STARTING)
                    setStatus(NodeStatus.RUNNING, "Radio up. Companion on port ${port ?: Profile.MESHCORE.clientPort}.")
            }
            "companion: client connected" in line -> _detail.value = "Client connected."
            "companion: client disconnected" in line ->
                _detail.value = "Waiting for a client on port ${Profile.MESHCORE.clientPort}."

            // target.cpp reports both of these before the daemon gives up.
            "Could not open the CH341" in line || "No CH341 HAL" in line ->
                setStatus(NodeStatus.ERROR, "Could not open the USB radio.")
            "Could not open SPI" in line ->
                setStatus(NodeStatus.ERROR, "Could not open the USB radio (SPI).")
            "radio did not initialise" in line || "std_init" in line && "false" in line ->
                setStatus(NodeStatus.ERROR, "The radio did not initialise. Check the antenna and the OTG cable.")

            // An identity that will not save leaves the node unable to sign an advert
            // or complete an ACK, which looks like a transmit fault. Surface it.
            "IdentityStore::save() write - Err" in line ->
                setStatus(NodeStatus.ERROR, "Could not save the node identity. Storage may be full.")

            "companion: bind(" in line ->
                setStatus(NodeStatus.ERROR,
                    "Port ${Profile.MESHCORE.clientPort} is already in use by another process.")
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
    /**
     * Port Meshtastic clients connect to. The proxy owns it when enabled,
     * meshtasticd when not. Each profile's own port is Profile.clientPort;
     * this is here for the log lines that name a port before a profile is known.
     */
    const val API_PORT = 4403

    /** Loopback port meshtasticd listens on while the proxy owns API_PORT. */
    const val DAEMON_PORT = 4404

    // Thresholds for the automatic recovery in DaemonService. A failed transfer on
    // its own means nothing: libch341 retries, and a short burst around a busy
    // moment clears by itself. A pipe that has desynchronised never clears, so what
    // distinguishes the two is faults that keep arriving rather than faults at all.

    /** A quiet gap longer than this ends a burst of transport faults. */
    const val FAULT_GAP_MS = 10_000L

    /** Faults in one unbroken burst before the link counts as stuck. */
    const val FAULT_COUNT = 20

    /** How long a burst must persist before it is a stuck pipe and not a hiccup. */
    const val FAULT_SUSTAIN_MS = 15_000L

    /** Automatic restarts before the user is asked to reconnect the dongle. */
    const val FAULT_MAX_RECOVERIES = 3

    /** Quiet time after which an earlier incident stops counting against the next. */
    const val FAULT_FORGET_MS = 30 * 60_000L
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

    /**
     * Which daemon the app runs. One radio and one USB descriptor, so exactly one
     * at a time; switching stops the running daemon before starting the other.
     * Read at launch, not live.
     */
    fun profileId(ctx: Context) = prefs(ctx).getString("profile", Profile.DEFAULT.id) ?: Profile.DEFAULT.id
    fun setProfileId(ctx: Context, v: String) = prefs(ctx).edit().putString("profile", v).apply()

    fun lanEnabled(ctx: Context) = prefs(ctx).getBoolean("lan", false)
    fun setLanEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("lan", v).apply()

    fun verbose(ctx: Context) = prefs(ctx).getBoolean("verbose", false)
    fun setVerbose(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("verbose", v).apply()

    fun autoStart(ctx: Context) = prefs(ctx).getBoolean("autostart", true)
    fun setAutoStart(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("autostart", v).apply()

    // With the proxy on, several clients share the node's single API connection. Off hands
    // port 4403 straight back to the daemon and restores the one-client behaviour.
    // The BBS answers commands from the mesh. Off by default: it transmits on its own,
    // and an operator should opt into that.
    fun bbsEnabled(ctx: Context) = prefs(ctx).getBoolean("bbs", false)
    fun setBbsEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("bbs", v).apply()

    /** Answer commands seen on a channel, not only direct messages. */
    fun bbsOnChannels(ctx: Context) = prefs(ctx).getBoolean("bbs_channels", true)
    fun setBbsOnChannels(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("bbs_channels", v).apply()

    /** What marks a channel message as meant for the BBS. Direct messages need no prefix. */
    fun bbsPrefix(ctx: Context) = prefs(ctx).getString("bbs_prefix", "!") ?: "!"
    fun setBbsPrefix(ctx: Context, v: String) = prefs(ctx).edit().putString("bbs_prefix", v.ifBlank { "!" }).apply()

    /**
     * Require admin commands to arrive encrypted to this node's key. On by default: a
     * sender's node number is forgeable, so the admin table alone is not authentication.
     */
    fun bbsAdminRequiresPki(ctx: Context) = prefs(ctx).getBoolean("bbs_admin_pki", true)
    fun setBbsAdminRequiresPki(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("bbs_admin_pki", v).apply()

    /** Closed by an admin with "admin stop". Only admins are answered while this is set. */
    fun bbsLocked(ctx: Context) = prefs(ctx).getBoolean("bbs_locked", false)
    fun setBbsLocked(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("bbs_locked", v).apply()

    /** File names of the imported Lua modules the operator has enabled. */
    fun luaEnabled(ctx: Context): Set<String> = prefs(ctx).getStringSet("lua_enabled", emptySet()) ?: emptySet()
    fun setLuaEnabled(ctx: Context, v: Set<String>) = prefs(ctx).edit().putStringSet("lua_enabled", v).apply()

    /** Seconds before the same sender gets another reply. */
    fun bbsCooldownSeconds(ctx: Context) = prefs(ctx).getInt("bbs_cooldown", 10)
    fun setBbsCooldownSeconds(ctx: Context, v: Int) = prefs(ctx).edit().putInt("bbs_cooldown", v).apply()

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
