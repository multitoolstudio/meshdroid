package studio.multitool.meshdroid.bbs

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Bulletins and mail, in SQLite.
 *
 * SQLite rather than a file or a dependency: it ships with Android, it survives a crash
 * mid-write, and the queries a BBS needs are the ones it is good at. The database lives in
 * the app's private storage alongside the node's own files and is not part of the node
 * backup, since it belongs to Meshdroid rather than to meshtasticd.
 */
class BbsStore(ctx: Context) : SQLiteOpenHelper(ctx, "bbs.db", null, 1) {

    data class Bulletin(val id: Long, val from: String, val text: String, val at: Long)
    data class Mail(val id: Long, val from: String, val to: String, val text: String, val at: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE bulletins (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "sender TEXT NOT NULL, body TEXT NOT NULL, at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE mail (id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, " +
                "recipient TEXT NOT NULL, body TEXT NOT NULL, at INTEGER NOT NULL, " +
                "delivered INTEGER NOT NULL DEFAULT 0)"
        )
        // Mail is read by recipient, and unread mail first.
        db.execSQL("CREATE INDEX mail_recipient ON mail (recipient, delivered)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

    // ---- bulletins ----

    fun postBulletin(sender: String, body: String): Long =
        writableDatabase.insert("bulletins", null, ContentValues().apply {
            put("sender", sender); put("body", body); put("at", now())
        })

    /** Newest first, which is the order a caller wants to read them in. */
    fun bulletins(limit: Int = 20): List<Bulletin> =
        readableDatabase.rawQuery(
            "SELECT id, sender, body, at FROM bulletins ORDER BY id DESC LIMIT ?", arrayOf("$limit")
        ).use { c ->
            buildList { while (c.moveToNext()) add(Bulletin(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }

    fun bulletin(id: Long): Bulletin? =
        readableDatabase.rawQuery(
            "SELECT id, sender, body, at FROM bulletins WHERE id = ?", arrayOf("$id")
        ).use { c -> if (c.moveToNext()) Bulletin(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)) else null }

    fun bulletinCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM bulletins", null).use { c ->
            if (c.moveToNext()) c.getInt(0) else 0
        }

    fun deleteBulletin(id: Long): Boolean =
        writableDatabase.delete("bulletins", "id = ?", arrayOf("$id")) > 0

    // ---- mail ----

    fun sendMail(sender: String, recipient: String, body: String): Long =
        writableDatabase.insert("mail", null, ContentValues().apply {
            put("sender", sender); put("recipient", recipient); put("body", body); put("at", now())
        })

    /** Undelivered mail for a recipient, oldest first. */
    fun mailFor(recipient: String): List<Mail> =
        readableDatabase.rawQuery(
            "SELECT id, sender, recipient, body, at FROM mail WHERE recipient = ? AND delivered = 0 ORDER BY id",
            arrayOf(recipient)
        ).use { c ->
            buildList { while (c.moveToNext()) add(Mail(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))) }
        }

    fun markDelivered(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL(
            "UPDATE mail SET delivered = 1 WHERE id IN (${ids.joinToString(",") { "?" }})",
            ids.map { it.toString() }.toTypedArray()
        )
    }

    fun pendingMailCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM mail WHERE delivered = 0", null).use { c ->
            if (c.moveToNext()) c.getInt(0) else 0
        }

    private fun now() = System.currentTimeMillis() / 1000
}
