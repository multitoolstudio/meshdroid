package studio.multitool.meshdroid.bbs

/**
 * Everything a BBS module handles arrives from the mesh, from senders nobody has vetted.
 *
 * There is no interpreter behind these commands, so a message cannot execute anything, and
 * every database call is parameterised, so it cannot alter a query. What remains are the
 * quieter problems: control characters and bidirectional overrides that make stored text
 * render as something other than what it is, newlines that forge extra lines in a reply or
 * a log, and length used to fill storage. Text is cleaned once on the way in, so nothing
 * downstream has to remember to do it.
 */
object Sanitize {

    /** Printable text on one line. Control characters, including newlines, become spaces. */
    fun line(input: String, max: Int): String = clean(input, max, keepNewlines = false)

    /** Printable text that may contain newlines, for a bulletin body. */
    fun body(input: String, max: Int): String = clean(input, max, keepNewlines = true)

    private fun clean(input: String, max: Int, keepNewlines: Boolean): String {
        val out = StringBuilder(minOf(input.length, max))
        var lastWasSpace = false
        for (ch in input) {
            if (out.length >= max) break
            val keep = when {
                ch == '\n' && keepNewlines -> true
                // C0 and C1 control ranges, and the delete character. Terminal escape
                // sequences and the like start here.
                ch.code < 0x20 || ch.code == 0x7f || (ch.code in 0x80..0x9f) -> false
                // Bidirectional overrides and embedding marks, which can display text in an
                // order it was not written in.
                ch.code in 0x202a..0x202e || ch.code in 0x2066..0x2069 -> false
                // Zero-width characters used to hide content or to fake a distinct name.
                ch.code == 0x200b || ch.code == 0x200c || ch.code == 0x200d || ch.code == 0xfeff -> false
                else -> true
            }
            if (!keep) {
                if (!lastWasSpace && out.isNotEmpty()) { out.append(' '); lastWasSpace = true }
                continue
            }
            lastWasSpace = ch == ' '
            out.append(ch)
        }
        return out.toString().trim()
    }

    /**
     * A mail address as stored and matched.
     *
     * The Meshtastic app writes a mention as `@!433d8ac0`, so the leading marker and the
     * bang are both optional, and eight hex digits are treated as a node number whatever
     * dressing they arrive in. Anything else is a name, lower cased, with only characters
     * that can be typed back reliably kept.
     */
    fun address(input: String): String {
        val raw = input.trim().removePrefix("@").removePrefix("!").lowercase()
        if (raw.length == 8 && raw.all { it in "0123456789abcdef" }) return "!$raw"
        return raw.filter { it.isLetterOrDigit() || it in "_-." }.take(32)
    }

    /** True when an address is a node number rather than a name. */
    fun isNodeAddress(address: String) =
        address.length == 9 && address.startsWith("!") && address.drop(1).all { it in "0123456789abcdef" }
}
