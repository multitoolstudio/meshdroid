package studio.multitool.meshdroid.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.multitool.meshdroid.Profile
import studio.multitool.meshdroid.R

/** Material 3 theme: dynamic colour on Android 12+, a fixed green scheme below that. */
@Composable
fun MeshdroidTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF81C784))
        else -> lightColorScheme(primary = Color(0xFF2E7D32))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private const val BBS_SETTINGS_ROUTE = "bbs_settings"

/**
 * Bottom-bar destinations.
 *
 * Not every tab applies to every profile. Config edits config.yaml, which only
 * meshtasticd has. Mesh and BBS both talk to the daemon through MeshLink, which
 * is Meshtastic's protocol. Showing them under MeshCore would offer screens that
 * cannot work, so they are hidden rather than left to fail.
 */
private enum class Tab(
    val route: String,
    val label: Int,
    val icon: ImageVector,
    val appliesTo: (Profile) -> Boolean = { true },
) {
    NODE("node", R.string.tab_node, Icons.Filled.Hub),
    CONFIG("config", R.string.tab_config, Icons.Filled.Description, { it.hasConfigFile }),
    MESH("mesh", R.string.tab_mesh, Icons.Filled.Forum, { it.supportsBbs }),
    BBS("bbs", R.string.tab_bbs, Icons.Filled.Dashboard, { it.supportsBbs }),
    LOGS("logs", R.string.tab_logs, Icons.Filled.Terminal),
}

/** Root scaffold: bottom navigation between the Node, Config and Logs screens. */
@Composable
fun MeshdroidApp(
    radioPresent: Boolean,
    notificationsDenied: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val profile by Profile.selected.collectAsStateWithLifecycle()
    val tabs = Tab.entries.filter { it.appliesTo(profile) }

    // Switching profile can hide the tab being looked at. Without this the user is
    // left on a screen that has no way back and no bar entry highlighted.
    LaunchedEffect(profile) {
        if (current != null && tabs.none { it.route == current } && current != BBS_SETTINGS_ROUTE) {
            nav.navigate(Tab.NODE.route) { popUpTo(Tab.NODE.route); launchSingleTop = true }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    val label = stringResource(t.label)
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { if (current != t.route) nav.navigate(t.route) { popUpTo(Tab.NODE.route); launchSingleTop = true } },
                        icon = { Icon(t.icon, contentDescription = label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { inner ->
        NavHost(nav, startDestination = Tab.NODE.route, modifier = Modifier.padding(inner)) {
            composable(Tab.NODE.route) { NodeScreen(radioPresent, notificationsDenied, onStart, onStop, onRestart, onClose) }
            composable(Tab.CONFIG.route) { ConfigScreen(onRestart) }
            composable(Tab.MESH.route) { MeshScreen() }
            composable(Tab.BBS.route) {
                BbsScreen(onOpenSettings = { nav.navigate(BBS_SETTINGS_ROUTE) })
            }
            // Reached from the BBS tab rather than the bottom bar: it is operator setup, not
            // somewhere to land.
            composable(BBS_SETTINGS_ROUTE) { BbsSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Tab.LOGS.route) { LogsScreen() }
        }
    }
}
