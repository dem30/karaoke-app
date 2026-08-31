package com.karaokeapp.audio.mic

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.karaokeapp.audio.music.CaptureLogBus

/**
 * Phase 2 - lay PCM tu mic qua AudioRecord thuan (khong dung cau hinh
 * WebRTC voice-chat mac dinh vi AEC/NS/AGC lam bien dang giong hat).
 *
 * Uu tien MediaRecorder.AudioSource.UNPROCESSED (tu dong tat AEC/NS/AGC neu
 * thiet bi ho tro). Fallback ve MediaRecorder.AudioSource.MIC thuong neu
 * UNPROCESSED khong duoc ho tro (kiem tra qua AudioManager.getProperty
 * truoc, VA du phong bang cach thu khoi tao that, vi mot so thiet bi bao
 * ho tro qua property nhung van khoi tao that bai tren thuc te).
 *
 * Dung chung sample rate/format voi MusicInput (44100Hz, PCM_16BIT, mono)
 * de sau nay LowLatencyMixer (Phase 3) cong 2 nguon PCM truc tiep duoc,
 * khong can resample.
 */
class MicInput(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var shouldStop = false

    companion object {
        private const val TAG = "MicInput"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[MicInput] $msg")
    }

    /** Kiem tra thiet bi co bao ho tro AudioSource.UNPROCESSED hay khong (qua AudioManager). */
    private fun isUnprocessedSupportedByProperty(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

    /**
     * Thu tao AudioRecord voi 1 audioSource cu the. Tra ve null neu khoi tao
     * that bai (state != STATE_INITIALIZED) thay vi throw, de goi noi co the
     * thu fallback sang source khac.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO da xin o MainActivity truoc khi toi day
    private fun tryBuildAudioRecord(audioSource: Int, minBufferSize: Int): AudioRecord? {
        val record = try {
            AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            )
        } catch (e: Exception) {
            logBoth("❌ Exception khi tao AudioRecord voi source=$audioSource: ${e.message}", isError = true)
            return null
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            logBoth("❌ AudioRecord voi source=$audioSource khoi tao that bai, state=${record.state}", isError = true)
            record.release()
            return null
        }
        return record
    }

    /**
     * Bat dau capture mic. onPcmChunk duoc goi lai MOI LAN doc duoc 1 buffer -
     * dung de OutputRouter (Phase 2) hoac LowLatencyMixer (Phase 3) tieu thu
     * PCM ngay lap tuc, khong luu trung gian - giu do tre thap nhat co the.
     *
     * @param onPcmChunk callback(buffer, soLuongSampleThucDoc)
     */
    fun startCapture(onPcmChunk: (ShortArray, Int) -> Unit) {
        shouldStop = false

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            logBoth("❌ getMinBufferSize khong hop le: $minBufferSize", isError = true)
            return
        }

        val unprocessedSupportedByProperty = isUnprocessedSupportedByProperty()
        logBoth("Thiet bi bao ho tro UNPROCESSED qua AudioManager property: $unprocessedSupportedByProperty")

        var record: AudioRecord? = null
        var sourceUsed = "UNKNOWN"

        if (unprocessedSupportedByProperty) {
            record = tryBuildAudioRecord(MediaRecorder.AudioSource.UNPROCESSED, minBufferSize)
            if (record != null) sourceUsed = "UNPROCESSED"
        }

        if (record == null) {
            logBoth("⚠️ Fallback ve MediaRecorder.AudioSource.MIC thuong (UNPROCESSED khong ho tro hoac khoi tao that bai).")
            record = tryBuildAudioRecord(MediaRecorder.AudioSource.MIC, minBufferSize)
            if (record != null) sourceUsed = "MIC"
        }

        if (record == null) {
            logBoth("❌ Khong the khoi tao AudioRecord voi bat ky source nao (UNPROCESSED lan MIC deu that bai).", isError = true)
            return
        }

        audioRecord = record
        record.startRecording()
        logBoth("✅ Bat dau capture mic, source=$sourceUsed, sampleRate=$SAMPLE_RATE, minBufferSize=$minBufferSize")

        captureJob = scope.launch {
            val buffer = ShortArray(minBufferSize / 2)
            var lastLogTime = System.currentTimeMillis()
            var sumAmplitude = 0L
            var sampleCount = 0L

            while (!shouldStop) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    onPcmChunk(buffer, read)
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
                    logBoth("amplitude mic trung binh 1s qua: $avg (sampleCount=$sampleCount)")
                    sumAmplitude = 0
                    sampleCount = 0
                    lastLogTime = now
                }
            }
            logBoth("Vong lap capture mic da dung (shouldStop=$shouldStop).")
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
        logBoth("🛑 Da dung capture mic")
    }
}