package io.github.kennethcheong.clauderemote.data

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the watch and the phone say to each other over the Wear OS Data Layer (Bluetooth).
 * Both apps compile this file, so the two sides can't drift apart.
 *
 * The phone relays the watch's bridge requests and watches the bridges in the background,
 * sending the watch an alert when Claude finishes or needs an approval.
 */
object Relay {
    /** Declared by the phone app (res/values/wear.xml); the watch looks for it. */
    const val CAPABILITY_PHONE = "claude_remote_relay"

    /** Declared by the watch app; the phone sends alerts to nodes that have it. */
    const val CAPABILITY_WATCH = "claude_remote_watch"

    /** Watch -> phone request: one HTTP call to a bridge ([HttpCall] in, [HttpResult] out). */
    const val PATH_HTTP = "/relay/http"

    /** Phone -> watch request: the watch answers with its paired bridges ([SharedBridge] list). */
    const val PATH_BRIDGES = "/relay/bridges"

    /** Watch -> phone message: the watch paired or unpaired a computer, so ask again. */
    const val PATH_BRIDGES_CHANGED = "/relay/bridges-changed"

    /** Phone -> watch message: an [Alert] to show as a notification. */
    const val PATH_ALERT = "/relay/alert"

    /** Phone -> watch message: some session changed state, so refresh what's on screen. */
    const val PATH_CHANGED = "/relay/changed"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Gzipped UTF-8, which keeps long transcripts well under the Data Layer's message limit. */
    fun pack(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    fun unpack(bytes: ByteArray): String = GZIPInputStream(bytes.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }

    inline fun <reified T> encode(value: T): ByteArray = pack(json.encodeToString(value))

    inline fun <reified T> decode(bytes: ByteArray): T = json.decodeFromString(unpack(bytes))
}

@Serializable
data class HttpCall(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** [relayFailed] means the phone couldn't reach the bridge at all, so [code] is the phone's own 502. */
@Serializable
data class HttpResult(
    val code: Int,
    val body: String = "",
    val relayFailed: Boolean = false,
)

/** A paired computer as the watch hands it to the phone, token included (the Data Layer is end-to-end encrypted). */
@Serializable
data class SharedBridge(val id: String, val url: String, val name: String, val token: String)

@Serializable
data class Alert(
    val kind: String,
    val bridgeId: String,
    val machine: String,
    val sessionId: String,
    val title: String,
    /** Claude's reply, the error, or what the tool wants to do. */
    val text: String,
    /** Set for [APPROVAL] alerts; the watch's Allow and Deny buttons answer it. */
    val approvalId: String? = null,
) {
    companion object {
        const val FINISHED = "finished"
        const val FAILED = "failed"
        const val APPROVAL = "approval"
    }
}
