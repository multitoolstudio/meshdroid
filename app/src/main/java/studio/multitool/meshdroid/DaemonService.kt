package studio.multitool.meshdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground service that owns one meshtasticd process.
 *
 * Every transition (start, stop, restart) runs on the single "daemon-control"
 * thread, which serialises them and keeps blocking work off the main thread.
 * onStartCommand posts the requested transition and returns; the only work it
 * does inline is startForeground, which the framework requires promptly.
 *
 * A start opens the USB device, acquires a wake lock, starts the fd hand-off
 * socket, then launches the daemon binary from nativeLibraryDir. A reader thread
 * forwards the daemon's stdout into DaemonState and to logcat. A stop signals
 * the daemon with SIGTERM, waits, then kills it, and releases everything in
 * reverse order.
 */
class DaemonService : Service() {

    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "daemon-control") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchdog: Job? = null

    // Touched only on the control thread, except where noted.
    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var usbPfd: ParcelFileDescriptor? = null
    private var fdServer: UsbFdServer? = null
    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var battery: BatteryReporter? = null
    private var proxy: studio.multitool.meshdroid.mesh.MeshProxy? = null

    /** True while a stop or restart is tearing the daemon down, so its exit is not an error. */
    @Volatile private var teardown = false
    /** True between a successful launch and the process exiting. */
    @Volatile private var daemonUp = false

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                DaemonState.appendLog("[meshdroid] USB radio detached")
                control.execute { shutdown("Radio unplugged.") ; stopSelf() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DaemonState.setServiceAlive(true)
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), Context.RECEIVER_EXPORTED)
        scope.launch {
            combine(DaemonState.status, DaemonState.detail) { s, d -> s to d }.collect { (s, d) -> showNotification(s, d) }
        }
        control.execute { Settings.warm(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground has to happen on this thread and soon after the start
        // request, whatever the request turns out to be.
        startInForeground()

        when (intent?.action) {
            ACTION_STOP -> control.execute {
                shutdown("Node stopped.")
                stopSelf()
            }
            ACTION_RESTART -> control.execute {
                val dev = device
                if (dev == null) {
                    DaemonState.setStatus(NodeStatus.ERROR, "Nothing to restart: no radio is attached.")
                    stopSelf()
                } else {
                    shutdown(null)
                    startDaemon(dev)
                }
            }
            else -> {
                @Suppress("DEPRECATION")
                val dev: UsbDevice? = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (dev == null) { stopSelf(); return START_NOT_STICKY }
                control.execute {
                    if (daemonUp) return@execute      // already running, nothing to do
                    device = dev
                    startDaemon(dev)
                }
            }
        }
        // The node is deliberately not restarted after a process kill: the phone is
        // the radio's host, and a start needs the USB device the user plugged in.
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val n = buildNotification(DaemonState.status.value, DaemonState.detail.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_ID, n)
    }

    // ---- start ----

    /** Runs on the control thread. Opens USB, hands over the fd and launches the binary. */
    private fun startDaemon(dev: UsbDevice) {
        teardown = false
        DaemonState.beginSession("start " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
        DaemonState.setStatus(NodeStatus.STARTING, "Opening USB radio…")

        val usb = getSystemService(USB_SERVICE) as UsbManager
        val conn = usb.openDevice(dev)
        if (conn == null) {
            fail("USB permission denied or the device is busy. Unplug it, plug it back in and accept the prompt.")
            return
        }
        connection = conn

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meshdroid:daemon").apply { acquire() }

        // fromFd() duplicates the descriptor. The ParcelFileDescriptor is held in a
        // field for the life of the session: it owns the duplicate, and letting it
        // become unreachable would have its finalizer close the descriptor while the
        // daemon is still using it.
        val pfd = try {
            ParcelFileDescriptor.fromFd(conn.fileDescriptor)
        } catch (e: Exception) {
            fail("Could not duplicate the USB descriptor: ${e.message}"); return
        }
        usbPfd = pfd

        // Unique per start. A restart can begin before the previous instance has
        // released its socket name, and abstract-namespace names cannot be reused
        // while bound.
        val socketName = "meshdroid-usbfd-" + System.nanoTime().toString(36)
        try {
            fdServer = UsbFdServer(socketName, pfd.fileDescriptor).also { it.start() }
        } catch (e: Exception) {
            fail("Could not create the USB hand-off socket: ${e.message}"); return
        }

        // With the proxy on, the daemon moves to loopback 4404 and Meshdroid owns 4403.
        val useProxy = Settings.proxyEnabled(this)
        val daemonPort = if (useProxy) Settings.DAEMON_PORT else Settings.API_PORT

        killStaleDaemons()
        for (port in listOfNotNull(daemonPort, if (useProxy) Settings.API_PORT else null)) {
            if (!waitForFreePort(port)) {
                fail("Port $port is already in use by another process (a leftover test daemon? run: adb shell pkill -f mtd).")
                return
            }
        }

        NodeFiles.ensureDefaultConfig(this)
        val config = NodeFiles.configFile(this)
        val fsDir = NodeFiles.fsDir(this)
        val bin = File(applicationInfo.nativeLibraryDir, "libmeshtasticd.so")
        if (!bin.exists()) { fail("Daemon binary missing from this build."); return }

        val args = mutableListOf(bin.absolutePath, "-c", config.absolutePath, "-d", fsDir.absolutePath,
            "-p", daemonPort.toString())
        if (Settings.verbose(this)) args += "-v"

        val lan = Settings.lanEnabled(this)
        val reportBattery = Settings.batteryReport(this)
        val batteryFile = BatteryReporter.fileFor(this)
        // Removed rather than left behind: a stale file from a session that had reporting
        // on would otherwise be read by this one until it aged out.
        if (!reportBattery) runCatching { batteryFile.delete() }

        val pb = ProcessBuilder(args).redirectErrorStream(true).directory(filesDir)
        pb.environment().apply {
            put("PINEDIO_USB_FD_SOCKET", socketName)
            put("HOME", filesDir.absolutePath)
            put("LD_LIBRARY_PATH", applicationInfo.nativeLibraryDir)   // libc++_shared.so is packaged beside the binary
            // The daemon never binds beyond loopback when the proxy is in front of it;
            // reaching the LAN is then the proxy's listener, not the daemon's.
            put("MESHTASTICD_BIND_ADDR", if (lan && !useProxy) "0.0.0.0" else "127.0.0.1")
            if (reportBattery) put("MESHTASTICD_BATTERY_FILE", batteryFile.absolutePath)
        }
        DaemonState.appendLog(
            "[meshdroid] starting meshtasticd on port $daemonPort, clients use ${Settings.API_PORT} " +
                (if (useProxy) "through the proxy" else "directly") +
                (if (lan) ", reachable from the LAN" else ", localhost only")
        )

        val p = try { pb.start() } catch (e: Exception) {
            fail("Could not launch the daemon: ${e.message}"); return
        }
        process = p
        daemonUp = true
        // Started after the daemon so the first reading lands while it is still booting,
        // which is well inside the daemon's staleness window.
        if (reportBattery) battery = BatteryReporter(this, batteryFile).also { it.start() }
        if (useProxy) {
            // The proxy retries its upstream connection, so starting it before the daemon
            // has finished binding is fine.
            runCatching {
                proxy = studio.multitool.meshdroid.mesh.MeshProxy(Settings.API_PORT, Settings.DAEMON_PORT, lan)
                    .also { it.start() }
            }.onFailure {
                DaemonState.appendLog("[meshdroid] proxy failed to start: ${it.message}. Clients cannot reach the node.")
                proxy = null
            }
        }
        readStdout(p)
        startWatchdog(lan)
    }

    private fun readStdout(p: Process) {
        Thread({
            // On stop the pipe closes while readLine() is blocked; that is expected.
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    DaemonState.appendLog(line)
                    Log.i("meshtasticd", line)
                }
            } catch (e: Exception) {
                if (!teardown) DaemonState.appendLog("[meshdroid] log stream ended: ${e.message}")
            }
            val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
            daemonUp = false
            DaemonState.appendLog("[meshdroid] meshtasticd exited with code $code")
            if (!teardown) {
                if (DaemonState.status.value != NodeStatus.ERROR)
                    DaemonState.setStatus(NodeStatus.ERROR, "Daemon exited with code $code. See Logs.")
                control.execute { shutdown(null); stopSelf() }
            }
        }, "meshtasticd-stdout").apply { isDaemon = true; start() }
    }

    /**
     * Watches a start. If the daemon never reports "init result 0" the UI would
     * otherwise sit on "Starting…" with no explanation. Once it is running, the
     * absence of the bind line from patch 0002 means the daemon is listening on
     * every interface whatever the LAN switch says.
     */
    private fun startWatchdog(lanRequested: Boolean) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(TimeUnit.SECONDS.toMillis(Settings.START_TIMEOUT_S))
            if (DaemonState.status.value == NodeStatus.STARTING) {
                DaemonState.appendLog("[meshdroid] still starting after ${Settings.START_TIMEOUT_S}s. " +
                    "The radio may not be answering on SPI. Check the antenna, the OTG cable and the pinmap in the Config tab.")
                DaemonState.setStatus(NodeStatus.STARTING, "Still starting after ${Settings.START_TIMEOUT_S}s. See Logs.")
            }
            if (DaemonState.status.value == NodeStatus.RUNNING && DaemonState.bindAddr.value == null && !lanRequested) {
                DaemonState.appendLog("[meshdroid] warning: this daemon build never reported its bind address, " +
                    "so it was built without patch 0002 and is listening on every interface. " +
                    "Rebuild with firmware/build-meshtasticd-android.sh before relying on the LAN switch.")
            }
        }
    }

    // ---- stop ----

    /**
     * Runs on the control thread. Stops the daemon and releases USB, the fd socket
     * and the wake lock. Safe to call when nothing is running.
     */
    private fun shutdown(finalDetail: String?) {
        teardown = true
        watchdog?.cancel(); watchdog = null
        if (process != null && DaemonState.status.value != NodeStatus.ERROR)
            DaemonState.setStatus(NodeStatus.STOPPING, "Stopping node…")
        // Stopped before the daemon so clients see a clean close rather than a stall.
        runCatching { proxy?.stop() }; proxy = null
        runCatching { battery?.stop() }; battery = null
        stopDaemonProcess()
        runCatching { fdServer?.stop() }; fdServer = null
        // Closed after the fd server, so nothing can send a descriptor that has
        // just been closed.
        runCatching { usbPfd?.close() }; usbPfd = null
        runCatching { connection?.close() }; connection = null
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }; wakeLock = null
        daemonUp = false
        if (DaemonState.status.value != NodeStatus.ERROR)
            DaemonState.setStatus(NodeStatus.STOPPED, finalDetail ?: DaemonState.detail.value)
    }

    /**
     * SIGTERM first so the daemon can flush its node database (firmware patch 0004),
     * then SIGKILL if it has not exited within Settings.STOP_TIMEOUT_S. Process.destroy()
     * on Android is a SIGKILL, which is why it is the fallback and not the first move.
     */
    private fun stopDaemonProcess() {
        val p = process ?: return
        process = null
        val pid = findDaemonPid()
        if (pid != null) runCatching { android.os.Process.sendSignal(pid, SIGTERM) }
        else DaemonState.appendLog("[meshdroid] could not find the daemon's pid, killing it instead")
        val exited = runCatching { p.waitFor(Settings.STOP_TIMEOUT_S, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!exited) {
            DaemonState.appendLog("[meshdroid] daemon still alive ${Settings.STOP_TIMEOUT_S}s after SIGTERM, killing it")
            runCatching { p.destroy() }
            runCatching { p.waitFor(2, TimeUnit.SECONDS) }
        }
    }

    /**
     * The pid of our daemon child, read from /proc.
     *
     * java.lang.Process.pid() is not part of the Android SDK, and the pid field of
     * the platform's Process implementation is private, so the process is found by
     * matching the binary name in /proc/<pid>/cmdline and the parent pid in
     * /proc/<pid>/stat against this process. Matching the parent as well as the name
     * keeps a stale daemon from an earlier app process out of the result.
     */
    private fun findDaemonPid(): Int? {
        val me = android.os.Process.myPid()
        File("/proc").listFiles { f -> f.isDirectory && f.name.all { c -> c.isDigit() } }?.forEach { d ->
            val cmd = runCatching { File(d, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (!cmd.contains("libmeshtasticd.so")) return@forEach
            val stat = runCatching { File(d, "stat").readText() }.getOrNull() ?: return@forEach
            // "<pid> (<comm>) <state> <ppid> ...". comm can hold spaces and brackets,
            // so the fields are counted from the last closing bracket.
            val ppid = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()
            if (ppid == me) return d.name.toIntOrNull()
        }
        return null
    }

    /**
     * Signals any meshtasticd left over from a previous app process. If the app
     * crashes, the daemon child survives as an orphan holding the USB device and
     * the TCP port. Processes of the same UID are visible under /proc and can be
     * signalled, which is all this needs.
     */
    private fun killStaleDaemons() {
        val me = android.os.Process.myPid()
        val stale = mutableListOf<Int>()
        File("/proc").listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }?.forEach { d ->
            val pid = d.name.toInt()
            if (pid == me) return@forEach
            val cmd = runCatching { File(d, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (cmd.contains("libmeshtasticd.so")) stale += pid
        }
        if (stale.isEmpty()) return
        stale.forEach {
            DaemonState.appendLog("[meshdroid] signalling stale meshtasticd (pid $it) from a previous run")
            runCatching { android.os.Process.sendSignal(it, SIGTERM) }
        }
        // Give them a moment to flush, then make sure they are gone.
        Thread.sleep(1000)
        stale.filter { File("/proc/$it").exists() }.forEach {
            DaemonState.appendLog("[meshdroid] killing stale meshtasticd (pid $it)")
            runCatching { android.os.Process.killProcess(it) }
        }
    }

    /**
     * Waits for the API port to become free. A restart can reach here while the
     * previous daemon's listening socket is still being torn down by the kernel.
     */
    private fun waitForFreePort(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + 4000
        while (true) {
            val free = runCatching {
                java.net.ServerSocket().use { it.reuseAddress = true; it.bind(java.net.InetSocketAddress("0.0.0.0", port)) }
                true
            }.getOrDefault(false)
            if (free) return true
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(250)
        }
    }

    /** Reports a failed start, releases whatever was opened, and stops the service. */
    private fun fail(message: String) {
        DaemonState.setStatus(NodeStatus.ERROR, message)
        shutdown(null)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(detachReceiver) }
        // The transitions run on the control thread; wait briefly for an in-flight
        // one, then make sure nothing is left holding the radio.
        control.execute { shutdown(null) }
        control.shutdown()
        // Bounded so that onDestroy cannot sit long enough to be called an ANR. In
        // the normal path the queued shutdown finds nothing left to do, because the
        // transition that led here already ran.
        runCatching { control.awaitTermination(3, TimeUnit.SECONDS) }
        scope.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        DaemonState.setServiceAlive(false)
        super.onDestroy()
    }

    // ---- notification ----

    private fun showNotification(status: NodeStatus, detail: String) {
        if (!DaemonState.serviceAlive.value) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(status, detail))
        }
    }

    private fun buildNotification(status: NodeStatus, detail: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_node_status), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, DaemonService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val title = when (status) {
            NodeStatus.STOPPED -> getString(R.string.status_stopped)
            NodeStatus.STARTING -> getString(R.string.status_starting)
            NodeStatus.RUNNING -> getString(R.string.notif_running, Settings.API_PORT)
            NodeStatus.STOPPING -> getString(R.string.status_stopping)
            NodeStatus.ERROR -> getString(R.string.status_error)
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_stat_node)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop_node), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "node"
        private const val NOTIF_ID = 1
        private const val SIGTERM = 15
        const val ACTION_STOP = "studio.multitool.meshdroid.STOP"
        const val ACTION_RESTART = "studio.multitool.meshdroid.RESTART"

        fun start(ctx: Context, device: UsbDevice) =
            ctx.startForegroundService(Intent(ctx, DaemonService::class.java).putExtra(UsbManager.EXTRA_DEVICE, device))

        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

        /** Stop and start again, sequenced on the service's control thread. */
        fun restart(ctx: Context) = send(ctx, ACTION_RESTART)

        private fun send(ctx: Context, action: String) {
            val i = Intent(ctx, DaemonService::class.java).setAction(action)
            runCatching { ctx.startService(i) }
                .onFailure { if (action == ACTION_STOP) ctx.stopService(Intent(ctx, DaemonService::class.java)) }
        }
    }
}
