package studio.multitool.meshdroid

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The two daemons Meshdroid can run, and everything that differs between them.
 *
 * One radio, one USB descriptor, so exactly one profile is active at a time and
 * switching stops one daemon before starting the other. Nothing here is about
 * running both.
 *
 * Everything a launch needs is gathered here rather than spread through
 * DaemonService: the binary, the data directory, the ports, the argument list
 * and the environment. Adding a third daemon should mean adding an entry here
 * and log patterns to DaemonState, not editing the service.
 */
enum class Profile(
    /** Stable key in SharedPreferences. Never change these; they are persisted. */
    val id: String,
    val displayName: String,
    /** Packaged in jniLibs as a lib*.so, because nativeLibraryDir is the only place an unrooted app may exec from. */
    val binary: String,
    /** Subdirectory of filesDir holding this daemon's state. Separate per profile so backups stay separate. */
    val dataDirName: String,
    /** Port clients connect to. */
    val clientPort: Int,
    /**
     * Whether MeshProxy can sit in front of this daemon. Meshtastic's API allows
     * it; meshcored's port is fixed at compile time (TCP_PORT in
     * platformio-android.ini), so there is no second port to move it to.
     */
    val supportsProxy: Boolean,
    /** Whether the BBS can run on this profile. MeshCore support is 2.1 at the earliest. */
    val supportsBbs: Boolean,
    /**
     * Whether the daemon reads a config file. meshtasticd has config.yaml;
     * MeshCore keeps its settings in a NodePrefs struct the client edits over
     * the companion protocol, so there is no file for the Config tab to show.
     */
    val hasConfigFile: Boolean,
) {
    MESHTASTIC(
        id = "meshtastic",
        displayName = "Meshtastic",
        binary = "libmeshtasticd.so",
        dataDirName = "meshtasticd",
        clientPort = 4403,
        supportsProxy = true,
        supportsBbs = true,
        hasConfigFile = true,
    ),

    MESHCORE(
        id = "meshcore",
        displayName = "MeshCore",
        binary = "libmeshcored.so",
        dataDirName = "meshcore",
        clientPort = 5000,
        supportsProxy = false,
        supportsBbs = false,
        hasConfigFile = false,
    );

    /** Where this daemon keeps its state. Created on demand. */
    fun dataDir(ctx: Context): File = File(ctx.filesDir, dataDirName).apply { mkdirs() }

    fun binaryFile(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, binary)

    fun isInstalled(ctx: Context): Boolean = binaryFile(ctx).exists()

    /**
     * The command line for a launch.
     *
     * meshtasticd takes a config file, a data directory and a port. meshcored
     * takes only a data directory: portduino parses --fsdir itself before
     * setup() runs, and its listening port is compiled in rather than passed.
     */
    fun argv(ctx: Context, daemonPort: Int): List<String> {
        val bin = binaryFile(ctx).absolutePath
        return when (this) {
            MESHTASTIC -> buildList {
                add(bin)
                add("-c"); add(NodeFiles.configFile(ctx).absolutePath)
                add("-d"); add(dataDir(ctx).absolutePath)
                add("-p"); add(daemonPort.toString())
                if (Settings.verbose(ctx)) add("-v")
            }
            MESHCORE -> listOf(bin, "--fsdir", dataDir(ctx).absolutePath)
        }
    }

    /**
     * Environment for a launch, on top of the entries every profile needs
     * (PINEDIO_USB_FD_SOCKET, HOME, LD_LIBRARY_PATH), which DaemonService adds.
     *
     * batteryFile is null when battery reporting is off, in which case neither
     * daemon is told about it.
     */
    fun env(ctx: Context, bindAddr: String, batteryFile: File?): Map<String, String> =
        buildMap {
            when (this@Profile) {
                MESHTASTIC -> {
                    put("MESHTASTICD_BIND_ADDR", bindAddr)
                    batteryFile?.let { put("MESHTASTICD_BATTERY_FILE", it.absolutePath) }
                }
                MESHCORE -> {
                    // meshcored binds from TCP_BIND_ADDR, compiled in as 0.0.0.0.
                    // There is no runtime equivalent of MESHTASTICD_BIND_ADDR yet,
                    // so the LAN switch cannot restrict it. Noted rather than
                    // pretended otherwise; see the LAN note in NodeScreen.
                    batteryFile?.let { put("MESHCORE_BATTERY_FILE", it.absolutePath) }
                }
            }
        }

    /**
     * True when this profile's daemon can be told to bind to loopback only.
     * Drives whether the LAN switch is shown or explained as unavailable.
     */
    val canRestrictBind: Boolean get() = this == MESHTASTIC

    companion object {
        val DEFAULT = MESHTASTIC

        /**
         * The selected profile, as a flow so the UI can hide the tabs a profile does
         * not support without polling SharedPreferences on every recomposition. Seeded
         * by the first current() call and updated by select().
         */
        private val _selected = MutableStateFlow(DEFAULT)
        val selected: StateFlow<Profile> = _selected

        fun fromId(id: String?): Profile =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        /** The profile the app is set to run. Reads SharedPreferences. */
        fun current(ctx: Context): Profile =
            fromId(Settings.profileId(ctx)).also { _selected.value = it }

        /**
         * Changes the selected profile. The caller is responsible for having stopped
         * the running daemon first: one radio and one USB descriptor means the old
         * daemon must be gone before the new one starts.
         */
        fun select(ctx: Context, p: Profile) {
            Settings.setProfileId(ctx, p.id)
            _selected.value = p
        }

        /** Profiles whose binary is actually present in this build. */
        fun installed(ctx: Context): List<Profile> = entries.filter { it.isInstalled(ctx) }
    }
}
