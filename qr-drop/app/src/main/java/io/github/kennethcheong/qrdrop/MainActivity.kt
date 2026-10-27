package io.github.kennethcheong.qrdrop

import android.app.Application
import android.os.Build
import android.os.Bundle
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

sealed interface HotspotState {
    data object Off : HotspotState
    data object Starting : HotspotState
    data class On(val info: HotspotInfo) : HotspotState
    data class Failed(val reason: String) : HotspotState
}

class DropViewModel(app: Application) : AndroidViewModel(app) {
    val deviceName: String = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    private val token = DropServer.newToken()
    val received = mutableStateListOf<Received>()
    private var nextId = 0L
    private val hotspot = PrivateHotspot(app)

    private val _addresses = MutableStateFlow(LocalAddresses.current())
    val addresses: StateFlow<List<LocalAddress>> = _addresses.asStateFlow()
    private val _hotspotState = MutableStateFlow<HotspotState>(HotspotState.Off)
    val hotspotState: StateFlow<HotspotState> = _hotspotState.asStateFlow()

    private val server = DropServer(token, DownloadsSink(app), page = { sendPage(deviceName) }) { event ->
        viewModelScope.launch(Dispatchers.Main) { onEvent(event) }
    }
    val port: Int = server.start()

    init {
        // Wi-Fi and hotspot addresses come and go; keep the QR codes current.
        viewModelScope.launch {
            while (true) {
                _addresses.value = LocalAddresses.current()
                delay(2_000)
            }
        }
    }

    fun linkFor(ip: String) = "http://$ip:$port/$token/"

    fun startHotspot() {
        _hotspotState.value = HotspotState.Starting
        hotspot.start(
            whenStarted = { _hotspotState.value = HotspotState.On(it) },
            whenFailed = { _hotspotState.value = HotspotState.Failed(it) },
        )
    }

    fun stopHotspot() {
        hotspot.stop()
        _hotspotState.value = HotspotState.Off
    }

    private fun onEvent(event: DropEvent) {
        when (event) {
            is DropEvent.Receiving -> received.add(0, Received(nextId++, event.name, total = event.total))
            is DropEvent.Progress -> replaceLatest(event.name) { it.copy(bytes = event.bytes, total = event.total) }
            is DropEvent.Received -> replaceLatest(event.file.name) { it.copy(name = event.file.name, bytes = event.file.bytes, uri = event.file.uri) }
            is DropEvent.Failed -> replaceLatest(event.name) { it.copy(failed = event.reason) }
        }
    }

    /** Uploads arrive one at a time, so the newest unfinished row is the one this event is about. */
    private fun replaceLatest(name: String, update: (Received) -> Received) {
        val index = received.indexOfFirst { it.uri == null && it.failed == null }
        if (index >= 0) received[index] = update(received[index]) else received.add(0, update(Received(nextId++, name)))
    }

    override fun onCleared() {
        server.stop()
        hotspot.stop()
    }
}
