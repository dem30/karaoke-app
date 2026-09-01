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
 * ve max ngay. Ket hop voi OutputRouter.requestAudioFocus() (xem
 * OutputRouter.kt) de chan tu goc, con day la lop phong thu bo sung.
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

    // ✅ MOI (Phase 3 - production): luu muc goc cua 2 stream de mute/boost
    // tu dong khi Mixer Test chay, khoi phuc khi tat - tranh nghe DOI
    // (YouTube phat truc tiep + mixer phat lai) va tranh mixer nghe qua nho
    // vi STREAM_SYSTEM mac dinh thap. Gia tri -1 nghia la "khong co gi dang
    // can khoi phuc" - dung lam guard chong khoi phuc 2 lan, VA dung lam co
    // de biet Mixer Test co dang chay hay khong trong
    // reassertStreamSystemVolumeIfMixerRunning().
    private var savedStreamMusicVolume = -1
    private var savedStreamSystemVolume = -1

    // ✅ MOI (fix "nhac nho dan qua Bluetooth" - nang cap tan suat kiem tra):
    // truoc day chi kiem tra/ep lai STREAM_SYSTEM 1 lan/giay (an theo tick
    // notification co san) - qua cham de bat kip AVRCP volume resync cua
    // loa Bluetooth (nghi van chinh, xem giai thich trong OutputRouter.kt).
    // Doi sang vong lap RIENG, chay ~300ms/lan, chi hoat dong trong luc
    // Mixer Test dang bat (start/stop cung luc voi startMixerTestInternal/
    // stopMixerTestInternal). serviceScope dung Dispatchers.Default vi day
    // chi la vong kiem tra volume don gian, khong can UI thread.
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var volumeGuardJob: Job? = null

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

        @Volatile
        private var capturingActive = false

        fun isCapturing(): Boolean = capturingActive
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[Service] $msg")
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
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedStreamMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        logBoth("🔇 Da mute STREAM_MUSIC (muc goc=$savedStreamMusicVolume) - YouTube se im, chi con mixer phat ra loa.")

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

        // ✅ MOI: dung vong lap guard volume TRUOC khi don dep gi khac.
        volumeGuardJob?.cancel()
        volumeGuardJob = null

        micInput?.stopCapture()
        micInput = null
        mixer?.stop()
        mixer = null
        mixerOutputRouter?.stop()
        mixerOutputRouter = null
        vocalLimiter?.reset()
        vocalLimiter = null

        // ✅ MOI: khoi phuc ca 2 stream da mute/boost o startMixerTestInternal() -
        // guard bang >= 0 de tranh khoi phuc 2 lan neu ham nay bi goi lai (vd tu
        // stopCurrentSessionIfAny() VA onDestroy() lien tiep).
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (savedStreamMusicVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedStreamMusicVolume, 0)
            logBoth("Da khoi phuc STREAM_MUSIC ve muc goc=$savedStreamMusicVolume")
            savedStreamMusicVolume = -1
        }
        if (savedStreamSystemVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, savedStreamSystemVolume, 0)
            logBoth("Da khoi phuc STREAM_SYSTEM ve muc goc=$savedStreamSystemVolume")
            savedStreamSystemVolume = -1
        }

        logBoth("🛑 Da dung Mixer Test (Phase 3). MusicInput (Phase 1) khong bi anh huong, van tiep tuc chay.")
    }

    /**
     * ✅ MOI: goi moi giay (tu onAmplitudeTick) trong luc Mixer Test dang
     * chay - kiem tra STREAM_SYSTEM hien tai, neu thap hon max thi log CANH
     * BAO (bang chung co yeu to ben ngoai dang ha volume xuong ngoai y muon
     * cua app, vi du audio focus ducking khi chuyen app) roi ep lai ve max.
     * Khong lam gi neu Mixer Test dang TAT (savedStreamSystemVolume == -1,
     * nghia la khong co gi dang can giu o muc boost ca).
     */
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
     * setStreamVolume() KHONG chac chan go duoc co mute nay tren moi OEM -
     * day la ly do truoc day nguoi dung PHAI vao Settings > Am bao (Notification)
     * bam lai 1 cai de "kich hoat" - thao tac do vo tinh goi toi co che unmute
     * cua UI he thong ma code chua lam.
     * Sua: goi THEM adjustStreamVolume(..., ADJUST_UNMUTE, ...) - day la API
     * chinh thuc cua Android de go co mute, TACH BIET voi setStreamVolume().
     * Goi ca 2 (unmute + ep lai max) MOI LAN vong lap chay (khong con dieu
     * kien "current < max" nua), de dam bao du roi vao truong hop nao (tut
     * so, hay chi bi mute flag ma so van 15/15) cung duoc xu ly.
     */
    private fun reassertStreamSystemVolumeIfMixerRunning() {
        if (savedStreamSystemVolume < 0) return // Mixer Test dang tat, khong lien quan.
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)
        val isMuted = audioManager.isStreamMute(AudioManager.STREAM_SYSTEM)

        // ✅ MOI (debug tam thoi - CHUA xac dinh duoc nguyen nhan that qua
        // suy luan tinh, can quan sat TRUC TIEP tai dung thoi diem chuyen
        // app): log MOI LAN chay (khong chi khi bat thuong) de bat duoc bien
        // dong tuc thoi (transient) co the da tu phuc hoi truoc khi lan poll
        // tiep theo (300ms sau) kiem tra lai - neu chi log luc bat thuong,
        // se BO LO chinh khoanh khac gay ra hien tuong "nghe nho".
        logBoth("[GuardTick] STREAM_SYSTEM current=$current/$max, isMuted=$isMuted")

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
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, max, 0)
        }
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