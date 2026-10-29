package io.github.kennethcheong.clauderemote.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import io.github.kennethcheong.clauderemote.data.Alert
import io.github.kennethcheong.clauderemote.data.BridgeClient
import io.github.kennethcheong.clauderemote.data.BridgeSettings
import io.github.kennethcheong.clauderemote.data.HttpCall
import io.github.kennethcheong.clauderemote.data.HttpResult
import io.github.kennethcheong.clauderemote.data.Relay
import io.github.kennethcheong.clauderemote.data.RunStatus
import io.github.kennethcheong.clauderemote.data.SavedBridge
import io.github.kennethcheong.clauderemote.data.SharedBridge
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The phone's half of Claude Remote: relays the watch's bridge requests, and keeps an eye on
 * every paired computer so the watch hears when Claude finishes or needs an approval.
 */
object Watcher {
    /** How one computer looked at the last poll. */
    data class Computer(val name: String, val working: Int, val error: String?)

    data class State(
        val running: Boolean = false,
        val computers: List<Computer> = emptyList(),
        /** When the computers were last checked (elapsedRealtime), 0 if never. */
        val checkedAt: Long = 0,
        /** The last alert sent, e.g. "Claude finished · MacBook Air", for the phone screen. */
        val lastAlert: String? = null,
        /** How many watch requests went through this phone. */
        val relayed: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val http = BridgeClient.defaultHttpClient()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val reloadBridges = AtomicBoolean(true)

    /** Check again now, e.g. after the watch sent something (its run may be short). */
    fun poke() {
        wake.trySend(Unit)
    }

    /** The watch paired or unpaired a computer. */
    fun bridgesChanged() {
        reloadBridges.set(true)
        poke()
    }

    /** Makes one bridge call for the watch, with the watch's own token. Never throws. */
    fun relay(call: HttpCall): HttpResult {
        val url = call.url.toHttpUrlOrNull()
        if (url == null || url.scheme !in setOf("http", "https")) return HttpResult(400, error("Not a bridge address"))
        val body = if (call.method == "GET") null else (call.body ?: "{}").toRequestBody(JSON)
        val request = Request.Builder().url(url).method(call.method, body)
        call.headers.filterKeys { it.equals("Authorization", ignoreCase = true) }.forEach { (k, v) -> request.header(k, v) }
        val result = try {
            http.newCall(request.build()).execute().use { HttpResult(it.code, it.body?.string().orEmpty()) }
        } catch (e: IOException) {
            HttpResult(502, error("Phone can't reach ${url.host}: ${e.message ?: "no connection"}"), relayFailed = true)
        }
        _state.value = _state.value.let { it.copy(relayed = it.relayed + 1) }
        if (call.method != "GET") poke()
        return result
    }

    /**
     * Polls until cancelled: every [BUSY_MS] while anything is running, otherwise every [IDLE_MS].
     * [onStatus] gets a one-line summary for the ongoing notification.
     */
    suspend fun run(context: Context, onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        val store = BridgeSettings(context)
        var bridges = store.bridges
        var pulledAt = 0L
        val seen = mutableMapOf<String, Map<String, String>>()
        val alertedApproval = mutableMapOf<String, String>()
        _state.value = _state.value.copy(running = true)
        try {
            while (currentCoroutineContext().isActive) {
                val now = SystemClock.elapsedRealtime()
                val stale = pulledAt == 0L || now - pulledAt > (if (bridges.isEmpty()) 60_000L else RELOAD_BRIDGES_MS)
                if (reloadBridges.getAndSet(false) || stale) {
                    pullBridges(context)?.let { fresh ->
                        bridges = fresh
                        store.bridges = fresh
                    }
                    pulledAt = now
                }

                var busy = false
                var changed = false
                val computers = bridges.map { bridge ->
                    val name = bridge.name.ifBlank { bridge.url.substringAfter("://").substringBefore('.') }
                    val client = BridgeClient(bridge.url, bridge.token, http = http)
                    val sessions = try {
                        client.listSessions()
                    } catch (e: IOException) {
                        return@map Computer(name, 0, e.message ?: "Can't reach bridge")
                    }
                    val c = changes(seen[bridge.id], sessions)
                    seen[bridge.id] = sessions.associate { it.id to it.status }
                    changed = changed || c.any
                    busy = busy || sessions.any { RunStatus.isBusy(it.status) }

                    for (s in c.finished) {
                        val detail = try { client.session(s.id, limit = 1) } catch (e: IOException) { null }
                        val failed = s.status == RunStatus.ERROR
                        val text = (if (failed) detail?.error else detail?.reply).orEmpty()
                        alert(context, Alert(if (failed) Alert.FAILED else Alert.FINISHED, bridge.id, name, s.id, s.title, plain(text).take(MAX_TEXT)))
                    }
                    for (s in c.waiting) {
                        val pending = try { client.session(s.id, limit = 1).pending } catch (e: IOException) { null } ?: continue
                        if (alertedApproval.put("${bridge.id}/${s.id}", pending.id) == pending.id) continue
                        // The bridge's summary already names the tool ("Bash: npm test").
                        alert(context, Alert(Alert.APPROVAL, bridge.id, name, s.id, s.title, pending.summary.take(MAX_TEXT), pending.id))
                    }
                    Computer(name, sessions.count { RunStatus.isBusy(it.status) }, null)
                }
                if (changed) tellWatches(context, Relay.PATH_CHANGED, ByteArray(0))

                _state.value = _state.value.copy(computers = computers, checkedAt = SystemClock.elapsedRealtime())
                onStatus(summary(computers))
                withTimeoutOrNull(if (busy) BUSY_MS else IDLE_MS) { wake.receive() }
            }
        } finally {
            _state.value = _state.value.copy(running = false)
        }
    }

    /** Asks the watch which computers it is paired with; null if no watch answered. */
    private fun pullBridges(context: Context): List<SavedBridge>? = try {
        val watch = watches(context).firstOrNull() ?: return null
        val reply = Tasks.await(Wearable.getMessageClient(context).sendRequest(watch, Relay.PATH_BRIDGES, ByteArray(0)), 15, TimeUnit.SECONDS)
        Relay.decode<List<SharedBridge>>(reply).map { SavedBridge(it.id, it.url, it.name, it.token) }
    } catch (e: Exception) {
        null
    }

    /** Sends [alert] to the watch, or shows it on the phone if no watch is in reach. */
    private fun alert(context: Context, alert: Alert) {
        val heading = when (alert.kind) {
            Alert.APPROVAL -> "Needs approval"
            Alert.FAILED -> "Claude stopped"
            else -> "Claude finished"
        }
        _state.value = _state.value.copy(lastAlert = "$heading · ${alert.machine}: ${alert.title}")
        if (tellWatches(context, Relay.PATH_ALERT, Relay.encode(alert)) > 0) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Claude finished or needs you", NotificationManager.IMPORTANCE_HIGH))
        val note = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("$heading · ${alert.machine}")
            .setContentText(alert.text.ifBlank { alert.title })
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.title + "\n" + alert.text))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify("${alert.bridgeId}/${alert.sessionId}".hashCode(), note)
        } catch (e: SecurityException) {
            // No notification permission on the phone either; the phone screen still shows lastAlert.
        }
    }

    /** Sends a message to every watch in reach; returns how many it went to. */
    private fun tellWatches(context: Context, path: String, data: ByteArray): Int {
        val nodes = watches(context)
        nodes.forEach { node ->
            runCatching { Tasks.await(Wearable.getMessageClient(context).sendMessage(node, path, data), 10, TimeUnit.SECONDS) }
        }
        return nodes.size
    }

    private fun watches(context: Context): List<String> = try {
        Tasks.await(
            Wearable.getCapabilityClient(context).getCapability(Relay.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE),
            10, TimeUnit.SECONDS,
        ).nodes.map { it.id }
    } catch (e: Exception) {
        emptyList()
    }

    private fun summary(computers: List<Computer>): String = when {
        computers.isEmpty() -> "Waiting for the watch to share its computers"
        else -> computers.joinToString(" · ") { c ->
            when {
                c.error != null -> "${c.name}: offline"
                c.working > 0 -> "${c.name}: ${c.working} working"
                else -> c.name
            }
        }
    }

    /** Claude's replies are markdown; a notification shows the markup literally, so drop the common marks. */
    internal fun plain(markdown: String): String = markdown
        .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        .replace("**", "")
        .replace("`", "")

    private fun error(message: String) = Relay.json.encodeToString(mapOf("error" to message))

    private const val BUSY_MS = 3_000L
    private const val IDLE_MS = 15_000L
    private const val RELOAD_BRIDGES_MS = 30 * 60_000L
    private const val MAX_TEXT = 600
    private const val ALERT_CHANNEL = "claude"
    private val JSON = "application/json".toMediaType()
}
