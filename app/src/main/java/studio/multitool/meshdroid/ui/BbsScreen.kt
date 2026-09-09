package studio.multitool.meshdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.multitool.meshdroid.DaemonState
import studio.multitool.meshdroid.NodeStatus
import studio.multitool.meshdroid.R
import studio.multitool.meshdroid.Settings
import studio.multitool.meshdroid.bbs.BbsHolder
import studio.multitool.meshdroid.mesh.NodeId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

/**
 * The BBS: whether it is answering, what it has answered, and a console that runs a command
 * without transmitting so modules can be tried out before the mesh sees them.
 */
@Composable
fun BbsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { BbsHolder.engine(ctx) }
    val running by engine.running.collectAsStateWithLifecycle()
    val entries by engine.log.collectAsStateWithLifecycle()
    val nodeStatus by DaemonState.status.collectAsStateWithLifecycle()
    val proxyClients by DaemonState.proxyClients.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    var enabled by remember { mutableStateOf(false) }
    var onChannels by remember { mutableStateOf(true) }
    var prefix by remember { mutableStateOf("!") }
    var console by remember { mutableStateOf("") }
    var consoleOut by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        BbsHolder.init(ctx)
        withContext(Dispatchers.IO) {
            enabled = Settings.bbsEnabled(ctx)
            onChannels = Settings.bbsOnChannels(ctx)
            prefix = Settings.bbsPrefix(ctx)
        }
    }
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1) }

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!keyboardOpen) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(if (running) R.string.bbs_running else R.string.bbs_stopped),
                                style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = {
                            Text(
                                when {
                                    !enabled -> stringResource(R.string.bbs_explain)
                                    nodeStatus != NodeStatus.RUNNING -> stringResource(R.string.bbs_needs_node)
                                    proxyClients == 0 -> stringResource(R.string.bbs_takes_slot)
                                    else -> stringResource(R.string.bbs_modules,
                                        engine.moduleList().joinToString(", ") { it.keyword })
                                }
                            )
                        },
                        trailingContent = {
                            Switch(checked = enabled, onCheckedChange = { v ->
                                enabled = v
                                scope.launch {
                                    withContext(Dispatchers.IO) { Settings.setBbsEnabled(ctx, v) }
                                    BbsHolder.refresh(ctx)
                                }
                            })
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.bbs_on_channels)) },
                        supportingContent = { Text(stringResource(R.string.bbs_on_channels_detail, prefix)) },
                        trailingContent = {
                            Switch(checked = onChannels, onCheckedChange = { v ->
                                onChannels = v
                                scope.launch { withContext(Dispatchers.IO) { Settings.setBbsOnChannels(ctx, v) } }
                            })
                        }
                    )
                }
            }

            Text(stringResource(R.string.bbs_activity), style = MaterialTheme.typography.titleSmall)
            Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 80.dp)) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF111111)).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(entries) { e ->
                            Column {
                                Text("${clock.format(Date(e.at * 1000))}  ${e.from}: ${e.request}",
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                                    color = Color(0xFF9CCC65))
                                e.reply?.let {
                                    Text("  -> $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        color = if (e.sent) Color(0xFFDDDDDD) else Color(0xFFFFD180))
                                }
                            }
                        }
                    }
                }
            }

            consoleOut?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = console,
                    onValueChange = { console = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.bbs_console)) }
                )
                Button(
                    onClick = {
                        val cmd = console
                        console = ""
                        scope.launch {
                            consoleOut = withContext(Dispatchers.IO) {
                                engine.runLocally(cmd, NodeId(0))
                            } ?: "(no reply)"
                        }
                    },
                    enabled = console.isNotBlank()
                ) { Text(stringResource(R.string.bbs_run)) }
            }
        }
    }
}
