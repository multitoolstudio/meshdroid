package studio.multitool.meshdroid.bbs

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import studio.multitool.meshdroid.DaemonState
import studio.multitool.meshdroid.Settings
import studio.multitool.meshdroid.mesh.MeshEvent
import studio.multitool.meshdroid.mesh.MeshLink
import studio.multitool.meshdroid.mesh.MeshtasticTcpLink
import studio.multitool.meshdroid.mesh.NodeId

/**
 * The bulletin board system.
 *
 * Holds its own connection to the node rather than sharing the Mesh tab's, so that running
 * a BBS does not depend on a screen being open. With the proxy on, that connection is one
 * client among several and costs nothing; with the proxy off it takes the node's only slot,
 * which the UI warns about.
 *
 * Replies are queued and spaced rather than sent as they are produced. A BBS that answers
 * every message the instant it arrives will saturate a duty-cycled channel, and the node it
 * runs on is the one that suffers first.
 */
class BbsEngine(private val ctx: Context, private val link: MeshLink = MeshtasticTcpLink()) {

    data class Entry(val at: Long, val from: String, val request: String, val reply: String?, val sent: Boolean)

    private val store = BbsStore(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outbox = Channel<Pending>(capacity = 64)
    private var jobs = mutableListOf<Job>()

    private val _log = MutableStateFlow<List<Entry>>(emptyList())
    val log: StateFlow<List<Entry>> = _log

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** Time of the last reply to each sender, for the per-sender cooldown. */
    private val lastReplyTo = mutableMapOf<Int, Long>()

    private data class Pending(val to: NodeId?, val channel: Int, val text: String, val entry: Entry)

    private val modules: MutableList<BbsModule> = mutableListOf()

    init {
        modules += HelpModule { modules.toList() }
        modules += AboutModule { link.selfNode.value?.toString() }
        modules += PingModule()
        modules += BoardModule()
        modules += MailModule()
        modules += AdminModule { Settings.bbsAdminRequiresPki(ctx) }
    }

    fun start() {
        if (_running.value) return
        _running.value = true
        link.start()
        jobs += scope.launch { consume() }
        jobs += scope.launch { drainOutbox() }
        DaemonState.appendLog("[meshdroid] BBS started with ${modules.size} modules")
    }

    fun stop() {
        if (!_running.value) return
        _running.value = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        link.stop()
        DaemonState.appendLog("[meshdroid] BBS stopped")
    }

    private suspend fun consume() {
        link.events.collect { event ->
            when (event) {
                // Names come from the node database the daemon sends on connect and from
                // sightings after that, so replies can address people by name.
                is MeshEvent.NodeSeen -> {
                    val name = event.shortName ?: event.longName
                    if (name != null) names[event.id.value] = name
                }
                is MeshEvent.Text -> if (event.from != link.selfNode.value) handleText(event)
                else -> Unit
            }
        }
    }

    private suspend fun handleText(event: MeshEvent.Text) {
        val prefix = Settings.bbsPrefix(ctx)
        // Cleaned once, here, so no module has to think about control characters, bidi
        // overrides or length. Nothing downstream interprets this text, but it is stored
        // and displayed, and both of those deserve clean input.
        val body = Sanitize.line(event.text, MAX_COMMAND)

        // Direct messages are addressed to this node already. On a shared channel the
        // prefix keeps the BBS out of ordinary conversation.
        val command = when {
            event.isDirect -> body
            !Settings.bbsOnChannels(ctx) -> return
            body.startsWith(prefix) -> body.removePrefix(prefix).trim()
            else -> return
        }
        if (command.isBlank()) return

        val keyword = command.substringBefore(' ').lowercase()
        val module = modules.firstOrNull { it.keyword == keyword || keyword in it.aliases }
        val request = BbsRequest(
            from = event.from,
            fromName = nameFor(event.from),
            keyword = keyword,
            args = command.substringAfter(' ', ""),
            direct = event.isDirect,
            channel = event.channel,
            snr = event.snr,
            rssi = event.rssi,
            hopsAway = event.hopsAway,
            pkiEncrypted = event.pkiEncrypted,
            receivedAt = event.rxTime,
            store = store,
            resolve = ::resolveNode
        )

        // An unknown word on a channel is somebody talking, not a failed command. In a
        // direct message it is worth answering, since the sender meant it for this node.
        val reply = when {
            module != null -> runCatching { module.handle(request) }
                .onFailure { Log.w(TAG, "module ${module.keyword} failed: ${it.message}") }
                .getOrElse { "That command failed." }
            event.isDirect -> "Unknown command. Send help for the list."
            else -> null
        }

        val entry = Entry(System.currentTimeMillis() / 1000, request.who(), command, reply, false)
        if (reply == null) { record(entry); return }

        if (!allowedToReply(event.from)) {
            record(entry.copy(reply = "$reply [suppressed: too soon]", sent = false))
            return
        }
        outbox.trySend(Pending(if (event.isDirect) event.from else null, event.channel, reply, entry))
    }

    /**
     * One reply per sender per cooldown. Without this a node stuck in a loop, or someone
     * playing, keeps the channel busy for everyone.
     */
    private fun allowedToReply(from: NodeId): Boolean {
        val now = System.currentTimeMillis()
        val cooldownMs = Settings.bbsCooldownSeconds(ctx) * 1000L
        val last = lastReplyTo[from.value]
        if (last != null && now - last < cooldownMs) return false
        lastReplyTo[from.value] = now
        return true
    }

    /**
     * Sends queued replies with a gap between them, and splits anything longer than a
     * packet. The gap is airtime politeness rather than a protocol requirement.
     */
    private suspend fun drainOutbox() {
        for (pending in outbox) {
            val parts = split(pending.text)
            var sentAll = true
            parts.forEachIndexed { i, part ->
                runCatching { link.sendText(part, pending.to, pending.channel) }
                    .onFailure { sentAll = false; Log.w(TAG, "reply failed: ${it.message}") }
                if (i < parts.lastIndex) delay(SEND_GAP_MS)
            }
            record(pending.entry.copy(sent = sentAll))
            delay(SEND_GAP_MS)
        }
    }

    /** Splits on whitespace so a reply never breaks mid-word, with a (n/m) marker. */
    private fun split(text: String): List<String> {
        if (text.toByteArray().size <= MAX_PART) return listOf(text)
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        text.split(Regex("(?<=\\s)")).forEach { token ->
            if ((current.toString() + token).toByteArray().size > MAX_PART - MARKER_ROOM && current.isNotEmpty()) {
                parts += current.toString().trimEnd(); current.clear()
            }
            current.append(token)
        }
        if (current.isNotEmpty()) parts += current.toString().trimEnd()
        val capped = parts.take(MAX_PARTS)
        return capped.mapIndexed { i, p -> if (capped.size > 1) "$p (${i + 1}/${capped.size})" else p }
    }

    private val names = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun nameFor(id: NodeId): String? = names[id.value]

    /** Finds a node by the name it is known by, for addressing mail to a name. */
    private fun resolveNode(address: String): NodeId? {
        val wanted = Sanitize.address(address)
        if (Sanitize.isNodeAddress(wanted)) {
            return wanted.drop(1).toLongOrNull(16)?.toInt()?.let { NodeId(it) }
        }
        val hit = names.entries.firstOrNull { Sanitize.address(it.value) == wanted } ?: return null
        return NodeId(hit.key)
    }

    private fun record(entry: Entry) {
        _log.value = (_log.value + entry).let { if (it.size > LOG_MAX) it.drop(it.size - LOG_MAX) else it }
    }

    /** Runs a command without touching the radio, for the console on the BBS tab. */
    suspend fun runLocally(text: String, from: NodeId): String? {
        val keyword = text.trim().substringBefore(' ').lowercase()
        val module = modules.firstOrNull { it.keyword == keyword || keyword in it.aliases }
            ?: return "Unknown command. Send help for the list."
        val request = BbsRequest(
            from = from, fromName = "local", keyword = keyword,
            args = Sanitize.line(text.trim().substringAfter(' ', ""), MAX_COMMAND),
            direct = true, channel = 0, snr = null, rssi = null, hopsAway = 0,
            // The console runs on this device, so it is as trusted as the app itself.
            pkiEncrypted = true,
            receivedAt = System.currentTimeMillis() / 1000, store = store, resolve = ::resolveNode
        )
        return runCatching { module.handle(request) }.getOrElse { "That command failed: ${it.message}" }
    }

    fun moduleList(): List<BbsModule> = modules.toList()
    fun storeHandle(): BbsStore = store

    companion object {
        private const val TAG = "BbsEngine"
        private const val LOG_MAX = 200
        private const val MAX_PART = 180        // leaves room for the (n/m) marker
        private const val MARKER_ROOM = 8
        private const val MAX_PARTS = 3
        private const val SEND_GAP_MS = 4000L
        /** Longest command accepted. A text packet cannot carry more than about this. */
        private const val MAX_COMMAND = 240
    }
}
