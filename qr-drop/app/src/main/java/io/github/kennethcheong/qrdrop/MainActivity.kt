package io.github.kennethcheong.qrdrop

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Uploads only run while the app is open, so don't let the screen sleep mid-transfer.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { DropTheme { DropApp() } }
    }
}

/** A file in the "Received" list. [uri] is set once it is saved. */
data class Received(val id: Long, val name: String, val bytes: Long = 0, val uri: String? = null, val failed: String? = null)

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
            is DropEvent.Receiving -> received.add(0, Received(nextId++, event.name))
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

private val Indigo = Color(0xFF3D4BD8)

@Composable
private fun DropTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF8E98FF), onPrimary = Color(0xFF0E1150), background = Color(0xFF0D0F16), surface = Color(0xFF0D0F16), surfaceContainer = Color(0xFF171A24))
    } else {
        lightColorScheme(primary = Indigo, background = Color(0xFFF5F6FB), surface = Color(0xFFF5F6FB), surfaceContainer = Color.White)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class Mode(val label: String) { WIFI("Same Wi-Fi"), HOTSPOT("Hotspot") }

@Composable
private fun DropApp(vm: DropViewModel = viewModel()) {
    val addresses by vm.addresses.collectAsStateWithLifecycle()
    val hotspotState by vm.hotspotState.collectAsStateWithLifecycle()
    val wifi = addresses.firstOrNull { it.kind == LocalAddress.Kind.WIFI }
    val hotspotAddress = addresses.firstOrNull { it.kind == LocalAddress.Kind.HOTSPOT }
    var mode by remember { mutableStateOf(if (wifi != null) Mode.WIFI else Mode.HOTSPOT) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            Text("QR DROP", fontSize = 12.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Receive from iPhone", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Mode.entries.forEachIndexed { index, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(index, Mode.entries.size),
                    ) { Text(m.label) }
                }
            }
            Spacer(Modifier.height(16.dp))
            when (mode) {
                Mode.WIFI -> WifiPanel(vm, wifi)
                Mode.HOTSPOT -> HotspotPanel(vm, hotspotState, hotspotAddress)
            }
            Spacer(Modifier.height(24.dp))
            ReceivedList(vm.received)
        }
    }
}

@Composable
private fun WifiPanel(vm: DropViewModel, wifi: LocalAddress?) {
    if (wifi == null) {
        InfoCard("This phone isn't on Wi-Fi.", "Switch to Hotspot: the iPhone joins a network from this phone instead.")
        return
    }
    QrCard(
        step = null,
        title = "Scan with the iPhone camera",
        body = "The iPhone must be on the same Wi-Fi. Tap the link that appears, then choose photos or files.",
        payload = vm.linkFor(wifi.ip),
        caption = vm.linkFor(wifi.ip),
    )
}

@Composable
private fun HotspotPanel(vm: DropViewModel, state: HotspotState, hotspotAddress: LocalAddress?) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startHotspot()
    }
    fun start() {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) vm.startHotspot() else ask.launch(permission)
    }

    when (state) {
        is HotspotState.On -> {
            QrCard(
                step = 1,
                title = "Join this phone's Wi-Fi",
                body = "Scan with the iPhone camera and tap Join. Network ${state.info.ssid}, password ${state.info.passphrase}.",
                payload = state.info.qrPayload(),
                caption = null,
            )
            Spacer(Modifier.height(12.dp))
            if (hotspotAddress != null) {
                QrCard(step = 2, title = "Then scan this to send", body = "Tap the link that appears, then choose photos or files.", payload = vm.linkFor(hotspotAddress.ip), caption = vm.linkFor(hotspotAddress.ip))
            } else {
                InfoCard("Starting the hotspot…", "The send link appears here in a moment.")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = vm::stopHotspot, modifier = Modifier.fillMaxWidth()) { Text("Stop hotspot") }
        }
        // Your own hotspot is already on: the iPhone joins it as usual, then scans the link.
        else -> if (hotspotAddress != null && state !is HotspotState.Starting) {
            QrCard(
                step = null,
                title = "Your hotspot is on",
                body = "Join it from the iPhone's Wi-Fi settings, then scan this with the camera.",
                payload = vm.linkFor(hotspotAddress.ip),
                caption = vm.linkFor(hotspotAddress.ip),
            )
        } else {
            InfoCard(
                "No shared Wi-Fi? Make one.",
                "This phone starts a private Wi-Fi network with no internet, just for sending. It works with mobile data off and uses none of it.",
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = ::start, enabled = state !is HotspotState.Starting, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (state is HotspotState.Starting) "Starting…" else "Start private hotspot")
            }
            (state as? HotspotState.Failed)?.let {
                Spacer(Modifier.height(8.dp))
                Text(it.reason, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun QrCard(step: Int?, title: String, body: String, payload: String, caption: String?) {
    val bitmap = remember(payload) { qrBitmap(payload).asImageBitmap() }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (step != null) {
                    Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                        Text("$step", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.size(10.dp))
                }
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(10.dp)) {
                Image(bitmap, contentDescription = title, filterQuality = FilterQuality.None, modifier = Modifier.size(232.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(body, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            caption?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReceivedList(items: List<Received>) {
    val context = LocalContext.current
    Text("RECEIVED", fontSize = 12.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    if (items.isEmpty()) {
        Text("Nothing yet. Files land in Downloads › ${DownloadsSink.FOLDER}.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Card(
                Modifier.fillMaxWidth().clickable(enabled = item.uri != null) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        // No app opens this type; it's still in Downloads.
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        when {
                            item.failed != null -> "Failed"
                            item.uri == null -> "Receiving…"
                            else -> Formatter.formatShortFileSize(context, item.bytes)
                        },
                        fontSize = 13.sp,
                        color = if (item.failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
