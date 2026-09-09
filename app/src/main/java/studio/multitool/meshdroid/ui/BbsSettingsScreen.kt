package studio.multitool.meshdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.multitool.meshdroid.R
import studio.multitool.meshdroid.Settings
import studio.multitool.meshdroid.bbs.BbsHolder
import studio.multitool.meshdroid.bbs.BbsStore
import studio.multitool.meshdroid.bbs.Sanitize

/**
 * Everything an operator sets for the BBS: who may run admin commands, what is stored, and
 * how it behaves on channels. Separate from the activity view so that a text field here is
 * never removed from the screen by the keyboard opening.
 */
@Composable
fun BbsSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { BbsHolder.engine(ctx) }
    val store = remember { engine.storeHandle() }

    var onChannels by remember { mutableStateOf(true) }
    var prefix by remember { mutableStateOf("!") }
    var requirePki by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var admins by remember { mutableStateOf<List<BbsStore.Admin>>(emptyList()) }
    var newAdmin by remember { mutableStateOf("") }
    var bulletins by remember { mutableStateOf(0) }
    var mail by remember { mutableStateOf(0) }
    var waiting by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf<String?>(null) }
    var addError by remember { mutableStateOf<String?>(null) }

    fun refresh() = scope.launch {
        val snapshot = withContext(Dispatchers.IO) {
            listOf(store.bulletinCount(), store.mailCount(), store.pendingMailCount()) to store.admins()
        }
        bulletins = snapshot.first[0]; mail = snapshot.first[1]; waiting = snapshot.first[2]
        admins = snapshot.second
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            onChannels = Settings.bbsOnChannels(ctx)
            prefix = Settings.bbsPrefix(ctx)
            requirePki = Settings.bbsAdminRequiresPki(ctx)
            locked = Settings.bbsLocked(ctx)
        }
        refresh()
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.bbs_back)) }
            Text(stringResource(R.string.bbs_settings_title), style = MaterialTheme.typography.titleLarge)
        }

        // ---- answering ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                ListItem(headlineContent = { Text(stringResource(R.string.bbs_group_answering),
                    style = MaterialTheme.typography.titleSmall) })
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
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.bbs_locked)) },
                    supportingContent = { Text(stringResource(if (locked) R.string.bbs_locked_on else R.string.bbs_locked_off)) },
                    trailingContent = {
                        Switch(checked = locked, onCheckedChange = { v ->
                            locked = v
                            scope.launch { withContext(Dispatchers.IO) { Settings.setBbsLocked(ctx, v) } }
                        })
                    }
                )
            }
        }

        // ---- admins ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.bbs_admins), style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text(stringResource(R.string.bbs_admins_detail)) }
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newAdmin,
                        onValueChange = { newAdmin = it; addError = null },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        isError = addError != null,
                        label = { Text(stringResource(R.string.bbs_admin_add_hint)) }
                    )
                    Button(
                        onClick = {
                            val address = Sanitize.address(newAdmin)
                            if (!Sanitize.isNodeAddress(address)) {
                                addError = ctx.getString(R.string.bbs_admin_bad_id)
                                return@Button
                            }
                            newAdmin = ""
                            scope.launch {
                                withContext(Dispatchers.IO) { store.addAdmin(address, address) }
                                refresh()
                            }
                        },
                        enabled = newAdmin.isNotBlank()
                    ) { Text(stringResource(R.string.bbs_admin_add)) }
                }
                addError?.let {
                    Text(it, Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (admins.isEmpty()) {
                    ListItem(headlineContent = {
                        Text(stringResource(R.string.bbs_admins_none), style = MaterialTheme.typography.bodySmall)
                    })
                }
                admins.forEach { a ->
                    ListItem(
                        headlineContent = { Text(a.nodeId, style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = {
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.removeAdmin(a.nodeId) }
                                    refresh()
                                }
                            }) { Text(stringResource(R.string.bbs_admin_remove)) }
                        }
                    )
                }
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.bbs_admin_pki)) },
                    supportingContent = {
                        Text(stringResource(if (requirePki) R.string.bbs_admin_pki_on else R.string.bbs_admin_pki_off))
                    },
                    trailingContent = {
                        Switch(checked = requirePki, onCheckedChange = { v ->
                            requirePki = v
                            scope.launch { withContext(Dispatchers.IO) { Settings.setBbsAdminRequiresPki(ctx, v) } }
                        })
                    }
                )
            }
        }

        // ---- storage ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.bbs_storage), style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text(stringResource(R.string.bbs_storage_detail, bulletins, mail, waiting)) }
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirmClear = "board" }) { Text(stringResource(R.string.bbs_clear_board)) }
                    OutlinedButton(onClick = { confirmClear = "mail" }) { Text(stringResource(R.string.bbs_clear_mail)) }
                    TextButton(onClick = { confirmClear = "delivered" }) { Text(stringResource(R.string.bbs_clear_delivered)) }
                }
            }
        }
    }

    confirmClear?.let { what ->
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text(stringResource(R.string.bbs_clear_title)) },
            text = {
                Text(stringResource(when (what) {
                    "board" -> R.string.bbs_clear_board_text
                    "mail" -> R.string.bbs_clear_mail_text
                    else -> R.string.bbs_clear_delivered_text
                }))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            when (what) {
                                "board" -> store.clearBulletins()
                                "mail" -> store.clearMail()
                                else -> store.clearDeliveredMail()
                            }
                        }
                        refresh()
                    }
                }) { Text(stringResource(R.string.bbs_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }
}
