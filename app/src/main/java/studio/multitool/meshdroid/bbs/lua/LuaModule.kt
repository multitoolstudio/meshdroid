package studio.multitool.meshdroid.bbs.lua

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import studio.multitool.meshdroid.DaemonState
import studio.multitool.meshdroid.bbs.BbsModule
import studio.multitool.meshdroid.bbs.BbsRequest
import studio.multitool.meshdroid.bbs.BbsStore
import studio.multitool.meshdroid.bbs.Sanitize

/**
 * A BBS command supplied as a Lua file.
 *
 * The contract is three globals. `keyword` is the word that selects it, `help` is the line
 * the help listing prints, and `handle(request)` returns the reply or nil for silence. The
 * script is run once at load to define them, which is also when a syntax error surfaces,
 * so a broken module is reported in the UI rather than at the moment somebody uses it.
 *
 * Everything the script can reach outside itself is on the `mesh` table: a clock, a log
 * line and a small key-value store scoped to this module. There is deliberately no way to
 * send a message from a script. A module returns text and the engine decides what happens
 * to it, so the rate limits and the airtime spacing cannot be bypassed by anything a user
 * imports.
 */
class LuaModule private constructor(
    val fileName: String,
    override val keyword: String,
    override val help: String,
    private val handler: LuaValue,
    private val sandbox: LuaSandbox
) : BbsModule {

    private val lock = Mutex()

    override suspend fun handle(request: BbsRequest): String? = lock.withLock {
        val table = LuaTable().apply {
            set("from", LuaValue.valueOf(request.fromAddress()))
            set("name", LuaValue.valueOf(request.who()))
            set("args", LuaValue.valueOf(request.args))
            set("keyword", LuaValue.valueOf(request.keyword))
            set("direct", LuaValue.valueOf(request.direct))
            set("channel", LuaValue.valueOf(request.channel))
            set("snr", request.snr?.let { LuaValue.valueOf(it.toDouble()) } ?: LuaValue.NIL)
            set("hops", request.hopsAway?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL)
        }
        try {
            val result = sandbox.withBudget { handler.call(table) }
            if (result.isnil()) null else Sanitize.body(result.tojstring(), MAX_REPLY)
        } catch (e: LuaError) {
            // A module that throws is a bug in that module, not a reason to take the BBS
            // down or to tell the mesh anything about this node's internals.
            Log.w(TAG, "$fileName failed: ${e.message}")
            DaemonState.appendLog("[meshdroid] BBS module $fileName failed: ${e.message}")
            "That command is not working right now."
        }
    }

    companion object {
        private const val TAG = "LuaModule"
        private const val MAX_REPLY = 600
        private const val MAX_KEYS_PER_MODULE = 100
        private const val MAX_VALUE_BYTES = 2048

        /**
         * Compiles and runs a script, returning the module it defines.
         *
         * Failures come back as an exception message that the UI shows next to the file, so
         * an author sees a bad script immediately rather than when a stranger tries it.
         */
        fun load(fileName: String, source: String, store: BbsStore,
                 instructionBudget: Int, timeoutMs: Long): LuaModule {
            val sandbox = LuaSandbox(instructionBudget, timeoutMs)
            sandbox.globals.set("mesh", hostApi(fileName, store))

            val chunk = sandbox.globals.load(source, fileName)
            sandbox.withBudget { chunk.call() }

            val keyword = sandbox.globals.get("keyword").optjstring("")?.lowercase()?.trim().orEmpty()
            require(keyword.isNotEmpty()) { "the script sets no keyword" }
            require(keyword.all { it.isLetterOrDigit() || it == '_' }) { "keyword must be letters, digits or underscore" }

            val handler = sandbox.globals.get("handle")
            require(handler.isfunction()) { "the script defines no handle(request) function" }

            val help = sandbox.globals.get("help").optjstring("$keyword - supplied by $fileName")
            return LuaModule(fileName, keyword, Sanitize.line(help, 120), handler, sandbox)
        }

        /** The `mesh` table: a clock, a log line, and storage scoped to this module. */
        private fun hostApi(fileName: String, store: BbsStore): LuaTable = LuaTable().apply {
            set("now", object : VarArgFunction() {
                override fun invoke(args: Varargs) = LuaValue.valueOf((System.currentTimeMillis() / 1000).toDouble())
            })
            set("log", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    DaemonState.appendLog("[meshdroid] $fileName: " + Sanitize.line(args.tojstring(1), 200))
                    return NONE
                }
            })
            set("get", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val value = store.luaGet(fileName, Sanitize.line(args.tojstring(1), 64))
                    return if (value == null) LuaValue.NIL else LuaValue.valueOf(value)
                }
            })
            set("set", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val key = Sanitize.line(args.tojstring(1), 64)
                    if (key.isBlank()) return LuaValue.valueOf(false)
                    if (args.isnil(2)) { store.luaDelete(fileName, key); return LuaValue.valueOf(true) }
                    val value = args.tojstring(2)
                    // Capped so an imported module cannot fill the phone through the only
                    // storage it is given.
                    if (value.toByteArray().size > MAX_VALUE_BYTES) return LuaValue.valueOf(false)
                    if (store.luaKeyCount(fileName) >= MAX_KEYS_PER_MODULE && store.luaGet(fileName, key) == null) {
                        return LuaValue.valueOf(false)
                    }
                    store.luaSet(fileName, key, value)
                    return LuaValue.valueOf(true)
                }
            })
        }
    }
}
