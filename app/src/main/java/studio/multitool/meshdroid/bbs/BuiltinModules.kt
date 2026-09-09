package studio.multitool.meshdroid.bbs

import studio.multitool.meshdroid.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The modules that ship with the app.
 *
 * Every reply is written to fit a single mesh packet where it can. A LoRa channel is shared
 * and slow, so a listing gives a count and the first few entries rather than everything,
 * and points at the command that fetches one item in full.
 */
private val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.US)

class HelpModule(private val modules: () -> List<BbsModule>) : BbsModule {
    override val keyword = "help"
    override val aliases = listOf("?", "commands")
    override val help = "help - this list"
    override suspend fun handle(request: BbsRequest): String {
        val topic = request.words().firstOrNull()?.lowercase()
        if (topic != null) {
            val m = modules().firstOrNull { it.keyword == topic || topic in it.aliases }
            return m?.help ?: "No command called $topic. Send help for the list."
        }
        return "Commands: " + modules().joinToString(", ") { it.keyword }
    }
}

class AboutModule(private val nodeName: () -> String?) : BbsModule {
    override val keyword = "about"
    override val help = "about - what this node is running"
    override suspend fun handle(request: BbsRequest): String {
        val name = nodeName()?.let { "$it, " } ?: ""
        return "${name}Meshdroid ${BuildConfig.VERSION_NAME} BBS on an Android phone. " +
            "${request.store.bulletinCount()} bulletins."
    }
}

/**
 * Path check. Answers with the radio quality of the last hop when the message was heard
 * directly, and with the distance in hops when it was relayed, since signal figures
 * describe the final hop rather than the whole path.
 */
class PingModule : BbsModule {
    override val keyword = "ping"
    override val aliases = listOf("test")
    override val help = "ping or test - report the path from you to this node"

    override suspend fun handle(request: BbsRequest): String {
        val head = if (request.keyword == "test") "test ok from" else "pong to"
        return "$head ${request.who()}, " + path(request)
    }

    companion object {
        fun path(request: BbsRequest): String {
            val signal = listOfNotNull(
                request.snr?.let { "snr %.1f".format(it) },
                request.rssi?.let { "rssi $it" }
            ).joinToString(" ")
            return when {
                request.hopsAway == null -> signal.ifBlank { "path unknown" }
                request.hopsAway == 0 -> "heard direct" + if (signal.isNotBlank()) ", $signal" else ""
                else -> "${request.hopsAway} hop" + (if (request.hopsAway == 1) "" else "s") +
                    " away" + if (signal.isNotBlank()) ", last hop $signal" else ""
            }
        }
    }
}

/**
 * The bulletin board. Posts are public to anyone who asks the node for them, which is the
 * point: a message left here reaches whoever passes by later.
 */
class BoardModule : BbsModule {
    override val keyword = "board"
    override val aliases = listOf("bb")
    override val help = "board list | board read <n> | board post <text>"

    override suspend fun handle(request: BbsRequest): String {
        val words = request.words()
        return when (words.firstOrNull()?.lowercase()) {
            null, "list" -> list(request)
            "read" -> read(request, words.getOrNull(1))
            "post" -> post(request)
            else -> "board list, board read <n>, or board post <text>"
        }
    }

    private fun list(request: BbsRequest): String {
        val items = request.store.bulletins(limit = 5)
        if (items.isEmpty()) return "No bulletins yet. Post one with: board post <text>"
        val total = request.store.bulletinCount()
        val lines = items.joinToString("\n") { b ->
            "${b.id}. ${b.from}: " + b.text.take(40) + if (b.text.length > 40) "..." else ""
        }
        val more = if (total > items.size) "\n$total total, board read <n> for one in full" else ""
        return lines + more
    }

    private fun read(request: BbsRequest, arg: String?): String {
        val id = arg?.toLongOrNull() ?: return "Which one? board read <n>"
        val b = request.store.bulletin(id) ?: return "No bulletin $id"
        return "${b.id}. ${b.from} ${stamp.format(Date(b.at * 1000))}\n${b.text}"
    }

    private fun post(request: BbsRequest): String {
        val body = Sanitize.body(request.args.trim().removePrefix("post").trim(), MAX_BULLETIN)
        if (body.isBlank()) return "Nothing to post. board post <text>"
        val sender = Sanitize.line(request.who(), 32)
        val recent = request.store.bulletinsBySenderSince(sender, (System.currentTimeMillis() / 1000) - 3600)
        if (recent >= BbsStore.MAX_BULLETINS_PER_SENDER_HOUR) {
            return "You have posted ${BbsStore.MAX_BULLETINS_PER_SENDER_HOUR} times this hour. Try later."
        }
        val id = request.store.postBulletin(sender, body)
        return "Posted as $id"
    }

    companion object { private const val MAX_BULLETIN = 500 }
}

/**
 * Store and forward mail between nodes.
 *
 * Mail waits in the database until its recipient asks for it, so both parties never have to
 * be on the mesh at the same time. Addresses are whatever the sender typed, matched against
 * the name or node id the recipient is known by, which is the loosest thing that works
 * without a directory.
 */
class MailModule : BbsModule {
    override val keyword = "mail"
    override val help = "mail - read yours | mail <to> <text> - leave a message"

    override suspend fun handle(request: BbsRequest): String {
        val words = request.words()
        if (words.isEmpty()) return inbox(request)

        val rawTo = words.first()
        val body = Sanitize.body(request.args.trim().removePrefix(rawTo).trim(), MAX_MAIL)
        if (body.isBlank()) return "Nothing to send. mail <to> <text>"

        // The Meshtastic app turns a mention into @!433d8ac0, so that form, a bare !nodeid
        // and a plain name all arrive here. A name that matches a node this node knows is
        // stored as the node number instead, so the mail still finds them if they rename.
        val address = Sanitize.address(rawTo)
        if (address.isBlank()) return "Who to? mail <name or !nodeid> <text>"
        val canonical = if (Sanitize.isNodeAddress(address)) address
            else request.resolve(address)?.toString() ?: address

        val sender = Sanitize.line(request.who(), 32)
        if (request.store.pendingMailFrom(sender) >= BbsStore.MAX_PENDING_MAIL_PER_SENDER) {
            return "You have too much mail waiting to be collected. Try later."
        }
        if (request.store.pendingMailCount() >= BbsStore.MAX_PENDING_MAIL) return "The mailbox is full."

        request.store.sendMail(sender, canonical, body)
        return "Held for $rawTo. They get it when they next check."
    }

    /**
     * Mail addressed either to the sender's node number or to a name they are known by.
     * Names are matched because a sender may have been addressed before this node learned
     * their number, and node numbers because names change.
     */
    private fun inbox(request: BbsRequest): String {
        val keys = listOfNotNull(
            request.fromAddress(),
            request.fromName?.let { Sanitize.address(it) }?.takeIf { it.isNotBlank() }
        ).distinct()
        val waiting = keys.flatMap { request.store.mailFor(it) }.distinctBy { it.id }
        if (waiting.isEmpty()) return "No mail waiting."
        val shown = waiting.take(3)
        request.store.markDelivered(shown.map { it.id })
        val rest = waiting.size - shown.size
        return shown.joinToString("\n") { "from ${it.from}: ${it.text}" } +
            if (rest > 0) "\n$rest more, send mail again" else ""
    }

    companion object { private const val MAX_MAIL = 400 }
}

/**
 * Remote housekeeping, for the operators of this node.
 *
 * Two checks have to pass, and the second is the one that matters. The sender's node number
 * must be in the admins table, and the message must have arrived encrypted to this node's
 * public key. A node number in a packet is whatever the sender chose to put there, so the
 * table alone would let anyone who knows an admin's number clear the board. The firmware
 * only reports pki_encrypted after decrypting with this node's private key, and only a
 * sender holding the admin's own key can produce that, so it stands in for a signature.
 *
 * The requirement can be relaxed in the BBS tab for a mesh with keys turned off, and the
 * setting says what that costs.
 */
class AdminModule(private val requirePki: () -> Boolean) : BbsModule {
    override val keyword = "admin"
    override val help = "admin status | admin clear board | admin clear mail | admin delete <n>"

    override suspend fun handle(request: BbsRequest): String? {
        // Never on a channel, whoever is asking: a channel is readable by everyone holding
        // the key, and an admin command is not a broadcast.
        if (!request.direct) return null

        if (!request.store.isAdmin(request.fromAddress())) {
            return "Not permitted."
        }
        if (requirePki() && !request.pkiEncrypted) {
            return "Admin commands must be sent as an encrypted direct message. " +
                "Your message arrived unencrypted, so it cannot be trusted."
        }

        val words = request.words()
        return when (words.firstOrNull()?.lowercase()) {
            null, "status" -> "Board ${request.store.bulletinCount()}, mail ${request.store.mailCount()} " +
                "(${request.store.pendingMailCount()} waiting), admins ${request.store.adminCount()}"
            "clear" -> clear(request, words.getOrNull(1)?.lowercase())
            "delete" -> {
                val id = words.getOrNull(1)?.toLongOrNull() ?: return "Which one? admin delete <n>"
                if (request.store.deleteBulletin(id)) "Deleted $id" else "No bulletin $id"
            }
            else -> help
        }
    }

    private fun clear(request: BbsRequest, what: String?): String = when (what) {
        "board" -> "Cleared ${request.store.clearBulletins()} bulletins"
        "mail" -> "Cleared ${request.store.clearMail()} messages"
        "delivered" -> "Cleared ${request.store.clearDeliveredMail()} collected messages"
        else -> "admin clear board, admin clear mail, or admin clear delivered"
    }
}
