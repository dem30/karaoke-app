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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Phase 1 - trong tam chinh cua toan bo du an.
 *
 * Pipeline: App phat nhac -> AudioPlaybackCaptureConfiguration -> AudioRecord -> PCM
 *
 * ✅ CAP NHAT (Phase 3 - QUAN TRONG, chong vong lap phan hoi so): them
 * .excludeUid(Process.myUid()) vao captureConfig. Ly do: AudioPlaybackCaptureConfiguration
 * voi addMatchingUsage(USAGE_MEDIA) bat MOI audio co nhan USAGE_MEDIA tren
 * may, KE CA audio do CHINH APP NAY tu phat ra (da xac nhan qua debug Phase
 * 2 - MusicInput vo tinh bat lai chinh am thanh test tone cua OutputRouter).
 * O Phase 3, OutputRouter se phat ra BAN MIX (nhac + giong hat) - neu khong
 * loai tru chinh app minh, MusicInput se bat lai ban mix do, cong tiep vao
 * chu ky mix tiep theo, tao vong lap phan hoi SO (khong phai vat ly) khien
 * giong hat bi cong don lap lai vo han, bien do tang dan.
 *
 * ✅ CAP NHAT (Phase 3): them tham so onPcmChunk (optional) - goi lai voi PCM
 * THO moi lan doc duoc 1 buffer, de LowLatencyMixer tieu thu truc tiep. Giu
 * nguyen onAmplitudeTick (dung cho notification real-time tu Phase 1) -
 * ca 2 callback cung ton tai song song, khong anh huong nhau.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MusicInput(
    private val mediaProjection: MediaProjection,
    private val onAmplitudeTick: ((Long) -> Unit)? = null,
    private val onPcmChunk: ((ShortArray, Int) -> Unit)? = null
) {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var shouldStop = false

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

    @SuppressLint("MissingPermission") // RECORD_AUDIO da xin o MainActivity truoc khi toi day
    fun startCapture() {
        shouldStop = false

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            // ✅ SUA LOI QUAN TRONG (xem giai thich dau file): loai tru chinh
            // app nay ra khoi pham vi capture, tranh bat lai am thanh do
            // chinh OutputRouter cua app tu phat ra.
            .excludeUid(Process.myUid())
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
        logBoth("✅ Bat dau capture, sampleRate=$SAMPLE_RATE, minBufferSize=$minBufferSize, excludeUid=${Process.myUid()}")

        captureJob = scope.launch {
            val buffer = ShortArray(minBufferSize / 2)
            var lastLogTime = System.currentTimeMillis()
            var sumAmplitude = 0L
            var sampleCount = 0L

            while (!shouldStop) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    onPcmChunk?.invoke(buffer, read)

                    for (i in 0 until read) {
                        sumAmplitude += abs(buffer[i].toInt())
                    }
                    sampleCount += read
                } else if (read < 0) {
                    logBoth("❌ AudioRecord.read() loi, code=$read", isError = true)
                    break
                }

                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 1000) {
                    val avg = if (sampleCount > 0) sumAmplitude / sampleCount else 0
                    logBoth("amplitude trung binh 1s qua: $avg (sampleCount=$sampleCount)")
                    onAmplitudeTick?.invoke(avg)
                    sumAmplitude = 0
                    sampleCount = 0
                    lastLogTime = now
                }
            }
            logBoth("Vong lap capture da dung (shouldStop=$shouldStop).")
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
        logBoth("🛑 Da dung capture")
    }
}