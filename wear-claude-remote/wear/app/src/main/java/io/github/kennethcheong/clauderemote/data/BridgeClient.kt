package io.github.kennethcheong.clauderemote.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class BridgeException(val status: Int, message: String) : IOException(message)

/**
 * Talks to the bridge running next to Claude Code on your computer.
 * Throws [IllegalArgumentException] if [baseUrl] is not an http(s) URL.
 *
 * [token] is this watch's device token from [pair]; leave it empty until paired.
 * [onUnauthorized] runs when the bridge rejects the token, e.g. after it was revoked.
 */
class BridgeClient(
    baseUrl: String,
    private val token: String = "",
    private val onUnauthorized: () -> Unit = {},
    private val http: OkHttpClient = defaultHttpClient(),
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    /** Exchanges a one-time pairing code shown by the bridge for this watch's own token. */
    suspend fun pair(code: String, deviceName: String): PairResult =
        post(url("pair"), json.encodeToString(PairBody(code, deviceName)))

    /** Which computer this bridge runs on. */
    suspend fun info(): BridgeInfo = get(url("info"))

    suspend fun listSessions(limit: Int = 20): List<SessionSummary> =
        get(url("sessions") { addQueryParameter("limit", limit.toString()) })

    /** The prompt templates kept in a file on the bridge's computer. */
    suspend fun templates(): List<PromptTemplate> = get(url("templates"))

    suspend fun session(id: String, limit: Int = 30): SessionDetail =
        get(url("sessions", id) { addQueryParameter("limit", limit.toString()) })

    /** Starts a new Claude Code session and returns its id. */
    suspend fun newSession(text: String): String =
        post<CreatedSession>(url("sessions"), json.encodeToString(TextBody(text))).id

    suspend fun sendMessage(id: String, text: String) {
        post<CreatedSession>(url("sessions", id, "messages"), json.encodeToString(TextBody(text)))
    }

    suspend fun answerApproval(id: String, approvalId: String, allow: Boolean) {
        post<Unit>(url("sessions", id, "approval"), json.encodeToString(ApprovalBody(approvalId, allow)))
    }

    suspend fun interrupt(id: String) {
        post<Unit>(url("sessions", id, "interrupt"), "{}")
    }

    private fun url(vararg segments: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        base.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query()
        }.build()

    private suspend inline fun <reified T> get(url: HttpUrl): T =
        execute(Request.Builder().url(url).get())

    private suspend inline fun <reified T> post(url: HttpUrl, body: String): T =
        execute(Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE)))

    private suspend inline fun <reified T> execute(builder: Request.Builder): T = withContext(Dispatchers.IO) {
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401 && token.isNotEmpty()) onUnauthorized()
            if (!response.isSuccessful) {
                val message = runCatching { json.decodeFromString<ErrorBody>(body).error }.getOrNull()
                throw BridgeException(response.code, message ?: "HTTP ${response.code}")
            }
            if (T::class == Unit::class) return@use Unit as T
            try {
                json.decodeFromString<T>(body)
            } catch (e: SerializationException) {
                throw IOException("Unexpected response from bridge", e)
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
