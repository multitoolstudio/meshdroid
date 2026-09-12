package studio.multitool.meshdroid

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * File layout and file operations for the daemons.
 *
 * Everything either daemon reads or writes lives under the app's filesDir:
 *
 *   files/config.yaml            equivalent of /etc/meshtasticd/config.yaml
 *   files/meshtasticd/prefs/     equivalent of /var/lib/meshtasticd (node DB, keys, channels)
 *   files/meshcore/              meshcored's portduino VFS root: identity/_main.id,
 *                                new_prefs, contacts3, channels2, bl/
 *
 * MeshCore has no config file. Its settings are a NodePrefs struct the client
 * edits over the companion protocol, so the Config tab has nothing to show for
 * that profile.
 *
 * Android keeps that directory private to the app. This object is the only way
 * in or out: the Config tab edits the YAML, and backups move through the system
 * file picker (Storage Access Framework), which reaches Downloads, Drive, USB
 * storage and anything else that provides documents.
 *
 * Every method here blocks on disk I/O and is meant to be called from a
 * background dispatcher, never from a composable or a lifecycle callback.
 */
object NodeFiles {
    fun configFile(ctx: Context) = File(ctx.filesDir, "config.yaml")
    // Profile is the one place that knows where each daemon keeps its state, so
    // this asks rather than repeating the directory name.
    fun fsDir(ctx: Context) = Profile.MESHTASTIC.dataDir(ctx)
    fun prefsDir(ctx: Context) = File(fsDir(ctx), "prefs")

    /**
     * Bumped whenever config.yaml changes on disk. The Config tab watches this so
     * that a region set from the Node tab, an import or a restore does not leave a
     * stale copy in the editor that a later Save would write back.
     */
    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion

    /** Largest backup zip and single entry accepted by importBackup. */
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024

    fun ensureDefaultConfig(ctx: Context) {
        val f = configFile(ctx)
        if (f.exists()) return
        val bytes = ctx.assets.open("config.yaml").use { it.readBytes() }
        writeAtomically(f, bytes)
        _configVersion.value++
    }

    fun readConfig(ctx: Context): String { ensureDefaultConfig(ctx); return configFile(ctx).readText() }

    fun writeConfig(ctx: Context, text: String) {
        writeAtomically(configFile(ctx), text.toByteArray())
        _configVersion.value++
    }

    /**
     * Writes through a temporary file in the same directory and renames it over the
     * target, so an interrupted write cannot leave a truncated config.yaml behind.
     */
    fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("could not replace ${target.name}")
        }
    }

    // ---- Lora.Region in config.yaml ----
    // Read by the daemon at boot (firmware patch 0003) and applied only while the
    // stored config has no region. A commented-out "# Region:" line does not match.
    // The search is confined to the Lora block so that a Region key added elsewhere
    // in config.yaml by a later firmware is left alone.
    private val regionLine = Regex("""(?m)^([ \t]+)Region:[ \t]*([A-Za-z0-9_]*)[ \t]*(#.*)?$""")
    private val loraHeader = Regex("""(?m)^Lora:[ \t]*(#.*)?$""")
    private val topLevelKey = Regex("""(?m)^[A-Za-z_][A-Za-z0-9_]*:""")

    /** Character range of the Lora block's body, or null if the file has no Lora block. */
    private fun loraBody(text: String): IntRange? {
        val header = loraHeader.find(text) ?: return null
        val bodyStart = header.range.last + 1
        val next = topLevelKey.find(text, bodyStart)
        return bodyStart until (next?.range?.first ?: text.length)
    }

    fun readRegion(ctx: Context): String? {
        val text = readConfig(ctx)
        val body = loraBody(text) ?: return null
        return regionLine.find(text.substring(body))?.groupValues?.get(2)?.ifBlank { null }
    }

    /**
     * Sets the Region key inside the Lora block, replacing an existing one and
     * keeping its indentation, or inserting it directly under the `Lora:` header.
     * If the file has no `Lora:` section one is prepended. Returns the new file text.
     */
    fun writeRegion(ctx: Context, code: String): String {
        val text = readConfig(ctx)
        val body = loraBody(text)
        val updated = when {
            body == null -> "Lora:\n  Region: $code\n" + text
            else -> {
                val block = text.substring(body)
                val match = regionLine.find(block)
                if (match != null) {
                    val indent = match.groupValues[1]
                    text.substring(0, body.first) +
                        block.replaceRange(match.range, "${indent}Region: $code") +
                        text.substring(body.last + 1)
                } else {
                    val insertAt = body.first
                    text.substring(0, insertAt) + "\n  Region: $code" + text.substring(insertAt)
                }
            }
        }
        writeConfig(ctx, updated)
        return updated
    }

    /** Region codes spelled as in the firmware's region table (RadioInterface.cpp). */
    val regions = listOf(
        "US", "EU_868", "EU_433", "ANZ", "ANZ_433", "JP", "KR", "TW", "CN", "IN", "RU", "TH",
        "UA_868", "UA_433", "MY_919", "MY_433", "SG_923", "NZ_865", "PH_915", "PH_868", "PH_433",
        "BR_902", "KZ_863", "KZ_433", "NP_865", "LORA_24"
    )

    // ---- presets ----
    // Bundled YAML files derived from meshtastic/firmware bin/config.d. The first
    // line of each file is a "# Meshdroid preset: <title>" comment used as its label.
    data class Preset(val file: String, val title: String)

    fun presets(ctx: Context): List<Preset> =
        (ctx.assets.list("presets") ?: emptyArray()).sorted().map { name ->
            val first = ctx.assets.open("presets/$name").bufferedReader().use { it.readLine() ?: name }
            Preset(name, first.removePrefix("# Meshdroid preset:").trim().ifBlank { name })
        }

    fun readPreset(ctx: Context, file: String): String =
        ctx.assets.open("presets/$file").bufferedReader().use { it.readText() }

    // ---- backup and restore ----
    // A backup zip holds config.yaml at the root and the prefs/ tree. The layout is
    // the same one meshtasticd uses on Linux, so backups are portable both ways.
    /** Entry prefix for a MeshCore backup. Also how a restore recognises one. */
    private const val MESHCORE_PREFIX = "meshcore/"

    /**
     * Which profile a backup zip belongs to, or null if it looks like neither.
     *
     * Backups taken before profiles existed have no marker, and are Meshtastic by
     * construction, so config.yaml or prefs/ identifies them.
     */
    fun backupProfile(ctx: Context, src: Uri): Profile? {
        ctx.contentResolver.openInputStream(src)!!.use { ins ->
            ZipInputStream(ins).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val n = e.name
                    if (n.startsWith(MESHCORE_PREFIX)) return Profile.MESHCORE
                    if (n == "config.yaml" || n.startsWith("prefs/")) return Profile.MESHTASTIC
                    zip.closeEntry(); e = zip.nextEntry
                }
            }
        }
        return null
    }

    fun exportBackup(ctx: Context, dest: Uri, profile: Profile = Profile.current(ctx)) {
        ctx.contentResolver.openOutputStream(dest)!!.use { out ->
            ZipOutputStream(out).use { zip ->
                when (profile) {
                    Profile.MESHTASTIC -> {
                        val cfg = configFile(ctx)
                        if (cfg.exists()) { zip.putNextEntry(ZipEntry("config.yaml")); cfg.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                        val prefs = prefsDir(ctx)
                        prefs.walkTopDown().filter { it.isFile }.forEach { f ->
                            zip.putNextEntry(ZipEntry("prefs/" + f.relativeTo(prefs).path))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    Profile.MESHCORE -> {
                        // The whole VFS root. The identity is in here, so this file is
                        // as sensitive as a private key, because it contains one.
                        val root = profile.dataDir(ctx)
                        root.walkTopDown().filter { it.isFile }.forEach { f ->
                            zip.putNextEntry(ZipEntry(MESHCORE_PREFIX + f.relativeTo(root).path))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
        }
    }

    /**
     * Restores config.yaml and prefs/ from a backup zip. The existing prefs/ tree is
     * removed first, so the result is the backup and nothing else; a file that the
     * backup does not contain does not survive into the restored node. Entries
     * outside those two paths, entries that resolve outside prefs/, and oversized
     * entries are skipped. Returns the number of files written.
     *
     * The caller must have stopped the daemon and waited for it to exit.
     */
    fun importBackup(ctx: Context, src: Uri, profile: Profile = Profile.current(ctx)): Int {
        // Refused rather than merged. Restoring a MeshCore backup over a Meshtastic
        // node, or the reverse, would delete the node's state and replace it with
        // files the daemon cannot read.
        val found = backupProfile(ctx, src)
            ?: throw java.io.IOException("This zip does not look like a Meshdroid backup.")
        if (found != profile) {
            throw java.io.IOException(
                "That is a ${found.displayName} backup and the active profile is " +
                    "${profile.displayName}. Switch profile before restoring it."
            )
        }
        if (profile == Profile.MESHCORE) return importMeshCoreBackup(ctx, src)

        val staged = mutableMapOf<File, ByteArray>()
        var total = 0L
        val prefs = prefsDir(ctx)
        val prefsPath = prefs.canonicalPath

        ctx.contentResolver.openInputStream(src)!!.use { ins ->
            ZipInputStream(ins).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = e.name
                    val target = when {
                        name == "config.yaml" -> configFile(ctx)
                        name.startsWith("prefs/") -> File(prefs, name.removePrefix("prefs/"))
                        else -> null
                    }
                    if (target != null && !e.isDirectory && withinPrefs(target, prefsPath, ctx)) {
                        val bytes = readCapped(zip)
                        total += bytes.size
                        if (total > MAX_TOTAL_BYTES) throw java.io.IOException("backup is larger than ${MAX_TOTAL_BYTES / (1024 * 1024)} MB")
                        staged[target] = bytes
                    }
                    zip.closeEntry(); e = zip.nextEntry
                }
            }
        }
        if (staged.isEmpty()) return 0

        // Nothing is written until the whole zip has been read, so a truncated or
        // unreadable backup leaves the node untouched.
        if (staged.keys.any { it.startsWith(prefs) }) prefs.deleteRecursively()
        staged.forEach { (target, bytes) -> writeAtomically(target, bytes) }
        _configVersion.value++
        return staged.size
    }

    /**
     * Restores a MeshCore backup: the whole VFS root, replacing what is there.
     *
     * Same shape as the Meshtastic path. Nothing is written until the entire zip
     * has been read, so a truncated or unreadable backup leaves the node
     * untouched, and entries that resolve outside the root are skipped.
     *
     * The caller must have stopped the daemon and waited for it to exit.
     */
    private fun importMeshCoreBackup(ctx: Context, src: Uri): Int {
        val root = Profile.MESHCORE.dataDir(ctx)
        val rootPath = root.canonicalPath
        val staged = mutableMapOf<File, ByteArray>()
        var total = 0L

        ctx.contentResolver.openInputStream(src)!!.use { ins ->
            ZipInputStream(ins).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = e.name
                    if (!e.isDirectory && name.startsWith(MESHCORE_PREFIX)) {
                        val target = File(root, name.removePrefix(MESHCORE_PREFIX))
                        val path = runCatching { target.canonicalPath }.getOrNull()
                        if (path != null && (path == rootPath || path.startsWith("$rootPath/"))) {
                            val bytes = readCapped(zip)
                            total += bytes.size
                            if (total > MAX_TOTAL_BYTES)
                                throw java.io.IOException("backup is larger than ${MAX_TOTAL_BYTES / (1024 * 1024)} MB")
                            staged[target] = bytes
                        }
                    }
                    zip.closeEntry(); e = zip.nextEntry
                }
            }
        }
        if (staged.isEmpty()) return 0

        root.deleteRecursively()
        staged.forEach { (target, bytes) -> writeAtomically(target, bytes) }
        return staged.size
    }

    private fun withinPrefs(target: File, prefsPath: String, ctx: Context): Boolean {
        if (target == configFile(ctx)) return true
        val path = runCatching { target.canonicalPath }.getOrNull() ?: return false
        return path == prefsPath || path.startsWith("$prefsPath/")
    }

    private fun readCapped(zip: ZipInputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n = zip.read(buf)
        while (n >= 0) {
            out.write(buf, 0, n)
            if (out.size() > MAX_ENTRY_BYTES) throw java.io.IOException("backup entry is larger than ${MAX_ENTRY_BYTES / (1024 * 1024)} MB")
            n = zip.read(buf)
        }
        return out.toByteArray()
    }

    fun importConfigYaml(ctx: Context, src: Uri): String {
        val text = ctx.contentResolver.openInputStream(src)!!.bufferedReader().use { it.readText() }
        writeConfig(ctx, text); return text
    }

    fun exportConfigYaml(ctx: Context, dest: Uri) {
        ctx.contentResolver.openOutputStream(dest)!!.use { it.write(readConfig(ctx).toByteArray()) }
    }

    fun exportLog(ctx: Context, dest: Uri, lines: List<String>) {
        ctx.contentResolver.openOutputStream(dest)!!.bufferedWriter().use { w -> lines.forEach { w.write(it); w.newLine() } }
    }

    /**
     * Factory reset for the active profile. The daemon installs defaults on its
     * next start. The caller must have stopped the daemon and waited for it to
     * exit.
     *
     * Meshtastic: deletes the prefs/ tree (node database, channels, keys and
     * identity) and keeps config.yaml, because the pinmap in it describes the
     * hardware rather than the node.
     *
     * MeshCore: deletes the whole VFS root, which is identity, prefs, contacts
     * and channels. There is no config file to keep. Peers holding this node as a
     * contact will see a stranger afterwards, which is what a factory reset means
     * on an embedded board too.
     */
    fun resetNode(ctx: Context, profile: Profile = Profile.current(ctx)) {
        when (profile) {
            Profile.MESHTASTIC -> prefsDir(ctx).deleteRecursively()
            Profile.MESHCORE -> profile.dataDir(ctx).deleteRecursively()
        }
    }
}
