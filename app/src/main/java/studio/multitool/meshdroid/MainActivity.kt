package studio.multitool.meshdroid

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import studio.multitool.meshdroid.ui.MeshdroidApp
import studio.multitool.meshdroid.ui.MeshdroidTheme

/**
 * Single activity. Hosts the Compose UI and owns everything USB-related that
 * needs an Activity context: device discovery, the permission request, and the
 * USB_DEVICE_ATTACHED launch intent. Starting and stopping the node is delegated
 * to DaemonService.
 */
class MainActivity : ComponentActivity() {

    private val usb by lazy { getSystemService(USB_SERVICE) as UsbManager }

    /** True while a CH341 dongle is attached. Enables the Start button. */
    var radioPresent by mutableStateOf(false)
        private set

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) startNode(device)
                    else DaemonState.setStatus(NodeStatus.STOPPED, "USB permission denied.")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshRadioPresent()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, Context.RECEIVER_EXPORTED)
        refreshRadioPresent()

        setContent {
            MeshdroidTheme {
                MeshdroidApp(
                    radioPresent = radioPresent,
                    onStart = { connect() },
                    onStop = { DaemonService.stop(this) },
                    onRestart = { DaemonService.stop(this); window.decorView.postDelayed({ connect() }, 1200) },
                    onClose = {
                        DaemonService.stop(this)
                        // Let the service release USB and the wake lock, then end the process.
                        window.decorView.postDelayed({ finishAndRemoveTask(); android.os.Process.killProcess(android.os.Process.myPid()) }, 600)
                    }
                )
            }
        }
        if (savedInstanceState == null) handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleUsbIntent(intent) }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        if (!Settings.autoStart(this)) return
        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { startNode(it) }
    }

    private fun findRadio(): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == 0x1A86 && it.productId == 0x5512 }

    private fun refreshRadioPresent() { radioPresent = findRadio() != null }

    private fun connect() {
        val device = findRadio()
        if (device == null) { DaemonState.setStatus(NodeStatus.STOPPED, "No CH341 radio found. Check the OTG cable."); return }
        if (usb.hasPermission(device)) { startNode(device); return }
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(device, pi)
        DaemonState.setStatus(NodeStatus.STOPPED, "Waiting for USB permission…")
    }

    private fun startNode(device: UsbDevice) = DaemonService.start(this, device)

    override fun onDestroy() { unregisterReceiver(usbReceiver); super.onDestroy() }

    companion object { const val ACTION_USB_PERMISSION = "studio.multitool.meshdroid.USB_PERMISSION" }
}
