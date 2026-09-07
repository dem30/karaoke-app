package com.karaokeapp.audio.output

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.karaokeapp.audio.music.CaptureLogBus

/**
 * Phase 2/3 - Output Stereo phat ra loa/tai nghe qua AudioTrack streaming.
 *
 * ✅ DA TOI UU:
 * 1. write() ghi truc tiep du lieu Stereo tu LowLatencyMixer vao AudioTrack,
 *    khong can loop nhan doi mau mono ton CPU nhu truoc day.
 * 2. Giu nguyen ham writeMono() du phong cho tinh nang test do do tre (Mic Loopback).
 */
class OutputRouter(
    private val context: Context,
    private val usage: Int = AudioAttributes.USAGE_MEDIA
) {

    private var audioTrack: AudioTrack? = null
    private var stereoScratchBuffer = ShortArray(0)

    @Volatile
    var totalFramesWritten: Long = 0
        private set

    companion object {
        private const val TAG = "OutputRouter"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val NANOS_PER_FRAME = 1_000_000_000L / SAMPLE_RATE
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[OutputRouter] $msg")
    }

    private fun logNativeAudioProperties() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val nativeSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            val nativeFramesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            logBoth("Native sample rate: $nativeSampleRate Hz | Native frames per buffer: $nativeFramesPerBuffer")
        } catch (e: Exception) {
            logBoth("Khong doc duoc native audio properties: ${e.message}")
        }
    }

    fun start() {
        totalFramesWritten = 0
        logNativeAudioProperties()

        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG_OUT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        } else {
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
            if (minBufferSize <= 0) {
                logBoth("❌ AudioTrack.getMinBufferSize khong hop le: $minBufferSize", isError = true)
                return
            }
            builder.setBufferSizeInBytes(minBufferSize)
        }

        val track = builder.build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            logBoth("❌ AudioTrack khoi tao that bai, state=${track.state}", isError = true)
            track.release()
            return
        }

        audioTrack = track
        track.play()
        logBoth("✅ Da bat dau OutputRouter (STEREO low-latency), sampleRate=$SAMPLE_RATE, usage=$usage")
    }

    /**
     * Ghi truc tiep du lieu STEREO vao AudioTrack (dung cho LowLatencyMixer).
     * @param size Tong so sample stereo (vi du: 3528 samples = 1764 frames L/R).
     */
    fun write(buffer: ShortArray, size: Int, isStereo: Boolean = true) {
        if (isStereo) {
            val track = audioTrack ?: return
            val written = track.write(buffer, 0, size)
            if (written < 0) {
                logBoth("❌ AudioTrack.write() loi, code=$written", isError = true)
            } else {
                totalFramesWritten += written / 2
            }
        } else {
            writeMono(buffer, size)
        }
    }

    /**
     * Ghi du lieu MONO bang cach nhan doi L=R (dung cho tinh nang test Mic Loopback).
     */
    fun writeMono(buffer: ShortArray, size: Int) {
        val track = audioTrack ?: return
        val requiredStereoSize = size * 2
        if (stereoScratchBuffer.size < requiredStereoSize) {
            stereoScratchBuffer = ShortArray(requiredStereoSize)
        }
        for (i in 0 until size) {
            stereoScratchBuffer[i * 2] = buffer[i]
            stereoScratchBuffer[i * 2 + 1] = buffer[i]
        }

        val written = track.write(stereoScratchBuffer, 0, requiredStereoSize)
        if (written < 0) {
            logBoth("❌ AudioTrack.write() loi, code=$written", isError = true)
        } else {
            totalFramesWritten += written / 2
        }
    }

    fun estimatePresentationNanoTime(targetFrame: Long): Long? {
        val track = audioTrack ?: return null
        val timestamp = AudioTimestamp()
        val success = track.getTimestamp(timestamp)
        if (!success) return null
        val frameDelta = targetFrame - timestamp.framePosition
        return timestamp.nanoTime + frameDelta * NANOS_PER_FRAME
    }

    fun stop() {
        audioTrack?.apply {
            stop()
            release()
        }
        audioTrack = null
        totalFramesWritten = 0
        logBoth("🛑 Da dung output")
    }

    fun recreate() {
        logBoth("🔄 [Recreate] Dang tao lai AudioTrack...")
        stop()
        start()
    }

    fun nudgeAudioMixerToClearDuck() {
        try {
            val durationMs = 200
            val sampleCount = SAMPLE_RATE * durationMs / 1000
            val toneFrequencyHz = 900.0
            val amplitude = 3000
            val fadeSamples = (sampleCount * 0.1).toInt().coerceAtLeast(1)
            val toneBuffer = ShortArray(sampleCount) { i ->
                val angle = 2.0 * Math.PI * toneFrequencyHz * i / SAMPLE_RATE
                var sample = amplitude * kotlin.math.sin(angle)
                val fadeGain = when {
                    i < fadeSamples -> i.toDouble() / fadeSamples
                    i >= sampleCount - fadeSamples -> (sampleCount - i).toDouble() / fadeSamples
                    else -> 1.0
                }
                sample *= fadeGain
                sample.toInt().toShort()
            }

            val nudgeTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(toneBuffer.size * 2)
                .build()

            val written = nudgeTrack.write(toneBuffer, 0, toneBuffer.size)
            if (written < 0 || nudgeTrack.state != AudioTrack.STATE_INITIALIZED) {
                nudgeTrack.release()
                return
            }

            nudgeTrack.play()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    nudgeTrack.stop()
                    nudgeTrack.release()
                } catch (_: Exception) {}
            }, (durationMs + 150).toLong())
        } catch (e: Exception) {
            logBoth("❌ [NudgeMixer] Loi khi tao nudge: ${e.message}", isError = true)
        }
    }
}