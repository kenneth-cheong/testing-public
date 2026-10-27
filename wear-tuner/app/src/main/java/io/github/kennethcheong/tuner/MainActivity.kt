package io.github.kennethcheong.tuner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // You hold your instrument, not the watch: keep the screen on while the tuner is open.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme { TunerApp() } }
    }
}

private val IN_TUNE = Color(0xFF4CD07D)
private val CLOSE = Color(0xFFF2B53A)
private val OFF = Color(0xFFEF5A4C)
private val DIM = Color(0xFF8A8F98)

/** Silent frames (about 50 ms each) to keep showing the last note before clearing it. */
private const val HOLD_FRAMES = 20

/** Silent frames before the reading is shown greyed out as "last heard". */
private const val LIVE_FRAMES = 4

/** Saved A4 and transposition. */
private class TunerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("tuner", Context.MODE_PRIVATE)

    var a4: Float
        get() = prefs.getFloat("a4", Notes.DEFAULT_A4)
        set(value) = prefs.edit().putFloat("a4", value).apply()

    var transposition: Transposition
        get() = runCatching { Transposition.valueOf(prefs.getString("transposition", null) ?: "") }.getOrDefault(Transposition.C)
        set(value) = prefs.edit().putString("transposition", value.name).apply()
}

@Composable
private fun TunerApp() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.RECORD_AUDIO) }

    val settings = remember { TunerSettings(context.applicationContext) }
    if (granted) {
        TunerScreen(settings)
    } else {
        Box(Modifier.fillMaxSize().padding(28.dp).clickable { askPermission.launch(Manifest.permission.RECORD_AUDIO) }, contentAlignment = Alignment.Center) {
            Text("The tuner needs the microphone. Tap to allow.", textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TunerScreen(settings: TunerSettings) {
    var a4 by remember { mutableFloatStateOf(settings.a4) }
    var transposition by remember { mutableStateOf(settings.transposition) }
    var hz by remember { mutableStateOf<Float?>(null) }
    var silentFrames by remember { mutableIntStateOf(HOLD_FRAMES) }
    var error by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        val smoother = PitchSmoother()
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            error = null
            try {
                @Suppress("MissingPermission") // TunerApp only shows this screen once RECORD_AUDIO is granted
                microphonePitches().collect { detected ->
                    if (detected != null) {
                        hz = smoother.add(detected)
                        silentFrames = 0
                    } else if (++silentFrames >= HOLD_FRAMES) {
                        smoother.reset()
                    }
                }
            } catch (e: CancellationException) {
                // The screen went off or the app was left: normal, not an error. (It extends IllegalStateException.)
                throw e
            } catch (e: IllegalStateException) {
                error = e.message
            }
        }
    }

    // The rotating bezel (or crown) nudges A4 by 1 Hz per click.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun setA4(value: Float) {
        a4 = value.coerceIn(Notes.MIN_A4, Notes.MAX_A4)
        settings.a4 = a4
    }

    val reading = hz?.takeIf { silentFrames < HOLD_FRAMES }?.let { Notes.read(it, a4, transposition) }
    // A frame or two without a pitch (breath, a noisy room) shouldn't grey out the reading.
    val live = silentFrames < LIVE_FRAMES

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { event ->
                if (event.verticalScrollPixels != 0f) setA4(a4 + if (event.verticalScrollPixels > 0) 1f else -1f)
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        CentsGauge(reading?.cents, live)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(18.dp))
            NoteName(reading, live)
            Text(
                reading?.let { centsLabel(it.cents) } ?: " ",
                fontSize = 16.sp,
                color = reading?.let { colorFor(it.cents, live) } ?: DIM,
            )
            Text(
                error ?: reading?.let { String.format(Locale.US, "%.1f Hz", it.hz) } ?: "Play a note",
                fontSize = 13.sp,
                color = if (error != null) OFF else DIM,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(transposition.label) {
                    transposition = transposition.next()
                    settings.transposition = transposition
                }
                Pill("A ${a4.roundToInt()}") { setA4(Notes.DEFAULT_A4) }
            }
        }
    }
}

@Composable
private fun NoteName(reading: NoteReading?, live: Boolean) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            reading?.name ?: "–",
            fontSize = 58.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (reading == null || !live) DIM else Color.White,
        )
        if (reading != null) {
            Text(
                reading.octave.toString(),
                fontSize = 20.sp,
                color = DIM,
                modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
            )
        }
    }
}

/** Arc across the top of the face from -50 to +50 cents, with a needle for the current reading. */
@Composable
private fun CentsGauge(cents: Float?, live: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)
        fun point(centsValue: Float, r: Float): Offset {
            // -50..+50 cents maps to -60°..+60° either side of 12 o'clock.
            val radians = Math.toRadians((centsValue * 1.2f - 90f).toDouble())
            return Offset(center.x + r * cos(radians).toFloat(), center.y + r * sin(radians).toFloat())
        }
        drawArc(
            color = Color(0xFF2A2D33),
            startAngle = -150f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(center.x - radius * 0.88f, center.y - radius * 0.88f),
            size = androidx.compose.ui.geometry.Size(radius * 1.76f, radius * 1.76f),
            style = Stroke(width = radius * 0.06f, cap = StrokeCap.Round),
        )
        for (tick in -50..50 step 10) {
            val long = tick == 0
            drawLine(
                color = if (long) IN_TUNE else DIM,
                start = point(tick.toFloat(), radius * (if (long) 0.76f else 0.80f)),
                end = point(tick.toFloat(), radius * 0.94f),
                strokeWidth = if (long) 4f else 2f,
                cap = StrokeCap.Round,
            )
        }
        if (cents != null) {
            drawLine(
                color = colorFor(cents, live),
                start = point(cents, radius * 0.66f),
                end = point(cents, radius * 0.96f),
                strokeWidth = radius * 0.035f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Color(0xFF24272D), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .width(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, color = Color.White)
    }
}

private fun colorFor(cents: Float, live: Boolean): Color = when {
    !live -> DIM
    abs(cents) <= 5 -> IN_TUNE
    abs(cents) <= 15 -> CLOSE
    else -> OFF
}

private fun centsLabel(cents: Float): String {
    val rounded = cents.roundToInt()
    return when {
        abs(rounded) <= 2 -> "in tune"
        rounded > 0 -> "+$rounded¢ sharp"
        else -> "$rounded¢ flat"
    }
}
