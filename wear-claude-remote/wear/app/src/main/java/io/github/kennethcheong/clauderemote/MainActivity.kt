package io.github.kennethcheong.clauderemote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.github.kennethcheong.clauderemote.data.BridgeSettings
import io.github.kennethcheong.clauderemote.ui.AppState
import io.github.kennethcheong.clauderemote.ui.Bridge
import io.github.kennethcheong.clauderemote.ui.NewSessionOnMachineScreen
import io.github.kennethcheong.clauderemote.ui.PickMachineScreen
import io.github.kennethcheong.clauderemote.ui.SessionsScreen
import io.github.kennethcheong.clauderemote.ui.SessionsViewModel
import io.github.kennethcheong.clauderemote.ui.SetupScreen
import io.github.kennethcheong.clauderemote.ui.TemplatesScreen
import io.github.kennethcheong.clauderemote.ui.TemplatesViewModel
import io.github.kennethcheong.clauderemote.ui.ThreadScreen
import io.github.kennethcheong.clauderemote.ui.ThreadViewModel

class MainActivity : ComponentActivity() {
    private val appState by lazy { AppState(BridgeSettings(applicationContext), BuildConfig.DEFAULT_BRIDGE_URL) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RemoteApp(appState) }
    }
}

@Composable
private fun RemoteApp(appState: AppState) {
    val bridges by appState.bridges.collectAsStateWithLifecycle()
    // Bridges paired by version 0.2 don't know their computer's name yet; ask them once.
    LaunchedEffect(appState) { appState.fillMissingNames() }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color(0xFFD97757))) {
        AppScaffold {
            if (bridges.isEmpty()) SetupScreen(appState) else RemoteNavHost(appState, bridges)
        }
    }
}

@Composable
private fun RemoteNavHost(appState: AppState, bridges: List<Bridge>) {
    val navController = rememberSwipeDismissableNavController()
    SwipeDismissableNavHost(navController = navController, startDestination = "sessions") {
        composable("sessions") {
            SessionsScreen(
                viewModel = viewModel(key = bridges.joinToString { "${it.id}:${it.name}" }) { SessionsViewModel(bridges) },
                bridges = bridges,
                onOpen = { bridgeId, id -> navController.navigate("thread/$bridgeId/$id") },
                onPickMachine = { action -> navController.navigate("pick/$action") },
                onTemplates = { bridgeId -> navController.navigate("templates/$bridgeId") },
                onAddComputer = { navController.navigate("add") },
                onUnpair = appState::unpair,
            )
        }
        composable("add") {
            SetupScreen(appState, title = "Add computer", onPaired = { navController.popBackStack() })
        }
        // With several computers, "New" first asks which one, then takes the prompt.
        composable("pick/voice") {
            NewSessionOnMachineScreen(bridges) { bridgeId, id ->
                navController.navigate("thread/$bridgeId/$id") { popUpTo("sessions") }
            }
        }
        composable("pick/template") {
            PickMachineScreen(bridges, title = "Template on…") { bridge ->
                navController.navigate("templates/${bridge.id}") { popUpTo("sessions") }
            }
        }
        composable("thread/{bridge}/{id}") { entry ->
            WithBridge(bridges, entry) { bridge ->
                val id = entry.arguments?.getString("id").orEmpty()
                ThreadScreen(
                    viewModel = viewModel(key = "${bridge.id}/$id") { ThreadViewModel(bridge.client, id) },
                    onTemplates = { navController.navigate("templates/${bridge.id}/$id") },
                )
            }
        }
        // A template from the session list starts a new session and opens it in place of the picker.
        composable("templates/{bridge}") { entry ->
            WithBridge(bridges, entry) { bridge ->
                TemplatesScreen(
                    viewModel = viewModel(key = "templates-${bridge.id}") { TemplatesViewModel(bridge.client, null) },
                    title = "New from template",
                    onSent = { id -> navController.navigate("thread/${bridge.id}/$id") { popUpTo("sessions") } },
                )
            }
        }
        // A template from a thread is sent as the next reply, then back to the thread.
        composable("templates/{bridge}/{id}") { entry ->
            WithBridge(bridges, entry) { bridge ->
                val id = entry.arguments?.getString("id").orEmpty()
                TemplatesScreen(
                    viewModel = viewModel(key = "templates-${bridge.id}/$id") { TemplatesViewModel(bridge.client, id) },
                    title = "Send template",
                    onSent = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Runs [content] with the bridge named in the route, or says it is gone (e.g. unpaired meanwhile). */
@Composable
private fun WithBridge(bridges: List<Bridge>, entry: NavBackStackEntry, content: @Composable (Bridge) -> Unit) {
    val bridge = bridges.firstOrNull { it.id == entry.arguments?.getString("bridge") }
    if (bridge != null) content(bridge) else Gone()
}

@Composable
private fun Gone() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("This computer was unpaired. Swipe back.", textAlign = TextAlign.Center)
    }
}
