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
class BbsStore(ctx: Context) : SQLiteOpenHelper(ctx, "bbs.db", null, 4) {

    data class Bulletin(val id: Long, val from: String, val title: String, val text: String, val at: Long)
    data class Mail(val id: Long, val from: String, val to: String, val text: String, val at: Long)
    data class Admin(val nodeId: String, val label: String, val at: Long)

    /**
     * Caps on what the mesh can put here. A node that floods the board would otherwise fill
     * the phone's storage, and there is no way to ask a stranger to stop.
     */
    companion object {
        const val MAX_BULLETINS = 200
        const val MAX_BULLETINS_PER_SENDER_HOUR = 5
        const val MAX_PENDING_MAIL = 500
        const val MAX_PENDING_MAIL_PER_SENDER = 20
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE bulletins (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "sender TEXT NOT NULL, title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL, at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE mail (id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, " +
                "recipient TEXT NOT NULL, body TEXT NOT NULL, at INTEGER NOT NULL, " +
                "delivered INTEGER NOT NULL DEFAULT 0)"
        )
        // Mail is read by recipient, and unread mail first.
        db.execSQL("CREATE INDEX mail_recipient ON mail (recipient, delivered)")
        createAdmins(db)
        createLuaKv(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) createAdmins(db)
        if (old < 3) db.execSQL("ALTER TABLE bulletins ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        if (old < 4) createLuaKv(db)
    }

    private fun createLuaKv(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS lua_kv (module TEXT NOT NULL, k TEXT NOT NULL, " +
                "v TEXT NOT NULL, PRIMARY KEY (module, k))"
        )
    }

    private fun createAdmins(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS admins (node_id TEXT PRIMARY KEY, label TEXT NOT NULL, at INTEGER NOT NULL)"
        )
    }

    // ---- bulletins ----

    /** Bulletins this sender posted in the last hour, for the per-sender quota. */
    fun bulletinsBySenderSince(sender: String, sinceSeconds: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM bulletins WHERE sender = ? AND at >= ?", arrayOf(sender, "$sinceSeconds")
        ).use { c -> if (c.moveToNext()) c.getInt(0) else 0 }

    fun postBulletin(sender: String, title: String, body: String): Long {
        val id = writableDatabase.insert("bulletins", null, ContentValues().apply {
            put("sender", sender); put("title", title); put("body", body); put("at", now())
        })
        // Oldest out when the board is full, so storage stays bounded without an operator
        // having to prune by hand.
        writableDatabase.execSQL(
            "DELETE FROM bulletins WHERE id NOT IN (SELECT id FROM bulletins ORDER BY id DESC LIMIT ?)",
            arrayOf(MAX_BULLETINS.toString())
        )
        return id
    }

    /** Newest first, which is the order a caller wants to read them in. */
    fun bulletins(limit: Int = 20): List<Bulletin> =
        readableDatabase.rawQuery(
            "SELECT id, sender, title, body, at FROM bulletins ORDER BY id DESC LIMIT ?", arrayOf("$limit")
        ).use { c ->
            buildList {
                while (c.moveToNext())
                    add(Bulletin(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
            }
        }

    fun bulletin(id: Long): Bulletin? =
        readableDatabase.rawQuery(
            "SELECT id, sender, title, body, at FROM bulletins WHERE id = ?", arrayOf("$id")
        ).use { c ->
            if (c.moveToNext()) Bulletin(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))
            else null
        }

    fun bulletinCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM bulletins", null).use { c ->
            if (c.moveToNext()) c.getInt(0) else 0
        }

    fun deleteBulletin(id: Long): Boolean {
        val gone = writableDatabase.delete("bulletins", "id = ?", arrayOf("$id")) > 0
        resetNumberingIfEmpty()
        return gone
    }

    /**
     * Numbering restarts at 1 once the board is empty.
     *
     * AUTOINCREMENT remembers the highest id ever used, so a cleared board would otherwise
     * carry on from 148 and every post number people read out would keep growing.
     */
    private fun resetNumberingIfEmpty() {
        if (bulletinCount() > 0) return
        runCatching { writableDatabase.execSQL("DELETE FROM sqlite_sequence WHERE name = 'bulletins'") }
    }

    // ---- mail ----

    fun pendingMailFrom(sender: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM mail WHERE sender = ? AND delivered = 0", arrayOf(sender)
        ).use { c -> if (c.moveToNext()) c.getInt(0) else 0 }

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

    // ---- admins ----
    // Node numbers allowed to run admin commands. Membership alone is not authority: the
    // engine also requires the command to arrive encrypted to this node's key, because a
    // sender's node number can be set to anything.

    fun admins(): List<Admin> =
        readableDatabase.rawQuery("SELECT node_id, label, at FROM admins ORDER BY label", null).use { c ->
            buildList { while (c.moveToNext()) add(Admin(c.getString(0), c.getString(1), c.getLong(2))) }
        }

    fun isAdmin(nodeId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM admins WHERE node_id = ?", arrayOf(nodeId)).use { it.moveToNext() }

    fun addAdmin(nodeId: String, label: String) {
        writableDatabase.insertWithOnConflict("admins", null, ContentValues().apply {
            put("node_id", nodeId); put("label", label); put("at", now())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeAdmin(nodeId: String): Boolean =
        writableDatabase.delete("admins", "node_id = ?", arrayOf(nodeId)) > 0

    fun adminCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM admins", null).use { c -> if (c.moveToNext()) c.getInt(0) else 0 }

    // ---- clearing ----

    fun clearBulletins(): Int {
        val n = writableDatabase.delete("bulletins", null, null)
        resetNumberingIfEmpty()
        return n
    }

    /** Removes every message, delivered or not. */
    fun clearMail(): Int = writableDatabase.delete("mail", null, null)

    /** Removes mail that has already been collected, leaving anything still waiting. */
    fun clearDeliveredMail(): Int = writableDatabase.delete("mail", "delivered = 1", null)

    fun mailCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM mail", null).use { c -> if (c.moveToNext()) c.getInt(0) else 0 }

    // ---- storage for Lua modules ----
    // One namespace per script file, so two modules cannot read or overwrite each other.

    fun luaGet(module: String, key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM lua_kv WHERE module = ? AND k = ?", arrayOf(module, key))
            .use { c -> if (c.moveToNext()) c.getString(0) else null }

    fun luaSet(module: String, key: String, value: String) {
        writableDatabase.insertWithOnConflict("lua_kv", null, ContentValues().apply {
            put("module", module); put("k", key); put("v", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun luaDelete(module: String, key: String) {
        writableDatabase.delete("lua_kv", "module = ? AND k = ?", arrayOf(module, key))
    }

    fun luaKeyCount(module: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM lua_kv WHERE module = ?", arrayOf(module))
            .use { c -> if (c.moveToNext()) c.getInt(0) else 0 }

    /** Called when a module is deleted, so an uninstalled script leaves nothing behind. */
    fun luaClear(module: String): Int = writableDatabase.delete("lua_kv", "module = ?", arrayOf(module))

    private fun now() = System.currentTimeMillis() / 1000
}
