package studio.multitool.meshdroid.bbs.lua

import android.content.Context
import android.net.Uri
import studio.multitool.meshdroid.DaemonState
import studio.multitool.meshdroid.Settings
import studio.multitool.meshdroid.bbs.BbsStore
import java.io.File

/**
 * Finds, imports and loads the user's Lua modules.
 *
 * Scripts live in files/bbs/apps. Each is enabled individually and disabled by default,
 * including the moment it is imported: adding a file should never be the same action as
 * running it.
 */
class LuaModuleLoader(private val ctx: Context, private val store: BbsStore) {

    data class Entry(val fileName: String, val enabled: Boolean, val keyword: String?, val error: String?)

    private val dir = File(ctx.filesDir, "bbs/apps").apply { mkdirs() }

    fun files(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".lua") }?.sortedBy { it.name } ?: emptyList()

    /**
     * Copies a script in through the file picker. The name is reduced to something safe,
     * since it arrives from a document provider and ends up as a path.
     */
    fun import(uri: Uri, suggestedName: String?): String {
        val base = (suggestedName ?: "module")
            .substringAfterLast('/')
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
            .removeSuffix(".lua")
            .take(48)
            .ifBlank { "module" }
        val target = File(dir, "$base.lua")
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("could not read the file")
        if (bytes.size > MAX_SCRIPT_BYTES) throw java.io.IOException("script is larger than ${MAX_SCRIPT_BYTES / 1024} KB")
        target.writeBytes(bytes)
        return target.name
    }

    fun delete(fileName: String) {
        File(dir, fileName).takeIf { it.isFile }?.delete()
        setEnabled(fileName, false)
        store.luaClear(fileName)
    }

    fun isEnabled(fileName: String) = fileName in Settings.luaEnabled(ctx)

    fun setEnabled(fileName: String, enabled: Boolean) {
        val current = Settings.luaEnabled(ctx).toMutableSet()
        if (enabled) current += fileName else current -= fileName
        Settings.setLuaEnabled(ctx, current)
    }

    /**
     * Loads every enabled script. A module that fails to compile is skipped and its message
     * kept for the UI, so one bad file cannot stop the others.
     */
    fun loadEnabled(): Pair<List<LuaModule>, List<Entry>> {
        val modules = mutableListOf<LuaModule>()
        val entries = mutableListOf<Entry>()
        files().forEach { file ->
            val enabled = isEnabled(file.name)
            if (!enabled) { entries += Entry(file.name, false, null, null); return@forEach }
            try {
                val module = LuaModule.load(
                    fileName = file.name,
                    source = file.readText(),
                    store = store,
                    instructionBudget = INSTRUCTION_BUDGET,
                    timeoutMs = TIMEOUT_MS
                )
                modules += module
                entries += Entry(file.name, true, module.keyword, null)
            } catch (e: Exception) {
                DaemonState.appendLog("[meshdroid] BBS module ${file.name} did not load: ${e.message}")
                entries += Entry(file.name, true, null, e.message ?: "failed to load")
            }
        }
        return modules to entries
    }

    companion object {
        private const val MAX_SCRIPT_BYTES = 64 * 1024
        /** Enough for text handling, far short of what a loop needs to hang the BBS. */
        private const val INSTRUCTION_BUDGET = 200_000
        private const val TIMEOUT_MS = 250L
    }
}
