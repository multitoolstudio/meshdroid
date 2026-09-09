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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import studio.multitool.meshdroid.BuildConfig
import studio.multitool.meshdroid.DaemonState
import studio.multitool.meshdroid.NodeFiles
import studio.multitool.meshdroid.NodeStatus
import studio.multitool.meshdroid.R
import studio.multitool.meshdroid.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Waits for the daemon to actually be gone. Used before anything replaces prefs/. */
private suspend fun awaitStopped(): Boolean = withTimeoutOrNull(15_000) {
    withContext(Dispatchers.Default) {
        DaemonState.status.first { it == NodeStatus.STOPPED || it == NodeStatus.ERROR }
    }
} != null

@Composable
fun NodeScreen(
    radioPresent: Boolean,
    notificationsDenied: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val status by DaemonState.status.collectAsStateWithLifecycle()
    val detail by DaemonState.detail.collectAsStateWithLifecycle()
    val nodeId by DaemonState.nodeId.collectAsStateWithLifecycle()
    val firmware by DaemonState.firmware.collectAsStateWithLifecycle()
    val serial by DaemonState.radioSerial.collectAsStateWithLifecycle()
    val regionUnset by DaemonState.regionUnset.collectAsStateWithLifecycle()
    val configVersion by NodeFiles.configVersion.collectAsStateWithLifecycle()
    val battery by DaemonState.battery.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val running = status == NodeStatus.RUNNING || status == NodeStatus.STARTING || status == NodeStatus.STOPPING

    var lan by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(true) }
    var batteryReport by remember { mutableStateOf(true) }
    var proxy by remember { mutableStateOf(true) }
    var confirmReset by remember { mutableStateOf(false) }
    var region by remember { mutableStateOf<String?>(null) }
    var regionMenu by remember { mutableStateOf(false) }
    var askRegion by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }

    // Settings and config.yaml are read off the main thread, and the region is read
    // again whenever config.yaml changes (an import, a restore, or the Config tab).
    LaunchedEffect(Unit) {
        val values = withContext(Dispatchers.IO) {
            listOf(Settings.lanEnabled(ctx), Settings.autoStart(ctx), Settings.batteryReport(ctx), Settings.proxyEnabled(ctx))
        }
        lan = values[0]; autoStart = values[1]; batteryReport = values[2]; proxy = values[3]
    }
    LaunchedEffect(configVersion) {
        region = withContext(Dispatchers.IO) { runCatching { NodeFiles.readRegion(ctx) }.getOrNull() }
    }

    // Ask once per session when the daemon reports the region as unset and the YAML
    // has no Region key either. Dismissing with "Later" does not ask again until
    // the node is restarted.
    LaunchedEffect(regionUnset, region) { if (regionUnset && region == null) askRegion = true }

    fun applyRegion(code: String) {
        regionMenu = false; askRegion = false
        scope.launch {
            withContext(Dispatchers.IO) { NodeFiles.writeRegion(ctx, code) }
            region = code
            if (running) onRestart() else snackbar.showSnackbar(ctx.getString(R.string.snack_region_saved, code))
        }
    }

    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NodeFiles.exportBackup(ctx, uri) } }
                .onSuccess { snackbar.showSnackbar(ctx.getString(R.string.snack_backup_saved)) }
                .onFailure { e -> snackbar.showSnackbar(ctx.getString(R.string.snack_backup_failed, e.message ?: "")) }
        }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri
    }

    /**
     * Stops the node, waits for the process to exit, then replaces the files. Doing
     * it in that order keeps the daemon from writing prefs/ underneath the restore.
     */
    fun runRestore(uri: android.net.Uri) {
        scope.launch {
            snackbar.showSnackbar(ctx.getString(R.string.snack_restoring))
            onStop()
            if (!awaitStopped()) {
                snackbar.showSnackbar(ctx.getString(R.string.snack_restore_failed, "the node did not stop"))
                return@launch
            }
            runCatching { withContext(Dispatchers.IO) { NodeFiles.importBackup(ctx, uri) } }
                .onSuccess { n -> snackbar.showSnackbar(ctx.getString(R.string.snack_restored, n)) }
                .onFailure { e -> snackbar.showSnackbar(ctx.getString(R.string.snack_restore_failed, e.message ?: "")) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(when (status) {
                        NodeStatus.STOPPED -> R.string.status_stopped
                        NodeStatus.STARTING -> R.string.status_starting
                        NodeStatus.RUNNING -> R.string.status_running
                        NodeStatus.STOPPING -> R.string.status_stopping
                        NodeStatus.ERROR -> R.string.status_error
                    }),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (status == NodeStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (nodeId != null || firmware != null || serial != null) {
                    Spacer(Modifier.height(6.dp))
                    nodeId?.let { Text(stringResource(R.string.label_node_id, it), style = MaterialTheme.typography.bodyMedium) }
                    firmware?.let { Text(stringResource(R.string.label_firmware, it), style = MaterialTheme.typography.bodyMedium) }
                    serial?.let { Text(stringResource(R.string.label_radio_serial, it), style = MaterialTheme.typography.bodyMedium) }
                }
                if (regionUnset && running) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.hint_region_unset),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (!radioPresent && !running) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.hint_plug_radio),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (notificationsDenied) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.hint_notifications_denied),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        Button(onClick = onStop) { Text(stringResource(R.string.btn_stop)) }
                        OutlinedButton(onClick = onRestart) { Text(stringResource(R.string.btn_restart)) }
                    } else {
                        Button(onClick = onStart, enabled = radioPresent) { Text(stringResource(R.string.btn_start)) }
                    }
                }
            }
        }

        SettingsSection(
            title = stringResource(R.string.section_radio),
            summary = stringResource(R.string.section_radio_summary, region ?: stringResource(R.string.region_none)),
            initiallyExpanded = region == null
        ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.item_region)) },
                    supportingContent = {
                        Text(region?.let { stringResource(R.string.item_region_set, it) } ?: stringResource(R.string.item_region_unset))
                    },
                    trailingContent = {
                        Column {
                            OutlinedButton(onClick = { regionMenu = true }) { Text(region ?: stringResource(R.string.btn_choose)) }
                            DropdownMenu(expanded = regionMenu, onDismissRequest = { regionMenu = false }) {
                                NodeFiles.regions.forEach { code ->
                                    DropdownMenuItem(text = { Text(code) }, onClick = { applyRegion(code) })
                                }
                            }
                        }
                    }
                )
        }

        SettingsSection(
            title = stringResource(R.string.section_connections),
            summary = stringResource(
                if (proxy) R.string.section_connections_shared else R.string.section_connections_single,
                Settings.API_PORT
            )
        ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.item_connection)) },
                    supportingContent = { Text(stringResource(R.string.item_connection_detail, Settings.API_PORT)) }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.item_lan)) },
                    supportingContent = { Text(stringResource(if (lan) R.string.item_lan_on else R.string.item_lan_off)) },
                    trailingContent = {
                        Switch(checked = lan, onCheckedChange = { v ->
                            lan = v
                            scope.launch {
                                withContext(Dispatchers.IO) { Settings.setLanEnabled(ctx, v) }
                                if (running) snackbar.showSnackbar(ctx.getString(R.string.snack_restart_to_apply))
                            }
                        })
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.item_proxy)) },
                    supportingContent = { Text(stringResource(if (proxy) R.string.item_proxy_on else R.string.item_proxy_off)) },
                    trailingContent = {
                        Switch(checked = proxy, onCheckedChange = { v ->
                            proxy = v
                            scope.launch {
                                withContext(Dispatchers.IO) { Settings.setProxyEnabled(ctx, v) }
                                if (running) snackbar.showSnackbar(ctx.getString(R.string.snack_restart_to_apply))
                            }
                        })
                    }
                )
        }

        SettingsSection(
            title = stringResource(R.string.section_phone),
            summary = stringResource(
                if (batteryReport) R.string.section_phone_on else R.string.section_phone_off
            )
        ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.item_battery)) },
                    supportingContent = {
                        val reading = battery
                        Text(
                            when {
                                !batteryReport -> stringResource(R.string.item_battery_off)
                                reading == null -> stringResource(R.string.item_battery_on)
                                else -> stringResource(R.string.item_battery_now, reading.percent, when {
                                    reading.charging -> stringResource(R.string.battery_charging)
                                    reading.powered -> stringResource(R.string.battery_plugged)
                                    else -> ""
                                })
                            }
                        )
                    },
                    trailingContent = {
                        Switch(checked = batteryReport, onCheckedChange = { v ->
                            batteryReport = v
                            scope.launch {
                                withContext(Dispatchers.IO) { Settings.setBatteryReport(ctx, v) }
                                if (running) snackbar.showSnackbar(ctx.getString(R.string.snack_restart_to_apply))
                            }
                        })
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.item_autostart)) },
                    trailingContent = {
                        Switch(checked = autoStart, onCheckedChange = { v ->
                            autoStart = v
                            scope.launch { withContext(Dispatchers.IO) { Settings.setAutoStart(ctx, v) } }
                        })
                    }
                )
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                ListItem(headlineContent = { Text(stringResource(R.string.item_files)) },
                    supportingContent = { Text(stringResource(R.string.item_files_detail)) })
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        exportBackup.launch("meshdroid-backup-$stamp.zip")
                    }) { Text(stringResource(R.string.btn_backup)) }
                    OutlinedButton(onClick = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                        Text(stringResource(R.string.btn_restore))
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { confirmReset = true }, enabled = !running) { Text(stringResource(R.string.btn_reset)) }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                ListItem(headlineContent = { Text(stringResource(R.string.item_close)) },
                    supportingContent = { Text(stringResource(R.string.item_close_detail)) })
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(onClick = onClose) { Text(stringResource(R.string.btn_close)) }
                }
            }
        }
        // Version footer. The firmware line names the commit the bundled daemon was
        // built from; once the node is running, the status card shows the version
        // the daemon itself reports.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.version_daemon, BuildConfig.DAEMON_FIRMWARE_COMMIT),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(40.dp))
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
        SnackbarHost(snackbar)
    }

    if (askRegion) AlertDialog(
        onDismissRequest = { askRegion = false },
        title = { Text(stringResource(R.string.dialog_region_title)) },
        text = {
            Column {
                Text(stringResource(R.string.dialog_region_text))
                Spacer(Modifier.height(12.dp))
                Column(Modifier.height(240.dp).verticalScroll(rememberScrollState())) {
                    NodeFiles.regions.forEach { code ->
                        TextButton(onClick = { applyRegion(code) }, modifier = Modifier.fillMaxWidth()) { Text(code) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { askRegion = false }) { Text(stringResource(R.string.btn_later)) } }
    )

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.dialog_restore_title)) },
            text = { Text(stringResource(R.string.dialog_restore_text)) },
            confirmButton = { TextButton(onClick = { pendingRestore = null; runRestore(uri) }) { Text(stringResource(R.string.btn_restore_confirm)) } },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text(stringResource(R.string.dialog_reset_title)) },
        text = { Text(stringResource(R.string.dialog_reset_text)) },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                scope.launch {
                    withContext(Dispatchers.IO) { NodeFiles.resetNode(ctx) }
                    snackbar.showSnackbar(ctx.getString(R.string.snack_reset_done))
                }
            }) { Text(stringResource(R.string.btn_reset_confirm)) }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.btn_cancel)) } }
    )
}
