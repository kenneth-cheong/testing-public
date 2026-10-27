package io.github.kennethcheong.clauderemote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kennethcheong.clauderemote.data.BridgeClient
import io.github.kennethcheong.clauderemote.data.RunStatus
import io.github.kennethcheong.clauderemote.data.SessionDetail
import io.github.kennethcheong.clauderemote.data.SessionSummary
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val BUSY_POLL_MS = 2_500L
private const val IDLE_POLL_MS = 15_000L

/**
 * Polls while the screen is visible: quickly while Claude is working, slowly otherwise.
 * [wake] cuts a wait short, e.g. right after the user sends something.
 */
private suspend fun pollLoop(wake: Channel<Unit>, load: suspend () -> Boolean) {
    while (true) {
        val busy = load()
        withTimeoutOrNull(if (busy) BUSY_POLL_MS else IDLE_POLL_MS) { wake.receive() }
    }
}

class SessionsViewModel(private val client: BridgeClient) : ViewModel() {
    data class UiState(
        val sessions: List<SessionSummary>? = null,
        val error: String? = null,
        val creating: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    suspend fun pollWhileVisible() = pollLoop(wake) {
        try {
            val sessions = client.listSessions()
            _state.update { it.copy(sessions = sessions, error = null) }
            sessions.any { RunStatus.isBusy(it.status) }
        } catch (e: IOException) {
            _state.update { it.copy(error = e.message ?: "Can't reach bridge") }
            false
        }
    }

    /** Starts a session from a voice prompt; returns its id, or null on failure. */
    suspend fun newSession(text: String): String? {
        _state.update { it.copy(creating = true) }
        return try {
            client.newSession(text)
        } catch (e: IOException) {
            _state.update { it.copy(error = e.message ?: "Couldn't start session") }
            null
        } finally {
            _state.update { it.copy(creating = false) }
            wake.trySend(Unit)
        }
    }
}

class ThreadViewModel(private val client: BridgeClient, private val id: String) : ViewModel() {
    data class UiState(
        val detail: SessionDetail? = null,
        val error: String? = null,
        val sending: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    suspend fun pollWhileVisible() = pollLoop(wake) {
        try {
            val detail = client.session(id)
            _state.update { it.copy(detail = detail, error = null) }
            RunStatus.isBusy(detail.status)
        } catch (e: IOException) {
            _state.update { it.copy(error = e.message ?: "Can't reach bridge") }
            // Keep polling quickly if Claude was mid-run; the bridge may just be blipping.
            _state.value.detail?.status?.let(RunStatus::isBusy) ?: false
        }
    }

    fun send(text: String) = act { client.sendMessage(id, text) }

    fun answer(approvalId: String, allow: Boolean) = act { client.answerApproval(id, approvalId, allow) }

    fun interrupt() = act { client.interrupt(id) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            try {
                block()
            } catch (e: IOException) {
                _state.update { it.copy(error = e.message ?: "Request failed") }
            } finally {
                _state.update { it.copy(sending = false) }
                wake.trySend(Unit)
            }
        }
    }
}
