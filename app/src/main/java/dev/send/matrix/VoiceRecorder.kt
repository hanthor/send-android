package dev.send.matrix

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File

/** What the composer needs from a voice recorder; [VoiceRecorder] records from the microphone. */
internal interface VoiceCapture {
    /** Time since [start], 0 when not recording. */
    val elapsedMillis: Long
    /** Starts recording; false when it can't (no RECORD_AUDIO); throws when the microphone fails. */
    fun start(): Boolean
    /** Records and returns the peak level since the previous call (0..1). */
    fun sampleAmplitude(): Float
    /** Stops and returns the clip, or null when it was too short to be useful. */
    fun stop(): VoiceRecording?
    /** Stops and discards the recording. */
    fun cancel()
}

/**
 * Hold-to-record voice clips: Ogg/Opus via [MediaRecorder] into the cache, with a coarse amplitude
 * waveform sampled by the caller through [sampleAmplitude].
 */
internal class VoiceRecorder(private val context: Context) : VoiceCapture {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L
    private val amplitudes = ArrayList<Float>()

    val isRecording: Boolean get() = recorder != null

    override val elapsedMillis: Long get() = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0L

    /** Starts recording; returns false when RECORD_AUDIO has not been granted. */
    override fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        cancel()
        val file = File(context.cacheDir.resolve("voice").apply { mkdirs() }, "voice-${System.currentTimeMillis()}.ogg")
        val mediaRecorder = MediaRecorder(context)
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            mediaRecorder.setAudioChannels(1)
            mediaRecorder.setAudioSamplingRate(48_000)
            mediaRecorder.setAudioEncodingBitRate(32_000)
            mediaRecorder.setOutputFile(file)
            mediaRecorder.prepare()
            mediaRecorder.start()
        } catch (failure: Exception) {
            mediaRecorder.release()
            file.delete()
            throw IllegalStateException("The microphone could not be started.", failure)
        }
        recorder = mediaRecorder
        output = file
        amplitudes.clear()
        startedAt = SystemClock.elapsedRealtime()
        return true
    }

    /** Records the peak amplitude since the previous call (0..1). */
    override fun sampleAmplitude(): Float {
        val level = ((recorder?.maxAmplitude ?: 0) / 32_767f).coerceIn(0f, 1f)
        if (recorder != null) amplitudes.add(level)
        return level
    }

    /** Stops and returns the clip, or null when it was too short to be useful. */
    override fun stop(): VoiceRecording? {
        val mediaRecorder = recorder ?: return null
        val file = output
        val duration = elapsedMillis
        recorder = null
        output = null
        val stopped = try {
            mediaRecorder.stop()
            true
        } catch (_: RuntimeException) {
            // MediaRecorder throws when no audio frames were captured yet.
            false
        } finally {
            mediaRecorder.release()
        }
        if (file == null) return null
        if (!stopped || duration < MIN_DURATION_MILLIS || file.length() == 0L) {
            file.delete()
            return null
        }
        return VoiceRecording(file, duration, downsample(amplitudes, WAVEFORM_SAMPLES))
    }

    override fun cancel() {
        val mediaRecorder = recorder ?: return
        recorder = null
        try {
            mediaRecorder.stop()
        } catch (_: RuntimeException) {
        } finally {
            mediaRecorder.release()
        }
        output?.delete()
        output = null
    }

    companion object {
        const val MIN_DURATION_MILLIS = 700L
        private const val WAVEFORM_SAMPLES = 40

        /** Buckets the polled peaks into [count] samples, keeping each bucket's maximum. */
        fun downsample(values: List<Float>, count: Int): List<Float> {
            if (values.isEmpty()) return emptyList()
            if (values.size <= count) return values.toList()
            return List(count) { bucket ->
                val from = bucket * values.size / count
                val to = ((bucket + 1) * values.size / count).coerceAtLeast(from + 1)
                values.subList(from, to).max()
            }
        }
    }
}
