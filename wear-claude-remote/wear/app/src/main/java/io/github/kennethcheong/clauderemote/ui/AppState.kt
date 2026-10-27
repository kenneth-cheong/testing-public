package io.github.kennethcheong.clauderemote.ui

import io.github.kennethcheong.clauderemote.data.BridgeClient
import io.github.kennethcheong.clauderemote.data.BridgeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the bridge settings and the client built from them; null client means "not paired". */
class AppState(private val settings: BridgeSettings, defaultUrl: String) {
    private val _url = MutableStateFlow(settings.url ?: defaultUrl.ifBlank { null })
    val url: StateFlow<String?> = _url.asStateFlow()

    private val _client = MutableStateFlow(buildClient())
    val client: StateFlow<BridgeClient?> = _client.asStateFlow()

    /** Saves the bridge address; accepts a bare host name and assumes HTTPS. Returns false if invalid. */
    fun setUrl(input: String): Boolean {
        val trimmed = input.trim().replace(" ", "")
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        if (runCatching { BridgeClient(candidate) }.isFailure) return false
        settings.url = candidate
        _url.value = candidate
        unpair()
        return true
    }

    /** A client without a token, used only to call [BridgeClient.pair]. */
    fun pairingClient(): BridgeClient? = _url.value?.let { runCatching { BridgeClient(it) }.getOrNull() }

    fun paired(token: String) {
        settings.token = token
        _client.value = buildClient()
    }

    fun unpair() {
        settings.token = null
        _client.value = null
    }

    private fun buildClient(): BridgeClient? {
        val url = _url.value ?: return null
        val token = settings.token ?: return null
        return runCatching { BridgeClient(url, token, onUnauthorized = ::unpair) }.getOrNull()
    }
}
