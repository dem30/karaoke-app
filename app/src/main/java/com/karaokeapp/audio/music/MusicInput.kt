package com.karaokeapp.audio.music

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Phase 1 - Capture nhac he thong (YouTube, Spotify...).
 *
 * ✅ NANG CAP STEREO:
 * 1. Chuyen CHANNEL_CONFIG tu CHANNEL_IN_MONO sang CHANNEL_IN_STEREO.
 *    Thu tron ven 2 kenh Trai/Phai, loai bo 100% triet tieu pha (phase cancellation),
 *    lay lai tieng treble leng keng, do rong khong gian 3D cua ban phoi goc.
 * 2. Kich thuoc moi chunk doc = 1764 frame * 2 kenh = 3528 sample short (dung chu ky 40ms).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MusicInput(
    private val mediaProjection: MediaProjection,
    private val onAmplitudeTick: ((Long) -> Unit)? = null,
    private val onPcmChunk: ((ShortArray, Int) -> Unit)? = null
) {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    private val captureDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        object : Thread(runnable, "MusicInputCapture") {
            override fun run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                super.run()
            }
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(captureDispatcher + SupervisorJob())
    private val notifyScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var shouldStop = false

    companion object {
        private const val TAG = "MusicInput"
        private const val SAMPLE_RATE = 44100
        // ✅ Chuyen sang thu am Stereo 2 kenh
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val AMPLITUDE_LOG_INTERVAL_MS = 3000L
        private const val MIXER_CHUNK_MS = 40L
        // 40ms o 44.1kHz = 1764 frame * 2 kenh (L + R) = 3528 samples
        private const val FRAMES_PER_CHUNK = (SAMPLE_RATE * MIXER_CHUNK_MS / 1000L).toInt()
        private const val READ_CHUNK_SAMPLES = FRAMES_PER_CHUNK * 2
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[MusicInput] $msg")
    }

    @SuppressLint("MissingPermission")
    fun startCapture() {
        shouldStop = false

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .excludeUid(Process.myUid())
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            logBoth("❌ getMinBufferSize khong hop le: $minBufferSize", isError = true)
            return
        }

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            logBoth("❌ AudioRecord khoi tao that bai, state=${record.state}", isError = true)
            record.release()
            return
        }

        audioRecord = record
        record.startRecording()
        logBoth("✅ Bat dau capture nhac (STEREO), sampleRate=$SAMPLE_RATE, minBufferSize=$minBufferSize")

        captureJob = scope.launch {
            val buffer = ShortArray(READ_CHUNK_SAMPLES)
            var lastLogTime = System.currentTimeMillis()
            var sumAmplitude = 0L
            var sampleCount = 0L
            var wasSilent = false

            while (!shouldStop) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    var maxAbs = 0
                    var nonZero = 0
                    for (i in 0 until read) {
                        val value = abs(buffer[i].toInt())
                        sumAmplitude += value
                        if (value != 0) nonZero++
                        if (value > maxAbs) maxAbs = value
                    }
                    sampleCount += read

                    val silentNow = maxAbs == 0
                    if (silentNow && !wasSilent) {
                        logBoth("⚠️ CAPTURE SILENCE: read=$read/${buffer.size}, nonZero=$nonZero, maxAbs=$maxAbs")
                    } else if (!silentNow && wasSilent) {
                        logBoth("🔄 CAPTURE RECOVERED: read=$read/${buffer.size}, nonZero=$nonZero, maxAbs=$maxAbs")
                    }
                    wasSilent = silentNow

                    onPcmChunk?.invoke(buffer, read)
                } else if (read < 0) {
                    logBoth("❌ AudioRecord.read() loi, code=$read", isError = true)
                    break
                }

                val now = System.currentTimeMillis()
                if (now - lastLogTime >= AMPLITUDE_LOG_INTERVAL_MS) {
                    val avg = if (sampleCount > 0) sumAmplitude / sampleCount else 0
                    logBoth("amplitude nhac Stereo trung binh ${AMPLITUDE_LOG_INTERVAL_MS / 1000}s qua: $avg")
                    val amplitudeSnapshot = avg
                    if (onAmplitudeTick != null) {
                        notifyScope.launch {
                            try {
                                onAmplitudeTick.invoke(amplitudeSnapshot)
                            } catch (e: Exception) {
                                logBoth("❌ onAmplitudeTick loi: ${e.message}", isError = true)
                            }
                        }
                    }
                    sumAmplitude = 0
                    sampleCount = 0
                    lastLogTime = now
                }
            }
            logBoth("Vong lap capture nhac da dung.")
        }
    }

    fun stopCapture() {
        shouldStop = true
        captureJob?.cancel()
        captureJob = null
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        captureDispatcher.close()
        notifyScope.cancel()
        logBoth("🛑 Da dung capture nhac")
    }
}