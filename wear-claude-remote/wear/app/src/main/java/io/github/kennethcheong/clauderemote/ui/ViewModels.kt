package io.github.kennethcheong.clauderemote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kennethcheong.clauderemote.data.BridgeClient
import io.github.kennethcheong.clauderemote.data.PromptTemplate
import io.github.kennethcheong.clauderemote.data.RunStatus
import io.github.kennethcheong.clauderemote.data.SessionDetail
import io.github.kennethcheong.clauderemote.data.SessionSummary
import io.github.kennethcheong.clauderemote.relay.PhoneRelay
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

/** A session and the computer it lives on. */
data class MachineSession(val bridgeId: String, val machine: String, val session: SessionSummary)

/** Polls every paired bridge and merges their sessions into one list, newest first. */
class SessionsViewModel(private val bridges: List<Bridge>) : ViewModel() {
    data class UiState(
        val sessions: List<MachineSession>? = null,
        /** One line per bridge that couldn't be reached, e.g. "MacBook Pro: Can't reach bridge". */
        val errors: List<String> = emptyList(),
        val creating: Boolean = false,
    )

    private class Fetch(val bridge: Bridge, val sessions: List<SessionSummary>?, val error: String?)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        // The phone app spots state changes first; refresh as soon as it says so.
        viewModelScope.launch { PhoneRelay.changed.collect { wake.trySend(Unit) } }
    }

    suspend fun pollWhileVisible() = pollLoop(wake) {
        val fetches = coroutineScope {
            bridges.map { bridge ->
                async {
                    try {
                        Fetch(bridge, bridge.client.listSessions(), null)
                    } catch (e: IOException) {
                        Fetch(bridge, null, e.message ?: "Can't reach bridge")
                    }
                }
            }.awaitAll()
        }
        val previous = _state.value.sessions.orEmpty()
        val merged = fetches.flatMap { fetch ->
            // Keep the last known sessions of a computer that just blipped instead of dropping them.
            fetch.sessions?.map { MachineSession(fetch.bridge.id, fetch.bridge.name, it) }
                ?: previous.filter { it.bridgeId == fetch.bridge.id }
        }.sortedByDescending { it.session.lastModified }
        val errors = fetches.mapNotNull { fetch ->
            fetch.error?.let { if (bridges.size > 1) "${fetch.bridge.name}: $it" else it }
        }
        _state.update { state ->
            state.copy(sessions = merged.takeIf { fetches.any { it.sessions != null } } ?: state.sessions, errors = errors)
        }
        merged.any { RunStatus.isBusy(it.session.status) }
    }

    /** Starts a session on [bridge] from a voice prompt; returns its id, or null on failure. */
    suspend fun newSession(bridge: Bridge, text: String): String? {
        _state.update { it.copy(creating = true) }
        return try {
            bridge.client.newSession(text)
        } catch (e: IOException) {
            _state.update { it.copy(errors = listOf(e.message ?: "Couldn't start session")) }
            null
        } finally {
            _state.update { it.copy(creating = false) }
            wake.trySend(Unit)
        }
    }
}

/**
 * Lists the bridge's prompt templates and sends one: as a reply to [sessionId],
 * or as the first prompt of a new session when [sessionId] is null.
 */
class TemplatesViewModel(private val client: BridgeClient, private val sessionId: String?) : ViewModel() {
    data class UiState(
        val templates: List<PromptTemplate>? = null,
        val error: String? = null,
        val sending: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    suspend fun load() {
        try {
            val templates = client.templates()
            _state.update { it.copy(templates = templates, error = null) }
        } catch (e: IOException) {
            _state.update { it.copy(error = e.message ?: "Can't reach bridge") }
        }
    }

    /** Sends [template]; returns the session it went to, or null on failure. */
    suspend fun send(template: PromptTemplate): String? {
        _state.update { it.copy(sending = true, error = null) }
        return try {
            sessionId?.also { client.sendMessage(it, template.text) } ?: client.newSession(template.text)
        } catch (e: IOException) {
            _state.update { it.copy(error = e.message ?: "Couldn't send") }
            null
        } finally {
            _state.update { it.copy(sending = false) }
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

    init {
        // The phone app spots state changes first; refresh as soon as it says so.
        viewModelScope.launch { PhoneRelay.changed.collect { wake.trySend(Unit) } }
    }

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
