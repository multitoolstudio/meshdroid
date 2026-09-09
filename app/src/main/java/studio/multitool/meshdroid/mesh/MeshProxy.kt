package studio.multitool.meshdroid.mesh

import android.util.Log
import org.meshtastic.proto.MeshProtos
import studio.multitool.meshdroid.DaemonState
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets several clients share the node's single API connection.
 *
 * meshtasticd accepts one TCP client. With this running the daemon listens on loopback
 * port 4404 and Meshdroid owns 4403, holding the daemon's one slot and fanning it out, so
 * the Meshtastic app, the Python CLI and Meshdroid's own MeshLink can all be attached at
 * once. MeshLink needs no special case: it connects to 4403 like anything else.
 *
 * Three rules make the sharing work.
 *
 * Config dumps are routed, not broadcast. Each client opens with a want_config_id nonce and
 * expects my_info, node_info, channels and config back, ending in config_complete_id. Those
 * go to the client that asked, in the order they asked, rather than to everyone, so a
 * second client connecting does not push an unrequested dump at the first.
 *
 * Live traffic is broadcast. Mesh packets, queue status and notifications reach every
 * client, since they are not part of anyone's handshake.
 *
 * A disconnect from a client is dropped rather than forwarded. Forwarding it would tell the
 * daemon to close the one upstream connection, taking every other client down with it.
 */
class MeshProxy(
    private val listenPort: Int,
    private val daemonPort: Int,
    private val bindLan: Boolean
) {
    private class Client(val socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val writeLock = Any()
        @Volatile var nonce: Int? = null    // set while waiting for its config dump
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private val awaitingDump = ArrayDeque<Client>()      // guarded by itself
    private var server: ServerSocket? = null
    private var upstream: Socket? = null
    private var upstreamOut: BufferedOutputStream? = null
    private val upstreamLock = Any()
    @Volatile private var stopped = false

    /**
     * Connects to the daemon, then starts listening. The daemon needs a moment to bind
     * after launch, so the upstream connection is retried rather than failing the start.
     */
    fun start() {
        val up = connectUpstream() ?: throw java.io.IOException("daemon did not accept a connection on port $daemonPort")
        upstream = up
        upstreamOut = BufferedOutputStream(up.getOutputStream())

        val bind = if (bindLan) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")
        server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bind, listenPort))
        }
        DaemonState.appendLog("[meshdroid] proxy listening on ${bind.hostAddress}:$listenPort, daemon on 127.0.0.1:$daemonPort")

        thread("mesh-proxy-downstream") { readFromDaemon(BufferedInputStream(up.getInputStream())) }
        thread("mesh-proxy-accept") { acceptLoop() }
    }

    private fun connectUpstream(): Socket? {
        val deadline = System.currentTimeMillis() + 15_000
        while (!stopped && System.currentTimeMillis() < deadline) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress("127.0.0.1", daemonPort), 2000)
                s.tcpNoDelay = true
                return s
            } catch (e: Exception) {
                runCatching { s.close() }
                Thread.sleep(300)
            }
        }
        return null
    }

    private fun acceptLoop() {
        while (!stopped) {
            val socket = try {
                server?.accept() ?: break
            } catch (e: Exception) {
                if (!stopped) Log.w(TAG, "accept ended: ${e.message}")
                break
            }
            socket.tcpNoDelay = true
            val client = Client(socket)
            clients += client
            DaemonState.setProxyClients(clients.size)
            DaemonState.appendLog("[meshdroid] proxy client connected from ${socket.inetAddress?.hostAddress} (${clients.size} attached)")
            thread("mesh-proxy-client") { readFromClient(client) }
        }
    }

    /** Client to daemon. Everything is forwarded except a client's own disconnect. */
    private fun readFromClient(client: Client) {
        try {
            while (!stopped) {
                val frame = StreamFrames.read(client.input) ?: break
                val msg = runCatching { MeshProtos.ToRadio.parseFrom(frame) }.getOrNull()
                when (msg?.payloadVariantCase) {
                    MeshProtos.ToRadio.PayloadVariantCase.WANT_CONFIG_ID -> {
                        client.nonce = msg.wantConfigId
                        synchronized(awaitingDump) { awaitingDump.addLast(client) }
                    }
                    MeshProtos.ToRadio.PayloadVariantCase.DISCONNECT -> {
                        // Meant for a one-client world. Honour it locally by closing this
                        // client, and never pass it upstream.
                        break
                    }
                    else -> Unit
                }
                // The null check stays outside synchronized(): a break out of an inline
                // lambda needs Kotlin 2.2, and this module targets 2.1.
                val out = upstreamOut ?: break
                synchronized(upstreamLock) { StreamFrames.write(out, frame) }
            }
        } catch (e: Exception) {
            if (!stopped) Log.d(TAG, "client ended: ${e.message}")
        } finally {
            drop(client)
        }
    }

    /** Daemon to clients. Config dump frames are routed; everything else is broadcast. */
    private fun readFromDaemon(input: BufferedInputStream) {
        try {
            while (!stopped) {
                val frame = StreamFrames.read(input) ?: break
                val msg = runCatching { MeshProtos.FromRadio.parseFrom(frame) }.getOrNull()
                val variant = msg?.payloadVariantCase

                if (variant != null && isDumpFrame(variant)) {
                    val target = synchronized(awaitingDump) { awaitingDump.firstOrNull() }
                    if (target != null) {
                        send(target, frame)
                        if (variant == MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID &&
                            msg.configCompleteId == target.nonce
                        ) {
                            target.nonce = null
                            synchronized(awaitingDump) { awaitingDump.removeFirstOrNull() }
                        }
                        continue
                    }
                }
                clients.forEach { send(it, frame) }
            }
        } catch (e: Exception) {
            if (!stopped) Log.w(TAG, "daemon stream ended: ${e.message}")
        }
        if (!stopped) DaemonState.appendLog("[meshdroid] proxy lost its connection to the daemon")
    }

    /**
     * Frames that belong to one client's handshake. Everything absent from this list, mesh
     * packets above all, is traffic every client should see.
     */
    private fun isDumpFrame(v: MeshProtos.FromRadio.PayloadVariantCase) = when (v) {
        MeshProtos.FromRadio.PayloadVariantCase.MY_INFO,
        MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO,
        MeshProtos.FromRadio.PayloadVariantCase.CONFIG,
        MeshProtos.FromRadio.PayloadVariantCase.MODULECONFIG,
        MeshProtos.FromRadio.PayloadVariantCase.CHANNEL,
        MeshProtos.FromRadio.PayloadVariantCase.METADATA,
        MeshProtos.FromRadio.PayloadVariantCase.FILEINFO,
        MeshProtos.FromRadio.PayloadVariantCase.DEVICEUICONFIG,
        MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID -> true
        else -> false
    }

    private fun send(client: Client, frame: ByteArray) {
        try {
            synchronized(client.writeLock) { StreamFrames.write(client.output, frame) }
        } catch (e: Exception) {
            Log.d(TAG, "write to client failed: ${e.message}")
            drop(client)
        }
    }

    private fun drop(client: Client) {
        if (!clients.remove(client)) return
        synchronized(awaitingDump) { awaitingDump.remove(client) }
        runCatching { client.socket.close() }
        DaemonState.setProxyClients(clients.size)
        DaemonState.appendLog("[meshdroid] proxy client disconnected (${clients.size} attached)")
    }

    fun stop() {
        stopped = true
        runCatching { server?.close() }; server = null
        clients.forEach { runCatching { it.socket.close() } }
        clients.clear()
        synchronized(awaitingDump) { awaitingDump.clear() }
        runCatching { upstream?.close() }
        upstream = null; upstreamOut = null
        DaemonState.setProxyClients(0)
    }

    private fun thread(name: String, body: () -> Unit) =
        Thread(body, name).apply { isDaemon = true; start() }

    companion object { private const val TAG = "MeshProxy" }
}
