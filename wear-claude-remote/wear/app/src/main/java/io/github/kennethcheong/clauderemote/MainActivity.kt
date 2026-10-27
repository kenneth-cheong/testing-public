package io.github.kennethcheong.clauderemote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.github.kennethcheong.clauderemote.data.BridgeClient
import io.github.kennethcheong.clauderemote.data.BridgeSettings
import io.github.kennethcheong.clauderemote.ui.AppState
import io.github.kennethcheong.clauderemote.ui.SessionsScreen
import io.github.kennethcheong.clauderemote.ui.SessionsViewModel
import io.github.kennethcheong.clauderemote.ui.SetupScreen
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
    val client by appState.client.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color(0xFFD97757))) {
        AppScaffold {
            val current = client
            if (current == null) SetupScreen(appState) else RemoteNavHost(current, onUnpair = appState::unpair)
        }
    }
}

@Composable
private fun RemoteNavHost(client: BridgeClient, onUnpair: () -> Unit) {
    val navController = rememberSwipeDismissableNavController()
    SwipeDismissableNavHost(navController = navController, startDestination = "sessions") {
        composable("sessions") {
            SessionsScreen(
                viewModel = viewModel { SessionsViewModel(client) },
                onOpen = { id -> navController.navigate("thread/$id") },
                onUnpair = onUnpair,
            )
        }
        composable("thread/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ThreadScreen(viewModel = viewModel(key = id) { ThreadViewModel(client, id) })
        }
    }
}
