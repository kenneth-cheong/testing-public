package io.github.kennethcheong.clauderemote.ui

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
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import java.io.IOException
import kotlinx.coroutines.launch

/** Lists the paired computers; tapping one calls [onPick]. */
@Composable
fun PickMachineScreen(bridges: List<Bridge>, title: String, busy: Boolean = false, error: String? = null, onPick: (Bridge) -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(if (busy) "Starting…" else title) } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) } }
            items(bridges, key = { it.id }) { bridge ->
                Button(
                    onClick = { onPick(bridge) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(bridge.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

/** "New" with several computers: pick one, then dictate the prompt; [onCreated] gets the new session. */
@Composable
fun NewSessionOnMachineScreen(bridges: List<Bridge>, onCreated: (bridgeId: String, sessionId: String) -> Unit) {
    var chosen by remember { mutableStateOf<Bridge?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val askForPrompt = rememberTextInput("New task for Claude") { text ->
        val bridge = chosen ?: return@rememberTextInput
        scope.launch {
            busy = true
            error = null
            try {
                onCreated(bridge.id, bridge.client.newSession(text))
            } catch (e: IOException) {
                error = e.message ?: "Couldn't start session"
            } finally {
                busy = false
            }
        }
    }
    PickMachineScreen(bridges, title = "New task on…", busy = busy, error = error) { bridge ->
        chosen = bridge
        askForPrompt()
    }
}
