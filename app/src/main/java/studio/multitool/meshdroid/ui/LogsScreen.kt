package studio.multitool.meshdroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import studio.multitool.meshdroid.DaemonState
import studio.multitool.meshdroid.NodeFiles
import studio.multitool.meshdroid.NodeStatus
import studio.multitool.meshdroid.Profile
import studio.multitool.meshdroid.R
import studio.multitool.meshdroid.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val log by DaemonState.log.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    var verbose by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val profile by Profile.selected.collectAsStateWithLifecycle()
    val status by DaemonState.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { verbose = withContext(Dispatchers.IO) { Settings.verbose(ctx) } }

    // DaemonState emits at most a few times a second, so filtering here costs a pass
    // over the buffer per emission rather than per log line.
    val shown by remember { derivedStateOf { if (filter.isBlank()) log else log.filter { it.contains(filter, ignoreCase = true) } } }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val snapshot = log
        scope.launch { withContext(Dispatchers.IO) { runCatching { NodeFiles.exportLog(ctx, uri, snapshot) } } }
    }

    // scrollToItem, not animateScrollToItem: a new animation every time a line
    // arrives never finishes, and the list stutters instead of following.
    LaunchedEffect(shown.size, follow) { if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.size - 1) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                export.launch("meshdroid-$stamp.log")
            }) { Text(stringResource(R.string.btn_export)) }
            TextButton(onClick = { DaemonState.clearLog() }) { Text(stringResource(R.string.btn_clear)) }
            FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text(stringResource(R.string.chip_follow)) })
            // Verbosity is a command-line flag the daemon reads once at launch, so
            // the chip does nothing until the next start. MeshCore takes no such
            // flag: its verbosity is the MESH_DEBUG and MESH_PACKET_LOGGING build
            // flags, so the chip is not offered there.
            if (profile.supportsVerbose) {
                FilterChip(selected = verbose, onClick = {
                    verbose = !verbose
                    val v = verbose
                    scope.launch {
                        withContext(Dispatchers.IO) { Settings.setVerbose(ctx, v) }
                        if (status == NodeStatus.RUNNING)
                            snackbar.showSnackbar(ctx.getString(R.string.snack_restart_to_apply))
                    }
                }, label = { Text(stringResource(R.string.chip_verbose)) })
            }
        }
        OutlinedTextField(value = filter, onValueChange = { filter = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text(stringResource(R.string.label_filter)) })

        // Weight on the Box, fill inside it. See the note in MeshScreen: the other way
        // round pushes whatever follows off the bottom of the screen.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color(0xFF111111)).padding(8.dp)
            ) {
                items(shown) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        color = when {
                            "ERROR" in line -> Color(0xFFFF8A80)
                            "WARN" in line -> Color(0xFFFFD180)
                            line.startsWith("[meshdroid]") -> Color(0xFF80CBC4)
                            else -> Color(0xFFDDDDDD)
                        })
                }
            }
            }
        }

        Text(stringResource(R.string.label_line_count, shown.size),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SnackbarHost(snackbar)
    }
}
