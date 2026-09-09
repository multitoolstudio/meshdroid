package studio.multitool.meshdroid.bbs.lua

import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.MathLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.ResourceFinder
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction

/**
 * A Lua interpreter with nothing dangerous in it.
 *
 * A module here is code a user imported from a file, running inside an app that holds a
 * radio, a node database and the operator's identity keys. It is treated as hostile, so the
 * environment is built up from the few libraries a text command needs rather than taken
 * whole and trimmed: there is no io, no os, no package or require, no coroutines, and the
 * loaders that would read another file are removed after the libraries are in place.
 *
 * Two limits stop a script that never returns. The debug hook counts virtual machine
 * instructions and aborts past a budget, which catches a tight loop, and the same hook
 * checks a wall clock, which catches something slow rather than merely long.
 */
class LuaSandbox(private val instructionBudget: Int, private val timeoutMs: Long) {

    private var used = 0
    private var deadline = 0L

    val globals: Globals = Globals().also { g ->
        // PackageLib has to be here even though require is taken away again below: TableLib,
        // StringLib, MathLib and DebugLib all end by registering themselves in
        // package.loaded, and without it that is an index into nil and no library loads.
        g.load(BaseLib())
        g.load(PackageLib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(MathLib())
        g.load(DebugLib())          // installed for the hook, then taken away below
        LoadState.install(g)
        LuaC.install(g)

        // Nothing on disk is reachable, so even a loader that survived would find nothing.
        g.finder = ResourceFinder { null }

        // Anything that reaches outside this interpreter goes. loadfile and dofile would
        // read the filesystem, load and loadstring would compile fresh code at call time,
        // require and package would pull in another module, and print writes to a stream
        // nobody is reading.
        listOf("dofile", "loadfile", "load", "loadstring", "print", "collectgarbage",
               "require", "module", "package")
            .forEach { g.set(it, LuaValue.NIL) }

        val hook = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                used += HOOK_EVERY
                if (used > instructionBudget) throw LuaError("script used more than $instructionBudget instructions")
                if (System.currentTimeMillis() > deadline) throw LuaError("script ran longer than $timeoutMs ms")
                return NONE
            }
        }
        val debug = g.get("debug")
        check(debug.istable()) { "the Lua debug library did not load, so a script could not be limited" }
        debug.get("sethook").invoke(
            LuaValue.varargsOf(arrayOf(hook, LuaValue.valueOf(""), LuaValue.valueOf(HOOK_EVERY)))
        )
        // The hook lives on the running thread, so the library itself is no longer needed
        // and a script must not be able to remove its own limiter.
        g.set("debug", LuaValue.NIL)
    }

    /** Resets the budget for one call. Every entry into script code goes through here. */
    fun <T> withBudget(block: () -> T): T {
        used = 0
        deadline = System.currentTimeMillis() + timeoutMs
        return block()
    }

    companion object {
        /** Instructions between hook calls. Small enough to stop a tight loop quickly. */
        private const val HOOK_EVERY = 1000
    }
}
