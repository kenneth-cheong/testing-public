package io.github.kennethcheong.clauderemote.ui

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import java.io.IOException
import kotlinx.coroutines.launch

/** First-run screen: set the bridge address, then pair with the code the bridge prints. */
@Composable
fun SetupScreen(appState: AppState) {
    val url by appState.url.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val editUrl = rememberTextInput("Bridge address") { input ->
        message = if (appState.setUrl(input)) null else "That doesn't look like an address"
    }
    val enterCode = rememberTextInput("6-digit pairing code") { code ->
        val client = appState.pairingClient() ?: return@rememberTextInput
        scope.launch {
            busy = true
            message = null
            try {
                appState.paired(client.pair(code, Build.MODEL))
            } catch (e: IOException) {
                message = e.message ?: "Pairing failed"
            } finally {
                busy = false
            }
        }
    }

    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text("Pair with bridge") } }
            item {
                FilledTonalButton(
                    onClick = editUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Bridge address") },
                    secondaryLabel = {
                        Text(url?.removePrefix("https://") ?: "Not set", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
            item {
                Button(
                    onClick = enterCode,
                    enabled = url != null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (busy) "Pairing…" else "Enter pairing code") },
                )
            }
            message?.let { text ->
                item { Text(text, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
            item {
                Text(
                    "Start the bridge on your computer. It prints a one-time code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
