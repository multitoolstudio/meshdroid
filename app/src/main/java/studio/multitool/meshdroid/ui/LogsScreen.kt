package studio.multitool.meshdroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.multitool.meshdroid.DaemonState
import studio.multitool.meshdroid.NodeFiles
import studio.multitool.meshdroid.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen() {
    val ctx = LocalContext.current
    val log by DaemonState.log.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    var verbose by remember { mutableStateOf(Settings.verbose(ctx)) }
    val listState = rememberLazyListState()
    val shown = remember(log, filter) { if (filter.isBlank()) log else log.filter { it.contains(filter, ignoreCase = true) } }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { NodeFiles.exportLog(ctx, it, log) }
    }

    LaunchedEffect(shown.size, follow) { if (follow && shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                export.launch("meshdroid-$stamp.log")
            }) { Text("Export") }
            TextButton(onClick = { DaemonState.clearLog() }) { Text("Clear") }
            FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Follow") })
            FilterChip(selected = verbose, onClick = { verbose = !verbose; Settings.setVerbose(ctx, verbose) }, label = { Text("Verbose") })
        }
        OutlinedTextField(value = filter, onValueChange = { filter = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("Filter") })

        SelectionContainer(Modifier.fillMaxWidth().weight(1f, fill = true)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color(0xFF111111)).padding(8.dp)
            ) {
                itemsIndexed(shown) { _, line ->
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

        Text("${shown.size} lines", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
