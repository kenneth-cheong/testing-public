package io.github.kennethcheong.qrdrop

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.LocalContentColor

// Palette: deep indigo night, glassy cards, a periwinkle accent and a mint "done".
private val Night = Color(0xFF07080F)
private val Dusk = Color(0xFF1B1E52)
private val Glass = Color(0x0FFFFFFF)
private val GlassEdge = Color(0x1AFFFFFF)
private val Ink = Color(0xFFF2F3FA)
private val Muted = Color(0xFF9BA0B8)
private val Accent = Color(0xFF8E98FF)
private val AccentDeep = Color(0xFF4F5BEA)
private val Mint = Color(0xFF5EE6C8)
private val Coral = Color(0xFFFF7A7A)

private enum class Mode(val label: String) { WIFI("Same Wi-Fi"), HOTSPOT("Hotspot") }

@Composable
fun DropApp(vm: DropViewModel = viewModel()) {
    val addresses by vm.addresses.collectAsStateWithLifecycle()
    val hotspotState by vm.hotspotState.collectAsStateWithLifecycle()
    val wifi = addresses.firstOrNull { it.kind == LocalAddress.Kind.WIFI }
    // Our Wi-Fi Direct group is reached on its p2p address; any other hotspot (the local-only one, or your own) on its AP address.
    val hotspotKind = if ((hotspotState as? HotspotState.On)?.info?.viaDirect == true) LocalAddress.Kind.DIRECT else LocalAddress.Kind.HOTSPOT
    val hotspotAddress = addresses.firstOrNull { it.kind == hotspotKind }
    // Until you pick a tab, follow the network: Same Wi-Fi when there is one.
    var picked by remember { mutableStateOf<Mode?>(null) }
    val mode = picked ?: if (wifi != null) Mode.WIFI else Mode.HOTSPOT

    val ready = when (mode) {
        Mode.WIFI -> wifi != null
        Mode.HOTSPOT -> hotspotAddress != null
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Night, surface = Night)) {
        CompositionLocalProvider(LocalContentColor provides Ink) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0f to Dusk, 0.45f to Color(0xFF0C0E22), 1f to Night))
                    .drawBehind {
                        // Soft glow behind the QR card.
                        drawCircle(
                            Brush.radialGradient(listOf(AccentDeep.copy(alpha = 0.35f), Color.Transparent), center = Offset(size.width / 2, size.height * 0.33f), radius = size.width * 0.75f),
                            radius = size.width * 0.75f,
                            center = Offset(size.width / 2, size.height * 0.33f),
                        )
                    },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                        Header(vm.deviceName, mode, ready)
                        Spacer(Modifier.height(22.dp))
                        ModeToggle(mode) { picked = it }
                        Spacer(Modifier.height(18.dp))
                        AnimatedContent(targetState = mode, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "mode") { m ->
                            Column {
                                when (m) {
                                    Mode.WIFI -> WifiPanel(vm, wifi)
                                    Mode.HOTSPOT -> HotspotPanel(vm, hotspotState, hotspotAddress)
                                }
                            }
                        }
                        Spacer(Modifier.height(30.dp))
                        ReceivedList(vm.received)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(deviceName: String, mode: Mode, ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LogoTile(48.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text("QR Drop", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (ready) Mint else Muted, pulsing = ready)
                Spacer(Modifier.width(7.dp))
                Text(
                    when {
                        !ready -> "Waiting for a network · $deviceName"
                        mode == Mode.WIFI -> "Ready on Wi-Fi · $deviceName"
                        else -> "Ready on hotspot · $deviceName"
                    },
                    fontSize = 13.sp,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The app icon in-app: a down arrow into a tray on an indigo gradient tile. */
@Composable
private fun LogoTile(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Brush.linearGradient(listOf(Accent, AccentDeep))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.55f)) {
            val w = this.size.width
            val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val c = Color.White
            drawLine(c, Offset(w / 2, w * 0.05f), Offset(w / 2, w * 0.6f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(c, Offset(w * 0.25f, w * 0.38f), Offset(w / 2, w * 0.62f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(c, Offset(w * 0.75f, w * 0.38f), Offset(w / 2, w * 0.62f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(c, Offset(w * 0.12f, w * 0.78f), Offset(w * 0.12f, w * 0.95f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(c, Offset(w * 0.12f, w * 0.95f), Offset(w * 0.88f, w * 0.95f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(c, Offset(w * 0.88f, w * 0.95f), Offset(w * 0.88f, w * 0.78f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val halo by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "halo")
    Canvas(Modifier.size(14.dp)) {
        val r = size.minDimension / 2
        if (pulsing) drawCircle(color.copy(alpha = 0.45f * (1 - halo)), radius = r * (0.45f + 0.55f * halo))
        drawCircle(color, radius = r * 0.42f)
    }
}

/** Two-way pill switch with a sliding highlight. */
@Composable
private fun ModeToggle(mode: Mode, onPick: (Mode) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Glass)
            .border(1.dp, GlassEdge, RoundedCornerShape(24.dp))
            .padding(4.dp),
    ) {
        val half = maxWidth / 2
        val x by animateDpAsState(if (mode == Mode.WIFI) 0.dp else half, tween(260), label = "toggle")
        Box(
            Modifier
                .offset(x = x)
                .width(half)
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.horizontalGradient(listOf(Accent, AccentDeep))),
        )
        Row(Modifier.fillMaxSize()) {
            Mode.entries.forEach { m ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).clickable { onPick(m) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(m.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (mode == m) Color.White else Muted)
                }
            }
        }
    }
}

@Composable
private fun WifiPanel(vm: DropViewModel, wifi: LocalAddress?) {
    if (wifi == null) {
        GlassCard {
            Text("This phone isn't on Wi-Fi", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Switch to Hotspot: the iPhone joins a network from this phone instead.", fontSize = 14.sp, color = Muted)
        }
        return
    }
    QrCard(
        step = null,
        title = "Point the iPhone camera here",
        payload = vm.linkFor(wifi.ip),
        footer = { StepsStrip(); Spacer(Modifier.height(10.dp)); LinkChip(vm.linkFor(wifi.ip)) },
    )
}

@Composable
private fun HotspotPanel(vm: DropViewModel, state: HotspotState, hotspotAddress: LocalAddress?) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.startHotspot() }
    fun start() {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) vm.startHotspot() else ask.launch(permission)
    }

    when {
        state is HotspotState.On -> {
            QrCard(
                step = 1,
                title = "Join this phone's Wi-Fi",
                payload = state.info.qrPayload(),
                footer = {
                    Text("Scan with the camera, then tap Join.", fontSize = 14.sp, color = Muted, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CredentialChip("Network", state.info.ssid, Modifier.weight(1f))
                        CredentialChip("Password", state.info.passphrase, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            state.info.fast -> "5 GHz · fast"
                            state.note != null -> state.note
                            else -> "2.4 GHz · this phone can't make a 5 GHz network right now"
                        },
                        fontSize = 13.sp,
                        color = Muted,
                        textAlign = TextAlign.Center,
                    )
                },
            )
            Spacer(Modifier.height(14.dp))
            if (hotspotAddress != null) {
                QrCard(step = 2, title = "Then scan this to send", payload = vm.linkFor(hotspotAddress.ip), footer = { StepsStrip() })
            } else {
                GlassCard { Text("Starting the hotspot… the send code appears here in a moment.", fontSize = 14.sp, color = Muted) }
            }
            Spacer(Modifier.height(14.dp))
            GhostButton("Stop hotspot", onClick = vm::stopHotspot)
        }
        // Your own hotspot is already on: the iPhone joins it as usual, then scans the link.
        hotspotAddress != null && state !is HotspotState.Starting -> QrCard(
            step = null,
            title = "Your hotspot is on",
            payload = vm.linkFor(hotspotAddress.ip),
            footer = {
                Text("Join it from the iPhone's Wi-Fi settings, then scan this.", fontSize = 14.sp, color = Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                StepsStrip()
            },
        )
        else -> {
            GlassCard {
                WifiGlyph(Modifier.size(44.dp))
                Spacer(Modifier.height(14.dp))
                Text("No shared Wi-Fi? Make one.", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "This phone starts a private Wi-Fi network with no internet, just for sending. Works with mobile data off and uses none of it.",
                    fontSize = 14.sp,
                    color = Muted,
                )
                Spacer(Modifier.height(18.dp))
                GradientButton(if (state is HotspotState.Starting) "Starting…" else "Start private hotspot", enabled = state !is HotspotState.Starting, onClick = ::start)
                (state as? HotspotState.Failed)?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it.reason, color = Coral, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Glass)
            .border(1.dp, GlassEdge, RoundedCornerShape(26.dp))
            .padding(22.dp),
    ) { content() }
}

@Composable
private fun QrCard(step: Int?, title: String, payload: String, footer: @Composable () -> Unit) {
    val bitmap = remember(payload) { qrBitmap(payload).asImageBitmap() }
    GlassCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step != null) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Accent, AccentDeep))), contentAlignment = Alignment.Center) {
                        Text("$step", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .drawBehind {
                        drawRoundRect(
                            Brush.radialGradient(listOf(Accent.copy(alpha = 0.45f), Color.Transparent), radius = size.maxDimension * 0.75f),
                            topLeft = Offset(-size.width * 0.15f, -size.height * 0.15f),
                            size = Size(size.width * 1.3f, size.height * 1.3f),
                        )
                    }
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .padding(14.dp),
            ) {
                Image(bitmap, contentDescription = title, filterQuality = FilterQuality.None, modifier = Modifier.size(220.dp))
            }
            Spacer(Modifier.height(18.dp))
            footer()
        }
    }
}

/** "1 Scan → 2 Tap the link → 3 Choose files". */
@Composable
private fun StepsStrip() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        listOf("Scan", "Tap the link", "Choose files").forEachIndexed { i, label ->
            if (i > 0) Text("  →  ", fontSize = 13.sp, color = Muted.copy(alpha = 0.6f))
            Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Accent)
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = 13.sp, color = Ink.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun LinkChip(link: String) {
    Text(
        link.removePrefix("http://"),
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        color = Muted,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun CredentialChip(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(label.uppercase(), fontSize = 10.sp, letterSpacing = 1.2.sp, color = Muted)
        Text(value, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GradientButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(if (enabled) listOf(Accent, AccentDeep) else listOf(Color(0xFF3A3F66), Color(0xFF2C3060))))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, GlassEdge, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, color = Muted)
    }
}

@Composable
private fun WifiGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val center = Offset(w / 2, w * 0.82f)
        for ((i, r) in listOf(0.28f, 0.52f, 0.76f).withIndex()) {
            drawArc(
                color = if (i == 0) Mint else Accent.copy(alpha = 1f - i * 0.25f),
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(center.x - w * r, center.y - w * r),
                size = Size(w * r * 2, w * r * 2),
                style = Stroke(width = w * 0.08f, cap = StrokeCap.Round),
            )
        }
        drawCircle(Mint, radius = w * 0.06f, center = center)
    }
}

@Composable
private fun ReceivedList(items: List<Received>) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Received", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (items.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text("${items.size}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Night,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Accent).padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
    if (items.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, GlassEdge, RoundedCornerShape(20.dp)).padding(vertical = 26.dp, horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Files you receive show up here.\nThey're saved in Downloads › ${DownloadsSink.FOLDER}.", fontSize = 14.sp, color = Muted, textAlign = TextAlign.Center)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item -> ReceivedRow(item) { open(context, item) } }
    }
}

private fun open(context: android.content.Context, item: Received) {
    val uri = item.uri ?: return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: ActivityNotFoundException) {
        // No app opens this type; it's still in Downloads.
    }
}

@Composable
private fun ReceivedRow(item: Received, onOpen: () -> Unit) {
    val context = LocalContext.current
    val (badge, tint) = fileBadge(item.name)
    val inProgress = item.uri == null && item.failed == null
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Glass)
            .border(1.dp, GlassEdge, RoundedCornerShape(18.dp))
            .clickable(enabled = item.uri != null, onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(badge, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tint, letterSpacing = 0.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
            Text(
                when {
                    item.failed != null -> "Didn't arrive · ${item.failed}"
                    item.uri != null -> "${size(item.bytes)} · Saved to Downloads"
                    item.total != null -> "${size(item.bytes)} of ${size(item.total)}"
                    else -> "Receiving… ${size(item.bytes)}"
                },
                fontSize = 12.sp,
                color = if (item.failed != null) Coral else Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (inProgress) {
                Spacer(Modifier.height(8.dp))
                val total = item.total
                ProgressBar(if (total != null && total > 0) (item.bytes.toFloat() / total).coerceIn(0f, 1f) else null)
            }
        }
        if (item.uri != null) {
            Spacer(Modifier.width(10.dp))
            CheckGlyph(Modifier.size(22.dp))
        }
    }
}

/** Gradient bar; null fraction shows a sliding shimmer for unknown sizes. */
@Composable
private fun ProgressBar(fraction: Float?) {
    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val slide by shimmer.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "slide")
    Canvas(Modifier.fillMaxWidth().height(5.dp)) {
        val r = size.height / 2
        drawRoundRect(Color(0x1FFFFFFF), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        val brush = Brush.horizontalGradient(listOf(Accent, Mint))
        if (fraction != null) {
            drawRoundRect(brush, size = Size(size.width * fraction, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        } else {
            val w = size.width * 0.3f
            drawRoundRect(brush, topLeft = Offset((size.width + w) * slide - w, 0f), size = Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        }
    }
}

@Composable
private fun CheckGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        drawCircle(Mint.copy(alpha = 0.18f))
        val stroke = w * 0.1f
        drawLine(Mint, Offset(w * 0.3f, w * 0.52f), Offset(w * 0.45f, w * 0.66f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(Mint, Offset(w * 0.45f, w * 0.66f), Offset(w * 0.72f, w * 0.36f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** Short type label and colour for a file name, e.g. "JPG" in coral. */
internal fun fileBadge(name: String): Pair<String, Color> {
    val ext = name.substringAfterLast('.', "").lowercase()
    val color = when (ext) {
        "jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "dng" -> Color(0xFFFF9F7A)
        "mov", "mp4", "m4v", "avi", "mkv", "webm" -> Color(0xFFB79CFF)
        "m4a", "mp3", "wav", "aac", "flac", "caf" -> Color(0xFF6ED3E8)
        "pdf" -> Color(0xFFFF7A7A)
        "zip", "rar", "7z", "gz", "tar" -> Color(0xFFFFD36E)
        "doc", "docx", "pages", "txt", "rtf", "md", "xls", "xlsx", "numbers", "csv", "key", "ppt", "pptx" -> Color(0xFF7FB2FF)
        else -> Muted
    }
    val label = ext.uppercase().take(4).ifEmpty { "FILE" }
    return label to color
}
