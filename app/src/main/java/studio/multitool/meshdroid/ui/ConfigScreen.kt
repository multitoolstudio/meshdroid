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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.multitool.meshdroid.NodeFiles
import studio.multitool.meshdroid.R

@Composable
fun ConfigScreen(onRestart: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val configVersion by NodeFiles.configVersion.collectAsStateWithLifecycle()

    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var loadedVersion by remember { mutableStateOf(-1) }
    var presetsOpen by remember { mutableStateOf(false) }
    var pendingPreset by remember { mutableStateOf<NodeFiles.Preset?>(null) }
    var presets by remember { mutableStateOf<List<NodeFiles.Preset>>(emptyList()) }
    val dirty = loaded && text != saved

    LaunchedEffect(Unit) {
        presets = withContext(Dispatchers.IO) { runCatching { NodeFiles.presets(ctx) }.getOrDefault(emptyList()) }
    }

    // Reloads whenever config.yaml changes on disk, which covers the region picker
    // on the Node tab, an import and a restore. An editor with unsaved changes is
    // left alone so that nothing the user typed disappears; the note tells them a
    // newer version exists.
    LaunchedEffect(configVersion) {
        if (loadedVersion == configVersion) return@LaunchedEffect
        val onDisk = withContext(Dispatchers.IO) { runCatching { NodeFiles.readConfig(ctx) }.getOrDefault("") }
        val hadEdits = loaded && text != saved
        val replaced = loaded && onDisk != saved
        loadedVersion = configVersion
        if (hadEdits) {
            saved = onDisk   // Revert now brings back what is really on disk
            snackbar.showSnackbar(ctx.getString(R.string.snack_config_changed_dirty))
        } else {
            text = onDisk; saved = onDisk; loaded = true
            if (replaced) snackbar.showSnackbar(ctx.getString(R.string.snack_config_changed))
        }
    }

    fun save(then: (() -> Unit)? = null) {
        val body = text
        scope.launch {
            withContext(Dispatchers.IO) { NodeFiles.writeConfig(ctx, body) }
            saved = body
            loadedVersion = NodeFiles.configVersion.value
            if (then == null) snackbar.showSnackbar(ctx.getString(R.string.snack_config_saved)) else then()
        }
    }

    val exportYaml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-yaml")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val body = text
        scope.launch {
            withContext(Dispatchers.IO) { NodeFiles.writeConfig(ctx, body); NodeFiles.exportConfigYaml(ctx, uri) }
            saved = body
            loadedVersion = NodeFiles.configVersion.value
            snackbar.showSnackbar(ctx.getString(R.string.snack_config_exported))
        }
    }
    val importYaml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) { NodeFiles.importConfigYaml(ctx, uri) }
            text = imported; saved = imported; loadedVersion = NodeFiles.configVersion.value
            snackbar.showSnackbar(ctx.getString(R.string.snack_config_imported))
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.config_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.config_subtitle),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                OutlinedButton(onClick = { presetsOpen = true }, enabled = presets.isNotEmpty()) { Text(stringResource(R.string.btn_presets)) }
                DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                    presets.forEach { p ->
                        DropdownMenuItem(text = { Text(p.title) }, onClick = { presetsOpen = false; pendingPreset = p })
                    }
                }
            }
            OutlinedButton(onClick = { importYaml.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.btn_import)) }
            OutlinedButton(onClick = { exportYaml.launch("config.yaml") }) { Text(stringResource(R.string.btn_export)) }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            enabled = loaded,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            label = { Text(stringResource(if (!loaded) R.string.label_loading else if (dirty) R.string.label_unsaved else R.string.label_saved)) }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save() }, enabled = dirty) { Text(stringResource(R.string.btn_save)) }
            Button(onClick = { save { onRestart() } }, enabled = loaded) { Text(stringResource(R.string.btn_save_restart)) }
            TextButton(onClick = { text = saved }, enabled = dirty) { Text(stringResource(R.string.btn_revert)) }
        }
        SnackbarHost(snackbar)
    }

    pendingPreset?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingPreset = null },
            title = { Text(stringResource(R.string.dialog_preset_title, p.title)) },
            text = { Text(stringResource(R.string.dialog_preset_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingPreset = null
                    scope.launch { text = withContext(Dispatchers.IO) { NodeFiles.readPreset(ctx, p.file) } }
                }) { Text(stringResource(R.string.btn_load)) }
            },
            dismissButton = { TextButton(onClick = { pendingPreset = null }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }
}
