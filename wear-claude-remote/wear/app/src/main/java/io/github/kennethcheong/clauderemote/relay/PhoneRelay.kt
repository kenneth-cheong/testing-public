package io.github.kennethcheong.clauderemote.relay

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import io.github.kennethcheong.clauderemote.data.BridgeClient
import io.github.kennethcheong.clauderemote.data.HttpCall
import io.github.kennethcheong.clauderemote.data.HttpResult
import io.github.kennethcheong.clauderemote.data.Relay
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * Sends bridge requests through the phone app when it's reachable, so the phone does the
 * networking (over Tailscale) and the watch only talks Bluetooth. Without the phone app,
 * or if the phone stops answering, requests go straight to the bridge as before.
 */
object PhoneRelay {
    private val _viaPhone = MutableStateFlow(false)

    /** Whether the last bridge request went through the phone. */
    val viaPhone: StateFlow<Boolean> = _viaPhone.asStateFlow()

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Fires when the phone says a session changed state; open screens refresh straight away. */
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    internal fun notifyChanged() {
        _changed.tryEmit(Unit)
    }

    @Volatile private var client: OkHttpClient? = null

    /** The HTTP client every [BridgeClient] on the watch uses. */
    fun http(context: Context): OkHttpClient = client ?: synchronized(this) {
        client ?: BridgeClient.defaultHttpClient().newBuilder()
            .addInterceptor(Interceptor(context.applicationContext))
            // The phone already waited on the bridge; allow for that plus the Bluetooth hop.
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
            .also { client = it }
    }

    /** Tells the phone the paired computers changed, so it re-reads them. Fire and forget. */
    fun bridgesChanged(context: Context) {
        val app = context.applicationContext
        Wearable.getCapabilityClient(app).getCapability(Relay.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                info.nodes.forEach { Wearable.getMessageClient(app).sendMessage(it.id, Relay.PATH_BRIDGES_CHANGED, ByteArray(0)) }
            }
    }

    private class Interceptor(private val context: Context) : okhttp3.Interceptor {
        @Volatile private var phone: String? = null
        @Volatile private var checkedAt = 0L

        override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            val request = chain.request()
            val node = phoneNode() ?: return direct(chain)
            val call = HttpCall(
                method = request.method,
                url = request.url.toString(),
                headers = request.headers.toMap(),
                body = request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() },
            )
            val result = try {
                val reply = Tasks.await(
                    Wearable.getMessageClient(context).sendRequest(node, Relay.PATH_HTTP, Relay.encode(call)),
                    40, TimeUnit.SECONDS,
                )
                Relay.decode<HttpResult>(reply)
            } catch (e: Exception) {
                // Phone out of range, app killed, or a timeout: forget it for now and go direct.
                checkedAt = 0
                phone = null
                return direct(chain)
            }
            // The phone couldn't reach the bridge. Reads can still try directly; a send might already
            // have arrived, so it isn't repeated.
            if (result.relayFailed && request.method == "GET") return direct(chain)
            _viaPhone.value = true
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(result.code)
                .message(if (result.relayFailed) "Phone can't reach bridge" else "Via phone")
                .body(result.body.toResponseBody(JSON))
                .build()
        }

        private fun direct(chain: okhttp3.Interceptor.Chain): Response {
            _viaPhone.value = false
            return chain.proceed(chain.request())
        }

        /** The nearby phone running the relay, looked up at most once a minute. */
        private fun phoneNode(): String? {
            val now = SystemClock.elapsedRealtime()
            if (now - checkedAt < RECHECK_MS) return phone
            checkedAt = now
            phone = try {
                val nodes = Tasks.await(
                    Wearable.getCapabilityClient(context).getCapability(Relay.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE),
                    5, TimeUnit.SECONDS,
                ).nodes
                (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
            } catch (e: Exception) {
                null
            }
            return phone
        }
    }

    private const val RECHECK_MS = 60_000L
    private val JSON = "application/json".toMediaType()
}
