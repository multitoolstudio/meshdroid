package studio.multitool.meshdroid

import android.net.LocalServerSocket
import android.net.LocalSocket
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
 * The abstract namespace is not scoped to an app, so the socket name alone would
 * let any process on the phone ask for the descriptor and take over the radio.
 * Every connection is therefore checked against the app's own UID with
 * SO_PEERCRED before anything is sent.
 *
 * The socket name is passed to the daemon in the PINEDIO_USB_FD_SOCKET
 * environment variable. Every accepted connection gets the fd, which lets the
 * firmware reopen the device after a LoRa error without a restart, so one failed
 * hand-off must not end the loop.
 */
class UsbFdServer(private val name: String, private val fd: FileDescriptor) {
    private var server: LocalServerSocket? = null
    private var thread: Thread? = null
    @Volatile private var stopped = false

    fun start() {
        server = LocalServerSocket(name)
        thread = Thread({
            while (!stopped) {
                val client = try {
                    server?.accept() ?: break
                } catch (e: Exception) {
                    if (!stopped) Log.d(TAG, "accept ended: ${e.message}")
                    break
                }
                try {
                    serve(client)
                } catch (e: Exception) {
                    // A daemon that dies mid-recovery closes the socket before the
                    // write lands. Log it and keep accepting.
                    Log.w(TAG, "fd hand-off failed: ${e.message}")
                } finally {
                    runCatching { client.close() }
                }
            }
        }, "usb-fd-server").apply { isDaemon = true; start() }
    }

    private fun serve(client: LocalSocket) {
        val peerUid = client.peerCredentials.uid
        if (peerUid != android.os.Process.myUid()) {
            Log.w(TAG, "refusing USB fd to uid $peerUid")
            DaemonState.appendLog("[meshdroid] refused a USB descriptor request from uid $peerUid")
            return
        }
        Log.i(TAG, "daemon connected, sending USB fd")
        client.setFileDescriptorsForSend(arrayOf(fd))
        client.outputStream.write(1)   // ancillary data rides on one payload byte
        client.outputStream.flush()
        client.setFileDescriptorsForSend(null)
    }

    fun stop() {
        stopped = true
        val s = server
        server = null
        try { s?.close() } catch (_: Exception) {}   // unblocks accept(), which ends the thread
        try { thread?.join(500) } catch (_: Exception) {}
        thread = null
    }

    companion object { private const val TAG = "UsbFdServer" }
}
