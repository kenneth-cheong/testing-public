package io.github.kennethcheong.clauderemote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.github.kennethcheong.clauderemote.data.BridgeClient
import io.github.kennethcheong.clauderemote.ui.SessionsScreen
import io.github.kennethcheong.clauderemote.ui.SessionsViewModel
import io.github.kennethcheong.clauderemote.ui.ThreadScreen
import io.github.kennethcheong.clauderemote.ui.ThreadViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = BuildConfig.BRIDGE_URL.takeIf { it.isNotBlank() && BuildConfig.BRIDGE_TOKEN.isNotBlank() }
            ?.let { runCatching { BridgeClient(it, BuildConfig.BRIDGE_TOKEN) }.getOrNull() }
        setContent { RemoteApp(client) }
    }
}

@Composable
private fun RemoteApp(client: BridgeClient?) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color(0xFFD97757))) {
        AppScaffold {
            if (client == null) NotConfigured() else RemoteNavHost(client)
        }
    }
}

@Composable
private fun RemoteNavHost(client: BridgeClient) {
    val navController = rememberSwipeDismissableNavController()
    SwipeDismissableNavHost(navController = navController, startDestination = "sessions") {
        composable("sessions") {
            SessionsScreen(
                viewModel = viewModel { SessionsViewModel(client) },
                onOpen = { id -> navController.navigate("thread/$id") },
            )
        }
        composable("thread/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ThreadScreen(viewModel = viewModel(key = id) { ThreadViewModel(client, id) })
        }
    }
}

@Composable
private fun NotConfigured() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "Set bridge.url and bridge.token in local.properties, then rebuild.",
            textAlign = TextAlign.Center,
        )
    }
}
