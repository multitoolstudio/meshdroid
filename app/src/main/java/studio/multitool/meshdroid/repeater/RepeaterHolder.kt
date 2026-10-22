package studio.multitool.meshdroid.repeater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import studio.multitool.meshdroid.Profile
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

enum class RepeaterConnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class RepeaterSnapshot(
    val board: String = "",
    val version: String = "",
    val clock: String = "",
    val name: String = "",
    val freq: String = "",
    val bw: String = "",
    val sf: String = "",
    val cr: String = "",
    val txPower: String = "",
    val repeatEnabled: Boolean = true,
    val allowReadOnly: Boolean = false,
    val powerSaving: Boolean = false,
    val advertIntervalMins: String = "",
    val floodAdvertHours: String = "",
    val dutyCycle: String = "",
    val floodMax: String = "",
    val guestPassword: String = "",
    val lat: String = "",
    val lon: String = "",
)

/**
 * Process-wide owner of the USB Serial connection to an attached MeshCore repeater.
 *
 * MeshCore's simple_repeater reads one command line up to 159 characters terminated by '\r',
 * echoes the command as it arrives, and prints "  -> <reply>\r\n" (or multi-line output).
 * Commands are serialised with [cmdMutex] so a status refresh and a user command never
 * interleave on the repeater's 160-byte receive buffer.
 */
object RepeaterHolder {

    private const val ACTION_REPEATER_USB_PERMISSION = "studio.multitool.meshdroid.REPEATER_USB_PERMISSION"
    private const val MAX_LOG_LINES = 500
    private const val MAX_CMD_LEN = 159

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cmdMutex = Mutex()

    private var appCtx: Context? = null
    private var initialized = false
    private var port: UsbSerialPort? = null
    private var readerJob: Job? = null

    private val _usbDeviceLabel = MutableStateFlow<String?>(null)
    val usbDeviceLabel: StateFlow<String?> = _usbDeviceLabel

    private val _state = MutableStateFlow(RepeaterConnState.DISCONNECTED)
    val state: StateFlow<RepeaterConnState> = _state

    private val _detail = MutableStateFlow("Plug a MeshCore repeater into the USB-C port.")
    val detail: StateFlow<String> = _detail

    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console

    private val _snapshot = MutableStateFlow(RepeaterSnapshot())
    val snapshot: StateFlow<RepeaterSnapshot> = _snapshot

    // Used by sendCommand() to collect lines emitted in response to a command.
    private val captureLock = Any()
    private var captureBuffer: MutableList<String>? = null
    private var pendingEcho: String? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_REPEATER_USB_PERMISSION -> {
                    @Suppress("DEPRECATION")
                    val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && dev != null) openPort(context, dev)
                    else {
                        _state.value = RepeaterConnState.ERROR
                        _detail.value = "USB permission denied."
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshUsbPresence(context)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val detached: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (detached != null && port?.device?.deviceId == detached.deviceId) {
                        disconnect("Repeater unplugged.")
                    }
                    refreshUsbPresence(context)
                }
            }
        }
    }

    fun init(ctx: Context) {
        synchronized(this) {
            if (initialized) {
                refreshUsbPresence(ctx)
                return
            }
            initialized = true
            val app = ctx.applicationContext
            appCtx = app

            app.registerReceiver(
                usbReceiver,
                IntentFilter(ACTION_REPEATER_USB_PERMISSION),
                Context.RECEIVER_NOT_EXPORTED
            )
            app.registerReceiver(
                usbReceiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                },
                Context.RECEIVER_EXPORTED
            )
            refreshUsbPresence(app)

            // Disconnect automatically if the operator switches away from MeshCore.
            scope.launch {
                Profile.selected.collect { profile ->
                    if (!profile.supportsRepeaterCli && _state.value != RepeaterConnState.DISCONNECTED) {
                        disconnect("Switched profile.")
                    }
                }
            }
        }
    }

    fun refreshUsbPresence(ctx: Context) {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = RepeaterUsb.findDevice(usb)
        _usbDeviceLabel.value = dev?.let { RepeaterUsb.describe(it) }
        if (dev == null && _state.value == RepeaterConnState.DISCONNECTED) {
            _detail.value = "Plug a MeshCore repeater into the USB-C port."
        } else if (dev != null && _state.value == RepeaterConnState.DISCONNECTED) {
            _detail.value = "Detected ${RepeaterUsb.describe(dev)}"
        }
    }

    fun connect(ctx: Context) {
        init(ctx)
        val app = ctx.applicationContext
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = RepeaterUsb.findDevice(usb)
        if (dev == null) {
            _state.value = RepeaterConnState.DISCONNECTED
            _detail.value = "No USB serial repeater found. Check the OTG cable."
            return
        }
        if (usb.hasPermission(dev)) {
            openPort(app, dev)
        } else {
            _state.value = RepeaterConnState.CONNECTING
            _detail.value = "Waiting for USB permission…"
            val pi = PendingIntent.getBroadcast(
                app, 0,
                Intent(ACTION_REPEATER_USB_PERMISSION).setPackage(app.packageName),
                PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(dev, pi)
        }
    }

    fun disconnect(reason: String = "Disconnected.") {
        readerJob?.cancel()
        readerJob = null
        port?.close()
        port = null
        _state.value = RepeaterConnState.DISCONNECTED
        _detail.value = reason
    }

    private fun openPort(ctx: Context, dev: UsbDevice) {
        scope.launch {
            disconnect()
            _state.value = RepeaterConnState.CONNECTING
            _detail.value = "Opening ${RepeaterUsb.describe(dev)}…"
            val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val opened = runCatching { RepeaterUsb.open(usb, dev, 115200) }.getOrElse { e ->
                _state.value = RepeaterConnState.ERROR
                _detail.value = "Could not open serial port: ${e.message}"
                return@launch
            }
            port = opened
            _state.value = RepeaterConnState.CONNECTED
            _detail.value = "Connected to ${RepeaterUsb.describe(dev)}"
            appendConsole("[repeater] Connected to ${RepeaterUsb.describe(dev)} at 115200 baud")

            startReader(opened)
            // Flush any partial command buffer on the repeater with an empty CR, then read settings.
            delay(150)
            runCatching { opened.write("\r".toByteArray(StandardCharsets.US_ASCII)) }
            delay(150)
            refreshSnapshot()
        }
    }

    private fun startReader(activePort: UsbSerialPort) {
        readerJob = scope.launch {
            val buf = ByteArray(256)
            val lineBytes = ByteArrayOutputStream(256)
            while (isActive && port === activePort) {
                val n = activePort.read(buf, 200)
                if (n < 0) {
                    disconnect("Serial connection lost.")
                    break
                }
                if (n == 0) continue
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xFF
                    when (b) {
                        '\r'.code -> Unit
                        '\n'.code -> {
                            val completed = lineBytes.toByteArray().toString(StandardCharsets.UTF_8)
                            lineBytes.reset()
                            onLineReceived(completed)
                        }
                        else -> if (lineBytes.size() < 1024) lineBytes.write(b)
                    }
                }
            }
        }
    }

    private fun onLineReceived(raw: String) {
        val trimmed = raw.trimEnd()
        if (trimmed.isEmpty()) return

        synchronized(captureLock) {
            // Strip the single-line echo that simple_repeater prints as characters arrive.
            if (pendingEcho != null && trimmed == pendingEcho) {
                pendingEcho = null
                return
            }
            captureBuffer?.add(trimmed)
        }
        appendConsole(trimmed)
    }

    private fun appendConsole(line: String) {
        val current = _console.value
        _console.value = if (current.size >= MAX_LOG_LINES) {
            current.drop(current.size - MAX_LOG_LINES + 1) + line
        } else {
            current + line
        }
    }

    fun clearConsole() {
        _console.value = emptyList()
    }

    /** Truncates [s] so its UTF-8 encoding is at most [maxBytes] without splitting a code point. */
    private fun truncateUtf8(s: String, maxBytes: Int): String {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        var len = maxBytes
        while (len > 0 && (bytes[len].toInt() and 0xC0) == 0x80) {
            len--
        }
        return String(bytes, 0, len, StandardCharsets.UTF_8)
    }

    /**
     * Sends one CLI command terminated with '\r' and waits briefly for the reply line(s).
     */
    suspend fun sendCommand(cmd: String, logToConsole: Boolean = true, waitMs: Long = 350L): List<String> =
        cmdMutex.withLock {
            val activePort = port ?: return@withLock emptyList()
            val clean = truncateUtf8(cmd.replace("\r", "").replace("\n", "").trim(), MAX_CMD_LEN)
            if (clean.isEmpty()) return@withLock emptyList()

            if (logToConsole) appendConsole("> $clean")
            val collected = mutableListOf<String>()
            synchronized(captureLock) {
                pendingEcho = clean
                captureBuffer = collected
            }

            try {
                withContext(Dispatchers.IO) {
                    activePort.write("$clean\r".toByteArray(StandardCharsets.UTF_8))
                }
                // Wait until we see a "-> " reply line (or timeout for multi-line commands).
                withTimeoutOrNull(waitMs) {
                    while (isActive) {
                        delay(40)
                        val done = synchronized(captureLock) {
                            collected.any { it.trimStart().startsWith("-> ") || it.trimStart().startsWith("EOF") }
                        }
                        if (done) {
                            // Give any trailing lines in the same USB transfer a moment to arrive.
                            delay(35)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                appendConsole("[repeater] Send failed: ${e.message}")
            } finally {
                synchronized(captureLock) {
                    pendingEcho = null
                    captureBuffer = null
                }
            }

            collected.map { line ->
                val s = line.trimStart()
                if (s.startsWith("-> ")) s.removePrefix("-> ").trim() else s
            }
        }

    private fun parseValue(lines: List<String>): String =
        lines.firstOrNull()?.removePrefix("> ")?.trim() ?: ""

    /** Reads current repeater configuration into [snapshot] for the UI cards. */
    suspend fun refreshSnapshot() {
        if (_state.value != RepeaterConnState.CONNECTED) return
        val board = parseValue(sendCommand("board", logToConsole = false))
        val ver = parseValue(sendCommand("ver", logToConsole = false))
        val clock = parseValue(sendCommand("clock", logToConsole = false))
        val name = parseValue(sendCommand("get name", logToConsole = false))
        val radio = parseValue(sendCommand("get radio", logToConsole = false))
        val tx = parseValue(sendCommand("get tx", logToConsole = false))
        val repeat = parseValue(sendCommand("get repeat", logToConsole = false))
        val ro = parseValue(sendCommand("get allow.read.only", logToConsole = false))
        val ps = parseValue(sendCommand("powersaving", logToConsole = false))
        val advInt = parseValue(sendCommand("get advert.interval", logToConsole = false))
        val floodAdv = parseValue(sendCommand("get flood.advert.interval", logToConsole = false))
        val dc = parseValue(sendCommand("get dutycycle", logToConsole = false))
        val floodMax = parseValue(sendCommand("get flood.max", logToConsole = false))
        val guestPw = parseValue(sendCommand("get guest.password", logToConsole = false))
        val lat = parseValue(sendCommand("get lat", logToConsole = false))
        val lon = parseValue(sendCommand("get lon", logToConsole = false))

        val radioParts = radio.split(",")
        _snapshot.value = RepeaterSnapshot(
            board = board,
            version = ver,
            clock = clock,
            name = name,
            freq = radioParts.getOrNull(0)?.trim() ?: "",
            bw = radioParts.getOrNull(1)?.trim() ?: "",
            sf = radioParts.getOrNull(2)?.trim() ?: "",
            cr = radioParts.getOrNull(3)?.trim() ?: "",
            txPower = tx,
            repeatEnabled = !repeat.equals("off", ignoreCase = true),
            allowReadOnly = ro.equals("on", ignoreCase = true),
            powerSaving = ps.startsWith("on", ignoreCase = true),
            advertIntervalMins = advInt,
            floodAdvertHours = floodAdv,
            dutyCycle = dc.removeSuffix("%"),
            floodMax = floodMax,
            guestPassword = guestPw,
            lat = lat,
            lon = lon,
        )
    }

    /** Syncs the repeater's RTC to the phone's current Unix epoch time (`time <secs>`). */
    suspend fun syncClockToPhone(): String {
        val epochSecs = System.currentTimeMillis() / 1000L
        val reply = sendCommand("time $epochSecs", logToConsole = true)
        val clock = parseValue(sendCommand("clock", logToConsole = false))
        if (clock.isNotEmpty()) _snapshot.value = _snapshot.value.copy(clock = clock)
        return reply.firstOrNull() ?: "Sent time $epochSecs"
    }
}
