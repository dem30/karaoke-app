package com.karaokeapp.audio.mic

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs
import com.karaokeapp.audio.music.CaptureLogBus

/**
 * Phase 2 - lay PCM tu mic qua AudioRecord thuan (khong dung cau hinh
 * WebRTC voice-chat mac dinh vi AEC/NS/AGC lam bien dang giong hat).
 *
 * Uu tien MediaRecorder.AudioSource.UNPROCESSED (tu dong tat AEC/NS/AGC neu
 * thiet bi ho tro). Fallback ve MediaRecorder.AudioSource.MIC thuong neu
 * UNPROCESSED khong duoc ho tro.
 *
 * Dung chung sample rate/format voi MusicInput (44100Hz, PCM_16BIT, mono)
 * de sau nay LowLatencyMixer (Phase 3) cong 2 nguon PCM truc tiep duoc.
 *
 * ✅ CAP NHAT (do latency tu dong qua timestamp, khong can quay video):
 * them tham so onTransientDetected - phat hien 1 tieng vo tay (bien do vuot
 * nguong dot ngot) trong buffer vua doc, bao cho noi goi biet CHINH XAC
 * offset trong buffer VA thoi diem uoc tinh phan cung mic thuc su bat duoc
 * am thanh do. Cach tinh: System.nanoTime() ngay luc record.read() tra ve
 * la thoi diem SAMPLE CUOI CUNG trong buffer duoc thu; cac sample truoc do
 * duoc thu SOM HON, tinh nguoc lai theo so sample con thieu chia cho sample
 * rate. Noi goi (MainActivity) doi chieu voi
 * OutputRouter.estimatePresentationNanoTime() de tinh do tre thuc, KHONG
 * can vo tay + quay video slow-motion nua (chi con can cho truong hop
 * Bluetooth, vi getTimestamp() cua AudioTrack co the khong bu chinh xac do
 * tre rieng cua Bluetooth tren moi thiet bi - xem ghi chu trong
 * OutputRouter.kt).
 */
class MicInput(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    // ✅ MOI (fix chung goc voi MusicInput - xem giai thich chi tiet trong
    // MusicInput.kt): mic cung la vong lap doc PCM real-time, cung can
    // thread rieng uu tien THREAD_PRIORITY_URGENT_AUDIO thay vi dung chung
    // Dispatchers.Default de khong bi tranh CPU voi app foreground khac.
    private val captureDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        object : Thread(runnable, "MicInputCapture") {
            override fun run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                super.run()
            }
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(captureDispatcher)

    @Volatile
    private var shouldStop = false

    // ✅ MOI: chong bat lien tuc nhieu lan cho CUNG 1 tieng vo tay (1 tieng
    // vo thuong keo dai vai chuc ms, co the vuot nguong o nhieu sample lien
    // tiep hoac o 2 buffer lien tiep) - chi lay diem DAU TIEN vuot nguong,
    // bo qua moi phat hien khac trong khoang COOLDOWN_NANOS sau do.
    private var lastDetectionNanoTime = 0L

    companion object {
        private const val TAG = "MicInput"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val NANOS_PER_SAMPLE = 1_000_000_000L / SAMPLE_RATE

        // Nguong bien do de coi la "tieng vo tay" - can du cao de khong bi
        // kich hoat nham boi giong noi binh thuong (thuong chi vai tram toi
        // ~2000), chi vo tay that manh moi vuot qua duoc.
        private const val CLAP_THRESHOLD = 12000

        // Thoi gian "nghi" sau 1 lan phat hien, tranh dem trung 1 tieng vo
        // thanh nhieu lan hoac bat lien tuc khi con tieng vang/echo.
        private const val COOLDOWN_NANOS = 1_500_000_000L

        // ✅ MOI (giam log du thua): tang tu 1000ms -> 3000ms, dong bo voi
        // MusicInput - van du day do phan giai de thay xu huong, giam 3 lan
        // so dong log.
        private const val AMPLITUDE_LOG_INTERVAL_MS = 3000L
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[MicInput] $msg")
    }

    private fun isUnprocessedSupportedByProperty(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

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
     * Quet 1 buffer vua doc de tim tieng vo tay (sample dau tien vuot
     * CLAP_THRESHOLD), tinh timestamp uoc luong that su cua sample do, roi
     * goi callback. Bo qua neu con dang trong thoi gian cooldown.
     */
    private fun detectClapAndReport(
        buffer: ShortArray,
        read: Int,
        bufferEndNanoTime: Long,
        onTransientDetected: ((offsetInBuffer: Int, captureNanoTime: Long) -> Unit)?
    ) {
        if (onTransientDetected == null) return
        val now = System.nanoTime()
        if (now - lastDetectionNanoTime < COOLDOWN_NANOS) return

        for (i in 0 until read) {
            if (abs(buffer[i].toInt()) >= CLAP_THRESHOLD) {
                val onsetNanoTime = bufferEndNanoTime - (read - i).toLong() * NANOS_PER_SAMPLE
                lastDetectionNanoTime = now
                logBoth("👏 Phat hien tieng vo tay tai sample offset=$i trong buffer (read=$read)")
                onTransientDetected(i, onsetNanoTime)
                return
            }
        }
    }

    /**
     * Bat dau capture mic.
     *
     * @param onPcmChunk callback(buffer, soLuongSampleThucDoc) - goi MOI LAN
     * doc duoc 1 buffer, dung de OutputRouter/Mixer tieu thu PCM ngay.
     * @param onTransientDetected callback(offsetInBuffer, captureNanoTime) -
     * optional, goi khi phat hien 1 tieng vo tay ro rang. Dung cho tinh nang
     * do latency tu dong (xem MainActivity.toggleMicLoopback()).
     */
    fun startCapture(
        onPcmChunk: (ShortArray, Int) -> Unit,
        onTransientDetected: ((offsetInBuffer: Int, captureNanoTime: Long) -> Unit)? = null
    ) {
        shouldStop = false
        lastDetectionNanoTime = 0L

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
            logBoth("❌ Khong the khoi tao AudioRecord voi bat ky source nao.", isError = true)
            return
        }

        audioRecord = record
        record.startRecording()
        logBoth("✅ Bat dau capture mic, source=$sourceUsed, sampleRate=$SAMPLE_RATE, minBufferSize=$minBufferSize")
        if (onTransientDetected != null) {
            logBoth("🎯 Che do do latency dang BAT - vo tay THAT MANH, ro rang truoc mic de kich hoat do (nguong=$CLAP_THRESHOLD).")
        }

        captureJob = scope.launch {
            val buffer = ShortArray(minBufferSize / 2)
            var lastLogTime = System.currentTimeMillis()
            var sumAmplitude = 0L
            var sampleCount = 0L

            while (!shouldStop) {
                val read = record.read(buffer, 0, buffer.size)
                val bufferEndNanoTime = System.nanoTime()

                if (read > 0) {
                    // ✅ Phat hien clap TRUOC khi forward buffer xuong duoi -
                    // dam bao tai thoi diem callback chay, ben nhan (OutputRouter
                    // qua MainActivity) CHUA nhan buffer nay, nen "so frame da
                    // ghi truoc do" con phan anh dung trang thai TRUOC buffer
                    // hien tai.
                    detectClapAndReport(buffer, read, bufferEndNanoTime, onTransientDetected)

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
                if (now - lastLogTime >= AMPLITUDE_LOG_INTERVAL_MS) {
                    val avg = if (sampleCount > 0) sumAmplitude / sampleCount else 0
                    logBoth("amplitude mic trung binh ${AMPLITUDE_LOG_INTERVAL_MS / 1000}s qua: $avg (sampleCount=$sampleCount)")
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
        // ✅ MOI: dong thread rieng, tranh ro ri (xem giai thich trong
        // MusicInput.stopCapture()).
        captureDispatcher.close()
        logBoth("🛑 Da dung capture mic")
    }
}