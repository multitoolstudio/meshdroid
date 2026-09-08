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
import java.io.File

/**
 * Foreground service that owns one meshtasticd process.
 *
 * Lifecycle: onStartCommand opens the USB device, acquires a wake lock, starts
 * the fd hand-off socket, then launches the daemon binary from nativeLibraryDir.
 * A reader thread forwards the daemon's stdout into DaemonState and to logcat.
 * onDestroy tears everything down in reverse order. The service is stopped by
 * the UI (ACTION_STOP), the notification action, USB detach, or the daemon
 * exiting on its own.
 */
class DaemonService : Service() {

    private var connection: UsbDeviceConnection? = null
    private var fdServer: UsbFdServer? = null
    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false
    @Volatile private var stopping = false

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                DaemonState.appendLog("[meshdroid] USB radio detached")
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            if (!running) runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf(); return START_NOT_STICKY
        }
        val device: UsbDevice? = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        if (device == null) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        running = true

        val notif = buildNotification("Starting node…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_ID, notif)

        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), Context.RECEIVER_EXPORTED)
        DaemonState.clearLog()
        DaemonState.setStatus(NodeStatus.STARTING, "Opening USB radio…")

        val usb = getSystemService(USB_SERVICE) as UsbManager
        val conn = usb.openDevice(device)
        if (conn == null) {
            DaemonState.setStatus(NodeStatus.ERROR, "USB permission denied or device busy.")
            stopSelf(); return START_NOT_STICKY
        }
        connection = conn

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meshdroid:daemon").apply { acquire() }

        // fromFd() duplicates the descriptor; `conn` keeps owning the original and
        // is closed in onDestroy.
        val pfd = ParcelFileDescriptor.fromFd(conn.fileDescriptor)
        // Unique per start. A Restart can begin before the previous instance has
        // released its socket name, and abstract-namespace names cannot be reused
        // while bound.
        val socketName = "meshdroid-usbfd-" + System.nanoTime().toString(36)
        try {
            fdServer = UsbFdServer(socketName, pfd.fileDescriptor).also { it.start() }
        } catch (e: Exception) {
            DaemonState.setStatus(NodeStatus.ERROR, "Could not create USB hand-off socket: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }

        launchDaemon(socketName)
        return START_STICKY
    }

    /**
     * Kills any meshtasticd left over from a previous app process. If the app
     * crashes, the daemon child survives as an orphan holding the USB device and
     * the TCP port. Processes of the same UID are visible under /proc and can be
     * signalled, which is all this needs.
     */
    private fun killStaleDaemons() {
        val me = android.os.Process.myPid()
        val procDir = File("/proc")
        procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }?.forEach { d ->
            val pid = d.name.toInt()
            if (pid == me) return@forEach
            val cmd = runCatching { File(d, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (cmd.contains("libmeshtasticd.so")) {
                DaemonState.appendLog("[meshdroid] killing stale meshtasticd (pid $pid) from a previous run")
                android.os.Process.killProcess(pid)
            }
        }
    }

    private fun portInUse(): Boolean = runCatching {
        java.net.ServerSocket().use { it.reuseAddress = true; it.bind(java.net.InetSocketAddress("0.0.0.0", Settings.API_PORT)) }
        false
    }.getOrDefault(true)

    private fun launchDaemon(socketName: String) {
        killStaleDaemons()
        Thread.sleep(150)
        if (portInUse()) {
            DaemonState.setStatus(NodeStatus.ERROR,
                "Port ${Settings.API_PORT} is already in use by another process (a leftover test daemon? run: adb shell pkill -f mtd).")
            stopSelf(); return
        }
        NodeFiles.ensureDefaultConfig(this)
        val config = NodeFiles.configFile(this)
        val fsDir = NodeFiles.fsDir(this)
        val bin = File(applicationInfo.nativeLibraryDir, "libmeshtasticd.so")
        if (!bin.exists()) {
            DaemonState.setStatus(NodeStatus.ERROR, "Daemon binary missing from this build.")
            stopSelf(); return
        }

        val args = mutableListOf(bin.absolutePath, "-c", config.absolutePath, "-d", fsDir.absolutePath,
            "-p", Settings.API_PORT.toString())
        if (Settings.verbose(this)) args += "-v"

        val lan = Settings.lanEnabled(this)
        val pb = ProcessBuilder(args).redirectErrorStream(true).directory(filesDir)
        pb.environment().apply {
            put("PINEDIO_USB_FD_SOCKET", socketName)
            put("HOME", filesDir.absolutePath)
            put("LD_LIBRARY_PATH", applicationInfo.nativeLibraryDir)   // libc++_shared.so is packaged beside the binary
            put("MESHTASTICD_BIND_ADDR", if (lan) "0.0.0.0" else "127.0.0.1")
        }
        DaemonState.appendLog("[meshdroid] starting meshtasticd, API ${if (lan) "on LAN + localhost" else "localhost only"}:${Settings.API_PORT}")

        val p = try { pb.start() } catch (e: Exception) {
            DaemonState.setStatus(NodeStatus.ERROR, "Could not launch daemon: ${e.message}")
            stopSelf(); return
        }
        process = p
        Thread({
            // On Stop the pipe closes while readLine() is blocked; that is expected.
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    DaemonState.appendLog(line)
                    Log.i("meshtasticd", line)
                }
            } catch (e: Exception) {
                if (!stopping) DaemonState.appendLog("[meshdroid] log stream ended: ${e.message}")
            }
            val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
            DaemonState.appendLog("[meshdroid] meshtasticd exited with code $code")
            if (!stopping && code != 0 && DaemonState.status.value != NodeStatus.ERROR)
                DaemonState.setStatus(NodeStatus.ERROR, "Daemon exited with code $code. See Logs.")
            if (!stopping) stopSelf()   // during a deliberate stop, onDestroy is already running
        }, "meshtasticd-stdout").apply { isDaemon = true; start() }

        updateNotification("Node running · Meshtastic app → 127.0.0.1:${Settings.API_PORT}")
    }

    override fun onDestroy() {
        stopping = true
        running = false
        runCatching { unregisterReceiver(detachReceiver) }
        runCatching { process?.destroy() }; process = null
        runCatching { fdServer?.stop() }; fdServer = null
        runCatching { connection?.close() }; connection = null
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }; wakeLock = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (DaemonState.status.value != NodeStatus.ERROR) DaemonState.setStatus(NodeStatus.STOPPED, "Node stopped.")
        super.onDestroy()
    }

    // ---- notification ----
    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Node status", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, DaemonService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Meshdroid")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_node)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop node", stop).build())
            .build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    companion object {
        private const val CHANNEL = "node"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "studio.multitool.meshdroid.STOP"

        fun start(ctx: Context, device: UsbDevice) =
            ctx.startForegroundService(Intent(ctx, DaemonService::class.java).putExtra(UsbManager.EXTRA_DEVICE, device))
        fun stop(ctx: Context) {
            // Deliver ACTION_STOP first; it sets `stopping` before teardown begins,
            // which keeps a killed daemon from being reported as an error.
            runCatching { ctx.startService(Intent(ctx, DaemonService::class.java).setAction(ACTION_STOP)) }
                .onFailure { ctx.stopService(Intent(ctx, DaemonService::class.java)) }
        }
    }
}
