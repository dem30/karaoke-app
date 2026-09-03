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
 * ❌ DA GO Audio Focus (fix truoc do): ban truoc co them requestAudioFocus()
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
 *
 * ✅ CAP NHAT MOI (fix "nhac/vocal nho xiu sau khi YouTube seek/doi bai/bo
 * quang cao, khong tu phuc hoi"): them ham recreate() - dung khi
 * PlaybackCaptureService phat hien su kien mat audio focus (qua
 * FocusObserver) va muon "reset sach" AudioTrack. Ly do can ham nay: da xac
 * nhan qua test thuc te rang chi co hanh dong TAO LAI AudioTrack tu dau
 * (nhu khi bat/tat Mixer Test thu cong) moi xoa duoc trang thai "duck" con
 * sot lai o tang HAL/OEM - viec ep lai so volume/mute-flag qua AudioManager
 * (volumeGuardJob) KHONG dong toi duoc lop nay vi duck la 1 gain noi bo,
 * khong lam thay doi index hay mute-flag ma AudioManager bao cao.
 *
 * ⚠️ CAP NHAT QUAN TRONG NHAT (fix "beep tu kich hoat lai gay MAT TIENG HOAN
 * TOAN thay vi chua no" - phat hien qua quan sat thuc te: nudge lap moi
 * 1.2-1.5s, kich hoat lan dau thanh cong nhung ngay lan ke tiep lam mat het
 * am thanh): nudgeAudioMixerToClearDuck() phat 1 AudioTrack usage=
 * NOTIFICATION/CONTENT_TYPE_SONIFICATION that, NGHE RO - va chinh viec phat
 * 1 am thanh loai NOTIFICATION/SONIFICATION nay co the TU KICH HOAT co che
 * "duck" cua he thong len cac stream media khac dang chay song song (o day
 * la STREAM_SYSTEM cua chinh mixer), HOAN TOAN DOC LAP voi AudioFocus - day
 * la hanh vi noi bo cua AudioPolicyManager/HAL tren nhieu OEM (dac biet
 * Honor), khong can app phai chu dong xin gi ca.
 *
 * Neu nudge lap lai NHANH HON thoi gian de lop duck do TU NHA (thuong vai
 * tram ms den vai giay tuy OEM), moi lan nudge tiep theo se CHONG THEM 1 lop
 * duck MOI len tren lop duck CU CHUA KIP NHA HET cua chinh no - ket qua la
 * gain noi bo bi keo xuong LIEN TUC thay vi duoc phuc hoi, dan den mat tieng
 * HOAN TOAN thay vi chi "nho xiu" nhu truoc khi co nudge. Noi cach khac:
 * chinh co che dung de chua lai la nguon gay ra trieu chung nang hon.
 *
 * ✅ CAP NHAT MOI NHAT (bo cooldown theo yeu cau): ca 2 nguon TU DONG tung
 * goi ham nay (periodicNudgeJob dinh ky, selfHealJob khi mat AudioFocus) DA
 * BI GO BO HOAN TOAN (xem PlaybackCaptureService.kt) - nudge GIO CHI con
 * duoc kich hoat qua 2 nut BAM TAY (nut noi tren man hinh / nut action tren
 * notification). Vi vay lop cooldown/debounce da GO KHOI ham nay - rui ro
 * "chong lop duck len chinh no" da mo ta o tren gan nhu khong con xay ra
 * duoc nua trong dieu kien su dung binh thuong (khoang cach giua 2 lan bam
 * tay tu nhien da du xa, hiem khi nhanh hon vai tram ms). Neu sau nay quan
 * sat lai duoc hien tuong tuong tu (vi du nguoi dung bam lien tuc rat
 * nhanh), day se la noi dau tien can them lai 1 lop bao ve.
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

    /**
     * ✅ MOI (fix "nhac/vocal nho xiu, khong tu phuc hoi sau khi YouTube
     * seek/doi bai/bo quang cao"): tao lai AudioTrack tu dau (stop() roi
     * start() lai), giu nguyen "usage" da cau hinh tu constructor.
     *
     * Goi ham nay khi co dau hieu OS/OEM da ap 1 trang thai "duck" (giam
     * gain noi bo, KHONG doi index/mute-flag) len AudioTrack hien tai - da
     * xac nhan qua test thuc te rang CHI co hanh dong tao AudioTrack MOI
     * (nhu khi nguoi dung tat/bat Mixer Test bang tay) moi xoa sach duoc
     * trang thai nay, con ep lai volume/mute qua AudioManager (xem
     * PlaybackCaptureService.reassertStreamSystemVolumeIfMixerRunning())
     * KHONG dong toi duoc.
     *
     * ⚠️ Se co 1 khoang ngat tieng rat ngan (thuong vai chuc ms, do
     * release() + tao AudioTrack moi + play() lai) - chap nhan duoc vi muc
     * tieu la tranh doan im/nho keo dai nhieu giay nhu hien tai, khong phai
     * loai bo hoan toan moi gian doan.
     */
    fun recreate() {
        logBoth("🔄 [Recreate] Dang tao lai AudioTrack de xoa trang thai duck con sot (usage=$usage)...")
        stop()
        start()
    }

    /**
     * ✅ MOI (fix NHE NHANG HON recreate() - da xac nhan qua test thuc te
     * that: recreate() gay ra bug NANG HON nhieu - ca phien MediaProjection
     * bi chinh Android thu hoi tren driver Honor, khi pha huy/tao lai
     * AudioTrack trong luc AudioRecord dua tren MediaProjection dang chay
     * song song).
     *
     * Bang chung MOI quan trong: nguoi dung xac nhan CHI CAN 1 THONG BAO
     * BAT KY (vi du Facebook) toi la nhac TU DONG to lai binh thuong, KHONG
     * can lam gi ca. Dieu nay chung minh: chi CAN 1 luong am thanh MOI xuat
     * hien (bat ky, khong lien quan gi den AudioTrack cua chinh chung ta)
     * la du de AudioFlinger tinh toan lai (re-mix) toan bo cac luong dang
     * phat va xoa trang thai "duck" con sot - KHONG can pha huy/tao lai
     * chinh AudioTrack dang bi anh huong.
     *
     * Ham nay MO PHONG dung hieu ung do: tu tao ra 1 "thong bao gia" - phat
     * 1 doan PCM CUC NGAN (~60ms) va GAN NHU IM LANG (bien do =1, khong du
     * to de tai nguoi nghe thay) tren 1 AudioTrack RIENG BIET, usage=
     * USAGE_NOTIFICATION (dung loai stream ma thong bao that su dung) -
     * kich hoat dung co che AudioFlinger da quan sat duoc, MA KHONG dung gi
     * toi AudioTrack chinh (audioTrack o tren) hay AudioRecord/MediaProjection
     * dang chay o noi khac.
     *
     * ✅ CAP NHAT MOI NHAT (bo cooldown theo yeu cau - CHI con nut bam tay
     * duoc phep goi ham nay): truoc day co 1 lop cooldown MIN_NUDGE_INTERVAL_MS
     * (5000ms) o dau ham, can thiet vi luc do CO NHIEU nguon TU DONG cung goi
     * ham nay (periodicNudgeJob moi 1.5s, selfHealJob moi khi mat AudioFocus)
     * - can chan chong lop duck len chinh no. Cac nhanh tu dong do DA BI GO BO
     * HOAN TOAN (xem PlaybackCaptureService.kt) - nudge GIO CHI con duoc kich
     * hoat qua 2 nut BAM TAY (nut noi tren man hinh / nut action tren
     * notification), tan suat goi hoan toan do TOC DO NGON TAY nguoi dung
     * quyet dinh, khong con nguy co "vong lap tu goi minh" nua nen KHONG con
     * can cooldown - moi lan bam la 1 lan phat am THAT SU, khong bi am tham
     * bo qua nua.
     */
    fun nudgeAudioMixerToClearDuck() {
        try {
            // ✅ CAP NHAT (chan doan vong 2 - nudge ban dau khong hieu qua):
            // buffer truoc day dung 1 GIA TRI HANG SO khong doi (vd toan bo
            // la "1") - day thuc chat la tin hieu GAN GIONG DC (khong doi
            // theo thoi gian). Phan cung am thanh THUONG LOC BO tin hieu DC
            // (tu nhien khong the phat duoc qua loa/tai nghe thuc su, bi
            // capacitor/high-pass loc mat), nen he thong co the coi day la
            // "khong co gi de phat" va BO QUA hoan toan, khong kich hoat
            // duoc hieu ung re-mix nhu 1 tieng "ting" thong bao THAT (co tan
            // so, thay doi lien tuc theo thoi gian).
            //
            // Sua (TAM THOI o muc NGHE DUOC ro rang de kiem chung - se giam
            // lai sau khi xac nhan huong dung): tao 1 SONG SINE THAT (co tan
            // so AMPLITUDE_FOR_DIAGNOSTIC Hz), co fade-in/fade-out ngan de
            // tranh tieng "click" do doi bien do dot ngot o dau/cuoi buffer.
            val durationMs = 200
            val sampleCount = SAMPLE_RATE * durationMs / 1000
            val toneFrequencyHz = 900.0
            // ⚠️ CHAN DOAN: dat o muc NGHE RO (khong con "gan-im-lang" nhu
            // truoc) - chap nhan nghe 1 tieng "beep" ngan khi test lan nay.
            // Neu xac nhan huong nay dung, se giam dan bien do o lan sua
            // tiep theo de tim muc nho nhat van con hieu qua.
            val amplitude = 3000
            val fadeSamples = (sampleCount * 0.1).toInt().coerceAtLeast(1)
            val toneBuffer = ShortArray(sampleCount) { i ->
                val angle = 2.0 * Math.PI * toneFrequencyHz * i / SAMPLE_RATE
                var sample = amplitude * kotlin.math.sin(angle)
                // Fade-in/fade-out tuyen tinh o 10% dau/cuoi - tranh click.
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

            // ⚠️ SUA LOI GOC (chan doan vong 3 - phat hien qua log thuc te
            // "state=2" moi lan goi, DU code sine wave o tren hoan toan dung):
            // truoc day check nudgeTrack.state NGAY SAU build(), TRUOC ca
            // khi write() du lieu. Voi MODE_STATIC, AudioTrack CHI chuyen
            // sang STATE_INITIALIZED SAU KHI da write() du du lieu tinh vao
            // track - truoc do (ke ca khi build() hoan toan thanh cong,
            // khong loi gi), getState() LUON tra ve STATE_NO_STATIC_DATA
            // (=2). Nghia la nhanh loi nay LUON bi kich hoat vo dieu kien,
            // ham return SOM truoc ca khi write()/play() duoc goi - toan bo
            // cac lan nudge truoc day (kem ca ban sine wave o tren) CHUA HE
            // THUC SU PHAT RA AM THANH LAN NAO, khong lien quan gi den noi
            // dung tin hieu (DC hay sine) nhu nghi van truoc do.
            //
            // Sua: goi write() TRUOC, roi moi kiem tra state (luc nay da
            // dung nghia la "thanh cong" hay "that bai that su").
            val written = nudgeTrack.write(toneBuffer, 0, toneBuffer.size)
            if (written < 0) {
                logBoth("❌ [NudgeMixer] nudgeTrack.write() loi, ma loi=$written", isError = true)
                nudgeTrack.release()
                return
            }
            if (nudgeTrack.state != AudioTrack.STATE_INITIALIZED) {
                logBoth("❌ [NudgeMixer] nudgeTrack khoi tao that bai SAU write(), state=${nudgeTrack.state}", isError = true)
                nudgeTrack.release()
                return
            }

            nudgeTrack.play()
            logBoth(
                "🔔 [NudgeMixer] [CHAN DOAN] Da phat 1 tieng beep NGHE RO (${durationMs}ms, " +
                    "${toneFrequencyHz}Hz, amplitude=$amplitude, usage=NOTIFICATION) de kiem chung " +
                    "gia thuyet 'can tin hieu that, khong phai DC' - se giam am luong lai sau khi xac nhan."
            )

            // Giai phong nudgeTrack sau khi phat xong (+ margin an toan) -
            // dung Handler.postDelayed (khong can coroutine/scope rieng cho
            // 1 tac vu 1-lan, ngan han nhu the nay).
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    nudgeTrack.stop()
                    nudgeTrack.release()
                } catch (e: Exception) {
                    logBoth("⚠️ [NudgeMixer] Loi khi giai phong nudgeTrack (khong nghiem trong): ${e.message}")
                }
            }, (durationMs + 150).toLong())
        } catch (e: Exception) {
            logBoth("❌ [NudgeMixer] Loi khi tao/phat am nudge: ${e.message}", isError = true)
        }
    }
}