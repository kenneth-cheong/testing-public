package io.github.kennethcheong.tuner

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchTest {
    private val sampleRate = 44_100
    private val detector = PitchDetector(sampleRate)

    /** Odd harmonics only, roughly like a clarinet. */
    private fun clarinetLike(hz: Float, amplitude: Float = 0.3f): FloatArray =
        FloatArray(detector.minFrameSize) { i ->
            listOf(1, 3, 5, 7).filter { hz * it < sampleRate / 2 }
                .sumOf { k -> sin(2 * PI * hz * k * i / sampleRate) / k }.toFloat() * amplitude
        }

    private fun centsOff(actual: Float, expected: Float) = abs(1200 * log2(actual / expected))

    @Test
    fun detectsPitchWithinOneCentAcrossTheRange() {
        for (hz in listOf(82.41f, 146.83f, 233.08f, 442f, 466.16f, 880f, 1318.5f, 1864.66f)) {
            val detected = detector.detect(clarinetLike(hz))
            requireNotNull(detected) { "no pitch for $hz Hz" }
            assertTrue("$hz Hz read as $detected Hz", centsOff(detected, hz) < 1f)
        }
    }

    @Test
    fun faintHalfFrequencyComponentDoesNotDropAnOctave() {
        // Without the sub-period check this read 221 Hz: the same error seen on the watch.
        val tone = clarinetLike(442f)
        val frame = FloatArray(tone.size) { i -> tone[i] + 0.12f * sin(2 * PI * 221.0 * i / sampleRate + 0.7).toFloat() }
        val detected = requireNotNull(detector.detect(frame))
        assertTrue("read as $detected Hz", centsOff(detected, 442f) < 20f)
    }

    @Test
    fun strongSecondHarmonicDoesNotJumpAnOctaveUp() {
        for (hz in listOf(82.4f, 146.8f, 233.1f, 442f, 880f)) {
            val frame = FloatArray(detector.minFrameSize) { i ->
                (1..5).sumOf { k -> sin(2 * PI * hz * k * i / sampleRate) * (if (k <= 2) 1.0 else 0.5) / k }.toFloat() * 0.3f
            }
            val detected = requireNotNull(detector.detect(frame)) { "no pitch for $hz Hz" }
            assertTrue("$hz Hz read as $detected Hz", centsOff(detected, hz) < 20f)
        }
    }

    @Test
    fun silenceAndQuietNoiseHaveNoPitch() {
        assertNull(detector.detect(FloatArray(detector.minFrameSize)))
        assertNull(detector.detect(clarinetLike(442f, amplitude = 0.001f)))
    }

    @Test
    fun concertPitchAtA442() {
        val reading = Notes.read(442f, a4 = 442f, Transposition.C)
        assertEquals("A", reading.name)
        assertEquals(4, reading.octave)
        assertEquals(0f, reading.cents, 0.01f)
    }

    @Test
    fun centsShowSharpAndFlat() {
        assertEquals(10f, Notes.read(Notes.frequency(69, 442f) * 1.005793f, 442f, Transposition.C).cents, 0.1f)
        assertEquals(-25f, Notes.read(Notes.frequency(60, 442f) * 0.985663f, 442f, Transposition.C).cents, 0.1f)
    }

    @Test
    fun bFlatInstrumentReadsAWholeToneHigher() {
        // Concert B♭4 is a written C5 on a B♭ clarinet.
        val reading = Notes.read(Notes.frequency(70, 442f), 442f, Transposition.B_FLAT)
        assertEquals("C", reading.name)
        assertEquals(5, reading.octave)
    }

    @Test
    fun eFlatAndFTranspositions() {
        // Concert C4 is a written A4 on alto sax and a written G4 on horn.
        assertEquals("A" to 4, Notes.read(Notes.frequency(60, 442f), 442f, Transposition.E_FLAT).let { it.name to it.octave })
        assertEquals("G" to 4, Notes.read(Notes.frequency(60, 442f), 442f, Transposition.F).let { it.name to it.octave })
    }

    @Test
    fun smootherTakesTheMedianAndRestartsOnANewNote() {
        val smoother = PitchSmoother(size = 3)
        smoother.add(440f)
        smoother.add(450f)
        assertEquals(442f, smoother.add(442f), 0f)
        assertEquals(523f, smoother.add(523f), 0f)
    }

    @Test
    fun transpositionCyclesThroughAll() {
        assertEquals(Transposition.B_FLAT, Transposition.C.next())
        assertEquals(Transposition.C, Transposition.F.next())
    }
}
