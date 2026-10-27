package io.github.kennethcheong.tuner

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

private val SAMPLE_RATES = listOf(44_100, 48_000, 16_000)
private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

/**
 * Streams the pitch heard by the microphone, about 20 times a second, until the
 * collector is cancelled: the fundamental in Hz, or null for silence or noise.
 */
@RequiresPermission(Manifest.permission.RECORD_AUDIO)
fun microphonePitches(): Flow<Float?> = flow {
    val rate = SAMPLE_RATES.firstOrNull { AudioRecord.getMinBufferSize(it, CHANNEL, ENCODING) > 0 }
        ?: error("The microphone doesn't support any of $SAMPLE_RATES Hz")
    val detector = PitchDetector(rate)
    val frameSize = detector.minFrameSize
    val bufferBytes = max(AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING), frameSize * 2 * 4)
    val record = AudioRecord(MediaRecorder.AudioSource.MIC, rate, CHANNEL, ENCODING, bufferBytes)
    check(record.state == AudioRecord.STATE_INITIALIZED) { "Couldn't open the microphone" }

    val pcm = ShortArray(frameSize)
    val frame = FloatArray(frameSize)
    record.startRecording()
    try {
        while (currentCoroutineContext().isActive) {
            var filled = 0
            while (filled < frameSize) {
                val read = record.read(pcm, filled, frameSize - filled)
                check(read >= 0) { "Microphone read failed ($read)" }
                filled += read
            }
            for (i in 0 until frameSize) frame[i] = pcm[i] / 32_768f
            emit(detector.detect(frame))
        }
    } finally {
        record.stop()
        record.release()
    }
}.flowOn(Dispatchers.Default)
