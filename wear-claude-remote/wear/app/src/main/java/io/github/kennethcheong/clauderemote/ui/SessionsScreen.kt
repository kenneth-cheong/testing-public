package io.github.kennethcheong.clauderemote.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import io.github.kennethcheong.clauderemote.data.RunStatus
import kotlinx.coroutines.launch

/**
 * One list of sessions from every paired computer. With more than one computer,
 * "New" and "New from template" first ask which computer to use ([onPickMachine]).
 */
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    bridges: List<Bridge>,
    onOpen: (bridgeId: String, sessionId: String) -> Unit,
    onPickMachine: (action: String) -> Unit,
    onTemplates: (bridgeId: String) -> Unit,
    onAddComputer: () -> Unit,
    onUnpair: (bridgeId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollWhileVisible() }
    }

    val single = bridges.singleOrNull()
    val scope = rememberCoroutineScope()
    val askForPrompt = rememberTextInput("New task for Claude") { text ->
        val bridge = single ?: return@rememberTextInput
        scope.launch { viewModel.newSession(bridge, text)?.let { onOpen(bridge.id, it) } }
    }

    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = { if (single != null) askForPrompt() else onPickMachine(PICK_VOICE) }, enabled = !state.creating) {
                Text(if (state.creating) "Starting…" else "New")
            }
        },
    ) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text("Claude Code") } }
            item {
                FilledTonalButton(
                    onClick = { if (single != null) onTemplates(single.id) else onPickMachine(PICK_TEMPLATE) },
                    enabled = !state.creating,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New from template") },
                )
            }
            items(state.errors) { error ->
                Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            val sessions = state.sessions
            when {
                sessions == null && state.errors.isEmpty() -> item { Text("Loading…") }
                sessions?.isEmpty() == true -> item { Text("No sessions yet", textAlign = TextAlign.Center) }
                sessions != null -> items(sessions, key = { "${it.bridgeId}/${it.session.id}" }) { entry ->
                    SessionButton(entry, showMachine = single == null, onClick = { onOpen(entry.bridgeId, entry.session.id) })
                }
            }
            item {
                FilledTonalButton(onClick = onAddComputer, modifier = Modifier.fillMaxWidth(), label = { Text("Add computer") })
            }
            items(bridges, key = { it.id }) { bridge ->
                FilledTonalButton(
                    onClick = { onUnpair(bridge.id) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Unpair ${bridge.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@Composable
private fun SessionButton(entry: MachineSession, showMachine: Boolean, onClick: () -> Unit) {
    val session = entry.session
    val activity = when (session.status) {
        RunStatus.RUNNING -> listOf("Working…")
        RunStatus.WAITING_APPROVAL -> listOf("Needs approval")
        else -> listOfNotNull(
            session.cwd?.substringAfterLast('/')?.takeIf { it.isNotEmpty() },
            DateUtils.getRelativeTimeSpanString(session.lastModified).toString(),
        )
    }
    val detail = (listOfNotNull(entry.machine.takeIf { showMachine }) + activity).joinToString(" · ")
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

const val PICK_VOICE = "voice"
const val PICK_TEMPLATE = "template"
