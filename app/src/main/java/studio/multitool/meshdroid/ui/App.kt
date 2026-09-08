package studio.multitool.meshdroid.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

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

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    NODE("node", "Node", Icons.Filled.Hub),
    CONFIG("config", "Config", Icons.Filled.Description),
    LOGS("logs", "Logs", Icons.Filled.Terminal),
}

/** Root scaffold: bottom navigation between the Node, Config and Logs screens. */
@Composable
fun MeshdroidApp(radioPresent: Boolean, onStart: () -> Unit, onStop: () -> Unit, onRestart: () -> Unit, onClose: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { if (current != t.route) nav.navigate(t.route) { popUpTo(Tab.NODE.route); launchSingleTop = true } },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { inner ->
        NavHost(nav, startDestination = Tab.NODE.route, modifier = Modifier.padding(inner)) {
            composable(Tab.NODE.route) { NodeScreen(radioPresent, onStart, onStop, onRestart, onClose) }
            composable(Tab.CONFIG.route) { ConfigScreen(onRestart) }
            composable(Tab.LOGS.route) { LogsScreen() }
        }
    }
}
