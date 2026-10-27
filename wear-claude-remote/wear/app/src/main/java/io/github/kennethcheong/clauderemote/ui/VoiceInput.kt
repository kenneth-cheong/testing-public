package io.github.kennethcheong.clauderemote.ui

import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.wear.input.RemoteInputIntentHelper

private const val TEXT_KEY = "prompt"

/**
 * Returns a function that opens the system Wear input screen (voice, keyboard
 * or handwriting) and passes the entered text to [onText].
 */
@Composable
fun rememberTextInput(label: String, onText: (String) -> Unit): () -> Unit {
    val currentOnText by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)?.getCharSequence(TEXT_KEY)?.toString()?.trim()
        if (!text.isNullOrEmpty()) currentOnText(text)
    }
    return {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val input = RemoteInput.Builder(TEXT_KEY).setLabel(label).build()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        launcher.launch(intent)
    }
}
