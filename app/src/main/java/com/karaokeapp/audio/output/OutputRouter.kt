package com.karaokeapp.audio.output

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.karaokeapp.audio.music.CaptureLogBus

/**
 * Phase 2 - ban toi gian, CHUA co mixer: nhan thang PCM tu MicInput va ghi
 * ra AudioTrack o che do streaming, chi de nghe duoc tieng minh noi qua
 * loa/tai nghe NGAY LAP TUC phuc vu do latency.
 *
 * ✅ CAP NHAT (do latency tu dong qua timestamp): them totalFramesWritten
 * (dem tong so frame/sample da ghi ra tu luc start()) va
 * estimatePresentationNanoTime(targetFrame) - dung API chinh thuc
 * AudioTrack.getTimestamp() de biet CHINH XAC thoi diem phan cung THAT SU
 * phat ra 1 vi tri frame cu the, khong chi la luc write() tra ve (write()
 * chi bao du lieu da vao HANG DOI, chua chac da phat).
 *
 * ⚠️ GIOI HAN QUAN TRONG: getTimestamp() phan anh dung thoi diem phat thuc
 * te cho duong loa trong/day - day la co che chuan cua Android audio HAL.
 * Nhung VOI BLUETOOTH, do chinh xac PHU THUOC THIET BI: mot so chip/Android
 * version co bu tru dung do tre truyen Bluetooth vao gia tri tra ve, mot so
 * thi khong (chi phan anh luc du lieu roi khoi buffer phan mem, chua tinh
 * do tre thuc cua duong truyen Bluetooth phia sau). VI VAY: voi loa trong/
 * tai nghe day, so do duoc TU TIN SU DUNG TRUC TIEP. Voi Bluetooth, NEN doi
 * chieu it nhat 1 lan bang cach vo tay + quay video slow-motion de biet con
 * so tu dong nay co dang tin tren may Honor cu the hay khong.
 */
class OutputRouter {

    private var audioTrack: AudioTrack? = null

    // ✅ MOI: dem tong so frame (=so sample voi mono) da ghi ra tu luc
    // start(). Dung de tinh "frame tuyet doi" tuong ung voi 1 sample cu the
    // trong luong PCM, phuc vu doi chieu voi getTimestamp().
    @Volatile
    var totalFramesWritten: Long = 0
        private set

    companion object {
        private const val TAG = "OutputRouter"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val NANOS_PER_FRAME = 1_000_000_000L / SAMPLE_RATE
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[OutputRouter] $msg")
    }

    fun start() {
        totalFramesWritten = 0
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            logBoth("❌ AudioTrack.getMinBufferSize khong hop le: $minBufferSize", isError = true)
            return
        }

        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
            .setBufferSizeInBytes(minBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        val track = builder.build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            logBoth("❌ AudioTrack khoi tao that bai, state=${track.state}", isError = true)
            track.release()
            return
        }

        audioTrack = track
        track.play()
        logBoth("✅ Da bat dau output, sampleRate=$SAMPLE_RATE, minBufferSize=$minBufferSize")
    }

    /**
     * Ghi 1 buffer PCM ra output. QUAN TRONG cho tinh nang do latency: goi
     * ham nay SAU KHI da doc totalFramesWritten (vi du de tinh vi tri frame
     * tuyet doi cua 1 sample trong buffer NAY truoc khi no duoc cong don) -
     * xem MainActivity.toggleMicLoopback() de biet thu tu goi dung.
     */
    fun write(buffer: ShortArray, size: Int) {
        val track = audioTrack ?: return
        val written = track.write(buffer, 0, size)
        if (written < 0) {
            logBoth("❌ AudioTrack.write() loi, code=$written", isError = true)
        } else {
            totalFramesWritten += written
        }
    }

    /**
     * Uoc tinh thoi diem (System.nanoTime() cung khong gian voi
     * MicInput) phan cung THAT SU phat ra frame o vi tri targetFrame, dua
     * tren AudioTrack.getTimestamp(). Tra ve null neu chua co du du lieu de
     * tinh (vi du moi bat dau phat, chua co frame nao thuc su ra khoi loa) -
     * noi goi nen bao nguoi dung vo tay lai sau vai giay.
     */
    fun estimatePresentationNanoTime(targetFrame: Long): Long? {
        val track = audioTrack ?: return null
        val timestamp = AudioTimestamp()
        val success = track.getTimestamp(timestamp)
        if (!success) {
            logBoth("⚠️ getTimestamp() chua co du lieu hop le (co the moi bat dau phat) - thu vo tay lai sau vai giay.")
            return null
        }
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
}