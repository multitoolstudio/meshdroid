package studio.multitool.meshdroid

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import studio.multitool.meshdroid.ui.MeshdroidApp
import studio.multitool.meshdroid.ui.MeshdroidTheme

/**
 * Single activity. Hosts the Compose UI and owns everything USB-related that
 * needs an Activity context: device discovery, the permission request, and the
 * USB_DEVICE_ATTACHED launch intent. Starting, stopping and restarting the node
 * are delegated to DaemonService, which sequences them on its own thread.
 */
class MainActivity : ComponentActivity() {

    private val usb by lazy { getSystemService(USB_SERVICE) as UsbManager }

    /** True while a CH341 dongle is attached. Enables the Start button. */
    var radioPresent by mutableStateOf(false)
        private set

    /** Set when the user denies notifications, so the UI can explain what is missing. */
    var notificationsDenied by mutableStateOf(false)
        private set

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsDenied = !granted
        }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) startNode(device)
                    else DaemonState.setStatus(NodeStatus.STOPPED, getString(R.string.detail_usb_permission_denied))
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshRadioPresent()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read before setContent. The bottom bar hides the tabs a profile does not
        // support, and Profile.selected starts at the default until something reads
        // the stored value, so leaving this to a LaunchedEffect painted Meshtastic's
        // tab set for a frame or two on every MeshCore launch. One small
        // SharedPreferences read, against a visible wrong first frame.
        Profile.current(this)
        requestNotificationPermission()
        lifecycleScope.launch(Dispatchers.IO) { Settings.warm(this@MainActivity) }
        // The BBS follows the node and the setting, whether or not its tab is ever opened.
        studio.multitool.meshdroid.bbs.BbsHolder.init(applicationContext)

        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, Context.RECEIVER_EXPORTED)
        refreshRadioPresent()

        setContent {
            MeshdroidTheme {
                MeshdroidApp(
                    radioPresent = radioPresent,
                    notificationsDenied = notificationsDenied,
                    onStart = { connect() },
                    onStop = { DaemonService.stop(this) },
                    onRestart = { DaemonService.restart(this) },
                    onClose = { closeApp() }
                )
            }
        }
        if (savedInstanceState == null) handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleUsbIntent(intent) }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) { notificationsDenied = false; return }
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            // Asked and refused before. Do not ask again on every launch; the UI
            // carries a note instead.
            notificationsDenied = true
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        if (!Settings.autoStart(this)) return
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { startNode(it) }
    }

    private fun findRadio(): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == CH341_VID && it.productId == CH341_PID }

    private fun refreshRadioPresent() { radioPresent = findRadio() != null }

    private fun connect() {
        val device = findRadio()
        if (device == null) { DaemonState.setStatus(NodeStatus.STOPPED, getString(R.string.detail_no_radio)); return }
        if (usb.hasPermission(device)) { startNode(device); return }
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(device, pi)
        DaemonState.setStatus(NodeStatus.STOPPED, getString(R.string.detail_waiting_permission))
    }

    private fun startNode(device: UsbDevice) = DaemonService.start(this, device)

    /**
     * Stops the node and quits. The activity waits for the service to report that
     * it has gone before finishing, so the daemon is never orphaned by the app
     * process disappearing first. The wait is bounded; a service that has not
     * finished tearing down in ten seconds is not going to.
     */
    private fun closeApp() {
        DaemonService.stop(this)
        lifecycleScope.launch {
            withTimeoutOrNull(10_000) {
                withContext(Dispatchers.Default) { DaemonState.serviceAlive.first { !it } }
            }
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() { runCatching { unregisterReceiver(usbReceiver) }; super.onDestroy() }

    companion object {
        const val ACTION_USB_PERMISSION = "studio.multitool.meshdroid.USB_PERMISSION"
        // Also listed in res/xml/device_filter.xml for the USB_DEVICE_ATTACHED intent.
        const val CH341_VID = 0x1A86
        const val CH341_PID = 0x5512
    }
}
