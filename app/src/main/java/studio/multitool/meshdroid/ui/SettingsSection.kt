package studio.multitool.meshdroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * A card that opens and closes.
 *
 * The Node tab had every setting on screen at once, which made the ones that matter on a
 * given day hard to find. Grouping them and starting closed keeps the screen to a list of
 * headings, with the summary line saying enough that most visits need no tap at all.
 */
@Composable
fun SettingsSection(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            ListItem(
                modifier = Modifier.clickable { expanded = !expanded },
                headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
                supportingContent = { Text(summary, style = MaterialTheme.typography.bodySmall) },
                trailingContent = {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) studio.multitool.meshdroid.R.string.section_collapse
                            else studio.multitool.meshdroid.R.string.section_expand
                        )
                    )
                }
            )
            AnimatedVisibility(visible = expanded) { Column { content() } }
        }
    }
}
