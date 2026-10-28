package io.github.kennethcheong.qrdrop

import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.net.wifi.WifiManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The design is dark only: light status-bar icons on the gradient.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Uploads only run while the app is open, so don't let the screen sleep mid-transfer.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { DropApp() }
    }
}

/** A file in the "Received" list. [uri] is set once it is saved; until then [bytes] of [total] have arrived. */
data class Received(
    val id: Long,
    val name: String,
    val bytes: Long = 0,
    val total: Long? = null,
    val uri: String? = null,
    val failed: String? = null,
)

/**
 * The secret path, kept for as long as the app's process lives: leaving with Back
 * and coming back makes a new view model and server, but the link a friend already
 * has open keeps working.
 */
object SessionToken {
    val value: String by lazy { DropServer.newToken() }
}

sealed interface HotspotState {
    data object Off : HotspotState
    data object Starting : HotspotState
    /** [note] explains a slow fallback, if that's what's running. */
    data class On(val info: HotspotInfo, val note: String? = null) : HotspotState
    data class Failed(val reason: String) : HotspotState
}

class DropViewModel(app: Application) : AndroidViewModel(app) {
    val deviceName: String = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    private val token = SessionToken.value
    val received = mutableStateListOf<Received>()
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val direct = DirectHotspot(app)
    private val hotspot = PrivateHotspot(app)

    private val _addresses = MutableStateFlow(LocalAddresses.current())
    val addresses: StateFlow<List<LocalAddress>> = _addresses.asStateFlow()
    private val _hotspotState = MutableStateFlow<HotspotState>(HotspotState.Off)
    val hotspotState: StateFlow<HotspotState> = _hotspotState.asStateFlow()

    /** Devices on QR Drop's Wi-Fi Direct hotspot, as far as Android reports them. */
    private val _joined = MutableStateFlow(0)
    val joined: StateFlow<Int> = _joined.asStateFlow()

    /** True while a send page is open somewhere: it pings every few seconds. */
    private val _pageOpen = MutableStateFlow(false)
    val pageOpen: StateFlow<Boolean> = _pageOpen.asStateFlow()
    private var pageSeenAt = 0L

    private val server = DropServer(token, DownloadsSink(app), page = { sendPage(deviceName) }) { event ->
        viewModelScope.launch(Dispatchers.Main) { onEvent(event) }
    }
    val port: Int = server.start()

    init {
        direct.clearLeftover()
        // Wi-Fi and hotspot addresses come and go; keep the QR codes current.
        viewModelScope.launch {
            while (true) {
                _addresses.value = LocalAddresses.current()
                _pageOpen.value = SystemClock.elapsedRealtime() - pageSeenAt < PAGE_GONE_AFTER_MS
                if ((_hotspotState.value as? HotspotState.On)?.info?.viaDirect == true) direct.countClients { _joined.value = it } else _joined.value = 0
                delay(2_000)
            }
        }
    }

    fun linkFor(ip: String) = "http://$ip:$port/$token/"

    /** Wi-Fi Direct on 5 GHz first; if that can't start, the slower local-only hotspot. */
    fun startHotspot() {
        _hotspotState.value = HotspotState.Starting
        if (!wifi.isWifiEnabled) return startPrivateHotspot("Turn on Wi-Fi (no need to join a network) for a faster 5 GHz hotspot.")
        direct.start(
            whenStarted = { _hotspotState.value = HotspotState.On(it) },
            whenFailed = { direct.stop(); startPrivateHotspot(null) },
        )
    }

    private fun startPrivateHotspot(note: String?) {
        hotspot.start(
            whenStarted = { _hotspotState.value = HotspotState.On(it, note) },
            whenFailed = { _hotspotState.value = HotspotState.Failed(it) },
        )
    }

    fun stopHotspot() {
        direct.stop()
        hotspot.stop()
        _hotspotState.value = HotspotState.Off
    }

    private fun onEvent(event: DropEvent) {
        when (event) {
            is DropEvent.PageSeen -> {
                pageSeenAt = SystemClock.elapsedRealtime()
                _pageOpen.value = true
            }
            is DropEvent.Receiving -> received.add(0, Received(event.upload, event.name, total = event.total))
            is DropEvent.Progress -> update(event.upload, event.name) { it.copy(bytes = event.bytes, total = event.total) }
            is DropEvent.Received -> update(event.upload, event.file.name) { it.copy(name = event.file.name, bytes = event.file.bytes, uri = event.file.uri) }
            is DropEvent.Failed -> update(event.upload, event.name) { it.copy(failed = event.reason) }
        }
    }

    /** Several files stream in at once; each row is keyed by the server's upload id. */
    private fun update(upload: Long, name: String, change: (Received) -> Received) {
        val index = received.indexOfFirst { it.id == upload }
        if (index >= 0) received[index] = change(received[index]) else received.add(0, change(Received(upload, name)))
    }

    private companion object {
        /** The page pings every 4 s; three missed pings and it's gone (closed, or the iPhone left the network). */
        const val PAGE_GONE_AFTER_MS = 12_000L
    }

    override fun onCleared() {
        server.stop()
        direct.stop()
        hotspot.stop()
    }
}
