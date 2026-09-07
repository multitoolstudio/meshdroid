package studio.multitool.meshdroid

import android.net.LocalServerSocket
import android.util.Log
import java.io.FileDescriptor

/**
 * Hands the USB device descriptor to the daemon process.
 *
 * Android's ProcessBuilder closes every inherited descriptor in the child, so
 * the usbfs fd cannot be passed by inheritance. Instead this class listens on an
 * abstract-namespace Unix socket; the daemon (libch341-spi-userspace with patch
 * 0001) connects to it, and the fd is sent as SCM_RIGHTS ancillary data, which
 * LocalSocket exposes through setFileDescriptorsForSend(). The daemon receives
 * a duplicate; closing it on either side does not affect the other.
 *
 * The socket name is passed to the daemon in the PINEDIO_USB_FD_SOCKET
 * environment variable. Every accepted connection gets the fd, which lets the
 * firmware reopen the device after a LoRa error without a restart.
 */
class UsbFdServer(private val name: String, private val fd: FileDescriptor) {
    private var server: LocalServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        server = LocalServerSocket(name)
        thread = Thread({
            try {
                while (true) {
                    val client = server?.accept() ?: break
                    Log.i(TAG, "daemon connected, sending USB fd")
                    client.setFileDescriptorsForSend(arrayOf(fd))
                    client.outputStream.write(1)   // ancillary data rides on one payload byte
                    client.outputStream.flush()
                    client.setFileDescriptorsForSend(null)
                    client.close()
                }
            } catch (e: Exception) {
                Log.d(TAG, "fd server stopped: ${e.message}")
            }
        }, "usb-fd-server").apply { isDaemon = true; start() }
    }

    fun stop() {
        val s = server
        server = null
        try { s?.close() } catch (_: Exception) {}   // unblocks accept(), which ends the thread
        try { thread?.join(500) } catch (_: Exception) {}
        thread = null
    }

    companion object { private const val TAG = "UsbFdServer" }
}
