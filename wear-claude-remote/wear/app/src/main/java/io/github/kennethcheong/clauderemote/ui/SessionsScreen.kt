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
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import io.github.kennethcheong.clauderemote.data.RunStatus
import io.github.kennethcheong.clauderemote.data.SessionSummary
import kotlinx.coroutines.launch

@Composable
fun SessionsScreen(viewModel: SessionsViewModel, onOpen: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollWhileVisible() }
    }

    val scope = rememberCoroutineScope()
    val askForPrompt = rememberTextInput("New task for Claude") { text ->
        scope.launch { viewModel.newSession(text)?.let(onOpen) }
    }

    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = askForPrompt, enabled = !state.creating) {
                Text(if (state.creating) "Starting…" else "New")
            }
        },
    ) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text("Claude Code") } }
            state.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
            val sessions = state.sessions
            when {
                sessions == null && state.error == null -> item { Text("Loading…") }
                sessions?.isEmpty() == true -> item { Text("No sessions yet", textAlign = TextAlign.Center) }
                sessions != null -> items(sessions, key = { it.id }) { session ->
                    SessionButton(session, onClick = { onOpen(session.id) })
                }
            }
        }
    }
}

@Composable
private fun SessionButton(session: SessionSummary, onClick: () -> Unit) {
    val detail = when (session.status) {
        RunStatus.RUNNING -> "Working…"
        RunStatus.WAITING_APPROVAL -> "Needs approval"
        else -> listOfNotNull(
            session.cwd?.substringAfterLast('/')?.takeIf { it.isNotEmpty() },
            DateUtils.getRelativeTimeSpanString(session.lastModified).toString(),
        ).joinToString(" · ")
    }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}
