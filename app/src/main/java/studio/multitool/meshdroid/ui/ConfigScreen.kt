package studio.multitool.meshdroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.multitool.meshdroid.NodeFiles

@Composable
fun ConfigScreen(onRestart: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var text by remember { mutableStateOf(NodeFiles.readConfig(ctx)) }
    var saved by remember { mutableStateOf(text) }
    var presetsOpen by remember { mutableStateOf(false) }
    var pendingPreset by remember { mutableStateOf<NodeFiles.Preset?>(null) }
    val presets = remember { NodeFiles.presets(ctx) }
    val dirty = text != saved

    val exportYaml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-yaml")) { uri ->
        uri?.let { NodeFiles.writeConfig(ctx, text); saved = text; NodeFiles.exportConfigYaml(ctx, it)
            scope.launch { snackbar.showSnackbar("config.yaml exported") } }
    }
    val importYaml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { text = NodeFiles.importConfigYaml(ctx, it); saved = text
            scope.launch { snackbar.showSnackbar("config.yaml imported. Restart to apply.") } }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("config.yaml", style = MaterialTheme.typography.titleLarge)
        Text("Same format as /etc/meshtasticd/config.yaml on Linux. Changes take effect after a restart.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                OutlinedButton(onClick = { presetsOpen = true }) { Text("Presets") }
                DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                    presets.forEach { p ->
                        DropdownMenuItem(text = { Text(p.title) }, onClick = { presetsOpen = false; pendingPreset = p })
                    }
                }
            }
            OutlinedButton(onClick = { importYaml.launch(arrayOf("*/*")) }) { Text("Import") }
            OutlinedButton(onClick = { exportYaml.launch("config.yaml") }) { Text("Export") }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            label = { Text(if (dirty) "Unsaved changes" else "Saved") }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { NodeFiles.writeConfig(ctx, text); saved = text
                scope.launch { snackbar.showSnackbar("Saved") } }, enabled = dirty) { Text("Save") }
            Button(onClick = { NodeFiles.writeConfig(ctx, text); saved = text; onRestart() }) { Text("Save & restart") }
            TextButton(onClick = { text = saved }, enabled = dirty) { Text("Revert") }
        }
        SnackbarHost(snackbar)
    }

    pendingPreset?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingPreset = null },
            title = { Text("Load preset “${p.title}”?") },
            text = { Text("Replaces the editor contents. Nothing is saved until you press Save.") },
            confirmButton = { TextButton(onClick = { text = NodeFiles.readPreset(ctx, p.file); pendingPreset = null }) { Text("Load") } },
            dismissButton = { TextButton(onClick = { pendingPreset = null }) { Text("Cancel") } }
        )
    }
}
