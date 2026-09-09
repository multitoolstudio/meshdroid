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

class PingModule : BbsModule {
    override val keyword = "ping"
    override val help = "ping - check the path to this node"
    override suspend fun handle(request: BbsRequest): String {
        val snr = request.snr?.let { " snr %.1f".format(it) } ?: ""
        return "pong to ${request.who()}$snr"
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
        val body = request.args.trim().removePrefix("post").trim()
        if (body.isBlank()) return "Nothing to post. board post <text>"
        val id = request.store.postBulletin(request.who(), body.take(MAX_BULLETIN))
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

        val to = words.first()
        val body = request.args.trim().removePrefix(to).trim()
        if (body.isBlank()) return "Nothing to send. mail <to> <text>"
        request.store.sendMail(request.who(), to.lowercase(), body.take(MAX_MAIL))
        return "Held for $to. They get it when they next check."
    }

    private fun inbox(request: BbsRequest): String {
        // Both the node id and the printable name are accepted as an address.
        val keys = listOfNotNull(request.from.toString().lowercase(), request.fromName?.lowercase())
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
