package io.github.kennethcheong.tuner

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * YIN pitch detector (de Cheveigné & Kawahara, 2002).
 *
 * Works on one frame of mono samples in [-1, 1]. Returns the fundamental in Hz,
 * or null for silence or sounds without a clear pitch.
 */
class PitchDetector(
    private val sampleRate: Int,
    minHz: Float = 40f,
    maxHz: Float = 2_100f,
    private val threshold: Float = 0.15f,
    /** Frames quieter than this RMS level count as silence. */
    private val minRms: Float = 0.01f,
) {
    private val minLag = (sampleRate / maxHz).toInt().coerceAtLeast(2)
    private val maxLag = (sampleRate / minHz).toInt()

    /** Frames need at least this many samples so the longest lag still has a full comparison window. */
    val minFrameSize: Int get() = maxLag * 2

    fun detect(frame: FloatArray): Float? {
        require(frame.size >= minFrameSize) { "Frame of ${frame.size} samples is shorter than $minFrameSize" }
        if (rms(frame) < minRms) return null

        val window = frame.size / 2
        val lags = maxLag + 1
        // Difference function d(tau), then its cumulative-mean-normalised form d'(tau).
        val d = FloatArray(lags)
        for (tau in 1 until lags) {
            var sum = 0f
            for (i in 0 until window) {
                val delta = frame[i] - frame[i + tau]
                sum += delta * delta
            }
            d[tau] = sum
        }
        val norm = FloatArray(lags)
        norm[0] = 1f
        var running = 0f
        for (tau in 1 until lags) {
            running += d[tau]
            norm[tau] = if (running == 0f) 1f else d[tau] * tau / running
        }

        // First dip below the threshold, followed down to its local minimum.
        var tau = minLag
        while (tau < lags) {
            if (norm[tau] < threshold) {
                while (tau + 1 < lags && norm[tau + 1] < norm[tau]) tau++
                return sampleRate / refine(norm, tau)
            }
            tau++
        }
        return null
    }

    /** Parabolic interpolation around [tau] for a sub-sample lag. */
    private fun refine(norm: FloatArray, tau: Int): Float {
        if (tau <= 0 || tau >= norm.size - 1) return tau.toFloat()
        val left = norm[tau - 1]
        val mid = norm[tau]
        val right = norm[tau + 1]
        val denominator = left - 2 * mid + right
        if (abs(denominator) < 1e-12f) return tau.toFloat()
        return tau + (left - right) / (2 * denominator)
    }

    private fun rms(frame: FloatArray): Float {
        var sum = 0.0
        for (s in frame) sum += s * s
        return sqrt(sum / frame.size).toFloat()
    }
}

/** How the note name is shown: concert pitch, or written pitch for a transposing instrument. */
enum class Transposition(val label: String, val description: String, val semitones: Int) {
    C("C", "Concert pitch", 0),
    B_FLAT("B♭", "Clarinet, trumpet, tenor sax", 2),
    E_FLAT("E♭", "Alto and baritone sax", 9),
    F("F", "Horn, cor anglais", 7),
    ;

    fun next(): Transposition = entries[(ordinal + 1) % entries.size]
}

/** A detected pitch as a note: [name] and [octave] are in the chosen transposition, [cents] is -50..+50. */
data class NoteReading(val name: String, val octave: Int, val cents: Float, val hz: Float)

object Notes {
    private val NAMES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

    const val DEFAULT_A4 = 442f
    const val MIN_A4 = 430f
    const val MAX_A4 = 450f

    fun read(hz: Float, a4: Float, transposition: Transposition): NoteReading {
        val concertMidi = 69 + 12 * log2(hz / a4)
        val nearest = concertMidi.roundToInt()
        val cents = (concertMidi - nearest) * 100
        val written = nearest + transposition.semitones
        return NoteReading(NAMES[Math.floorMod(written, 12)], Math.floorDiv(written, 12) - 1, cents, hz)
    }

    /** Frequency of a concert MIDI note, e.g. 69 -> [a4]. */
    fun frequency(midi: Int, a4: Float): Float = a4 * 2f.pow((midi - 69) / 12f)
}

/** Median of the last few readings, so one stray frame doesn't flick the needle. */
class PitchSmoother(private val size: Int = 5) {
    private val recent = ArrayDeque<Float>()

    fun add(hz: Float): Float {
        // A jump of more than about a semitone is a new note: start over instead of blending.
        if (recent.isNotEmpty() && abs(log2(hz / recent.last())) > 1f / 12) recent.clear()
        recent.addLast(hz)
        if (recent.size > size) recent.removeFirst()
        return recent.sorted()[recent.size / 2]
    }

    fun reset() = recent.clear()
}
