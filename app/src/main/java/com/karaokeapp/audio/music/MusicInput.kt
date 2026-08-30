package com.karaokeapp.audio.music

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Phase 1 - trong tam chinh cua toan bo du an.
 *
 * Pipeline: App phat nhac -> AudioPlaybackCaptureConfiguration -> AudioRecord -> PCM
 *
 * O giai doan nay CHUA can luu file WAV hay day PCM di dau - chi can chung
 * minh lay duoc tin hieu that (amplitude khac 0, thay doi theo nhac dang
 * phat). Moi dong log quan trong day ca vao Log.d LAN CaptureLogBus (de
 * hien thi/copy ngay trong app, vi khong co adb tren may build qua GitHub
 * Actions). Xem PLAN.md muc "Phase 1" de biet tieu chi DONE.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MusicInput(private val mediaProjection: MediaProjection) {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    companion object {
        private const val TAG = "MusicInput"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[MusicInput] $msg")
    }

    /**
     * Bat dau capture. Can duoc goi tu 1 Service (khong phai Activity truc
     * tiep) vi MediaProjection yeu cau chay trong context foreground service
     * theo quy dinh cua Android 10+.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO da xin o MainActivity truoc khi toi day
    fun startCapture() {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            logBoth("❌ getMinBufferSize khong hop le: $minBufferSize - thiet bi co the khong ho tro cau hinh nay", isError = true)
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
        logBoth("✅ Bat dau capture, sampleRate=$SAMPLE_RATE, minBufferSize=$minBufferSize")

        captureJob = scope.launch {
            val buffer = ShortArray(minBufferSize / 2)
            var lastLogTime = System.currentTimeMillis()
            var sumAmplitude = 0L
            var sampleCount = 0L

            while (audioRecord != null) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        sumAmplitude += abs(buffer[i].toInt())
                    }
                    sampleCount += read
                } else if (read < 0) {
                    logBoth("❌ AudioRecord.read() loi, code=$read", isError = true)
                }

                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 1000) {
                    val avg = if (sampleCount > 0) sumAmplitude / sampleCount else 0
                    logBoth("amplitude trung binh 1s qua: $avg (sampleCount=$sampleCount)")
                    sumAmplitude = 0
                    sampleCount = 0
                    lastLogTime = now
                }
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        logBoth("🛑 Da dung capture")
    }
}
