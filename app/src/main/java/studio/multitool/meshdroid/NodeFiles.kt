package studio.multitool.meshdroid

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * File layout and file operations for the daemon.
 *
 * Everything meshtasticd reads or writes lives under the app's filesDir:
 *
 *   files/config.yaml            equivalent of /etc/meshtasticd/config.yaml
 *   files/meshtasticd/prefs/     equivalent of /var/lib/meshtasticd (node DB, keys, channels)
 *
 * Android keeps that directory private to the app. This object is the only way
 * in or out: the Config tab edits the YAML, and backups move through the system
 * file picker (Storage Access Framework), which reaches Downloads, Drive, USB
 * storage and anything else that provides documents.
 */
object NodeFiles {
    fun configFile(ctx: Context) = File(ctx.filesDir, "config.yaml")
    fun fsDir(ctx: Context) = File(ctx.filesDir, "meshtasticd").apply { mkdirs() }
    fun prefsDir(ctx: Context) = File(fsDir(ctx), "prefs")

    fun ensureDefaultConfig(ctx: Context) {
        val f = configFile(ctx)
        if (!f.exists()) ctx.assets.open("config.yaml").use { it.copyTo(f.outputStream()) }
    }

    fun readConfig(ctx: Context): String { ensureDefaultConfig(ctx); return configFile(ctx).readText() }
    fun writeConfig(ctx: Context, text: String) = configFile(ctx).writeText(text)

    // ---- Lora.Region in config.yaml ----
    // Read by the daemon at boot (firmware patch 0003) and applied only while the
    // stored config has no region. A commented-out "# Region:" line does not match.
    private val regionLine = Regex("""(?m)^[ \t]+Region:[ \t]*([A-Za-z0-9_]*)[ \t]*(#.*)?$""")

    fun readRegion(ctx: Context): String? = regionLine.find(readConfig(ctx))?.groupValues?.get(1)?.ifBlank { null }

    /**
     * Sets the Region key, replacing an existing one or inserting it directly under
     * the `Lora:` header. If the file has no `Lora:` section one is prepended.
     * Returns the new file text.
     */
    fun writeRegion(ctx: Context, code: String): String {
        var text = readConfig(ctx)
        text = if (regionLine.containsMatchIn(text)) {
            regionLine.replace(text, "  Region: $code")
        } else {
            val loraHeader = Regex("""(?m)^Lora:[ \t]*$""")
            if (loraHeader.containsMatchIn(text)) loraHeader.replace(text, "Lora:\n  Region: $code")
            else "Lora:\n  Region: $code\n" + text
        }
        writeConfig(ctx, text)
        return text
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
    fun exportBackup(ctx: Context, dest: Uri) {
        ctx.contentResolver.openOutputStream(dest)!!.use { out ->
            ZipOutputStream(out).use { zip ->
                val cfg = configFile(ctx)
                if (cfg.exists()) { zip.putNextEntry(ZipEntry("config.yaml")); cfg.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                val prefs = prefsDir(ctx)
                prefs.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("prefs/" + f.relativeTo(prefs).path))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Restores config.yaml and prefs/ from a backup zip. Entries outside those two
     * paths, and any containing "..", are skipped. Returns the number of files
     * written. The caller must stop the daemon first.
     */
    fun importBackup(ctx: Context, src: Uri): Int {
        var n = 0
        ctx.contentResolver.openInputStream(src)!!.use { ins ->
            ZipInputStream(ins).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = e.name
                    val target = when {
                        name == "config.yaml" -> configFile(ctx)
                        name.startsWith("prefs/") && !name.contains("..") -> File(prefsDir(ctx), name.removePrefix("prefs/"))
                        else -> null
                    }
                    if (target != null && !e.isDirectory) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                        n++
                    }
                    zip.closeEntry(); e = zip.nextEntry
                }
            }
        }
        return n
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
     * Deletes the prefs/ tree: node database, channels, keys and identity. The daemon
     * installs defaults on its next start. config.yaml is kept. The caller must stop
     * the daemon first.
     */
    fun resetNode(ctx: Context) { prefsDir(ctx).deleteRecursively() }
}
