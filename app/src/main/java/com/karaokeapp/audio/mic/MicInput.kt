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
 * ✅ CAP NHAT: them tham so onAmplitudeTick (optional) - goi lai moi giay voi gia
 * tri amplitude trung binh vua tinh duoc, de PlaybackCaptureService co the cap
 * nhat notification REAL-TIME. Muc dich: cho phep nguoi dung kiem tra tu
 * notification shade (khong can mo lai app - mo lai app se kich hoat lai toan
 * bo flow xin quyen) xem capture co con dang chay that su khi app bi thu
 * xuong/man hinh tat hay khong, de phan biet 2 kha nang: (a) tien trinh van
 * chay binh thuong nen nhung khong co UI de xem log, hay (b) tien trinh da bi
 * OS "dong bang" (frozen) - luc do notification cung se NGUNG cap nhat, vi
 * chinh coroutine goi callback nay cung bi dong bang theo.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MusicInput(
    private val mediaProjection: MediaProjection,
    private val onAmplitudeTick: ((Long) -> Unit)? = null
) {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    // ✅ MOI: co bao hieu coroutine capture nen dung han (dat true trong
    // stopCapture() TRUOC KHI release AudioRecord). Vong lap doc trong dung
    // co nay thay vi chi kiem tra "audioRecord != null" - truoc day sau khi
    // stopCapture() goi audioRecord = null, NEU coroutine dang o giua 1 lan
    // goi record.read() (bien local "record" van con tro toi object cu, KHONG
    // phai audioRecord field), no van tiep tuc doc tren object da release,
    // sinh loi -2 (ERROR_BAD_VALUE) lien tuc mai mai vi khong co dieu kien nao
    // trong vong lap kiem tra lai cong bang field da bi null hoa ca.
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

    /**
     * Bat dau capture. Can duoc goi tu 1 Service (khong phai Activity truc
     * tiep) vi MediaProjection yeu cau chay trong context foreground service
     * theo quy dinh cua Android 10+.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO da xin o MainActivity truoc khi toi day
    fun startCapture() {
        shouldStop = false

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

            // ✅ SUA LOI: dung "shouldStop" (dat true DUNG LUC trong stopCapture(),
            // truoc khi release()) thay vi kiem tra "audioRecord != null" - tranh
            // truong hop bien local "record" (da chup tham chieu tu truoc) van
            // tiep tuc duoc goi read() sau khi object da bi release/thay the boi
            // 1 session moi o ben ngoai, gay loi -2 lap lai vo han.
            while (!shouldStop) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        sumAmplitude += abs(buffer[i].toInt())
                    }
                    sampleCount += read
                } else if (read < 0) {
                    logBoth("❌ AudioRecord.read() loi, code=$read", isError = true)
                    // ✅ MOI: read() tra ve loi (thay vi throw) thuong nghia la
                    // AudioRecord nay da bi he thong thu hoi/vo hieu hoa ngam (vi
                    // du co session capture MOI duoc tao) - KHONG con ly do gi de
                    // tiep tuc vong lap voi toc do toi da (spam log + ton CPU vo
                    // ich). Dung han ngay tai day thay vi de vong lap chay mai.
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
        // ✅ Dat co DUNG truoc, de coroutine tu thoat vong lap o lan kiem tra
        // tiep theo, TRUOC KHI release() ben duoi lam AudioRecord thanh khong
        // hop le - tranh khoang thoi gian coroutine con doc tren object sap bi
        // release song song voi thread nay dang release no.
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