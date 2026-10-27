package io.github.kennethcheong.clauderemote.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch

/** Tap a template to send it; [onSent] gets the session it went to. */
@Composable
fun TemplatesScreen(viewModel: TemplatesViewModel, title: String, onSent: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    val scope = rememberCoroutineScope()

    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(if (state.sending) "Sending…" else title) } }
            state.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
            val templates = state.templates
            when {
                templates == null && state.error == null -> item { Text("Loading…") }
                templates?.isEmpty() == true -> item {
                    Text("No templates. Add some to templates.json on your computer.", textAlign = TextAlign.Center)
                }
                templates != null -> items(templates) { template ->
                    Button(
                        onClick = { scope.launch { viewModel.send(template)?.let(onSent) } },
                        enabled = !state.sending,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(template.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text(template.text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}
