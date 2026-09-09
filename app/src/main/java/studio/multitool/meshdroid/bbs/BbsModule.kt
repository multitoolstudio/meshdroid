package studio.multitool.meshdroid.bbs

import studio.multitool.meshdroid.mesh.NodeId

/**
 * One BBS command, or a small family of them sharing a keyword.
 *
 * Modules see a parsed request and return the text to send back, or null to stay silent.
 * They are called one at a time on a background dispatcher, so a module may block on
 * storage, and none of them should assume anything about the transport: the same module
 * has to work when the link underneath is MeshCore rather than Meshtastic.
 */
interface BbsModule {
    /** First word that selects this module, lower case. */
    val keyword: String

    /** Extra keywords that select it, for aliases like "?" for help. */
    val aliases: List<String> get() = emptyList()

    /** One line for the help listing. Keep it short; help has to fit in a packet. */
    val help: String

    suspend fun handle(request: BbsRequest): String?
}

/**
 * A command as received. [args] is everything after the keyword, untrimmed of internal
 * spacing so a module can take free text.
 */
data class BbsRequest(
    val from: NodeId,
    val fromName: String?,
    val keyword: String,
    val args: String,
    val direct: Boolean,
    val channel: Int,
    val snr: Float?,
    val rssi: Int?,
    /** Hops this message took, zero for heard directly, null when the sender did not say. */
    val hopsAway: Int?,
    /** True when the message arrived encrypted to this node's key. Admin commands need it. */
    val pkiEncrypted: Boolean,
    val receivedAt: Long,
    val store: BbsStore,
    /** Resolves a name or a mention to a node number, when the node is one this node knows. */
    val resolve: (String) -> NodeId? = { null }
) {
    /**
     * Run by the engine once the reply has actually been sent. A module sets this when an
     * action should only take effect on delivery, which is how collected mail avoids being
     * marked as read when the reply never left.
     */
    var onDelivered: (() -> Unit)? = null

    /** The sender's node number in the canonical `!hex` form used for addresses and admins. */
    fun fromAddress(): String = from.toString()

    /** Args split on whitespace, empty entries dropped. */
    fun words(): List<String> = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** The sender as something worth printing: their short name if known, else the node id. */
    fun who(): String = fromName?.takeIf { it.isNotBlank() } ?: from.toString()
}
