package studio.multitool.meshdroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import studio.multitool.meshdroid.DaemonState
import studio.multitool.meshdroid.NodeFiles
import studio.multitool.meshdroid.NodeStatus
import studio.multitool.meshdroid.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NodeScreen(radioPresent: Boolean, onStart: () -> Unit, onStop: () -> Unit, onRestart: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val status by DaemonState.status.collectAsStateWithLifecycle()
    val detail by DaemonState.detail.collectAsStateWithLifecycle()
    val nodeId by DaemonState.nodeId.collectAsStateWithLifecycle()
    val firmware by DaemonState.firmware.collectAsStateWithLifecycle()
    val serial by DaemonState.radioSerial.collectAsStateWithLifecycle()
    val regionUnset by DaemonState.regionUnset.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val running = status == NodeStatus.RUNNING || status == NodeStatus.STARTING

    var lan by remember { mutableStateOf(Settings.lanEnabled(ctx)) }
    var autoStart by remember { mutableStateOf(Settings.autoStart(ctx)) }
    var confirmReset by remember { mutableStateOf(false) }
    var region by remember { mutableStateOf(NodeFiles.readRegion(ctx)) }
    var regionMenu by remember { mutableStateOf(false) }
    var askRegion by remember { mutableStateOf(false) }
    // Ask once per session when the daemon reports the region as unset and the YAML
    // has no Region key either. Dismissing with "Later" does not ask again until
    // the node is restarted.
    androidx.compose.runtime.LaunchedEffect(regionUnset) { if (regionUnset && region == null) askRegion = true }

    fun applyRegion(code: String) {
        NodeFiles.writeRegion(ctx, code); region = code; regionMenu = false; askRegion = false
        if (running) onRestart() else scope.launch { snackbar.showSnackbar("Region $code saved. Applied on next start.") }
    }

    val stamp = remember { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { runCatching { NodeFiles.exportBackup(ctx, it) }
            .onSuccess { scope.launch { snackbar.showSnackbar("Backup saved") } }
            .onFailure { e -> scope.launch { snackbar.showSnackbar("Backup failed: ${e.message}") } } }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            onStop()
            runCatching { NodeFiles.importBackup(ctx, it) }
                .onSuccess { n -> scope.launch { snackbar.showSnackbar("Restored $n files. Start the node to apply.") } }
                .onFailure { e -> scope.launch { snackbar.showSnackbar("Restore failed: ${e.message}") } }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (status) {
                        NodeStatus.STOPPED -> "Node stopped"
                        NodeStatus.STARTING -> "Starting…"
                        NodeStatus.RUNNING -> "Node running"
                        NodeStatus.ERROR -> "Node error"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (status == NodeStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (nodeId != null || firmware != null || serial != null) {
                    Spacer(Modifier.height(6.dp))
                    nodeId?.let { Text("Node ID  $it", style = MaterialTheme.typography.bodyMedium) }
                    firmware?.let { Text("Firmware  $it", style = MaterialTheme.typography.bodyMedium) }
                    serial?.let { Text("Radio serial  $it", style = MaterialTheme.typography.bodyMedium) }
                }
                if (regionUnset && running) {
                    Spacer(Modifier.height(6.dp))
                    Text("LoRa region not set. The node won't transmit or create its identity keys until it is. " +
                        "Pick one below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (!radioPresent && !running) {
                    Spacer(Modifier.height(6.dp))
                    Text("Plug a CH341 LoRa dongle into the USB-C port.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        Button(onClick = onStop) { Text("Stop") }
                        OutlinedButton(onClick = onRestart) { Text("Restart") }
                    } else {
                        Button(onClick = onStart, enabled = radioPresent) { Text("Start node") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                ListItem(
                    headlineContent = { Text("LoRa region") },
                    supportingContent = { Text(if (region == null) "Not set. Required before the node can transmit."
                        else "$region. Seeds a new node; later changes from the Meshtastic app take precedence.") },
                    trailingContent = {
                        Column {
                            OutlinedButton(onClick = { regionMenu = true }) { Text(region ?: "Choose") }
                            androidx.compose.material3.DropdownMenu(expanded = regionMenu, onDismissRequest = { regionMenu = false }) {
                                NodeFiles.regions.forEach { code ->
                                    androidx.compose.material3.DropdownMenuItem(text = { Text(code) }, onClick = { applyRegion(code) })
                                }
                            }
                        }
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Meshtastic app connection") },
                    supportingContent = { Text("Network → 127.0.0.1, port ${Settings.API_PORT}") }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Allow LAN access") },
                    supportingContent = { Text(if (lan) "Other devices on Wi-Fi can connect and reconfigure this node." else "API reachable only from this phone. Recommended.") },
                    trailingContent = {
                        Switch(checked = lan, onCheckedChange = { v -> lan = v; Settings.setLanEnabled(ctx, v)
                            if (running) scope.launch { snackbar.showSnackbar("Restart the node to apply") } })
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Start when radio is plugged in") },
                    trailingContent = { Switch(checked = autoStart, onCheckedChange = { v -> autoStart = v; Settings.setAutoStart(ctx, v) }) }
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                ListItem(headlineContent = { Text("Node files") },
                    supportingContent = { Text("config.yaml plus the node database, keys and channels (prefs/).") })
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportBackup.launch("meshdroid-backup-$stamp.zip") }) { Text("Back up") }
                    OutlinedButton(onClick = { importBackup.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { confirmReset = true }, enabled = !running) { Text("Reset identity") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                ListItem(headlineContent = { Text("Close Meshdroid") },
                    supportingContent = { Text("Stops the node and quits the app. Stop/Restart above only affect the meshtasticd process.") })
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(onClick = onClose) { Text("Close Meshdroid") }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
        SnackbarHost(snackbar)
    }

    if (askRegion) AlertDialog(
        onDismissRequest = { askRegion = false },
        title = { Text("Set your LoRa region") },
        text = {
            Column {
                Text("The node has no region yet, so it can't transmit or create its identity keys. Choose the region you're in:")
                Spacer(Modifier.height(12.dp))
                Column(Modifier.height(240.dp).verticalScroll(rememberScrollState())) {
                    NodeFiles.regions.forEach { code ->
                        TextButton(onClick = { applyRegion(code) }, modifier = Modifier.fillMaxWidth()) { Text(code) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { askRegion = false }) { Text("Later") } }
    )

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset node identity?") },
        text = { Text("Deletes the node database, channels and keys. The node gets a new keypair on next start. config.yaml is kept.") },
        confirmButton = { TextButton(onClick = { NodeFiles.resetNode(ctx); confirmReset = false
            scope.launch { snackbar.showSnackbar("Node data cleared") } }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
    )
}
