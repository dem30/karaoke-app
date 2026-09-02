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
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
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

    // ✅ MOI (fix "nhac nho xiu dung luc doi bai tren YouTube" - nghi van moi
    // dua tren quan sat thuc te: notification amplitude DUNG YEN khi YouTube
    // dang thuc su o foreground, chay lai binh thuong khi keo thanh thong
    // bao xuong hoac chuyen app khac): truoc day vong lap doc PCM chay tren
    // Dispatchers.Default - 1 THREAD POOL DUNG CHUNG cho MOI coroutine
    // Default khac trong toan bo app, VA bi Android xep vao cgroup uu tien
    // scheduling THAP HON "top-app" (app dang thuc su o tren cung man hinh,
    // vi du YouTube). Khi YouTube lam viec nang (decode video/audio bai
    // moi...), scheduler co the tam nhuong CPU cho no truoc, khien thread
    // doc AudioRecord cua chinh MusicInput bi tre - AudioRecord co buffer
    // noi bo GIOI HAN, tre qua lau se lam MAT sample (khong bao loi ro
    // rang) - giai thich dung hien tuong "nho xiu" dung luc do.
    //
    // Sua: dung 1 THREAD RIENG (khong dung chung pool), dat priority
    // THREAD_PRIORITY_URGENT_AUDIO NGAY khi thread vua khoi tao - day la
    // muc uu tien CAO NHAT Android danh rieng cho xu ly am thanh real-time
    // (cung muc AudioFlinger/AudioRecord/AudioTrack noi bo dang dung), giup
    // vong lap nay it bi tranh CPU voi app khac (nhu YouTube) hon nhieu.
    private val captureDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        object : Thread(runnable, "MusicInputCapture") {
            override fun run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                super.run()
            }
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(captureDispatcher + SupervisorJob())

    @Volatile
    private var shouldStop = false

    companion object {
        private const val TAG = "MusicInput"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // ✅ MOI (giam log du thua): tang tu 1000ms -> 3000ms. Van du day do
        // phan giai de thay xu huong am luong tang/giam qua thoi gian, nhung
        // giam 3 lan so dong log so voi truoc (dong log nay chay lien tuc
        // suot ca session capture nhac, khong chi luc Mixer Test).
        private const val AMPLITUDE_LOG_INTERVAL_MS = 3000L
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

            // ✅ MOI (chan doan mat tieng khi seek/doi bai): theo doi tung
            // buffer xem co "im lang THAT SU" (maxAbs==0, tuc toan bo PCM
            // doc duoc la 0) hay khong, va CHI log khi CHUYEN TRANG THAI
            // (silent <-> co am thanh) - tranh spam log moi ~40ms neu im
            // lang keo dai nhieu giay (se lam tran CaptureLogBus.MAX_LINES
            // =500 rat nhanh, mat het log truoc do can doi chieu thoi diem
            // voi [AutoReassert]/[GuardTick] ben PlaybackCaptureService).
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
                        logBoth(
                            "⚠️ CAPTURE SILENCE: read=$read/${buffer.size}, nonZero=$nonZero, " +
                                "maxAbs=$maxAbs, recordState=${record.state}, " +
                                "recordingState=${record.recordingState}"
                        )
                    } else if (!silentNow && wasSilent) {
                        logBoth(
                            "🔄 CAPTURE RECOVERED: read=$read/${buffer.size}, nonZero=$nonZero, " +
                                "maxAbs=$maxAbs, recordState=${record.state}, " +
                                "recordingState=${record.recordingState}"
                        )
                    }
                    wasSilent = silentNow

                    onPcmChunk?.invoke(buffer, read)
                } else if (read < 0) {
                    logBoth(
                        "❌ AudioRecord.read() loi, code=$read, state=${record.state}, " +
                            "recordingState=${record.recordingState}",
                        isError = true
                    )
                    break
                }

                val now = System.currentTimeMillis()
                if (now - lastLogTime >= AMPLITUDE_LOG_INTERVAL_MS) {
                    val avg = if (sampleCount > 0) sumAmplitude / sampleCount else 0
                    logBoth("amplitude trung binh ${AMPLITUDE_LOG_INTERVAL_MS / 1000}s qua: $avg (sampleCount=$sampleCount)")
                    // ✅ SUA LOI GIAT/RE (phat hien qua test Phase 3): KHONG goi
                    // onAmplitudeTick truc tiep (dong bo) nua - callback nay
                    // cuoi cung goi toi NotificationManager.notify(), la 1 IPC
                    // toi system_server co the mat vai-vai chuc ms. Vi no nam
                    // NGAY TRONG vong lap doc PCM real-time nay, moi giay 1 lan
                    // no lam nghen viec doc am thanh dung khoang thoi gian do -
                    // gay giat co chu ky. Tach ra 1 coroutine rieng (fire-and-
                    // forget) de vong lap chinh khong phai cho no xong.
                    val amplitudeSnapshot = avg
                    if (onAmplitudeTick != null) {
                        scope.launch {
                            // ✅ MOI (chan doan cuoi cung): log NGAY TRUOC va SAU
                            // invoke() de xac nhan 100% dong nay thuc su chay toi,
                            // khong chi suy luan qua viec thieu log loi.
                            logBoth("🔔 [InvokeDebug] Chuan bi goi onAmplitudeTick.invoke($amplitudeSnapshot)")
                            try {
                                onAmplitudeTick.invoke(amplitudeSnapshot)
                                logBoth("🔔 [InvokeDebug] Da goi onAmplitudeTick.invoke() xong, khong co loi.")
                            } catch (e: Exception) {
                                // ✅ MOI (fix rui ro crash im lang): onAmplitudeTick cuoi
                                // cung goi toi NotificationManager.notify() ben ngoai -
                                // bat ky exception nao o day (vi du IPC toi
                                // system_server that bai) TRUOC DAY co the huy ca
                                // "scope" (Job thuong, khong SupervisorJob), keo theo
                                // captureJob chinh (vong lap doc PCM) bi huy theo MA
                                // KHONG CO LOG NAO - dung hien tuong "kep, im lang, khong
                                // bao loi" da quan sat duoc. Da doi "scope" sang dung
                                // SupervisorJob() de chan lay lan, nhung van bat o day
                                // them 1 lop de dam bao khong bao gio de exception thoat
                                // ra ngoai coroutine nay (tranh phu thuoc vao
                                // CoroutineExceptionHandler mac dinh cua he thong).
                                logBoth("❌ onAmplitudeTick loi (khong anh huong capture chinh): ${e.message}", isError = true)
                            }
                        }
                    }
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
        // ✅ MOI: dong thread rieng (captureDispatcher) - tranh ro ri thread
        // neu MusicInput bi tao/huy nhieu lan (moi lan bat/tat capture Phase
        // 1 se tao 1 instance moi). Goi SAU khi da stop()/release()
        // AudioRecord (khien record.read() dang block se tra ve loi va thoat
        // vong lap ngay), nen thread hau nhu chac chan da ranh truoc khi
        // dong.
        captureDispatcher.close()
        logBoth("🛑 Da dung capture")
    }
}