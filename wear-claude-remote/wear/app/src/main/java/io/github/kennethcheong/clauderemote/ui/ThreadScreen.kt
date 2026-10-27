package io.github.kennethcheong.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import io.github.kennethcheong.clauderemote.data.ChatLine
import io.github.kennethcheong.clauderemote.data.PendingApproval
import io.github.kennethcheong.clauderemote.data.RunStatus

@Composable
fun ThreadScreen(viewModel: ThreadViewModel, onTemplates: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollWhileVisible() }
    }

    val detail = state.detail
    val messages = detail?.messages.orEmpty()
    val pending = detail?.pending
    val busy = detail != null && RunStatus.isBusy(detail.status)

    // Buzz when Claude needs a decision, so you can glance at the watch.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(pending?.id) {
        if (pending != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val askForReply = rememberTextInput("Reply to Claude") { viewModel.send(it) }

    // Rows: header, messages, then one status row. Keep the newest content in view.
    val listState = rememberScalingLazyListState()
    val lastIndex = messages.size + 1
    LaunchedEffect(messages.size, detail?.status) {
        if (detail != null) listState.animateScrollToItem(lastIndex)
    }

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            if (busy) {
                EdgeButton(onClick = viewModel::interrupt, enabled = !state.sending) { Text("Stop") }
            } else {
                EdgeButton(onClick = askForReply, enabled = detail != null && !state.sending) { Text("Reply") }
            }
        },
    ) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader {
                    Text(detail?.title ?: "Loading…", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            items(messages.size) { index -> MessageRow(messages[index]) }
            item {
                Column(Modifier.fillMaxWidth()) {
                    when {
                        pending != null -> ApprovalCard(pending, enabled = !state.sending) { allow ->
                            viewModel.answer(pending.id, allow)
                        }
                        busy -> StatusText("Claude is working…")
                        detail?.status == RunStatus.ERROR -> StatusText("Run failed: ${detail.error ?: "unknown error"}", isError = true)
                    }
                    state.error?.let { StatusText(it, isError = true) }
                    if (detail != null && !busy && pending == null) {
                        FilledTonalButton(
                            onClick = onTemplates,
                            enabled = !state.sending,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            label = { Text("Templates") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(line: ChatLine) {
    val colors = MaterialTheme.colorScheme
    when (line.role) {
        "tool" -> Text(
            "⚙ ${line.text}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        else -> {
            val isUser = line.role == "user"
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (isUser) colors.primaryContainer else colors.surfaceContainer,
                        RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUser) colors.onPrimaryContainer else colors.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ApprovalCard(pending: PendingApproval, enabled: Boolean, onAnswer: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Allow ${pending.tool}?",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            pending.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        Button(onClick = { onAnswer(true) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), label = { Text("Allow") })
        FilledTonalButton(
            onClick = { onAnswer(false) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            label = { Text("Deny") },
        )
    }
}

@Composable
private fun StatusText(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}
