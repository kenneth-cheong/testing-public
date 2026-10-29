package io.github.kennethcheong.clauderemote.ui

import io.github.kennethcheong.clauderemote.data.BridgeClient
import io.github.kennethcheong.clauderemote.data.BridgeSettings
import io.github.kennethcheong.clauderemote.data.PairResult
import io.github.kennethcheong.clauderemote.data.SavedBridge
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/** A paired computer and the client that talks to its bridge. */
class Bridge(val id: String, val name: String, val client: BridgeClient)

/**
 * Holds the paired bridges and the clients built from them; an empty list means "not paired yet".
 * Every client uses [http]; [onBridgesChanged] runs after a computer is paired, renamed or unpaired.
 */
class AppState(
    private val settings: BridgeSettings,
    defaultUrl: String,
    private val http: OkHttpClient = BridgeClient.defaultHttpClient(),
    private val onBridgesChanged: () -> Unit = {},
) {
    private val _url = MutableStateFlow(settings.url ?: defaultUrl.ifBlank { null })
    val url: StateFlow<String?> = _url.asStateFlow()

    private val _bridges = MutableStateFlow(build(settings.bridges))
    val bridges: StateFlow<List<Bridge>> = _bridges.asStateFlow()

    /** Saves the address to pair with next; accepts a bare host name and assumes HTTPS. Returns false if invalid. */
    fun setUrl(input: String): Boolean {
        val trimmed = input.trim().replace(" ", "")
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        if (runCatching { BridgeClient(candidate, http = http) }.isFailure) return false
        settings.url = candidate
        _url.value = candidate
        return true
    }

    /** A client without a token, used only to call [BridgeClient.pair]. */
    fun pairingClient(): BridgeClient? = _url.value?.let { runCatching { BridgeClient(it, http = http) }.getOrNull() }

    /** Adds the computer at the current address, replacing an earlier pairing with the same address. */
    fun paired(result: PairResult) {
        val url = _url.value ?: return
        val others = settings.bridges.filter { it.url != url }
        update(others + SavedBridge(UUID.randomUUID().toString(), url, result.machine.orEmpty(), result.token))
    }

    fun unpair(id: String) = update(settings.bridges.filter { it.id != id })

    /** Asks bridges paired before they reported a name (version 0.2) what computer they are on. */
    suspend fun fillMissingNames() {
        for (bridge in _bridges.value.filter { it.name.isBlank() }) {
            val machine = try {
                bridge.client.info().machine
            } catch (e: IOException) {
                continue
            }
            update(settings.bridges.map { if (it.id == bridge.id) it.copy(name = machine) else it })
        }
    }

    private fun update(saved: List<SavedBridge>) {
        settings.bridges = saved
        _bridges.value = build(saved)
        onBridgesChanged()
    }

    private fun build(saved: List<SavedBridge>): List<Bridge> = saved.mapNotNull { s ->
        runCatching {
            Bridge(s.id, s.name.ifBlank { hostLabel(s.url) }, BridgeClient(s.url, s.token, onUnauthorized = { unpair(s.id) }, http = http))
        }.getOrNull()
    }

    /** "https://my-pc.tailnet.ts.net" -> "my-pc", until the bridge reports a proper name. */
    private fun hostLabel(url: String): String = url.substringAfter("://").substringBefore('.').substringBefore('/')
}
