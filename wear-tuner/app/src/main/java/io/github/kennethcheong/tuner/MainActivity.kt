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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
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

// Palette: near-black with a cool cast, and a soft mint / amber / coral scale for tuning state.
private val BACKGROUND = Color(0xFF07090D)
private val IN_TUNE = Color(0xFF5EE6A8)
private val CLOSE = Color(0xFFF5C26B)
private val OFF = Color(0xFFFF7A6B)
private val TEXT = Color(0xFFF3F5F8)
private val MUTED = Color(0xFF8C93A0)
private val FAINT = Color(0xFF3A404B)

/** Silent frames (about 50 ms each) to keep showing the last note before clearing it. */
private const val HOLD_FRAMES = 20

/** Silent frames before the reading is shown greyed out as "last heard". */
private const val LIVE_FRAMES = 4

/** The gauge spans ±70° either side of 12 o'clock for -50..+50 cents. */
private const val DEGREES_PER_CENT = 1.4f

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
        Box(
            Modifier.fillMaxSize().background(BACKGROUND).padding(28.dp).clickable { askPermission.launch(Manifest.permission.RECORD_AUDIO) },
            contentAlignment = Alignment.Center,
        ) {
            Text("The tuner needs the microphone. Tap to allow.", color = TEXT, textAlign = TextAlign.Center)
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
    val live = reading != null && silentFrames < LIVE_FRAMES

    // The needle glides instead of jumping, and the colour fades between states.
    val cents by animateFloatAsState(
        targetValue = reading?.cents ?: 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "cents",
    )
    val accent by animateColorAsState(
        targetValue = when {
            !live -> MUTED
            else -> stateColor(reading!!.cents)
        },
        animationSpec = tween(250),
        label = "accent",
    )
    val glow by animateFloatAsState(targetValue = if (live) 1f else 0f, animationSpec = tween(400), label = "glow")

    Box(
        Modifier
            .fillMaxSize()
            .background(BACKGROUND)
            .onRotaryScrollEvent { event ->
                if (event.verticalScrollPixels != 0f) setA4(a4 + if (event.verticalScrollPixels > 0) 1f else -1f)
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Gauge(cents = cents, accent = accent, glow = glow, showNeedle = reading != null)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(30.dp))
            NoteName(reading, live)
            Spacer(Modifier.height(2.dp))
            Text(
                text = when {
                    error != null -> error!!
                    reading == null -> "PLAY A NOTE"
                    else -> centsLabel(reading.cents)
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                color = if (error != null) OFF else accent,
                textAlign = TextAlign.Center,
            )
            Text(
                reading?.let { String.format(Locale.US, "%.1f Hz", it.hz) } ?: " ",
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = if (live) MUTED else FAINT,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(caption = if (transposition == Transposition.C) "CONCERT" else "IN", value = transposition.label) {
                    transposition = transposition.next()
                    settings.transposition = transposition
                }
                Pill(caption = "A4", value = "${a4.roundToInt()}") { setA4(Notes.DEFAULT_A4) }
            }
        }
    }
}

/** "B♭" + "4" set like engraved notation: big letter, raised accidental, lowered octave. */
@Composable
private fun NoteName(reading: NoteReading?, live: Boolean) {
    val color = if (live) TEXT else MUTED
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            reading?.name?.take(1) ?: "—",
            fontSize = 64.sp,
            fontWeight = FontWeight.Light,
            color = if (reading == null) FAINT else color,
        )
        if (reading != null) {
            Column(Modifier.padding(start = 2.dp)) {
                Text(reading.name.drop(1).ifEmpty { " " }, fontSize = 24.sp, fontWeight = FontWeight.Light, color = color)
                // Scientific pitch notation: middle C is C4, the tuning A is A4.
                Text(reading.octave.toString(), fontSize = 22.sp, fontWeight = FontWeight.Normal, color = if (live) MUTED else FAINT)
            }
        }
    }
}

/**
 * The cents arc across the top: fine ticks every 5 cents, a soft glow behind the
 * face in the current state colour, a lit segment from centre to the needle, and
 * a glowing bead for the needle itself.
 */
@Composable
private fun Gauge(cents: Float, accent: Color, glow: Float, showNeedle: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)
        val arcRadius = radius * 0.86f
        val clamped = cents.coerceIn(-50f, 50f)

        // Ambient glow from the top of the face, tinted by tuning state.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.22f * glow), Color.Transparent),
                center = Offset(center.x, center.y - radius * 0.35f),
                radius = radius * 1.05f,
            ),
            radius = radius,
            center = center,
        )

        // Track.
        drawArc(
            color = Color(0xFF1A1E25),
            startAngle = -90f - 50 * DEGREES_PER_CENT,
            sweepAngle = 100 * DEGREES_PER_CENT,
            useCenter = false,
            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
            size = Size(arcRadius * 2, arcRadius * 2),
            style = Stroke(width = radius * 0.045f, cap = StrokeCap.Round),
        )
        // The in-tune window (±5 cents), always faintly marked.
        drawArc(
            color = IN_TUNE.copy(alpha = 0.35f),
            startAngle = -90f - 5 * DEGREES_PER_CENT,
            sweepAngle = 10 * DEGREES_PER_CENT,
            useCenter = false,
            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
            size = Size(arcRadius * 2, arcRadius * 2),
            style = Stroke(width = radius * 0.045f, cap = StrokeCap.Round),
        )

        // Ticks every 5 cents, longer every 10, longest at 0.
        for (tick in -50..50 step 5) {
            val length = when {
                tick == 0 -> 0.11f
                tick % 10 == 0 -> 0.07f
                else -> 0.04f
            }
            drawLine(
                color = if (tick == 0) TEXT.copy(alpha = 0.8f) else Color(0xFF4A515E),
                start = polar(center, tick.toFloat(), radius * 0.955f - radius * length),
                end = polar(center, tick.toFloat(), radius * 0.955f),
                strokeWidth = if (tick % 10 == 0) 2.5f else 1.5f,
                cap = StrokeCap.Round,
            )
        }

        if (showNeedle) {
            // Lit segment from 0 to the reading.
            drawArc(
                color = accent.copy(alpha = 0.9f),
                startAngle = -90f,
                sweepAngle = clamped * DEGREES_PER_CENT,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(width = radius * 0.045f, cap = StrokeCap.Round),
            )
            bead(polar(center, clamped, arcRadius), radius, accent, glow)
        }
    }
}

private fun DrawScope.bead(at: Offset, radius: Float, color: Color, glow: Float) {
    drawCircle(
        brush = Brush.radialGradient(listOf(color.copy(alpha = 0.55f * glow), Color.Transparent), center = at, radius = radius * 0.13f),
        radius = radius * 0.13f,
        center = at,
    )
    drawCircle(color = color, radius = radius * 0.042f, center = at)
    drawCircle(color = Color.White.copy(alpha = 0.85f), radius = radius * 0.018f, center = at)
}

/** Point on the gauge for [centsValue] at distance [r] from the centre. */
private fun polar(center: Offset, centsValue: Float, r: Float): Offset {
    val radians = Math.toRadians((centsValue * DEGREES_PER_CENT - 90f).toDouble())
    return Offset(center.x + r * cos(radians).toFloat(), center.y + r * sin(radians).toFloat())
}

@Composable
private fun Pill(caption: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier
            .width(64.dp)
            .background(Color(0xFF12151B), RoundedCornerShape(50))
            .border(1.dp, Color(0xFF262B34), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(caption, fontSize = 8.sp, letterSpacing = 1.5.sp, color = MUTED)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TEXT)
    }
}

private fun stateColor(cents: Float): Color = when {
    abs(cents) <= 5 -> IN_TUNE
    abs(cents) <= 15 -> CLOSE
    else -> OFF
}

private fun centsLabel(cents: Float): String {
    val rounded = cents.roundToInt()
    return when {
        abs(rounded) <= 2 -> "IN TUNE"
        rounded > 0 -> "+$rounded¢  SHARP"
        else -> "−${-rounded}¢  FLAT"
    }
}
