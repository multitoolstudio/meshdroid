package studio.multitool.meshdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import studio.multitool.meshdroid.R
import studio.multitool.meshdroid.repeater.RepeaterConnState
import studio.multitool.meshdroid.repeater.RepeaterHolder

private data class CliCommandDoc(
    val category: String,
    val usage: String,
    val insertText: String,
    val description: String,
)

private val REPEATER_COMMANDS: List<CliCommandDoc> = listOf(
    // Operational
    CliCommandDoc("Operational", "advert", "advert", "Send a flood advertisement to the mesh"),
    CliCommandDoc("Operational", "advert.zerohop", "advert.zerohop", "Send a zero-hop (local) advertisement"),
    CliCommandDoc("Operational", "clock", "clock", "Display current repeater UTC time"),
    CliCommandDoc("Operational", "time <epoch_seconds>", "time ", "Set repeater RTC to Unix epoch seconds"),
    CliCommandDoc("Operational", "clock sync", "clock sync", "Sync clock (when sent with sender timestamp)"),
    CliCommandDoc("Operational", "reboot", "reboot", "Reboot the repeater node"),
    CliCommandDoc("Operational", "clkreboot", "clkreboot", "Reset the clock and reboot"),
    CliCommandDoc("Operational", "poweroff / shutdown", "poweroff", "Power off the repeater node"),
    CliCommandDoc("Operational", "start ota", "start ota", "Start an Over-The-Air firmware update"),
    CliCommandDoc("Operational", "erase", "erase", "Factory reset / format repeater filesystem (serial only, destructive)"),

    // Info
    CliCommandDoc("Info", "ver", "ver", "Show firmware version and build date"),
    CliCommandDoc("Info", "board", "board", "Show hardware board / manufacturer name"),
    CliCommandDoc("Info", "get public.key", "get public.key", "Show repeater 32-byte public key (hex)"),
    CliCommandDoc("Info", "get role", "get role", "Show configured firmware role (repeater)"),

    // Neighbors
    CliCommandDoc("Neighbors", "neighbors", "neighbors", "List up to 8 most recently heard zero-hop neighbors"),
    CliCommandDoc("Neighbors", "discover.neighbors", "discover.neighbors", "Broadcast a zero-hop neighbor discovery request"),
    CliCommandDoc("Neighbors", "neighbor.remove <pubkey_prefix>", "neighbor.remove ", "Remove neighbor(s) matching hex pubkey prefix"),

    // Statistics
    CliCommandDoc("Statistics", "stats-core", "stats-core", "System stats: battery, uptime, queue length (serial only)"),
    CliCommandDoc("Statistics", "stats-radio", "stats-radio", "Radio stats: noise floor, last RSSI/SNR, airtime, rx errors (serial only)"),
    CliCommandDoc("Statistics", "stats-packets", "stats-packets", "Packet counters: received, sent, flood, direct (serial only)"),
    CliCommandDoc("Statistics", "clear stats", "clear stats", "Reset all radio and packet statistics"),

    // Logging
    CliCommandDoc("Logging", "log start", "log start", "Begin capturing RX packet log to repeater storage"),
    CliCommandDoc("Logging", "log stop", "log stop", "Stop capturing RX packet log"),
    CliCommandDoc("Logging", "log", "log", "Dump captured packet log to serial console (serial only)"),
    CliCommandDoc("Logging", "log erase", "log erase", "Erase captured packet log file"),

    // Radio Configuration
    CliCommandDoc("Radio", "get radio", "get radio", "View radio params (<freq>,<bw>,<sf>,<cr>)"),
    CliCommandDoc("Radio", "set radio <freq>,<bw>,<sf>,<cr>", "set radio ", "Set frequency (MHz), BW (kHz), SF (5-12), CR (5-8); reboot to apply"),
    CliCommandDoc("Radio", "tempradio <freq>,<bw>,<sf>,<cr>,<mins>", "tempradio ", "Apply temporary radio params for <mins> minutes"),
    CliCommandDoc("Radio", "get freq", "get freq", "View frequency in MHz"),
    CliCommandDoc("Radio", "set freq <mhz>", "set freq ", "Set frequency in MHz (serial only; reboot to apply)"),
    CliCommandDoc("Radio", "get tx", "get tx", "View transmit power in dBm"),
    CliCommandDoc("Radio", "set tx <dbm>", "set tx ", "Set transmit power in dBm (-9 to 30)"),
    CliCommandDoc("Radio", "get radio.rxgain", "get radio.rxgain", "View RX boosted gain state (on|off)"),
    CliCommandDoc("Radio", "set radio.rxgain <on|off>", "set radio.rxgain ", "Enable or disable chip RX boosted gain"),
    CliCommandDoc("Radio", "get radio.fem.rxgain", "get radio.fem.rxgain", "View external LoRa FEM LNA state (on|off)"),
    CliCommandDoc("Radio", "set radio.fem.rxgain <on|off>", "set radio.fem.rxgain ", "Enable or disable external LoRa FEM RX LNA"),
    CliCommandDoc("Radio", "get radio.fem.txgain", "get radio.fem.txgain", "View external LoRa FEM PA gain state (on|off)"),
    CliCommandDoc("Radio", "set radio.fem.txgain <on|off>", "set radio.fem.txgain ", "Enable or disable external LoRa FEM TX gain"),

    // System Configuration
    CliCommandDoc("System", "get name", "get name", "View repeater advertised name"),
    CliCommandDoc("System", "set name <name>", "set name ", "Set repeater advertised name (UTF-8, up to 31 bytes)"),
    CliCommandDoc("System", "password <new_password>", "password ", "Change admin password"),
    CliCommandDoc("System", "get guest.password", "get guest.password", "View guest password"),
    CliCommandDoc("System", "set guest.password <password>", "set guest.password ", "Set guest password"),
    CliCommandDoc("System", "get owner.info", "get owner.info", "View owner info text"),
    CliCommandDoc("System", "set owner.info <text>", "set owner.info ", "Set owner info ('|' translates to newline)"),
    CliCommandDoc("System", "get lat / set lat <deg>", "set lat ", "View or set latitude in degrees"),
    CliCommandDoc("System", "get lon / set lon <deg>", "set lon ", "View or set longitude in degrees"),
    CliCommandDoc("System", "get prv.key", "get prv.key", "Read repeater 64-byte private key in hex (serial only)"),
    CliCommandDoc("System", "set prv.key <hex>", "set prv.key ", "Write repeater private key in hex (reboot to apply)"),
    CliCommandDoc("System", "powersaving [on|off]", "powersaving", "View or set idle sleep mode (on|off)"),
    CliCommandDoc("System", "get adc.multiplier / set adc.multiplier <val>", "get adc.multiplier", "View or set battery ADC multiplier (0.0-10.0)"),

    // Routing Configuration
    CliCommandDoc("Routing", "get repeat / set repeat <on|off>", "set repeat ", "View or change packet forwarding state (on|off)"),
    CliCommandDoc("Routing", "get dutycycle / set dutycycle <1-100>", "set dutycycle ", "View or set airtime duty cycle percentage (1-100)"),
    CliCommandDoc("Routing", "get af / set af <0-9>", "get af", "View or set airtime factor (deprecated by dutycycle)"),
    CliCommandDoc("Routing", "get advert.interval / set advert.interval <mins>", "set advert.interval ", "Zero-hop advert interval in minutes (60-240, or 0)"),
    CliCommandDoc("Routing", "get flood.advert.interval / set flood.advert.interval <hrs>", "set flood.advert.interval ", "Flood advert interval in hours (3-168, or 0)"),
    CliCommandDoc("Routing", "get flood.max / set flood.max <0-64>", "set flood.max ", "Max flood hop count (0-64)"),
    CliCommandDoc("Routing", "get flood.max.unscoped / set flood.max.unscoped <0-64>", "set flood.max.unscoped ", "Max flood hops for unscoped packets (0-64)"),
    CliCommandDoc("Routing", "get flood.max.advert / set flood.max.advert <0-64>", "set flood.max.advert ", "Max flood hops for advert packets (0-64)"),
    CliCommandDoc("Routing", "get path.hash.mode / set path.hash.mode <0|1|2>", "set path.hash.mode ", "Advert path hash byte size: 0=1B, 1=2B, 2=3B"),
    CliCommandDoc("Routing", "get loop.detect / set loop.detect <mode>", "set loop.detect ", "Loop detection: off, minimal, moderate, strict"),
    CliCommandDoc("Routing", "get txdelay / set txdelay <0-2>", "set txdelay ", "Flood retransmit random delay factor (0-2)"),
    CliCommandDoc("Routing", "get direct.txdelay / set direct.txdelay <0-2>", "set direct.txdelay ", "Direct packet retransmit delay factor (0-2)"),
    CliCommandDoc("Routing", "get rxdelay / set rxdelay <0-20>", "set rxdelay ", "Weak-signal receive processing delay base (0-20)"),
    CliCommandDoc("Routing", "get cad / set cad <on|off>", "set cad ", "Hardware Channel Activity Detection (on|off)"),
    CliCommandDoc("Routing", "get int.thresh / set int.thresh <val>", "set int.thresh ", "Local interference threshold"),
    CliCommandDoc("Routing", "get agc.reset.interval / set agc.reset.interval <secs>", "set agc.reset.interval ", "AGC reset interval in seconds (multiple of 4)"),
    CliCommandDoc("Routing", "get multi.acks / set multi.acks <0|1>", "set multi.acks ", "Extra ACK transmit count (0 or 1)"),

    // ACL
    CliCommandDoc("ACL", "get acl", "get acl", "Print current client ACL table (serial only)"),
    CliCommandDoc("ACL", "setperm <pubkey_hex> <0-3>", "setperm ", "Set client permission (0=guest, 1=RO, 2=RW, 3=admin)"),
    CliCommandDoc("ACL", "get allow.read.only / set allow.read.only <on|off>", "set allow.read.only ", "Allow unauthenticated guest read-only access"),

    // Regions
    CliCommandDoc("Regions", "region", "region", "Export defined region hierarchy and flood flags"),
    CliCommandDoc("Regions", "region list <allowed|denied>", "region list allowed", "List regions filtered by allowed or denied (serial only)"),
    CliCommandDoc("Regions", "region get <name>", "region get ", "Show parent and flood flag for a region (or *)"),
    CliCommandDoc("Regions", "region put <name> [parent]", "region put ", "Create a region under parent (defaults to *)"),
    CliCommandDoc("Regions", "region def <tokens...>", "region def ", "Define region hierarchy in one line"),
    CliCommandDoc("Regions", "region remove <name>", "region remove ", "Remove a leaf region"),
    CliCommandDoc("Regions", "region allowf <name>", "region allowf ", "Allow flood forwarding for region (or *)"),
    CliCommandDoc("Regions", "region denyf <name>", "region denyf ", "Deny flood forwarding for region (or *)"),
    CliCommandDoc("Regions", "region home [name]", "region home", "View or set home region"),
    CliCommandDoc("Regions", "region default [name|<null>]", "region default", "View or set default flood scope region"),
    CliCommandDoc("Regions", "region save", "region save", "Persist region changes to storage"),

    // GPS & Sensors
    CliCommandDoc("GPS & Sensors", "gps [on|off]", "gps", "View GPS status or turn GPS hardware on/off"),
    CliCommandDoc("GPS & Sensors", "gps sync", "gps sync", "Sync repeater clock from GPS time"),
    CliCommandDoc("GPS & Sensors", "gps setloc", "gps setloc", "Save current GPS coordinates as node lat/lon"),
    CliCommandDoc("GPS & Sensors", "gps advert [none|share|prefs]", "gps advert", "View or set location advert policy"),
    CliCommandDoc("GPS & Sensors", "sensor list [start]", "sensor list", "List sensor variables"),
    CliCommandDoc("GPS & Sensors", "sensor get <key>", "sensor get ", "Read a sensor setting value"),
    CliCommandDoc("GPS & Sensors", "sensor set <key> <value>", "sensor set ", "Set a sensor setting value"),

    // Bridge, Power & Ethernet
    CliCommandDoc("Bridge & Power", "get bridge.type", "get bridge.type", "Show compiled bridge type (rs232, espnow, none)"),
    CliCommandDoc("Bridge & Power", "get bridge.enabled / set bridge.enabled <on|off>", "get bridge.enabled", "View or toggle packet bridge"),
    CliCommandDoc("Bridge & Power", "get bootloader.ver", "get bootloader.ver", "Show nRF52 bootloader version"),
    CliCommandDoc("Bridge & Power", "get pwrmgt.support", "get pwrmgt.support", "Check if board power management is supported"),
    CliCommandDoc("Bridge & Power", "get pwrmgt.source", "get pwrmgt.source", "View current power source (external vs battery)"),
    CliCommandDoc("Bridge & Power", "get pwrmgt.bootreason", "get pwrmgt.bootreason", "View last reset and shutdown reasons"),
    CliCommandDoc("Bridge & Power", "get pwrmgt.bootmv", "get pwrmgt.bootmv", "View voltage at boot in mV"),
    CliCommandDoc("Bridge & Power", "eth.status", "eth.status", "Show Ethernet IP and port status (if compiled in)"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepeaterScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val connState by RepeaterHolder.state.collectAsStateWithLifecycle()
    val detail by RepeaterHolder.detail.collectAsStateWithLifecycle()
    val usbLabel by RepeaterHolder.usbDeviceLabel.collectAsStateWithLifecycle()
    val consoleLines by RepeaterHolder.console.collectAsStateWithLifecycle()
    val snap by RepeaterHolder.snapshot.collectAsStateWithLifecycle()

    val connected = connState == RepeaterConnState.CONNECTED
    val consoleListState = rememberLazyListState()

    // Inline feedback banner for top-card actions so the user immediately sees commands in flight and their result.
    var busyAction by remember { mutableStateOf<String?>(null) }
    var lastActionFeedback by remember { mutableStateOf<String?>(null) }
    var showCommandsDialog by remember { mutableStateOf(false) }

    // Editable fields seeded from snapshot whenever a refresh completes.
    var name by remember(snap.name) { mutableStateOf(snap.name) }
    var freq by remember(snap.freq) { mutableStateOf(snap.freq) }
    var bw by remember(snap.bw) { mutableStateOf(snap.bw) }
    var sf by remember(snap.sf) { mutableStateOf(snap.sf) }
    var cr by remember(snap.cr) { mutableStateOf(snap.cr) }
    var txPower by remember(snap.txPower) { mutableStateOf(snap.txPower) }
    var adminPassword by remember { mutableStateOf("") }
    var guestPassword by remember(snap.guestPassword) { mutableStateOf(snap.guestPassword) }
    var prvKey by remember { mutableStateOf("") }
    var advertMins by remember(snap.advertIntervalMins) { mutableStateOf(snap.advertIntervalMins) }
    var floodHours by remember(snap.floodAdvertHours) { mutableStateOf(snap.floodAdvertHours) }
    var dutyCycle by remember(snap.dutyCycle) { mutableStateOf(snap.dutyCycle) }
    var floodMax by remember(snap.floodMax) { mutableStateOf(snap.floodMax) }
    var lat by remember(snap.lat) { mutableStateOf(snap.lat) }
    var lon by remember(snap.lon) { mutableStateOf(snap.lon) }

    var rawCmd by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { RepeaterHolder.init(ctx) }
    LaunchedEffect(connected) {
        if (!connected) {
            busyAction = null
            lastActionFeedback = null
        }
    }
    LaunchedEffect(consoleLines.size) {
        if (consoleLines.isNotEmpty()) consoleListState.scrollToItem(consoleLines.size - 1)
    }

    fun runCli(cmd: String, toastReply: Boolean = false) {
        scope.launch {
            val reply = RepeaterHolder.sendCommand(cmd, logToConsole = true)
            if (toastReply && reply.isNotEmpty()) {
                snackbar.showSnackbar(reply.joinToString(" "))
            }
        }
    }

    fun runTopAction(label: String, cmdDisplay: String, block: suspend () -> String) {
        scope.launch {
            busyAction = label
            lastActionFeedback = "Sending $cmdDisplay…"
            try {
                val result = block()
                val summary = "✓ $cmdDisplay → $result"
                lastActionFeedback = summary
                snackbar.showSnackbar(summary)
            } finally {
                busyAction = null
            }
        }
    }

    if (showCommandsDialog) {
        RepeaterCommandsDialog(
            onDismiss = { showCommandsDialog = false },
            onSelectCommand = { chosen ->
                rawCmd = chosen
                showCommandsDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- Connection & Quick Actions Card ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        when (connState) {
                            RepeaterConnState.CONNECTED -> R.string.repeater_connected
                            RepeaterConnState.CONNECTING -> R.string.repeater_connecting
                            RepeaterConnState.ERROR -> R.string.repeater_error
                            RepeaterConnState.DISCONNECTED -> R.string.repeater_disconnected
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (connected) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    if (snap.name.isNotBlank()) Text("Name  ${snap.name}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (snap.board.isNotBlank()) Text("Board  ${snap.board}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (snap.version.isNotBlank()) Text("Firmware  ${snap.version}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (snap.clock.isNotBlank()) Text("Clock  ${snap.clock}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }

                // Inline feedback bar for top-section button presses.
                if (busyAction != null || lastActionFeedback != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (busyAction != null) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            }
                            Text(
                                text = lastActionFeedback ?: "",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!connected) {
                        Button(
                            onClick = { RepeaterHolder.connect(ctx) },
                            enabled = usbLabel != null && connState != RepeaterConnState.CONNECTING
                        ) { Text(stringResource(R.string.btn_connect)) }
                        OutlinedButton(onClick = {
                            RepeaterHolder.refreshUsbPresence(ctx)
                            val found = RepeaterHolder.usbDeviceLabel.value
                            lastActionFeedback = if (found != null) "✓ Detected $found" else "No USB serial device found"
                        }) {
                            Text(stringResource(R.string.btn_refresh))
                        }
                    } else {
                        val idle = busyAction == null
                        OutlinedButton(
                            onClick = { RepeaterHolder.disconnect() },
                            enabled = idle
                        ) {
                            Text(stringResource(R.string.btn_disconnect))
                        }
                        OutlinedButton(
                            onClick = {
                                runTopAction("refresh", "refresh") {
                                    RepeaterHolder.refreshSnapshot()
                                    "Settings reloaded"
                                }
                            },
                            enabled = idle
                        ) {
                            Text(stringResource(R.string.btn_refresh))
                        }
                        Button(
                            onClick = {
                                val epoch = System.currentTimeMillis() / 1000L
                                runTopAction("clock", "time $epoch") {
                                    RepeaterHolder.syncClockToPhone()
                                }
                            },
                            enabled = idle
                        ) { Text(stringResource(R.string.btn_sync_clock)) }
                        OutlinedButton(
                            onClick = {
                                runTopAction("advert", "advert") {
                                    val r = RepeaterHolder.sendCommand("advert", logToConsole = true)
                                    r.firstOrNull() ?: "Sent"
                                }
                            },
                            enabled = idle
                        ) {
                            Text(stringResource(R.string.btn_advert_flood))
                        }
                        OutlinedButton(
                            onClick = {
                                runTopAction("advert.zerohop", "advert.zerohop") {
                                    val r = RepeaterHolder.sendCommand("advert.zerohop", logToConsole = true)
                                    r.firstOrNull() ?: "Sent"
                                }
                            },
                            enabled = idle
                        ) {
                            Text(stringResource(R.string.btn_advert_zero))
                        }
                        OutlinedButton(
                            onClick = {
                                runTopAction("reboot", "reboot") {
                                    val r = RepeaterHolder.sendCommand("reboot", logToConsole = true)
                                    r.firstOrNull() ?: "Reboot command sent"
                                }
                            },
                            enabled = idle
                        ) {
                            Text(stringResource(R.string.btn_reboot))
                        }
                    }
                }
            }
        }

        if (connected) {
            // ---- Radio Section ----
            val radioSummary = if (snap.freq.isNotBlank())
                "${snap.freq} MHz · BW ${snap.bw} · SF${snap.sf} · CR${snap.cr} · ${snap.txPower} dBm"
            else "Frequency, bandwidth, spreading factor, TX power"

            SettingsSection(title = "Radio", summary = radioSummary) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            freq = "910.525"; bw = "62.5"; sf = "7"; cr = "5"
                        }) { Text("US/CA Preset (910.525 / 62.5 / SF7 / CR5)") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = freq, onValueChange = { freq = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Freq (MHz)") })
                        OutlinedTextField(value = bw, onValueChange = { bw = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("BW (kHz)") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = sf, onValueChange = { sf = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("SF (5-12)") })
                        OutlinedTextField(value = cr, onValueChange = { cr = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("CR (5-8)") })
                        OutlinedTextField(value = txPower, onValueChange = { txPower = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("TX (dBm)") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                if (freq.isNotBlank() && bw.isNotBlank() && sf.isNotBlank() && cr.isNotBlank()) {
                                    RepeaterHolder.sendCommand("set radio ${freq.trim()},${bw.trim()},${sf.trim()},${cr.trim()}")
                                }
                                if (txPower.isNotBlank()) {
                                    RepeaterHolder.sendCommand("set tx ${txPower.trim()}")
                                }
                                RepeaterHolder.refreshSnapshot()
                                snackbar.showSnackbar("Radio settings saved (reboot repeater to apply RF changes)")
                            }
                        }) { Text(stringResource(R.string.btn_save)) }
                        OutlinedButton(onClick = {
                            scope.launch {
                                if (freq.isNotBlank() && bw.isNotBlank() && sf.isNotBlank() && cr.isNotBlank()) {
                                    RepeaterHolder.sendCommand("set radio ${freq.trim()},${bw.trim()},${sf.trim()},${cr.trim()}")
                                }
                                if (txPower.isNotBlank()) {
                                    RepeaterHolder.sendCommand("set tx ${txPower.trim()}")
                                }
                                RepeaterHolder.sendCommand("reboot")
                            }
                        }) { Text(stringResource(R.string.btn_save_restart)) }
                    }
                }
            }

            // ---- Identity & Access Section ----
            SettingsSection(
                title = "Identity & access",
                summary = snap.name.ifBlank { "Name, passwords, private key" }
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Node name") })
                        Button(onClick = {
                            scope.launch {
                                val r = RepeaterHolder.sendCommand("set name ${name.trim()}")
                                RepeaterHolder.refreshSnapshot()
                                snackbar.showSnackbar("set name → ${r.firstOrNull() ?: "OK"}")
                            }
                        }, enabled = name.isNotBlank()) { Text("Set") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = adminPassword, onValueChange = { adminPassword = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("New admin password") })
                        Button(onClick = {
                            val pw = adminPassword.trim()
                            adminPassword = ""
                            runCli("password $pw", toastReply = true)
                        }, enabled = adminPassword.isNotBlank()) { Text("Set") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = guestPassword, onValueChange = { guestPassword = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Guest password") })
                        Button(onClick = {
                            scope.launch {
                                val r = RepeaterHolder.sendCommand("set guest.password ${guestPassword.trim()}")
                                RepeaterHolder.refreshSnapshot()
                                snackbar.showSnackbar("set guest.password → ${r.firstOrNull() ?: "OK"}")
                            }
                        }) { Text("Set") }
                    }
                    ListItem(
                        headlineContent = { Text("Allow read-only access") },
                        supportingContent = { Text("Let guests read status without admin password") },
                        trailingContent = {
                            Switch(checked = snap.allowReadOnly, onCheckedChange = { on ->
                                scope.launch {
                                    RepeaterHolder.sendCommand("set allow.read.only ${if (on) "on" else "off"}")
                                    RepeaterHolder.refreshSnapshot()
                                }
                            })
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = prvKey, onValueChange = { prvKey = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Private key (hex)") })
                        OutlinedButton(onClick = {
                            scope.launch {
                                val res = RepeaterHolder.sendCommand("get prv.key")
                                prvKey = res.firstOrNull()?.removePrefix("> ")?.trim() ?: ""
                            }
                        }) { Text("Read") }
                        Button(onClick = {
                            runCli("set prv.key ${prvKey.trim()}", toastReply = true)
                        }, enabled = prvKey.length >= 64) { Text("Write") }
                    }
                }
            }

            // ---- Forwarding & Timing Section ----
            SettingsSection(
                title = "Forwarding & intervals",
                summary = if (snap.repeatEnabled) "Forwarding on · Flood max ${snap.floodMax.ifBlank { "-" }}" else "Forwarding disabled"
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListItem(
                        headlineContent = { Text("Repeat packets") },
                        supportingContent = { Text("Forward mesh traffic through this repeater") },
                        trailingContent = {
                            Switch(checked = snap.repeatEnabled, onCheckedChange = { on ->
                                scope.launch {
                                    RepeaterHolder.sendCommand("set repeat ${if (on) "on" else "off"}")
                                    RepeaterHolder.refreshSnapshot()
                                }
                            })
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Power saving") },
                        supportingContent = { Text("Sleep between packets when idle") },
                        trailingContent = {
                            Switch(checked = snap.powerSaving, onCheckedChange = { on ->
                                scope.launch {
                                    RepeaterHolder.sendCommand("powersaving ${if (on) "on" else "off"}")
                                    RepeaterHolder.refreshSnapshot()
                                }
                            })
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = advertMins, onValueChange = { advertMins = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Local advert (min)") })
                        OutlinedTextField(value = floodHours, onValueChange = { floodHours = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Flood advert (hr)") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = dutyCycle, onValueChange = { dutyCycle = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Duty cycle (%)") })
                        OutlinedTextField(value = floodMax, onValueChange = { floodMax = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Max flood hops") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = lat, onValueChange = { lat = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Latitude") })
                        OutlinedTextField(value = lon, onValueChange = { lon = it }, modifier = Modifier.weight(1f),
                            singleLine = true, label = { Text("Longitude") })
                    }
                    Button(onClick = {
                        scope.launch {
                            if (advertMins.isNotBlank()) RepeaterHolder.sendCommand("set advert.interval ${advertMins.trim()}")
                            if (floodHours.isNotBlank()) RepeaterHolder.sendCommand("set flood.advert.interval ${floodHours.trim()}")
                            if (dutyCycle.isNotBlank()) RepeaterHolder.sendCommand("set dutycycle ${dutyCycle.trim()}")
                            if (floodMax.isNotBlank()) RepeaterHolder.sendCommand("set flood.max ${floodMax.trim()}")
                            if (lat.isNotBlank()) RepeaterHolder.sendCommand("set lat ${lat.trim()}")
                            if (lon.isNotBlank()) RepeaterHolder.sendCommand("set lon ${lon.trim()}")
                            RepeaterHolder.refreshSnapshot()
                            snackbar.showSnackbar("Forwarding & timing settings saved")
                        }
                    }) { Text(stringResource(R.string.btn_save)) }
                }
            }

            // ---- Diagnostics Section ----
            SettingsSection(title = "Diagnostics & logs", summary = "Neighbors, packet stats, regions, live packet logging") {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { runCli("neighbors") }) { Text("Neighbors") }
                    OutlinedButton(onClick = { runCli("stats-core") }) { Text("Core stats") }
                    OutlinedButton(onClick = { runCli("stats-radio") }) { Text("Radio stats") }
                    OutlinedButton(onClick = { runCli("stats-packets") }) { Text("Packet stats") }
                    OutlinedButton(onClick = { runCli("region") }) { Text("Regions") }
                    OutlinedButton(onClick = { runCli("log start") }) { Text("Log start") }
                    OutlinedButton(onClick = { runCli("log stop") }) { Text("Log stop") }
                    OutlinedButton(onClick = { runCli("log") }) { Text("Dump log") }
                    OutlinedButton(onClick = { runCli("clear stats") }) { Text("Clear stats") }
                }
            }

            // ---- Interactive CLI Console (shown only while connected) ----
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Serial CLI console", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showCommandsDialog = true }) {
                        Text(stringResource(R.string.btn_commands))
                    }
                    TextButton(onClick = { RepeaterHolder.clearConsole() }) {
                        Text(stringResource(R.string.btn_clear))
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF111111))
                    .padding(8.dp)
            ) {
                SelectionContainer {
                    LazyColumn(
                        state = consoleListState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(consoleLines) { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = when {
                                    line.startsWith("> ") -> Color(0xFF9CCC65)
                                    line.startsWith("[repeater]") -> Color(0xFF80CBC4)
                                    line.contains("ERR", ignoreCase = true) || line.contains("Error", ignoreCase = true) -> Color(0xFFFF8A80)
                                    else -> Color(0xFFDDDDDD)
                                }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = rawCmd,
                    onValueChange = { rawCmd = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.repeater_console_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (rawCmd.isNotBlank()) {
                            val c = rawCmd
                            rawCmd = ""
                            runCli(c)
                        }
                    })
                )
                Button(
                    onClick = {
                        val c = rawCmd
                        rawCmd = ""
                        runCli(c)
                    },
                    enabled = rawCmd.isNotBlank()
                ) { Text(stringResource(R.string.bbs_run)) }
            }
        }

        SnackbarHost(snackbar)
    }
}

@Composable
private fun RepeaterCommandsDialog(
    onDismiss: () -> Unit,
    onSelectCommand: (String) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val filtered = remember(filter) {
        if (filter.isBlank()) REPEATER_COMMANDS
        else REPEATER_COMMANDS.filter {
            it.usage.contains(filter, ignoreCase = true) ||
                it.description.contains(filter, ignoreCase = true) ||
                it.category.contains(filter, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MeshCore Repeater Commands") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Tap any command to insert it into the CLI input bar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_filter)) }
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered) { item ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectCommand(item.insertText) }
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.usage,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = item.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
