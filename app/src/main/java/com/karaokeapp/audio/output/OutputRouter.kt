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
 * Phase 2/3 - nhan PCM (da mix nhac + mic, hoac mic don o Phase 2) va ghi
 * ra AudioTrack o che do streaming, phat qua loa/tai nghe cua CHINH may
 * dang chay app - day la stream OUTPUT rieng cua karaoke-app, khong phai
 * mot app "phat nhac" canh tranh voi nguon khac.
 *
 * ✅ CAP NHAT QUAN TRONG (sua bug latency ~584ms do chinh code gay ra, PHAT
 * HIEN qua so lieu LatencyProbe qua on dinh bat thuong o lan test truoc):
 *
 * 1. Doi CHANNEL_OUT_MONO -> CHANNEL_OUT_STEREO: PERFORMANCE_MODE_LOW_LATENCY
 *    cua Android CHI thuc su kich hoat duong "fast mixer" (buffer rat nho,
 *    do tre thap) khi dung STEREO - dung MONO khien he thong am tham roi ve
 *    duong xu ly thuong (buffer sau, do tre cao ~500ms+), DU van bao
 *    STATE_INITIALIZED binh thuong khong loi gi ca. Vi buffer PCM dau vao
 *    van la mono (tu MicInput), write() gio se tu nhan doi moi sample thanh
 *    2 kenh (L=R) truoc khi ghi.
 *
 * 2. BO qua setBufferSizeInBytes() khi dang xin PERFORMANCE_MODE_LOW_LATENCY
 *    (API 26+): truoc day code tu tinh "minBufferSize * 2" va ep AudioTrack
 *    dung dung kich thuoc do - day CHINH LA nguyen nhan gay ra ~520ms do
 *    tre (buffer qua lon so voi yeu cau cua duong fast mixer that su can).
 *    Khong goi setBufferSizeInBytes() nua trong nhanh low-latency, de he
 *    thong TU CHON kich thuoc buffer toi uu (thuong chi vai chuc frame,
 *    tuong duong vai ms) - dung khuyen nghi chinh thuc cua Android cho
 *    performance mode nay.
 *
 * ⚠️ Van con 1 yeu to co the anh huong them (CHUA sua trong lan nay, ghi
 * chu de theo doi neu latency van con cao sau khi sua 2 diem tren): fast
 * mixer con yeu cau sample rate TRUNG KHOP voi "native sample rate" that su
 * cua thiet bi (doc qua AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) - neu may
 * Honor nay co native rate khac 44100 (vi du 48000 - kha pho bien), fast
 * path van co the khong duoc cap du da sua STEREO + bo buffer size. Log gia
 * tri nay ra de biet truoc, xem log "[OutputRouter] Native sample rate cua
 * thiet bi".
 *
 * ❌ DA GO Audio Focus (fix lan nay): ban truoc co them requestAudioFocus()
 * voi AUDIOFOCUS_GAIN + tu dong xin lai ngay khi mat focus. Day la sai lam -
 * AUDIOFOCUS_GAIN la loai focus doc quyen, khien he thong coi day la nguon
 * phat "chinh" va gui AUDIOFOCUS_LOSS cho app dang phat nhac nguon (vi du
 * YouTube o Phase 1 khi dang test capture), buoc app do tu pause. Viec tu
 * dong xin lai focus ngay khi mat con tao vong lap "giat" focus qua lai moi
 * khi app nguon co gang phat lai. Vi OutputRouter o day CHI phat ra ket qua
 * am thanh cuoi (nhac da capture + mic da mix), khong can va KHONG duoc xin
 * Audio Focus doc quyen - da bo hoan toan phan nay. Van de "tut volume qua
 * Bluetooth AVRCP" duoc xu ly rieng boi volumeGuardJob (khong thay doi),
 * khong lien quan gi den Audio Focus.
 */
class OutputRouter(
    private val context: Context,
    // Cho phep truyen usage khac, MAC DINH van la USAGE_MEDIA (STREAM_MUSIC).
    //
    // ⚠️ TUYET DOI KHONG dung USAGE_VOICE_COMMUNICATION o day: usage nay
    // mang ngu nghia "audio cuoc goi", tren nhieu thiet bi/OEM co the khien
    // he thong tu chuyen route Bluetooth tu A2DP (stereo, chat luong nhac)
    // sang SCO (mono, ~8-16kHz, chat luong thoai) - pha hong hoan toan chat
    // luong am thanh karaoke qua loa BT. Day la ly do KHONG chon huong nay
    // du no co ve la cach "sach" nhat de thoat STREAM_MUSIC.
    private val usage: Int = AudioAttributes.USAGE_MEDIA
) {

    private var audioTrack: AudioTrack? = null

    // Buffer stereo tam dung lai de tranh cap phat moi lan write() - kich
    // thuoc se tu dong lon len neu can (xem write()).
    private var stereoScratchBuffer = ShortArray(0)

    // Dem so sample da qua ke tu lan log bien do cuoi - dung de log ~1 lan/s
    // thay vi moi lan write() (tranh spam log).
    private var outputAmplitudeLogCounter = 0

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
            logBoth("Native sample rate cua thiet bi: $nativeSampleRate Hz (dang dung $SAMPLE_RATE Hz)")
            logBoth("Native frames per buffer cua thiet bi: $nativeFramesPerBuffer")
            if (nativeSampleRate != null && nativeSampleRate.toIntOrNull() != SAMPLE_RATE) {
                logBoth(
                    "⚠️ Sample rate dang dung ($SAMPLE_RATE) KHONG khop native rate " +
                        "cua may ($nativeSampleRate) - co the van chan duong fast mixer " +
                        "du da sua STEREO + bo buffer size. Neu latency van cao sau ban " +
                        "sua nay, day la nghi van tiep theo can xu ly.",
                    isError = false
                )
            }
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
            // ✅ SUA LOI CHINH: KHONG goi setBufferSizeInBytes() o day nua - de
            // he thong tu chon buffer toi uu cho fast mixer path. Goi ham nay
            // voi 1 gia tri lon (nhu truoc day) se VO HIEU HOA duong low
            // latency that su, du khong bao loi gi ca.
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        } else {
            // Truoc API 26 khong co performance mode - buoc phai tu dat buffer,
            // dung dung minBufferSize (KHONG nhan doi) de giu do tre thap nhat
            // co the trong dieu kien khong co fast mixer.
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
        logBoth("✅ Da bat dau output (stereo, low-latency), sampleRate=$SAMPLE_RATE, usage=$usage")
    }

    /**
     * Ghi 1 buffer PCM MONO ra output STEREO - tu dong nhan doi moi sample
     * thanh 2 kenh L=R truoc khi ghi (xem giai thich o dau file ve ly do can
     * doi sang stereo).
     */
    fun write(buffer: ShortArray, size: Int) {
        val track = audioTrack ?: return

        val requiredStereoSize = size * 2
        if (stereoScratchBuffer.size < requiredStereoSize) {
            stereoScratchBuffer = ShortArray(requiredStereoSize)
        }
        for (i in 0 until size) {
            stereoScratchBuffer[i * 2] = buffer[i]
            stereoScratchBuffer[i * 2 + 1] = buffer[i]
        }

        // Log bien do cua ban MIX CUOI CUNG thuc su ghi ra loa (khac voi log
        // bien do input music/vocal rieng le o noi khac) - ~1 lan/giay de
        // khong spam log. Day la diem gan nhat app co the do truoc khi mat
        // dau vet vao AudioFlinger/HAL (noi co the bi OEM ap duck-gain).
        outputAmplitudeLogCounter += size
        if (outputAmplitudeLogCounter >= SAMPLE_RATE) {
            outputAmplitudeLogCounter = 0
            var sum = 0L
            for (i in 0 until size) sum += kotlin.math.abs(buffer[i].toInt())
            val avg = if (size > 0) sum / size else 0
            logBoth("[OutputAmplitude] mixedAvg=$avg frame=$totalFramesWritten")
        }

        val written = track.write(stereoScratchBuffer, 0, requiredStereoSize)
        if (written < 0) {
            logBoth("❌ AudioTrack.write() loi, code=$written", isError = true)
        } else {
            // written la so SAMPLE stereo (ca 2 kenh) - chia 2 de ra so FRAME
            // (1 frame = 1 cap L+R) tuong ung voi so sample mono goc da ghi.
            totalFramesWritten += written / 2
        }
    }

    /**
     * Uoc tinh thoi diem (System.nanoTime() cung khong gian voi MicInput)
     * phan cung THAT SU phat ra frame o vi tri targetFrame, dua tren
     * AudioTrack.getTimestamp().
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