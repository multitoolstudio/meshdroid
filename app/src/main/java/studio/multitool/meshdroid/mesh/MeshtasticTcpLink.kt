package studio.multitool.meshdroid.mesh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums
import org.meshtastic.proto.TelemetryProtos
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

/**
 * MeshLink over meshtasticd's TCP API.
 *
 * The daemon accepts exactly one client, so while this link is connected the Meshtastic
 * phone app cannot be. That is the reason the link is off by default and driven from the
 * Mesh tab rather than started with the node.
 *
 * The session is the same one any client runs: connect, send a want_config_id nonce, read
 * the config dump (my_info, node_info, channels, config) until config_complete_id comes
 * back with the same nonce, then stay on the stream. A heartbeat every few minutes keeps
 * the daemon from treating the connection as dead.
 */
class MeshtasticTcpLink(
    private val host: String = "127.0.0.1",
    private val port: Int = 4403
) : MeshLink {

    private val _status = MutableStateFlow(LinkStatus.DISCONNECTED)
    override val status: StateFlow<LinkStatus> = _status

    private val _selfNode = MutableStateFlow<NodeId?>(null)
    override val selfNode: StateFlow<NodeId?> = _selfNode

    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<MeshEvent> = _events

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: Job? = null
    private var socket: Socket? = null
    private var out: BufferedOutputStream? = null
    private val writeLock = Mutex()
    @Volatile private var enabled = false

    override fun start() {
        if (session?.isActive == true) return
        enabled = true
        session = scope.launch { runSessions() }
    }

    override fun stop() {
        enabled = false
        val running = session
        session = null
        // The disconnect goes first, while the socket is still open, so the daemon frees
        // its single client slot immediately instead of waiting for the read to fail.
        scope.launch {
            runCatching { sendToRadio(MeshProtos.ToRadio.newBuilder().setDisconnect(true).build()) }
            runCatching { running?.cancelAndJoin() }
            closeSocket()
            setStatus(LinkStatus.DISCONNECTED)
            _selfNode.value = null
        }
    }

    /** Reconnects with a widening delay for as long as the link is enabled. */
    private suspend fun runSessions() {
        var backoffMs = 1000L
        while (enabled && scope.isActive) {
            try {
                runSession()
                backoffMs = 1000L        // a session that ran is a healthy one
            } catch (e: Exception) {
                if (!enabled) break
                Log.w(TAG, "session ended: ${e.message}")
                setStatus(LinkStatus.ERROR, e.message)
            } finally {
                closeSocket()
            }
            if (!enabled) break
            setStatus(LinkStatus.CONNECTING, "reconnecting in ${backoffMs / 1000}s")
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        if (!enabled) setStatus(LinkStatus.DISCONNECTED)
    }

    private suspend fun runSession() = withContext(Dispatchers.IO) {
        setStatus(LinkStatus.CONNECTING)
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 5000)
        s.tcpNoDelay = true
        socket = s
        val input = BufferedInputStream(s.getInputStream())
        out = BufferedOutputStream(s.getOutputStream())

        val nonce = Random.nextInt(1, Int.MAX_VALUE)
        setStatus(LinkStatus.SYNCING)
        sendToRadio(MeshProtos.ToRadio.newBuilder().setWantConfigId(nonce).build())

        val heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                runCatching {
                    sendToRadio(MeshProtos.ToRadio.newBuilder()
                        .setHeartbeat(MeshProtos.Heartbeat.newBuilder().setNonce(Random.nextInt())).build())
                }
            }
        }

        try {
            while (isActive) {
                // Null means the daemon closed the socket. That is usually another client
                // taking the slot rather than a fault, so it is logged and not thrown.
                val frame = StreamFrames.read(input) ?: run {
                    Log.i(TAG, "daemon closed the connection, most likely another client took the slot")
                    setStatus(LinkStatus.DISCONNECTED, "the node closed the connection")
                    null
                } ?: break
                val from = try {
                    MeshProtos.FromRadio.parseFrom(frame)
                } catch (e: Exception) {
                    Log.w(TAG, "undecodable frame of ${frame.size} bytes: ${e.message}")
                    continue
                }
                // One packet that cannot be turned into an event is not a reason to drop
                // the session, which would hand the client slot away for the length of a
                // reconnect.
                try {
                    handle(from, nonce)
                } catch (e: Exception) {
                    Log.w(TAG, "failed to handle ${from.payloadVariantCase}: ${e.message}")
                }
            }
        } finally {
            heartbeat.cancel()
        }
    }

    private suspend fun handle(from: MeshProtos.FromRadio, nonce: Int) {
        when (from.payloadVariantCase) {
            MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> {
                _selfNode.value = NodeId(from.myInfo.myNodeNum)
            }
            MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID -> {
                // Only our own dump ends the handshake. Another client's nonce would be a
                // different number, which matters once a proxy fans this stream out.
                if (from.configCompleteId == nonce) setStatus(LinkStatus.CONNECTED)
            }
            MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> {
                val n = from.nodeInfo
                emit(MeshEvent.NodeSeen(
                    id = NodeId(n.num),
                    longName = n.user.longName.ifBlank { null },
                    shortName = n.user.shortName.ifBlank { null },
                    batteryLevel = if (n.hasDeviceMetrics() && n.deviceMetrics.hasBatteryLevel())
                        n.deviceMetrics.batteryLevel else null,
                    lastHeard = n.lastHeard.toLong() and 0xFFFFFFFFL
                ))
            }
            MeshProtos.FromRadio.PayloadVariantCase.PACKET -> handlePacket(from.packet)
            else -> Unit   // config, module config, channels, log records, queue status
        }
    }

    private suspend fun handlePacket(p: MeshProtos.MeshPacket) {
        if (!p.hasDecoded()) {
            // Encrypted: a channel key this node does not hold, or PKI for someone else.
            emit(MeshEvent.Other(NodeId(p.from), "ENCRYPTED", p.encrypted.size()))
            return
        }
        val data = p.decoded
        when (data.portnum) {
            Portnums.PortNum.TEXT_MESSAGE_APP -> emit(MeshEvent.Text(
                from = NodeId(p.from),
                to = NodeId(p.to),
                channel = p.channel,
                text = data.payload.toStringUtf8(),
                rxTime = if (p.hasRxTime()) p.rxTime.toLong() and 0xFFFFFFFFL else System.currentTimeMillis() / 1000,
                snr = if (p.rxSnr != 0f) p.rxSnr else null,
                rssi = if (p.hasRxRssi()) p.rxRssi else null,
                // hop_start is what the sender set out with and hop_limit is what is left,
                // so the difference is how far this packet travelled. Older senders leave
                // hop_start at zero, where the count is unknown rather than zero hops.
                hopsAway = if (p.hopStart > 0) (p.hopStart - p.hopLimit).coerceAtLeast(0) else null,
                pkiEncrypted = p.pkiEncrypted
            ))
            else -> emit(MeshEvent.Other(NodeId(p.from), data.portnum.name, data.payload.size()))
        }
    }

    override suspend fun sendText(text: String, to: NodeId?, channel: Int) {
        val body = text.toByteArray()
        require(body.size <= MAX_TEXT_BYTES) { "message is longer than $MAX_TEXT_BYTES bytes" }
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo((to ?: NodeId.BROADCAST).value)
            .setChannel(channel)
            .setId(Random.nextInt())
            .setWantAck(to != null)
            .setDecoded(
                MeshProtos.Data.newBuilder()
                    .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                    .setPayload(com.google.protobuf.ByteString.copyFrom(body))
            )
            .build()
        sendToRadio(MeshProtos.ToRadio.newBuilder().setPacket(packet).build())
    }

    override suspend fun sendEnvironment(pressureHpa: Float?, lux: Float?,
                                         temperatureC: Float?, relativeHumidity: Float?) {
        val env = TelemetryProtos.EnvironmentMetrics.newBuilder()
        pressureHpa?.let { env.setBarometricPressure(it) }
        lux?.let { env.setLux(it) }
        temperatureC?.let { env.setTemperature(it) }
        relativeHumidity?.let { env.setRelativeHumidity(it) }
        val telemetry = TelemetryProtos.Telemetry.newBuilder()
            .setTime((System.currentTimeMillis() / 1000).toInt())
            .setEnvironmentMetrics(env)
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(NodeId.BROADCAST.value)
            .setId(Random.nextInt())
            .setDecoded(
                MeshProtos.Data.newBuilder()
                    .setPortnum(Portnums.PortNum.TELEMETRY_APP)
                    .setPayload(com.google.protobuf.ByteString.copyFrom(telemetry.toByteArray()))
            )
            .build()
        sendToRadio(MeshProtos.ToRadio.newBuilder().setPacket(packet).build())
    }

    private suspend fun sendToRadio(msg: MeshProtos.ToRadio) = writeLock.withLock {
        val stream = out ?: throw IllegalStateException("not connected")
        withContext(Dispatchers.IO) { StreamFrames.write(stream, msg.toByteArray()) }
    }

    private fun closeSocket() {
        runCatching { out?.flush() }
        runCatching { socket?.close() }
        socket = null; out = null
    }

    private suspend fun emit(e: MeshEvent) { _events.emit(e) }

    private fun setStatus(s: LinkStatus, detail: String? = null) {
        _status.value = s
        _events.tryEmit(MeshEvent.StatusChanged(s, detail))
    }

    companion object {
        private const val TAG = "MeshLink"
        private const val HEARTBEAT_MS = 5 * 60 * 1000L
        /** A text message has to fit in one frame alongside its packet headers. */
        const val MAX_TEXT_BYTES = 200
    }
}
