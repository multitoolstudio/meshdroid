package studio.multitool.meshdroid.mesh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A connection to the node, in terms that say nothing about how the node is reached.
 *
 * Everything above this interface (the Mesh tab today, the BBS and phone sensor telemetry
 * later) works in the types below and never touches a protobuf. That is deliberate: the
 * Meshtastic implementation is one of at least three intended shapes. A MeshCore link
 * would speak an entirely different wire format, and a multiplexing proxy would tap the
 * stream rather than own it, and neither should reach any further up than this file.
 */
interface MeshLink {
    val status: StateFlow<LinkStatus>

    /** Node number of the local node, once the config handshake has finished. */
    val selfNode: StateFlow<NodeId?>

    /** Hot stream. Events emitted before a collector arrives are not replayed. */
    val events: Flow<MeshEvent>

    fun start()
    fun stop()

    /** Sends a text message. A null destination broadcasts on the given channel. */
    suspend fun sendText(text: String, to: NodeId? = null, channel: Int = 0)

    /**
     * Publishes environment telemetry measured by the host rather than by the radio.
     * Every argument is optional; only the ones supplied are sent.
     */
    suspend fun sendEnvironment(pressureHpa: Float? = null, lux: Float? = null,
                                temperatureC: Float? = null, relativeHumidity: Float? = null)
}

/** A node number. Meshtastic writes these as `!a1b2c3d4`. */
@JvmInline
value class NodeId(val value: Int) {
    override fun toString() = "!" + value.toUInt().toString(16).padStart(8, '0')
    companion object { val BROADCAST = NodeId(-1) }   // 0xFFFFFFFF
}

enum class LinkStatus { DISCONNECTED, CONNECTING, SYNCING, CONNECTED, ERROR }

sealed interface MeshEvent {
    /** A text message, whether addressed to this node or broadcast on a channel. */
    data class Text(
        val from: NodeId,
        val to: NodeId,
        val channel: Int,
        val text: String,
        val rxTime: Long,
        val snr: Float?,
        val rssi: Int?,
        /** Hops the packet took to arrive, or null when the firmware did not say. Zero means heard directly. */
        val hopsAway: Int?,
        /**
         * True when the packet arrived encrypted to this node's public key, which the
         * firmware only reports after decrypting it with the matching private key. A
         * sender's node number is trivially forged; this is not, so it is what the admin
         * commands check.
         */
        val pkiEncrypted: Boolean
    ) : MeshEvent {
        val isDirect get() = to != NodeId.BROADCAST
    }

    /** A node appeared in the database, or was heard from again. */
    data class NodeSeen(
        val id: NodeId,
        val longName: String?,
        val shortName: String?,
        val batteryLevel: Int?,
        val lastHeard: Long
    ) : MeshEvent

    /** Anything else that arrived on the stream, kept so activity stays visible. */
    data class Other(val from: NodeId?, val portName: String, val bytes: Int) : MeshEvent

    data class StatusChanged(val status: LinkStatus, val detail: String? = null) : MeshEvent
}
