package studio.multitool.meshdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.multitool.meshdroid.DaemonState
import studio.multitool.meshdroid.EnvironmentReporter
import studio.multitool.meshdroid.PhoneSensors
import studio.multitool.meshdroid.Settings
import studio.multitool.meshdroid.NodeStatus
import studio.multitool.meshdroid.R
import studio.multitool.meshdroid.mesh.LinkStatus
import studio.multitool.meshdroid.mesh.MeshEvent
import studio.multitool.meshdroid.mesh.MeshLinkHolder
import studio.multitool.meshdroid.mesh.MeshtasticTcpLink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Console for the mesh link: connect, watch what the node is hearing, send a message.
 *
 * Connecting takes the daemon's single client slot, which disconnects the Meshtastic app
 * for as long as this is up. The screen says so rather than letting the other app appear
 * to fail on its own.
 */
@Composable
fun MeshScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val nodeStatus by DaemonState.status.collectAsStateWithLifecycle()
    val linkStatus by MeshLinkHolder.link.status.collectAsStateWithLifecycle()
    val selfNode by MeshLinkHolder.link.selfNode.collectAsStateWithLifecycle()
    val history by MeshLinkHolder.history.collectAsStateWithLifecycle()
    val proxyClients by DaemonState.proxyClients.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val published by (MeshLinkHolder.environment?.last ?: MutableStateFlow(null)).collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val nodeRunning = nodeStatus == NodeStatus.RUNNING
    val connected = linkStatus == LinkStatus.CONNECTED
    // With the keyboard up there is roughly half a screen to work with. The cards are
    // reference material rather than something to read while typing, so they give up their
    // space to the event list and come back when the keyboard is dismissed.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(Unit) { MeshLinkHolder.init(ctx) }
    LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.scrollToItem(history.size - 1) }

    Box(Modifier.fillMaxSize().imePadding()) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!keyboardOpen) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(when (linkStatus) {
                        LinkStatus.DISCONNECTED -> R.string.link_disconnected
                        LinkStatus.CONNECTING -> R.string.link_connecting
                        LinkStatus.SYNCING -> R.string.link_syncing
                        LinkStatus.CONNECTED -> R.string.link_connected
                        LinkStatus.ERROR -> R.string.link_error
                    }),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (linkStatus == LinkStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    when {
                        connected && proxyClients > 1 -> stringResource(R.string.link_shared, proxyClients - 1)
                        connected && proxyClients == 1 -> stringResource(R.string.link_shared_alone)
                        connected -> stringResource(R.string.link_takes_slot)
                        proxyClients > 0 -> stringResource(R.string.link_explain_proxy)
                        else -> stringResource(R.string.link_explain)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                selfNode?.let { Text(stringResource(R.string.link_self, it.toString()), style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (linkStatus == LinkStatus.DISCONNECTED || linkStatus == LinkStatus.ERROR) {
                        Button(onClick = { MeshLinkHolder.connect() }, enabled = nodeRunning) {
                            Text(stringResource(R.string.btn_connect))
                        }
                    } else {
                        OutlinedButton(onClick = { MeshLinkHolder.disconnect() }) { Text(stringResource(R.string.btn_disconnect)) }
                    }
                    TextButton(onClick = { MeshLinkHolder.clearHistory() }) { Text(stringResource(R.string.btn_clear)) }
                }
                if (!nodeRunning) Text(stringResource(R.string.link_needs_node),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }

        if (!keyboardOpen) SensorCard(published)

        // The weight goes on a plain Box and the list fills it. Putting weight on the
        // SelectionContainer and fillMaxHeight on the list inside it left the row below
        // measured off the bottom of the screen, with only the text field's top border
        // visible under the navigation bar.
        Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 80.dp)) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF111111)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(history) { _, e -> EventLine(e) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.toByteArray().size <= MeshtasticTcpLink.MAX_TEXT_BYTES) draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = connected,
                label = { Text(stringResource(R.string.label_broadcast)) }
            )
            Button(
                onClick = {
                    val body = draft
                    draft = ""
                    scope.launch {
                        runCatching { MeshLinkHolder.link.sendText(body) }
                            .onFailure { snackbar.showSnackbar(ctx.getString(R.string.snack_send_failed, it.message ?: "")) }
                    }
                },
                enabled = connected && draft.isNotBlank()
            ) { Text(stringResource(R.string.btn_send)) }
        }
    }
    // Overlaid rather than placed in the column: a SnackbarHost in the flow reserves
    // space at the bottom of the screen whether or not a snackbar is showing.
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * What this phone can measure, and what of it is being published. Lives here rather than on
 * the Node tab because publishing only happens while the link above is connected.
 */
@Composable
private fun SensorCard(published: EnvironmentReporter.Published?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<List<PhoneSensors.Kind>>(emptyList()) }
    var pressureOn by remember { mutableStateOf(false) }
    var luxOn by remember { mutableStateOf(false) }
    var readings by remember { mutableStateOf<Map<PhoneSensors.Kind, Float?>>(emptyMap()) }

    LaunchedEffect(Unit) {
        available = withContext(Dispatchers.IO) { PhoneSensors.available(ctx) }
        pressureOn = withContext(Dispatchers.IO) { Settings.envPressure(ctx) }
        luxOn = withContext(Dispatchers.IO) { Settings.envLux(ctx) }
        readings = available.associateWith { PhoneSensors.readOnce(ctx, it) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.sensors_title), style = MaterialTheme.typography.titleMedium)
            if (available.isEmpty()) {
                Text(stringResource(R.string.sensors_none), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            available.forEach { kind ->
                val reading = readings[kind]
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${kind.label}  " + (reading?.let { "%.1f ${kind.unit}".format(it) } ?: "-"),
                            style = MaterialTheme.typography.bodyMedium)
                        PhoneSensors.describe(ctx, kind)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    when (kind) {
                        PhoneSensors.Kind.PRESSURE -> Switch(checked = pressureOn, onCheckedChange = { v ->
                            pressureOn = v
                            scope.launch { withContext(Dispatchers.IO) { Settings.setEnvPressure(ctx, v) } }
                        })
                        PhoneSensors.Kind.LIGHT -> Switch(checked = luxOn, onCheckedChange = { v ->
                            luxOn = v
                            scope.launch { withContext(Dispatchers.IO) { Settings.setEnvLux(ctx, v) } }
                        })
                        // Temperature and humidity have no publishing path yet: the phones
                        // that carry them are rare enough that nothing has been tested.
                        else -> Text(stringResource(R.string.sensors_unused),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (available.isNotEmpty()) {
                Text(
                    published?.let {
                        stringResource(R.string.sensors_last, clock.format(Date(it.atSeconds * 1000)))
                    } ?: stringResource(R.string.sensors_pending, Settings.envIntervalMinutes(ctx)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun EventLine(e: MeshEvent) {
    val (colour, body) = when (e) {
        is MeshEvent.Text -> Color(0xFF9CCC65) to
            "${if (e.isDirect) "DM" else "ch${e.channel}"} ${e.from}: ${e.text}" +
                (e.snr?.let { " (snr %.1f)".format(it) } ?: "")
        is MeshEvent.NodeSeen -> Color(0xFF80CBC4) to
            "node ${e.id} ${e.longName ?: ""}" + (e.batteryLevel?.let { if (it > 100) " PWR" else " $it%" } ?: "")
        is MeshEvent.Other -> Color(0xFF9E9E9E) to "${e.from ?: ""} ${e.portName} ${e.bytes}B"
        is MeshEvent.StatusChanged -> Color(0xFFFFD180) to
            "link ${e.status.name.lowercase()}" + (e.detail?.let { ": $it" } ?: "")
    }
    Text(
        "${clock.format(Date())}  $body",
        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = colour
    )
}
