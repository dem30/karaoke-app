package com.karaokeapp.audio.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.karaokeapp.audio.mic.MicInput
import com.karaokeapp.audio.mixer.LowLatencyMixer
import com.karaokeapp.audio.output.OutputRouter
import com.karaokeapp.audio.processor.Limiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Foreground service bat buoc de dung MediaProjection cho AudioPlaybackCapture.
 *
 * ✅ CAP NHAT (Phase 3): them kha nang bat/tat "mixer test" - chay them
 * MicInput + LowLatencyMixer + OutputRouter NGAY TRONG service nay (khong
 * phai trong Activity nhu Mic Loopback rieng le cua Phase 2), vi day moi la
 * kien truc dung voi muc tieu cuoi: toan bo pipeline xu ly am thanh song
 * ben vung trong foreground service, khong phu thuoc Activity con song hay
 * khong - dung tinh than da xac lap tu Phase 1 (WakeLock + onTaskRemoved).
 *
 * ✅ CAP NHAT (Phase 3 - production, thay the nut "Test Mute STREAM_MUSIC" +
 * "Usage test" thu cong o MainActivity bang co che TU DONG): khi bat Mixer
 * Test, service se:
 *   1. Tu mute STREAM_MUSIC - da xac nhan qua test thu cong truoc do rang
 *      AudioPlaybackCapture bat tin hieu TRUOC buoc ap volume he thong, nen
 *      mute STREAM_MUSIC KHONG lam mat tin hieu MusicInput, chi lam YouTube
 *      het tu phat truc tiep ra loa (tranh nghe DOI: vua YouTube goc vua
 *      mixer).
 *   2. Phat mixer qua usage=ASSISTANCE_SONIFICATION (STREAM_SYSTEM) thay vi
 *      USAGE_MEDIA mac dinh - vi STREAM_SYSTEM doc lap voi STREAM_MUSIC vua
 *      mute o buoc 1 nen mixer khong bi im theo. Da test A/B qua Mic
 *      Loopback (MainActivity): latency ngang MEDIA (~296-300ms ca 2), an
 *      toan hon USAGE_VOICE_COMMUNICATION (khong co rui ro bi ep sang
 *      Bluetooth SCO mono) va tot hon USAGE_ALARM (latency cao hon ~100ms +
 *      co dau hieu de hu/feedback vi STREAM_ALARM luon o muc max).
 *   3. Tam day STREAM_SYSTEM len max (vi mac dinh thuong thap, khong phai do
 *      nguoi dung tung chinh tay) de mixer nghe du to, tuong tu bat buoc voi
 *      MEDIA truoc day.
 * Ca 2 stream duoc khoi phuc ve muc goc khi tat Mixer Test (hoac khi service
 * bi huy dot ngot, qua stopCurrentSessionIfAny() -> stopMixerTestInternal()).
 *
 * ✅ CAP NHAT (fix nghi van "nhac tu nho dan khi chuyen app qua lai" - phat
 * hien qua test thuc te): them reassertStreamSystemVolumeIfMixerRunning(),
 * goi moi giay tu onAmplitudeTick (tick co san tu Phase 1) trong luc Mixer
 * Test dang chay - kiem tra STREAM_SYSTEM co bi tut duoi max khong (co the
 * do audio focus ducking tu he thong/app khac khi chuyen app, vi truoc day
 * OutputRouter chua he xin AudioFocus), neu co thi log canh bao va ep lai
 * ve max ngay. Ket hop voi FocusObserver (xem duoi) de chan tu goc, con day
 * la lop phong thu bo sung.
 *
 * ✅ CAP NHAT MOI (fix "FocusObserver chi bat duoc 1 lan roi 'diec'"): ban
 * truoc xin AudioFocus kieu GAIN_TRANSIENT_MAY_DUCK CHI 1 LAN luc bat dau
 * Mixer Test, khong bao gio xin lai. Theo dung ngu nghia Android, sau khi
 * nhan AUDIOFOCUS_LOSS (khong phai ban _TRANSIENT), request goc coi nhu DA
 * BI HUY - muon nghe tiep cac lan mat focus SAU DO (vi du YouTube gianh lai
 * quyen nhieu lan lien tiep khi nguoi dung seek/doi bai/bo quang cao lien
 * tuc) BAT BUOC phai goi requestAudioFocus() lai. Thieu buoc nay khien log
 * chi hien dung 1 dong AUDIOFOCUS_LOSS duy nhat trong ca phien, dung nhu
 * nguoi dung da quan sat ("moi lan YouTube bi hanh dong gi la no danh lai
 * quyen" - xay ra NHIEU lan, nhung code cu chi ghi lai duoc LAN DAU).
 * Sua: tach viec dang ky ra ham rieng requestFocusObserver(), goi lai NGAY
 * trong chinh focusObserverListener moi khi nhan duoc bat ky loai LOSS nao -
 * dam bao listener "song" xuyen suot ca phien Mixer Test, bat duoc MOI lan
 * YouTube gianh quyen, khong chi lan dau.
 *
 * ✅ CAP NHAT MOI (tu dong "chua" trieu chung, thay vi chi quan sat): da xac
 * nhan qua test thu cong rang HANH DONG DUY NHAT chua duoc trieu chung
 * "nhac/vocal nho xiu khong tu phuc hoi" la TAO LAI AudioTrack tu dau (nhu
 * khi tat/bat Mixer Test bang tay) - ep lai volume/mute qua AudioManager
 * (reassertStreamSystemVolumeIfMixerRunning) KHONG dong toi duoc vi day la
 * 1 dang "duck" (gain noi bo tang OEM/HAL), doc lap voi index/mute-flag ma
 * AudioManager bao cao (xem giai thich chi tiet trong OutputRouter.kt,
 * ham recreate()). Moi lan focusObserverListener nhan duoc bat ky loai LOSS
 * nao, ngoai viec xin lai focus (o tren), con debounce ~400ms roi tu dong
 * goi mixerOutputRouter?.recreate() - mo phong dung thao tac tay da xac
 * nhan hieu qua, khong can nguoi dung phai tu tat/bat Mixer Test nua.
 *
 * Dieu khien qua 2 Intent action rieng (KHONG dung chung voi flow
 * resultCode/resultData chinh de tranh xung dot):
 * - ACTION_START_MIXER_TEST: bat dau tron Music (dang chay san) + Mic moi.
 * - ACTION_STOP_MIXER_TEST: dung mixer test, MusicInput van tiep tuc chay
 *   binh thuong (khong anh huong Phase 1).
 */
class PlaybackCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var musicInput: MusicInput? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ✅ MOI (Phase 3): 3 thanh phan cua mixer test, doc lap voi session
    // capture nhac chinh - co the bat/tat rieng ma khong lam gian doan
    // MusicInput dang chay.
    private var micInput: MicInput? = null
    private var mixer: LowLatencyMixer? = null
    private var mixerOutputRouter: OutputRouter? = null

    // ✅ MOI (fix "hu qua loa ngoai/Bluetooth" - vong lap phan hoi am hoc):
    // limiter RIENG ap cho VOCAL TRUOC KHI vao mixer, xem giai thich chi
    // tiet trong Limiter.kt. Day KHONG phai limiter cuoi chuoi cua PLAN.md
    // (do se lam sau, ap cho MIX TONG) - day la lop chan GOC vong lap hu,
    // can thiet NGAY vi test bang loa ngoai/Bluetooth dang bi hu that.
    private var vocalLimiter: Limiter? = null

    // ✅ MOI (Phase 3 - production): luu muc goc cua STREAM_SYSTEM de boost
    // tu dong khi Mixer Test chay, khoi phuc khi tat. Gia tri -1 nghia la
    // "khong co gi dang can khoi phuc" - dung lam guard chong khoi phuc 2
    // lan, VA dung lam co de biet Mixer Test co dang chay hay khong trong
    // reassertStreamSystemVolumeIfMixerRunning().
    private var savedStreamSystemVolume = -1

    // ✅ SUA (khong con la "chi quan sat" nua - xem giai thich chi tiet o
    // dau file): listener nay GIO se TU DONG xin lai focus + kich hoat
    // self-heal moi khi nhan duoc bat ky loai LOSS nao, KHONG con chi log
    // roi thoi. OutputRouter van HOAN TOAN KHONG tu xin focus doc quyen -
    // day chi la 1 client "quan sat + tu phuc hoi", khong tranh gianh phat
    // am thanh voi YouTube.
    private var focusObserverRequest: android.media.AudioFocusRequest? = null

    // ✅ MOI: debounce cho self-heal - tranh goi recreate() lien tuc neu co
    // nhieu su kien LOSS don don gan nhau (vi du YouTube giai phong + xin
    // lai focus nhieu lan rat nhanh trong 1 lan chuyen video) - chi thuc su
    // tao lai AudioTrack SAU KHI da yen ~400ms khong co su kien moi nao.
    private var selfHealJob: Job? = null

    private val focusObserverListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val label = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
            else -> "UNKNOWN($focusChange)"
        }
        logBoth("🎧 [FocusObserver] onAudioFocusChange=$label")

        val isLossEvent = focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK

        if (isLossEvent) {
            // ✅ 1. Xin lai NGAY de KHONG bi "diec" cho su kien tiep theo -
            // day la phan sua loi "one-shot" da giai thich o dau file. Chi
            // xin lai neu Mixer Test van dang chay that su (mixer != null) -
            // tranh xin lai vo ich neu nguoi dung vua tat Mixer Test dung
            // luc su kien LOSS nay toi.
            if (mixer != null) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                requestFocusObserver(audioManager)
            }

            // ✅ 2. Tu dong "chua" trieu chung: debounce ~400ms roi TAO LAI
            // AudioTrack cua mixer - mo phong dung thao tac tay "tat/bat
            // Mixer Test" da xac nhan chua duoc trieu chung "nhac/vocal nho
            // xiu khong tu phuc hoi". Huy job cu (neu co) truoc khi dat job
            // moi, tranh recreate() bi goi chong cheo nhieu lan lien tiep.
            selfHealJob?.cancel()
            selfHealJob = serviceScope.launch {
                delay(400L)
                logBoth(
                    "🩹 [SelfHeal] Tao lai AudioTrack cua mixer sau su kien " +
                        "mat focus ($label), de xoa moi trang thai duck con sot."
                )
                mixerOutputRouter?.recreate()
            }
        }
    }

    // ✅ MOI (fix goc "Karaoke App tu dat am luong Bluetooth = 0" - xac nhan
    // qua thong bao he thong that cua Android, thay vi doan bang log nua):
    // truoc day dung setStreamVolume(STREAM_MUSIC, 0, 0) - hanh dong nay
    // DOI INDEX cua STREAM_MUSIC, va STREAM_MUSIC la "stream chinh" Android
    // dung de dong bo AVRCP absolute-volume THUC SU gui cho loa Bluetooth -
    // BAT KE audio cua chinh app dang phat qua stream nao khac (o day la
    // STREAM_SYSTEM). Doi index ve 0 -> loa BT bi ha volume PHAN CUNG ve 0,
    // anh huong luon output cua chinh mixer.
    //
    // Sua: chuyen sang dung CO MUTE RIENG (adjustStreamVolume ADJUST_MUTE/
    // ADJUST_UNMUTE) thay vi doi index - day la co chế TACH BIET voi index,
    // hy vong (CHUA chac chan 100%, can kiem chung thuc te) khong kich hoat
    // dong bo AVRCP giong nhu khi doi index. 2 bien duoi day thay the hoan
    // toan cho savedStreamMusicVolume (khong con can luu/khoi phuc INDEX cu
    // nua vi khong con doi index).
    private var streamMusicWasMutedBeforeMixerTest = false
    private var musicMuteAppliedByMixerTest = false

    // ✅ MOI (fix "nhac nho dan qua Bluetooth" - nang cap tan suat kiem tra):
    // truoc day chi kiem tra/ep lai STREAM_SYSTEM 1 lan/giay (an theo tick
    // notification co san) - qua cham de bat kip AVRCP volume resync cua
    // loa Bluetooth (nghi van chinh, xem giai thich trong OutputRouter.kt).
    // Doi sang vong lap RIENG, chay ~300ms/lan, chi hoat dong trong luc
    // Mixer Test dang bat (start/stop cung luc voi startMixerTestInternal/
    // stopMixerTestInternal). serviceScope dung Dispatchers.Default vi day
    // chi la vong kiem tra volume don gian, khong can UI thread. Cung dung
    // chung scope nay cho selfHealJob o tren.
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var volumeGuardJob: Job? = null

    // ✅ MOI (giam log du thua): dem so lan vong lap guard da chay - dung de
    // CHI in dong trang thai "[GuardTick] ..." day du moi
    // GUARD_STATUS_LOG_EVERY_N_TICKS lan, thay vi MOI 300ms/lan (truoc day
    // ra ~200 dong/phut, lam log 500 dong (MAX_LINES cua CaptureLogBus) day
    // trong chua toi 3 phut va nguoi dung khong copy-paste noi de gui debug).
    // Viec EP volume/unmute (phan quan trong that su) VAN chay du moi 300ms
    // nhu cu - CHI co dong IN THONG TIN la bi gian cach, khong anh huong toc
    // do phan ung cua guard.
    private var guardTickCount = 0

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        private const val TAG = "PlaybackCaptureService"
        private const val CHANNEL_ID = "playback_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "KaraokeApp::PlaybackCaptureWakeLock"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val ACTION_START_MIXER_TEST = "com.karaokeapp.action.START_MIXER_TEST"
        const val ACTION_STOP_MIXER_TEST = "com.karaokeapp.action.STOP_MIXER_TEST"

        // ✅ MOI: guard loop chay 300ms/lan (giu nguyen, can nhanh de bat kip
        // AVRCP resync cua Bluetooth) nhung dong log trang thai day du chi in
        // 1 lan trong so N lan chay - 10 lan * 300ms = ~3s/dong, van du day
        // do phan giai de thay xu huong "nho dan qua thoi gian" nhung giam
        // ~10 lan so luong dong log so voi truoc.
        private const val GUARD_STATUS_LOG_EVERY_N_TICKS = 10

        // ✅ MOI (cong cu chan doan - CO THE TAT de cach ly nghi van "dang
        // gianh mute-flag STREAM_MUSIC voi YouTube la nguyen nhan/yeu to gay
        // mat tieng khi seek"). Mac dinh TRUE (giu nguyen hanh vi hien tai,
        // khong doi UX cua ban production).
        //
        // Cach doc ket qua sau khi build voi flag nay = true (mac dinh):
        // - Neu log cho thay CAPTURE SILENCE (MusicInput) xay ra ma KHONG co
        //   dong [AutoReassert] "STREAM_MUSIC bi GO MUTE FLAG ngoai y muon"
        //   nao xung quanh cung thoi diem -> mute-guard KHONG lien quan, co
        //   the giu flag nay = true va tap trung dieu tra huong khac
        //   (AudioPlaybackCapture/session cua YouTube).
        // - Neu CAPTURE SILENCE luon xay ra NGAY SAU/CUNG LUC voi dong
        //   [AutoReassert] do -> rat co the day la nguyen nhan hoac yeu to
        //   kich hoat chinh. Doi flag nay thanh false, build lai 1 lan nua
        //   (chap nhan YouTube phat de ra loa song song voi mixer trong ban
        //   build chan doan nay) de xac nhan: neu CAPTURE SILENCE bien mat
        //   hoan toan khi seek, da xac nhan chac chan.
        private const val ENABLE_MUSIC_STREAM_MUTE_GUARD = true

        @Volatile
        private var capturingActive = false

        fun isCapturing(): Boolean = capturingActive
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[Service] $msg")
    }

    /**
     * ✅ MOI (tach ra tu startMixerTestInternal() de dung lai duoc o CA 2
     * noi: luc bat dau Mixer Test LAN luc focusObserverListener tu xin lai
     * sau moi lan mat focus - xem giai thich chi tiet o dau file/khai bao
     * focusObserverListener).
     */
    private fun requestFocusObserver(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setOnAudioFocusChangeListener(focusObserverListener)
            .setWillPauseWhenDucked(false)
            .build()
        focusObserverRequest = request
        val result = audioManager.requestAudioFocus(request)
        logBoth("🎧 [FocusObserver] requestAudioFocus() tra ve=$result (khong dung de gianh phat doc quyen).")
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    logBoth("🔒 Da kich hoat WakeLock giu thuc CPU cho capture chay nen.")
                }
            }
        } catch (e: Exception) {
            logBoth("❌ Khong the acquire WakeLock: ${e.message}", isError = true)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    logBoth("🔓 Da giai phong WakeLock.")
                }
            }
        } catch (e: Exception) {
            logBoth("❌ Khong the release WakeLock: ${e.message}", isError = true)
        }
    }

    private fun stopCurrentSessionIfAny() {
        if (musicInput != null || mediaProjection != null) {
            logBoth("⚠️ Phat hien session capture cu con song - dung han truoc khi tao session moi.")
        }
        stopMixerTestInternal()
        musicInput?.stopCapture()
        musicInput = null
        mediaProjection?.stop()
        mediaProjection = null
        capturingActive = false
    }

    /** Phase 3: bat dau tron Music (dang chay) + Mic moi, phat ra qua OutputRouter rieng. */
    private fun startMixerTestInternal() {
        if (!capturingActive || musicInput == null) {
            logBoth("❌ Chua co MusicInput dang chay - phai bat capture nhac (Phase 1) truoc khi test mixer.", isError = true)
            return
        }
        if (mixer != null) {
            logBoth("⚠️ Mixer test da chay roi, bo qua.")
            return
        }

        // ✅ MOI: mute STREAM_MUSIC de YouTube khong tu phat ra loa song song voi
        // mixer nua - da xac nhan qua "Test Mute STREAM_MUSIC" (MainActivity)
        // rang MusicInput van capture binh thuong du STREAM_MUSIC = 0, vi capture
        // tap TRUOC buoc ap volume he thong.
        // ✅ SUA (fix goc "Karaoke App tu dat am luong Bluetooth = 0" - xem
        // giai thich chi tiet o khai bao streamMusicWasMutedBeforeMixerTest/
        // musicMuteAppliedByMixerTest phia tren): dung CO MUTE thay vi doi
        // INDEX ve 0, tranh kich hoat dong bo AVRCP volume=0 cho loa
        // Bluetooth. Van giu duoc muc dich goc: YouTube khong tu phat truc
        // tiep ra loa nua (capture van hoat dong binh thuong vi xay ra
        // TRUOC ca buoc ap volume LAN buoc ap mute flag).
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (ENABLE_MUSIC_STREAM_MUTE_GUARD) {
            streamMusicWasMutedBeforeMixerTest = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            if (!streamMusicWasMutedBeforeMixerTest) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
            musicMuteAppliedByMixerTest = true
            logBoth(
                "🔇 Da mute STREAM_MUSIC bang MUTE FLAG (KHONG doi so volume/index nua) - " +
                    "YouTube se im, chi con mixer phat ra loa. (da mute san tu truoc=" +
                    "$streamMusicWasMutedBeforeMixerTest)"
            )
        } else {
            logBoth(
                "🔬 [Chan doan] ENABLE_MUSIC_STREAM_MUTE_GUARD=false - KHONG mute STREAM_MUSIC. " +
                    "YouTube se phat song song voi mixer (nghe trung tieng) - CHI dung de cach ly " +
                    "nguyen nhan mat tieng khi seek, khong phai ban chay that."
            )
        }

        // ✅ MOI: usage=ASSISTANCE_SONIFICATION (STREAM_SYSTEM) cho output mixer -
        // da test latency ngang MEDIA (~296-300ms ca 2), khong dinh rui ro SCO
        // nhu VOICE_COMMUNICATION, khong hu/cham nhu ALARM (xem test truoc).
        // Doc lap voi STREAM_MUSIC vua mute o tren nen KHONG bi im theo.
        savedStreamSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
        val maxSystemVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)
        // ✅ MOI: go co mute an (xem giai thich chi tiet trong
        // reassertStreamSystemVolumeIfMixerRunning()) TRUOC khi ep volume len
        // max - phong truong hop co mute da ton tai tu truoc do (vi du con
        // sot lai tu lan Mixer Test truoc), tranh phai cho toi vong guard
        // dau tien (~300ms sau) moi duoc go.
        if (audioManager.isStreamMute(AudioManager.STREAM_SYSTEM)) {
            logBoth("⚠️ STREAM_SYSTEM dang bi mute flag ngay luc bat dau - go ngay bang ADJUST_UNMUTE.")
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        }
        audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, maxSystemVolume, 0)
        logBoth("🔊 Da day STREAM_SYSTEM len max=$maxSystemVolume (muc goc=$savedStreamSystemVolume) de mixer nghe du to.")

        // ✅ SUA: dang ky FocusObserver qua ham dung chung requestFocusObserver()
        // (xem khai bao ham + focusObserverListener phia tren) - GIO listener
        // nay KHONG con chi "quan sat" nua, ma se TU DONG xin lai focus + kich
        // hoat self-heal moi khi nhan LOSS, xuyen suot ca phien Mixer Test.
        requestFocusObserver(audioManager)

        val router = OutputRouter(this, AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).apply { start() }
        val mix = LowLatencyMixer(router).apply { start() }
        val mic = MicInput(this)

        // ✅ MOI: limiter rieng cho vocal, threshold 0.85 (85% Short.MAX_VALUE),
        // release 50ms - xem Limiter.kt de biet chi tiet vi sao dat o day
        // (chan goc re vong lap phan hoi, khong phai chi chong clipping don
        // thuan). Noise gate CHUA bat (giu null/mac dinh) - can nghe thu
        // truoc de biet muc nen that su cua moi truong test (phong, khoang
        // cach mic-loa) truoc khi chon nguong gate hop ly, tranh gate cat
        // nham tieng hat nho/thi tham.
        val limiter = Limiter(sampleRate = 44100, thresholdRatio = 0.85f, releaseMs = 50f)

        mixerOutputRouter = router
        mixer = mix
        micInput = mic
        vocalLimiter = limiter

        // ✅ Noi MusicInput hien tai vao mixer: vi MusicInput da duoc tao TU
        // TRUOC (o onStartCommand chinh) voi onPcmChunk da tro ve mixerRef
        // (xem ham buildMusicPcmForwarder() ben duoi) - khong can lam gi them
        // o day, chi can gan bien mixer o tren la MusicInput's callback se tu
        // dong bat dau day du lieu vao no.

        mic.startCapture(onPcmChunk = { buffer, size ->
            // ✅ MOI: limiter chay TRUOC khi PCM vao mixer - xem giai thich
            // o khai bao vocalLimiter/Limiter.kt. Vi tri nay QUAN TRONG: neu
            // dat limiter SAU mixer (chi o mix tong), no van cho phep vocal
            // rieng le tang bien do khong gioi han truoc khi bi cong voi
            // nhac - vong lap phan hoi van co the tu nuoi no o day truoc khi
            // limiter mix tong kip chan.
            limiter.process(buffer, size)
            mix.pushVocal(buffer, size)
        })

        // ✅ MOI: bat dau vong lap guard volume ~300ms/lan - xem giai thich
        // chi tiet o khai bao volumeGuardJob ben tren.
        guardTickCount = 0
        volumeGuardJob = serviceScope.launch {
            while (isActive) {
                reassertStreamSystemVolumeIfMixerRunning()
                delay(300L)
            }
        }

        logBoth("✅ Da bat dau Mixer Test (Phase 3) - YouTube da mute, chi nghe Music+Mic qua mixer.")
    }

    private fun stopMixerTestInternal() {
        if (mixer == null && micInput == null && mixerOutputRouter == null && vocalLimiter == null) return

        // ✅ MOI: dung vong lap guard volume + self-heal job TRUOC khi don
        // dep gi khac.
        volumeGuardJob?.cancel()
        volumeGuardJob = null
        selfHealJob?.cancel()
        selfHealJob = null

        // ✅ MOI: go dang ky listener quan sat/tu phuc hoi audio focus (xem
        // giai thich o dau file) - khong con can quan sat khi Mixer Test da
        // tat.
        focusObserverRequest?.let {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.abandonAudioFocusRequest(it)
        }
        focusObserverRequest = null

        micInput?.stopCapture()
        micInput = null
        mixer?.stop()
        mixer = null
        mixerOutputRouter?.stop()
        mixerOutputRouter = null
        vocalLimiter?.reset()
        vocalLimiter = null

        // ✅ SUA: khoi phuc STREAM_MUSIC bang co MUTE (khong con phuc hoi
        // INDEX nua vi khong con doi index) - guard bang
        // musicMuteAppliedByMixerTest de tranh xu ly 2 lan neu ham nay bi
        // goi lai (vd tu stopCurrentSessionIfAny() VA onDestroy() lien
        // tiep). CHI unmute neu STREAM_MUSIC KHONG bi mute san tu truoc khi
        // Mixer Test bat dau (tranh vo tinh unmute 1 trang thai nguoi dung
        // da tu chon tu truoc, khong lien quan gi den app).
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (musicMuteAppliedByMixerTest) {
            if (!streamMusicWasMutedBeforeMixerTest) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                logBoth("Da bo MUTE FLAG cua STREAM_MUSIC (khoi phuc trang thai truoc do - khong bi mute).")
            } else {
                logBoth("STREAM_MUSIC da bi mute TU TRUOC khi bat Mixer Test - giu nguyen, khong dong gi them.")
            }
            musicMuteAppliedByMixerTest = false
        }
        if (savedStreamSystemVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, savedStreamSystemVolume, 0)
            logBoth("Da khoi phuc STREAM_SYSTEM ve muc goc=$savedStreamSystemVolume")
            savedStreamSystemVolume = -1
        }

        logBoth("🛑 Da dung Mixer Test (Phase 3). MusicInput (Phase 1) khong bi anh huong, van tiep tuc chay.")
    }

    /**
     * ✅ CAP NHAT QUAN TRONG (fix "nhac nho dan khong the tu phuc hoi, phai
     * vao Settings bam lai" - xac dinh qua test thuc te voi log that): ban
     * truoc CHI kiem tra so volume (0..15) roi ep lai bang setStreamVolume()
     * - nhung Android co 2 co che TACH BIET nhau:
     *   1. "Volume level" (0..15) - day la thu setStreamVolume() dieu khien.
     *   2. "Mute flag" rieng (isStreamMute()) - mot co bat/tat DOC LAP voi
     *      so volume, co the bi BAT (vi du do he thong tu dat khi app mat
     *      focus tam thoi luc chuyen qua app khac roi quay lai) MA KHONG
     *      lam thay doi so volume (van bao la 15/15 - nhu da xac nhan qua
     *      log thuc te KHONG co dong [AutoReassert] nao ca, tuc "current <
     *      max" luon la false, nhung nguoi dung van nghe nho).
     * setStreamVolume() KHONG chac chan go duoc co mute nay tren moi OEM.
     * Sua: goi THEM adjustStreamVolume(..., ADJUST_UNMUTE, ...) - day la API
     * chinh thuc cua Android de go co mute, TACH BIET voi setStreamVolume().
     * Goi ca 2 (unmute + ep lai max) MOI LAN vong lap chay (khong con dieu
     * kien "current < max" nua), de dam bao du roi vao truong hop nao (tut
     * so, hay chi bi mute flag ma so van 15/15) cung duoc xu ly.
     *
     * ⚠️ LUU Y QUAN TRONG (van con gioi han da xac nhan qua test thuc te):
     * ham nay CHI xu ly duoc 2 co che "index" va "mute-flag" ma AudioManager
     * bao cao. No KHONG the phat hien hay sua duoc kieu "duck" (gain noi bo
     * tang OEM/HAL, ap khi phat hien co su kien audio focus canh tranh) - vi
     * duck KHONG doi index/mute-flag, GuardTick van bao 15/15 isMuted=false
     * binh thuong du dang bi nho thuc te. Loai loi nay duoc xu ly boi
     * focusObserverListener + OutputRouter.recreate() (xem dau file), KHONG
     * phai ham nay.
     */
    private fun reassertStreamSystemVolumeIfMixerRunning() {
        if (savedStreamSystemVolume < 0) return // Mixer Test dang tat, khong lien quan.
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)
        val isMuted = audioManager.isStreamMute(AudioManager.STREAM_SYSTEM)

        val musicCurrent = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val musicMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)

        // ✅ MOI (giam log du thua): dong trang thai day du nay CHI in moi
        // GUARD_STATUS_LOG_EVERY_N_TICKS lan (~3s/dong o 300ms/tick) - cac
        // dong [AutoReassert] canh bao ben duoi (thuc su co su kien bat
        // thuong xay ra) VAN in MOI LAN, khong bi anh huong boi throttle nay.
        guardTickCount++
        if (guardTickCount % GUARD_STATUS_LOG_EVERY_N_TICKS == 0) {
            logBoth(
                "[GuardTick] STREAM_SYSTEM current=$current/$max isMuted=$isMuted | " +
                    "STREAM_MUSIC current=$musicCurrent/$musicMax isMuted=$musicMuted"
            )
        }

        // ✅ Kiem tra CO MUTE cua STREAM_MUSIC co bi go mat khong (vi du
        // YouTube/he thong tu unmute khi phat bai moi). CHI xu ly khi Mixer
        // Test dang thuc su enforce mute (musicMuteAppliedByMixerTest=true).
        if (ENABLE_MUSIC_STREAM_MUTE_GUARD && musicMuteAppliedByMixerTest && !musicMuted) {
            logBoth(
                "⚠️ [AutoReassert] STREAM_MUSIC bi GO MUTE FLAG ngoai y muon " +
                    "(co the do YouTube tu unmute khi phat bai moi) - mute lai."
            )
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }

        if (isMuted) {
            logBoth(
                "⚠️ [AutoReassert] Phat hien STREAM_SYSTEM dang bi MUTE FLAG " +
                    "(doc lap voi so volume, hien tai=$current/$max) - goi ADJUST_UNMUTE de go."
            )
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        }

        if (current < max) {
            logBoth(
                "⚠️ [AutoReassert] Phat hien STREAM_SYSTEM bi tut con $current/$max " +
                    "(khong phai do app - co yeu to ben ngoai dang ha volume, vi du " +
                    "audio focus ducking khi chuyen app) - ep lai ve max."
            )
        }

        // ✅ Goi setStreamVolume() VO DIEU KIEN moi tick (khong gate theo
        // current<max) - buoc AudioService "day lai" gia tri AVRCP cho loa
        // BT dinh ky (~300ms/lan). Day la lenh nhe (khong dung
        // FLAG_PLAY_SOUND nen khong phat am bao/tieng click), an toan de
        // goi lien tuc.
        audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, max, 0)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ✅ MOI (Phase 3): xu ly 2 action rieng cho mixer test TRUOC, khong
        // dung chung nhanh xu ly resultCode/resultData ben duoi.
        when (intent?.action) {
            ACTION_START_MIXER_TEST -> {
                startMixerTestInternal()
                return START_NOT_STICKY
            }
            ACTION_STOP_MIXER_TEST -> {
                stopMixerTestInternal()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("Dang khoi dong..."))
        acquireWakeLock()

        logBoth(
            "Service started. intent=$intent, hasExtras=${intent?.extras != null}, " +
                "keys=${intent?.extras?.keySet()?.joinToString()}"
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        logBoth("resultCode doc duoc=$resultCode, resultData doc duoc=$resultData")

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            logBoth(
                "❌ Thieu resultCode/resultData, khong the tao MediaProjection. " +
                    "Neu day la lan restart sau khi tien trinh bi kill, day la gioi han " +
                    "khong the tranh cua Android - mo lai MainActivity de xin lai.",
                isError = true
            )
            stopSelf()
            return START_NOT_STICKY
        }

        stopCurrentSessionIfAny()

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)

        if (projection == null) {
            logBoth("❌ getMediaProjection() tra ve null", isError = true)
            stopSelf()
            return START_NOT_STICKY
        }

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                logBoth("MediaProjection.onStop() - he thong da thu hoi quyen capture")
                stopMixerTestInternal()
                musicInput?.stopCapture()
                musicInput = null
                capturingActive = false
                stopSelf()
            }
        }, null)

        mediaProjection = projection

        // ✅ MOI (fix nghi van "Dang khoi dong... bi ket vinh vien, khong co
        // amplitude nao"): truoc day khoi tao MusicInput + goi startCapture()
        // KHONG co try/catch, chay TRUC TIEP tren main thread cua
        // onStartCommand. AudioRecord.Builder().build() va
        // AudioPlaybackCaptureConfiguration.Builder().build() deu co the nem
        // exception dong bo (vi du IllegalStateException/UnsupportedOperationException
        // tuy OEM, hoac do mediaProjection chua san sang ngay sau khi dialog
        // cap quyen vua dong) - neu xay ra, tien trinh crash NGAY tai day,
        // TRUOC KHI startCapture() kip chay lan dau. Vi startForeground() da
        // hien thi "Dang khoi dong..." truoc do, notification bi dong bang
        // vinh vien tren thanh thong bao (tien trinh chet, khong con ai cap
        // nhat/xoa no nua) - dung hien tuong da quan sat qua anh chup man
        // hinh. Boc try/catch de: (1) khong crash ca tien trinh, (2) cap nhat
        // lai notification voi thong bao loi ro rang thay vi de no dong bang
        // im lang, (3) log day du de biet chinh xac buoc nao that bai.
        try {
            musicInput = MusicInput(
                mediaProjection = projection,
                onAmplitudeTick = { avgAmplitude ->
                    val now = timeFormat.format(java.util.Date())
                    updateNotification("Cap nhat luc $now - amplitude=$avgAmplitude")
                    // ✅ Luu y: KHONG con goi reassertStreamSystemVolumeIfMixerRunning()
                    // o day nua - da thay bang volumeGuardJob (vong lap rieng
                    // ~300ms/lan, xem startMixerTestInternal()) nhanh hon nhieu
                    // so voi tick 1 lan/giay nay, giup bat kip AVRCP volume
                    // resync cua Bluetooth tot hon.
                },
                onPcmChunk = { buffer, size ->
                    // ✅ Phase 3: neu mixer test dang chay, day PCM nhac vao. Neu
                    // chua bat mixer, mixer == null nen dong nay khong lam gi ca -
                    // khong anh huong Phase 1 khi chua test mixer.
                    mixer?.pushMusic(buffer, size)
                }
            ).apply { startCapture() }
            capturingActive = true
        } catch (e: Exception) {
            logBoth(
                "❌ Khoi tao MusicInput/startCapture() that bai: ${e.message} - " +
                    "dung lai session nay, KHONG de tien trinh crash im lang.",
                isError = true
            )
            updateNotification("Loi khoi dong capture: ${e.message}")
            musicInput = null
            capturingActive = false
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCurrentSessionIfAny()
        releaseWakeLock()
        logBoth("Service destroyed")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val stillCapturing = capturingActive
        logBoth("Task removed (app bi vuot xoa khoi da nhiem). Dang capture=$stillCapturing")

        if (stillCapturing) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification("Dang tiep tuc capture sau khi app bi vuot xoa..."))
                logBoth("✅ Da tai khang dinh foreground service ngay sau khi task bi vuot xoa, van tiep tuc capture.")
            } catch (e: Exception) {
                logBoth("❌ Loi khi tai khang dinh foreground service sau onTaskRemoved: ${e.message}", isError = true)
            }
        } else {
            logBoth("Khong co session capture dang chay - khong can lam gi them.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Karaoke - Test Capture Nhac",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Karaoke App - Phase 1")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}