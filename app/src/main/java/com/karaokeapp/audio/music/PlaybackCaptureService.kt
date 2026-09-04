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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.karaokeapp.MainActivity
import com.karaokeapp.audio.mic.MicInput
import com.karaokeapp.audio.mixer.LowLatencyMixer
import com.karaokeapp.audio.output.OutputRouter
import com.karaokeapp.audio.processor.Limiter
import com.karaokeapp.audio.processor.VocalProcessor
import com.karaokeapp.audio.processor.Compressor
import com.karaokeapp.audio.processor.EchoReverb
import com.karaokeapp.overlay.MixerToggleOverlayButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Foreground service quan ly toan bo pipeline: capture nhac (Phase 1),
 * Mixer Test (Music + Mic -> LowLatencyMixer -> OutputRouter, Phase 3),
 * va co che tu phuc hoi am luong khi bat lai Mixer Test.
 *
 * Co che mute STREAM_MUSIC + phat mixer qua STREAM_SYSTEM (usage
 * ASSISTANCE_SONIFICATION) da duoc xac nhan qua test thuc te: capture tap
 * PCM TRUOC buoc ap volume he thong nen mute khong lam mat tin hieu capture.
 *
 * volumeGuardJob (~300ms/lan) chi xu ly duoc muc "index"/"mute-flag" ma
 * AudioManager bao cao - KHONG xu ly duoc "duck" noi bo o tang HAL/OEM (gain
 * giam nhung index/mute-flag van bao binh thuong). Duck CHI duoc xoa khi co
 * 1 su kien chuyen foreground THAT xay ra (da xac nhan qua test thuc te:
 * roi YouTube - vi du ve app karaoke - roi quay lai, moi cuu duoc am luong;
 * cac bien phap "gia lap" tu Service dang chay nen - beep, recreate() goi
 * truc tiep - deu KHONG du).
 *
 * ✅ MOI (quy trinh "Bat lai Mixer Test qua nut noi"): khi nguoi dung bam nut
 * noi de BAT LAI (dang o trang thai TAT, tuc ho da tu bam TAT truoc do vi ly
 * do rieng - nghe dien thoai, tam dung...), thay vi bat Mixer Test NGAY TAI
 * CHO (van dang dung trong YouTube - KHONG tao ra duoc su kien chuyen
 * foreground that, dan den am luong khong on dinh nhu da quan sat), Service
 * se chay 1 chuoi TU DONG mo phong DUNG THAO TAC TAY da xac nhan hieu qua
 * (dua app len foreground, TAT, BAT LAI, roi quay ve app nguon) - KHONG chi
 * goi startMixerTestInternal() 1 lan don gian:
 *   1. Dua MainActivity len foreground (FLAG_ACTIVITY_REORDER_TO_FRONT -
 *      dung Activity DA TON TAI san trong task, KHONG tao Activity/task moi,
 *      tranh dung toi vong doi cua MediaProjection dang song trong Service
 *      nay - bai hoc rut ra tu lan thu truoc voi 1 Activity rieng biet gay
 *      dut MediaProjection).
 *   2. Cho tin hieu MainActivity.onResume() THAT SU chay (xem
 *      onResumedCallback), roi doi them AUDIO_FOREGROUND_SETTLE_DELAY_MS de
 *      tang AudioPolicy/AudioFlinger kip xu ly xong viec chuyen foreground.
 *   3. Goi stopMixerTestInternal() (buoc "tat" - an toan/idempotent du dang
 *      thuc su tat hay khong).
 *   4. Doi OFF_ON_CYCLE_DELAY_MS.
 *   5. Goi startMixerTestInternal() that su (buoc "bat lai").
 *   6. Doi REACTIVATION_STEP_DELAY_MS roi tu mo lai app nguon (YouTube).
 * Nut BAM DE TAT (dang o trang thai BAT) van GIU NGUYEN hanh vi don gian,
 * tuc thi nhu truoc gio - KHONG doi gi ca, vi nguoi dung co the dang can
 * tat gap (nghe dien thoai...).
 */
class PlaybackCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var musicInput: MusicInput? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var micInput: MicInput? = null
    private var mixer: LowLatencyMixer? = null
    private var mixerOutputRouter: OutputRouter? = null
    private var mixerToggleOverlay: MixerToggleOverlayButton? = null
    private var vocalLimiter: Limiter? = null
    // ✅ MOI (Phase 4 - phan con lai): 3 khoi xu ly vocal con thieu, chay
    // NGAY SAU vocalLimiter (KHONG thay the vi tri cua no) - xem giai thich
    // day du o startMixerTestInternal().
    private var vocalProcessor: VocalProcessor? = null
    private var vocalCompressor: Compressor? = null
    private var vocalEcho: EchoReverb? = null
    // ✅ MOI: Limiter THU HAI, doc lap voi vocalLimiter - ap dung cho MIX
    // TONG (nhac + vocal) SAU khi LowLatencyMixer da cong 2 nguon, dung vi
    // tri ma PLAN.md goc va comment dau Limiter.kt da du tinh tu dau ("dung
    // chung 1 class cho 2 vi tri") nhung truoc gio chua ai noi vi tri thu 2
    // nay vao. Ly do can CA HAI (khong chi 1): vocalLimiter chan feedback
    // AM HOC tu som (truoc khi EQ/Compressor/Echo kip khuyech dai them);
    // finalMixLimiter chan CLIPPING SO khi nhac + vocal (da qua Compressor
    // makeup-gain va Echo feedback) cong lai co the vuot nguong dù tung
    // nguon rieng le van on.
    private var finalMixLimiter: Limiter? = null

    private var savedStreamSystemVolume = -1

    private var focusObserverRequest: android.media.AudioFocusRequest? = null

    // ✅ MOI: dung de huy chuoi "Bat lai qua nut noi" dang cho dang delay
    // (postDelayed) neu nguoi dung bam TAT giua chung luc chuoi dang chay -
    // tranh chuoi cu (dang cho) tiep tuc chay ngam va tu y bat lai Mixer Test
    // sau khi nguoi dung da chu dong tat.
    private val reactivationHandler = Handler(Looper.getMainLooper())
    private var reactivationRunnable: Runnable? = null

    private val focusObserverListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val label = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
            else -> "UNKNOWN($focusChange)"
        }
        logBoth("🎧 [FocusObserver] onAudioFocusChange=$label (chi ghi log).")

        val isLossEvent = focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK

        if (isLossEvent && mixer != null) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            requestFocusObserver(audioManager)
        }
    }

    private var streamMusicWasMutedBeforeMixerTest = false
    private var musicMuteAppliedByMixerTest = false

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var volumeGuardJob: Job? = null
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

        private const val GUARD_STATUS_LOG_EVERY_N_TICKS = 10
        private const val ENABLE_MUSIC_STREAM_MUTE_GUARD = true

        // ✅ MOI: thoi gian cho o buoc 2->3 (bat Mixer Test xong -> mo lai
        // YouTube) cua chuoi "Bat lai qua nut noi" - xem giai thich chi tiet
        // o dau file. Co the can chinh lai sau khi test thuc te tren thiet
        // bi that. (Buoc 1->2, tuc dua MainActivity len foreground -> bat
        // Mixer Test, KHONG con dung gia tri co dinh nay nua - xem
        // FALLBACK_TIMEOUT_MS va onResumedCallback trong MainActivity.kt.)
        private const val REACTIVATION_STEP_DELAY_MS = 1000L

        // ✅ MOI: thoi gian cho TOI DA cho tin hieu MainActivity.onResume()
        // THAT SU chay - neu qua lau khong thay (vi du thiet bi qua cham,
        // hoac loi khong ro), van tiep tuc chay buoc bat Mixer Test thay vi
        // treo chuoi vinh vien. Dat cao hon nhieu so voi
        // REACTIVATION_STEP_DELAY_MS cu (1s) vi day la GIOI HAN TREN cho
        // truong hop xau nhat, khong phai thoi gian cho binh thuong (binh
        // thuong callback se toi RAT nhanh, thuong duoi 300-500ms).
        private const val FALLBACK_TIMEOUT_MS = 3000L

        // ✅ MOI (thu nghiem fix "bat xong nhung van im lang, phai tu tay
        // tat/bat lai moi co tieng"): onResume() cua MainActivity CHI la tin
        // hieu o TANG UI/lifecycle rang Activity da resumed - no KHONG dam
        // bao tang AudioPolicy/AudioFlinger cua he thong da xu ly XONG viec
        // danh gia lai "app nao dang la foreground" tai chinh xac thoi diem
        // do (2 tang nay hoat dong doc lap, khong dong bo cung nhau). Da
        // quan sat thuc te: startMixerTestInternal() chay ngay sau tin hieu
        // onResume() (0ms doi them) doi luc "thanh cong" (khong loi, log
        // dung thu tu) nhung VAN im lang, trong khi lam lai y het thao tac
        // do THU CONG (co khoang nghi tu nhien giua cac buoc) lai luon hieu
        // qua. Them 1 khoang dem NHO sau khi co tin hieu onResume() that (ca
        // 2 duong: callback that VA fallback timeout) truoc khi thuc su goi
        // startMixerTestInternal(), de tang co them chut thoi gian "on dinh"
        // truoc khi mixer bat dau day am thanh ra. Gia tri ban dau chon thu
        // nghiem - co the can chinh lai (tang/giam) sau khi test tren thiet
        // bi that.
        private const val AUDIO_FOREGROUND_SETTLE_DELAY_MS = 500L

        // ✅ MOI: khoang doi giua buoc "tat" va buoc "bat lai" trong chuoi tu
        // dong (xem beginOverlayReactivationSequence()) - mo phong dung
        // khoang nghi tu nhien giua 2 lan bam tay that su (tat, roi bat lai)
        // ma nguoi dung da xac nhan hieu qua. Neu 2 buoc nay chay qua sat
        // nhau (0ms) co the khong du thoi gian de he thong xu ly xong buoc
        // "tat" (giai phong AudioTrack/OutputRouter cu, khoi phuc volume...)
        // truoc khi buoc "bat" tao lai moi thu tu dau.
        private const val OFF_ON_CYCLE_DELAY_MS = 400L

        // ✅ MOI (fix "quay lai YouTube nhung van nho/im" - xac nhan qua log
        // thuc te ngay 03/09: MusicInput/LowLatencyMixer van bat duoc PCM
        // BINH THUONG (amplitude ~3500, gan bang truoc luc toggle) NGAY SAU
        // khi returnToSourceApp() thanh cong - nghia la KHONG phai loi capture/
        // mix, ma la loi o TANG SAU CUNG (AudioTrack/HAL) - dung LOAI "duck
        // HAL/OEM an hinh" da duoc ghi chi tiet trong OutputRouter.kt
        // (recreate()/nudgeAudioMixerToClearDuck()): duck loai nay KHONG hien
        // qua AudioManager (GuardTick van bao current=15/15, isMuted=false
        // trong suot qua trinh, nhung am thanh THAT SU phat ra van bi giam).
        //
        // Nghi van cu the: buoc 'bat lai' (tao AudioTrack MOI, sach) hien dang
        // chay TRUOC khi returnToSourceApp() dua YouTube tro lai foreground -
        // neu CHINH hanh dong dua YouTube (dang la app nguon THAT SU, khac
        // voi truong hop bam tu 1 app khac) tro lai foreground la thu KICH
        // HOAT lai duck o tang HAL/OEM, thi AudioTrack MOI vua tao lai bi duck
        // NGAY SAU DO - va khong con buoc nao don no nua. Them buoc 4: sau khi
        // returnToSourceApp() thanh cong, doi 1 khoang de he thong on dinh xong
        // viec chuyen foreground, roi goi nudgeAudioMixerToClearDuck() (KHONG
        // dung recreate() - da ghi chu trong OutputRouter.kt rang recreate()
        // tung gay thu hoi ca MediaProjection tren driver Honor, nudge la lua
        // chon AN TOAN hon da duoc kiem chung).
        private const val NUDGE_AFTER_RETURN_DELAY_MS = 600L

        // ✅ SUA LOI GOC (xac nhan qua log thuc te: getLaunchIntentForPackage
        // ("com.google.android.youtube") tra ve null tren may cua nguoi
        // dung - nghia la app YouTube GOC KHONG duoc cai, nguoi dung dang
        // xem qua trinh duyet thay vi app rieng): truoc day hardcode DUY
        // NHAT 1 package YouTube goc - neu package do khong ton tai tren
        // may, returnToSourceApp() se KHONG mo duoc gi ca, dan den chuoi
        // "Bat lai" chay het cac buoc am thanh (tat/bat mixer) nhung KHONG
        // BAO GIO thuc su tao ra su kien chuyen foreground THAT can thiet
        // de xoa duck/mute - day moi la ly do goc khien "van nho tieng, chi
        // bam tay quay lai YouTube (qua Chrome) moi to" nhu quan sat duoc.
        //
        // GIO thay bang 1 DANH SACH candidate, thu lan luot theo thu tu uu
        // tien - dung candidate DAU TIEN resolve duoc launch intent hop le.
        //
        // ✅ SUA LOI THU TU (xac nhan truc tiep tu nguoi dung: dang dung APP
        // YOUTUBE GOC, KHONG dung Chrome de xem): ban truoc xep Chrome len
        // uu tien 1 dua tren suy doan sai tu comment cu ("bang chung tu
        // log") - suy doan do dua tren viec getLaunchIntentForPackage(youtube)
        // tra ve null, nhung nguyen nhan THAT SU cua null do la thieu khai
        // bao <queries> trong AndroidManifest.xml (Android 11+ package
        // visibility), KHONG phai vi nguoi dung dang dung Chrome. Sau khi
        // AndroidManifest.xml da khai bao <queries> cho CA HAI package, neu
        // Chrome van dung uu tien 1, va Chrome hau nhu LUON duoc cai san tren
        // moi may (dung de dieu huong link he thong, khong lien quan gi den
        // viec nguoi dung co dung no de xem YouTube hay khong), thi code se
        // LUON chon nham Chrome, khong bao gio thu toi YouTube goc nua - day
        // la loi vua xay ra. Doi lai dung thu tu uu tien theo thuc te nguoi
        // dung dang dung (YouTube goc), giu Chrome lam fallback cuoi cung
        // (phong truong hop sau nay nguoi dung doi sang xem qua trinh duyet).
        private val TARGET_APP_CANDIDATES = listOf(
            "com.google.android.youtube",   // App YouTube goc - uu tien 1 (nguoi dung xac nhan dang dung app nay)
            "com.android.chrome"            // Chrome - uu tien 2 (fallback, phong truong hop doi sang xem qua trinh duyet)
        )

        @Volatile
        private var capturingActive = false

        @Volatile
        private var mixerTestActive = false

        fun isCapturing(): Boolean = capturingActive
        fun isMixerTestActive(): Boolean = mixerTestActive

        // ✅ MOI (Phase 5): tham chieu tinh (companion) toi LowLatencyMixer
        // DANG CHAY, de WebRtcManager (chay o tang UI/MainActivity, KHONG
        // biet gi ve noi bo Service) co the day PCM cua mic tu xa (May B/C)
        // thang vao mixer ma khong can Bind Service hay AIDL/Messenger phuc
        // tap - giong tinh than activityRef trong MainActivity.kt (WeakReference
        // + bien static don gian, vi ca 2 chay chung 1 process).
        //
        // @Volatile vi duoc ghi tu thread cua startMixerTestInternal()/
        // stopMixerTestInternal() (co the chay tu Main thread hoac tu
        // serviceScope tuy duong goi) va doc tu thread cua WebRTC DataChannel
        // callback (thread rieng cua thu vien WebRTC, khong phai thread nao
        // trong so cac coroutine dispatcher da biet cua app) - can dam bao
        // gia tri moi nhat luon thay duoc giua cac thread, khong bi cache
        // stale o CPU core khac.
        @Volatile
        private var activeMixerInstance: LowLatencyMixer? = null

        /**
         * ✅ MOI (Phase 5): goi tu WebRtcManager.onRemotePcmChunk (May A nhan
         * duoc PCM tu Mic khong day qua WebRTC DataChannel) - day THANG vao
         * LowLatencyMixer dang chay, hoa chung voi nhac YouTube + Mic tai cho
         * (neu co). An toan goi khi mixer dang KHONG chay (activeMixerInstance
         * = null) - tu bo qua, khong crash, giong quy uoc onToggle/pushVocal
         * cua cac noi khac trong app.
         */
        fun pushRemoteVocalChunk(buffer: ShortArray, size: Int) {
            activeMixerInstance?.pushVocal(buffer, size)
        }
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[Service] $msg")
    }

    private fun requestFocusObserver(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setOnAudioFocusChangeListener(focusObserverListener)
            .setWillPauseWhenDucked(false)
            .build()
        focusObserverRequest = request
        val result = audioManager.requestAudioFocus(request)
        logBoth("🎧 [FocusObserver] requestAudioFocus() tra ve=$result.")
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
                    logBoth("🔒 Da kich hoat WakeLock.")
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
        cancelPendingReactivation()
        stopMixerTestInternal()
        musicInput?.stopCapture()
        musicInput = null
        mediaProjection?.stop()
        mediaProjection = null
        capturingActive = false
        mixerToggleOverlay?.hide()
        mixerToggleOverlay = null
    }

    /**
     * ✅ MOI: huy chuoi "Bat lai qua nut noi" dang cho (neu co) - goi khi
     * nguoi dung bam TAT giua chung, hoac khi ca phien Phase 1 ket thuc.
     */
    private fun cancelPendingReactivation() {
        reactivationRunnable?.let { reactivationHandler.removeCallbacks(it) }
        reactivationRunnable = null
        // ✅ MOI: don luon callback cho MainActivity.onResume() (neu con dang
        // treo) - tranh truong hop nguoi dung bam TAT giua chung luc dang
        // cho, roi sau do TU HO mo lai app vi ly do khac (khong lien quan
        // chuoi nay) - luc do onResume() se KHONG con vo tinh kich hoat
        // proceedToStep2() cua 1 chuoi da bi huy tu truoc.
        MainActivity.onResumedCallback = null
    }

    /**
     * ✅ SUA: nut noi bam de TAT (dang BAT) - GIU NGUYEN hanh vi tuc thi don
     * gian nhu truoc gio, KHONG doi gi ca. Nut noi bam de BAT (dang TAT) -
     * GIO se chay qua chuoi "Bat lai" moi (xem beginOverlayReactivationSequence())
     * thay vi bat Mixer Test ngay tai cho.
     */
    private fun toggleMixerTestFromOverlay() {
        if (mixer != null) {
            logBoth("👆 [OverlayToggle] Dang BAT -> chuyen sang TAT Mixer Test (tuc thi, khong doi).")
            cancelPendingReactivation()
            stopMixerTestInternal()
        } else {
            logBoth("👆 [OverlayToggle] Dang TAT -> bat dau chuoi 'Kich hoat lai' (dua app len foreground truoc).")
            beginOverlayReactivationSequence()
        }
    }

    /**
     * ✅ SUA (fix "doan gio co dinh 1s cho buoc 1 la KHONG DU - MainActivity
     * chua thuc su len foreground da bat Mixer Test roi"): buoc 1/3 cua
     * chuoi "Bat lai qua nut noi" - dua MainActivity len foreground bang
     * Intent thuong (KHONG tao Activity/task moi, dung lai chinh MainActivity
     * da khai bao san trong Manifest voi ACTION_MAIN/LAUNCHER) - tranh dung
     * toi vong doi MediaProjection dang song trong chinh Service nay.
     *
     * KHAC bien truoc: KHONG con doan gio co dinh cho buoc nay nua - thay
     * vao do, gan MainActivity.onResumedCallback TRUOC khi goi startActivity(),
     * roi CHO THAT SU callback nay duoc goi (tuc onResume() cua MainActivity
     * da THAT SU chay, Activity da o trang thai foreground/resumed that su) -
     * xem giai thich chi tiet trong MainActivity.kt (companion object). Day
     * moi la dieu kien CAN chinh xac, thay vi doan mo 1 khoang thoi gian co
     * dinh (startActivity() tu Service KHONG dam bao Activity len foreground
     * trong bat ky khoang thoi gian co dinh nao, tuy thiet bi/tai he thong).
     *
     * Them 1 lop AN TOAN: neu callback KHONG BAO GIO duoc goi (vi du
     * MainActivity bi loi khong len duoc, hoac he thong tre bat thuong),
     * dat 1 fallback timeout FALLBACK_TIMEOUT_MS - qua thoi gian nay ma
     * callback van chua toi, VAN tiep tuc chay buoc 2 (bat Mixer Test) thay
     * vi treo chuoi vinh vien.
     */
    private fun beginOverlayReactivationSequence() {
        cancelPendingReactivation()

        var alreadyProceeded = false

        val proceedToStep2: () -> Unit = {
            if (!alreadyProceeded) {
                alreadyProceeded = true
                cancelPendingReactivation() // huy fallback timeout con lai (neu co)

                logBoth(
                    "⏳ [Reactivation] Da co tin hieu onResume() (hoac fallback) - " +
                        "doi them ${AUDIO_FOREGROUND_SETTLE_DELAY_MS}ms de tang AudioPolicy/AudioFlinger " +
                        "kip xu ly xong viec chuyen foreground."
                )

                // ✅ MOI (mo phong dung quy trinh THU CONG da xac nhan hieu qua:
                // "tat Mixer Test roi bat lai", KHONG chi bat 1 lan don gian):
                // buoc nay CHU DONG goi stopMixerTestInternal() TRUOC (an toan
                // du mixer dang thuc su tat hay khong - ham nay tu kiem tra va
                // bo qua neu khong co gi de tat), roi MOI goi
                // startMixerTestInternal() sau 1 khoang doi rieng
                // (OFF_ON_CYCLE_DELAY_MS) - dung y het 1 lan "tat" roi "bat lai"
                // that su nhu nguoi dung tu tay bam 2 lan, thay vi chi goi
                // startMixerTestInternal() 1 lan duy nhat nhu truoc.
                val offStepRunnable = Runnable {
                    logBoth("🔁 [Reactivation] Buoc 'tat' (dam bao trang thai sach truoc khi bat lai that su).")
                    stopMixerTestInternal()

                    val onStepRunnable = Runnable {
                        logBoth("🔁 [Reactivation] Buoc 'bat lai' - goi startMixerTestInternal() that su.")
                        startMixerTestInternal()
                        logBoth(
                            "✅ [Reactivation] Da bat Mixer Test that - cho ${REACTIVATION_STEP_DELAY_MS}ms roi mo lai YouTube."
                        )

                        val step3 = Runnable { returnToSourceApp() }
                        reactivationRunnable = step3
                        reactivationHandler.postDelayed(step3, REACTIVATION_STEP_DELAY_MS)
                    }
                    reactivationRunnable = onStepRunnable
                    reactivationHandler.postDelayed(onStepRunnable, OFF_ON_CYCLE_DELAY_MS)
                }
                reactivationRunnable = offStepRunnable
                reactivationHandler.postDelayed(offStepRunnable, AUDIO_FOREGROUND_SETTLE_DELAY_MS)
            }
        }

        // ✅ Gan callback TRUOC khi goi startActivity() - tranh race condition
        // (truong hop onResume() chay qua nhanh, TRUOC ca khi kip gan xong).
        MainActivity.onResumedCallback = {
            logBoth("✅ [Reactivation] MainActivity.onResume() THAT SU da chay - tiep tuc bat Mixer Test ngay.")
            // onResumedCallback duoc goi tren main thread (tu onResume()) -
            // proceedToStep2 lai dong bo, an toan de goi truc tiep o day.
            proceedToStep2()
        }

        // ✅ Fallback timeout - phong truong hop callback khong bao gio toi.
        val fallback = Runnable {
            logBoth("⚠️ [Reactivation] Khong nhan duoc tin hieu onResume() sau ${FALLBACK_TIMEOUT_MS}ms - tiep tuc bat Mixer Test du sao (fallback).", isError = true)
            MainActivity.onResumedCallback = null
            proceedToStep2()
        }
        reactivationRunnable = fallback
        reactivationHandler.postDelayed(fallback, FALLBACK_TIMEOUT_MS)

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        try {
            startActivity(activityIntent)
            logBoth("✅ [Reactivation] Da goi startActivity() dua MainActivity len foreground - dang cho tin hieu onResume() that.")
        } catch (e: Exception) {
            logBoth("❌ [Reactivation] Loi khi dua MainActivity len foreground: ${e.message}", isError = true)
            cancelPendingReactivation()
            MainActivity.onResumedCallback = null
        }
    }

    /**
     * ✅ SUA (fix goc "khong tu mo lai duoc app nguon" - xac nhan qua log
     * thuc te: getLaunchIntentForPackage("com.google.android.youtube") tra
     * ve null tren may cua nguoi dung, nghia la app YouTube GOC KHONG duoc
     * cai - nguoi dung dang xem qua trinh duyet thay vi app rieng): truoc
     * day CHI thu 1 package YouTube goc co dinh - neu package do khong ton
     * tai tren may, ham nay se KHONG mo duoc gi ca, dan den chuoi "Bat lai"
     * chay het cac buoc am thanh (tat/bat mixer) nhung KHONG BAO GIO thuc
     * su tao ra su kien chuyen foreground THAT can thiet de xoa duck/mute -
     * day chinh la ly do goc khien "van nho tieng, chi bam tay quay lai
     * YouTube (qua Chrome) moi to" nhu quan sat duoc qua log thuc te.
     *
     * GIO thu lan luot tung candidate trong TARGET_APP_CANDIDATES theo dung
     * thu tu uu tien, dung candidate DAU TIEN vua resolve duoc launch
     * intent HOP LE VA startActivity() khong nem loi. Log chi tiet tung
     * buoc thu (thanh cong/that bai) de de doi chieu neu van con sai
     * package.
     */
    private fun returnToSourceApp() {
        reactivationRunnable = null

        for (candidatePackage in TARGET_APP_CANDIDATES) {
            val launchIntent = try {
                packageManager.getLaunchIntentForPackage(candidatePackage)
            } catch (e: Exception) {
                Log.e(TAG, "[Reactivation] Loi khi resolve launch intent cho $candidatePackage", e)
                logBoth(
                    "❌ [Reactivation] Loi khi tim launch intent cho $candidatePackage: " +
                        "${e::class.java.simpleName} - ${e.message}",
                    isError = true
                )
                null
            }

            if (launchIntent == null) {
                logBoth("⚠️ [Reactivation] $candidatePackage khong resolve duoc (co the chua cai) - thu candidate ke tiep.")
                continue
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            logBoth(
                "ℹ️ [Reactivation] $candidatePackage resolve duoc: component=${launchIntent.component} " +
                    "flags=${launchIntent.flags}"
            )
            try {
                startActivity(launchIntent)
                logBoth("✅ [Reactivation] Da goi startActivity() cho $candidatePackage - chuoi hoan tat.")

                // ✅ MOI: buoc 4 - xem giai thich chi tiet o NUDGE_AFTER_RETURN_DELAY_MS
                // phia tren. Doi 1 khoang de foreground/AudioPolicy on dinh
                // xong SAU KHI da quay lai app nguon, roi nudge de don duck
                // HAL/OEM co the vua bi chinh viec quay lai nay kich hoat lai
                // - CHI nudge, KHONG recreate() (rui ro thu hoi MediaProjection).
                val step4 = Runnable {
                    logBoth("🔔 [Reactivation] Buoc 4 - nudge de don duck HAL/OEM (neu co) sau khi da quay lai app nguon.")
                    mixerOutputRouter?.nudgeAudioMixerToClearDuck()
                }
                reactivationRunnable = step4
                reactivationHandler.postDelayed(step4, NUDGE_AFTER_RETURN_DELAY_MS)

                return
            } catch (e: Exception) {
                Log.e(TAG, "[Reactivation] Loi khi startActivity() cho $candidatePackage", e)
                logBoth(
                    "❌ [Reactivation] Loi khi mo $candidatePackage: " +
                        "${e::class.java.simpleName} - ${e.message} - thu candidate ke tiep.",
                    isError = true
                )
            }
        }

        logBoth(
            "❌ [Reactivation] KHONG mo duoc bat ky candidate nao trong danh sach: $TARGET_APP_CANDIDATES. " +
                "Chuoi 'Bat lai' se dung o day - Mixer Test van BAT nhung co the van nho tieng vi khong " +
                "co su kien chuyen foreground THAT nao xay ra.",
            isError = true
        )
    }

    private fun startMixerTestInternal() {
        if (!capturingActive || musicInput == null) {
            logBoth("❌ Chua co MusicInput dang chay - phai bat capture nhac (Phase 1) truoc.", isError = true)
            return
        }
        if (mixer != null) {
            logBoth("⚠️ Mixer test da chay roi, bo qua.")
            return
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (ENABLE_MUSIC_STREAM_MUTE_GUARD) {
            streamMusicWasMutedBeforeMixerTest = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            if (!streamMusicWasMutedBeforeMixerTest) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
            musicMuteAppliedByMixerTest = true
            logBoth("🔇 Da mute STREAM_MUSIC bang MUTE FLAG (da mute san tu truoc=$streamMusicWasMutedBeforeMixerTest)")
        }

        savedStreamSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
        val maxSystemVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)
        if (audioManager.isStreamMute(AudioManager.STREAM_SYSTEM)) {
            logBoth("⚠️ STREAM_SYSTEM dang bi mute flag ngay luc bat dau - go ngay bang ADJUST_UNMUTE.")
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        }
        audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, maxSystemVolume, 0)
        logBoth("🔊 Da day STREAM_SYSTEM len max=$maxSystemVolume (muc goc=$savedStreamSystemVolume).")

        requestFocusObserver(audioManager)

        // ✅ MOI (Phase 4 - phan con lai): finalMixLimiter tao TRUOC LowLatencyMixer
        // vi mixer can nhan no qua constructor (ap dung SAU khi mix, TRUOC khi
        // ghi ra OutputRouter - xem thay doi tuong ung trong LowLatencyMixer.kt).
        val finalLimiterInstance = Limiter(sampleRate = 44100, thresholdRatio = 0.9f, releaseMs = 50f)
        val router = OutputRouter(this, AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).apply { start() }
        val mix = LowLatencyMixer(router, finalLimiter = finalLimiterInstance).apply { start() }
        val mic = MicInput(this)
        val limiter = Limiter(sampleRate = 44100, thresholdRatio = 0.85f, releaseMs = 50f)

        // ✅ MOI (Phase 4 - phan con lai): EQ 3-band -> Compressor -> Echo/Reverb,
        // ca 3 chay TREN VOCAL RIENG (truoc khi vao mixer), SAU vocalLimiter -
        // xem giai thich day du ve thu tu nay trong comment khai bao field
        // finalMixLimiter phia tren. Gia tri gain mac dinh (bass -2dB, mid
        // +1dB, treble +3dB) dua theo de xuat cua nguoi dung (huong_dan.txt) -
        // giam nhe truc de bot duc, tang nhe mid de ro loi, tang treble de
        // sang giong - can nghe thu thuc te de tinh chinh lai, day chi la
        // diem khoi dau hop ly, KHONG phai con so da kiem chung bang tai.
        val processor = VocalProcessor(sampleRate = 44100, bassGainDb = -2.0f, midGainDb = 1.0f, trebleGainDb = 3.0f)
        val compressor = Compressor(sampleRate = 44100, thresholdDb = -18.0f, ratio = 3.0f, attackMs = 12f, releaseMs = 100f, makeupGainDb = 2.0f)
        val echo = EchoReverb(sampleRate = 44100, delayMs = 200f, feedback = 0.38f, wetLevel = 0.32f, damping = 0.35f)

        mixerOutputRouter = router
        mixer = mix
        micInput = mic
        vocalLimiter = limiter
        vocalProcessor = processor
        vocalCompressor = compressor
        vocalEcho = echo
        finalMixLimiter = finalLimiterInstance
        // ✅ MOI (Phase 5): cong bo mixer nay ra companion object de
        // WebRtcManager.onRemotePcmChunk (chay o tang UI, xem giai thich chi
        // tiet o khai bao activeMixerInstance) co the day PCM cua mic tu xa
        // vao thang mixer dang chay.
        activeMixerInstance = mix

        // ✅ MOI: chuoi xu ly vocal day du - Limiter (chan feedback am hoc,
        // vi tri quan trong nhat, KHONG doi) -> EQ -> Compressor -> Echo ->
        // day vao Mixer. finalMixLimiter chay o BEN TRONG LowLatencyMixer,
        // SAU khi mix, khong chay o day.
        mic.startCapture(onPcmChunk = { buffer, size ->
            limiter.process(buffer, size)      // 1. Chan hu/feedback am hoc SOM NHAT co the
            processor.process(buffer, size)    // 2. EQ 3-band
            compressor.process(buffer, size)   // 3. Nen dai dong
            echo.process(buffer, size)         // 4. Vang/nhai
            mix.pushVocal(buffer, size)        // 5. Day vao Mixer (finalMixLimiter chay sau day, trong Mixer)
        })

        guardTickCount = 0
        volumeGuardJob = serviceScope.launch {
            while (isActive) {
                try {
                    reassertStreamSystemVolumeIfMixerRunning()
                } catch (e: Exception) {
                    logBoth("⚠️ [GuardTick] Loi thoang qua (bo qua): ${e.message}")
                }
                delay(300L)
            }
        }

        if (mixerToggleOverlay == null) {
            mixerToggleOverlay = MixerToggleOverlayButton(applicationContext) {
                toggleMixerTestFromOverlay()
            }
        }
        mixerToggleOverlay?.show(initiallyRunning = true)
        mixerTestActive = true

        // ✅ MOI (fix "nut trong app hien sai/cu, phai roi app roi quay lai
        // moi thay dung"): bao ngay cho MainActivity biet trang thai VUA
        // doi - KHONG doi den lan onResume() ke tiep, vi luong nay co the
        // duoc kich hoat tu nut noi trong luc Activity dang o nen (dang xem
        // YouTube), co the khong bao gio onResume() lai neu nguoi dung
        // khong chu dong quay ve app.
        MainActivity.refreshMixerTestButtonState(true)

        logBoth("✅ Da bat dau Mixer Test (Phase 3).")
    }

    private fun stopMixerTestInternal() {
        if (mixer == null && micInput == null && mixerOutputRouter == null && vocalLimiter == null) return

        volumeGuardJob?.cancel()
        volumeGuardJob = null

        mixerToggleOverlay?.updateState(isRunning = false)
        mixerTestActive = false

        // ✅ MOI: xem giai thich chi tiet o cuoi startMixerTestInternal() -
        // dong bo ngay lap tuc, khong doi onResume().
        MainActivity.refreshMixerTestButtonState(false)

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
        // ✅ MOI (Phase 4 - phan con lai): don dep dung 3 khoi moi + Limiter thu 2.
        vocalProcessor?.reset()
        vocalProcessor = null
        vocalCompressor?.reset()
        vocalCompressor = null
        vocalEcho?.reset()
        vocalEcho = null
        finalMixLimiter?.reset()
        finalMixLimiter = null
        // ✅ MOI (Phase 5): go tham chieu ngay khi mixer dung, tranh
        // WebRtcManager con giu PCM cua mic tu xa day vao 1 mixer da
        // dung/huy (pushVocal se khong crash vi mixer=null se duoc kiem tra
        // truoc, nhung go som van gon hon, tranh push vao instance "zombie").
        activeMixerInstance = null

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (musicMuteAppliedByMixerTest) {
            if (!streamMusicWasMutedBeforeMixerTest) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                logBoth("Da bo MUTE FLAG cua STREAM_MUSIC (khoi phuc trang thai truoc do).")
            } else {
                logBoth("STREAM_MUSIC da bi mute TU TRUOC khi bat Mixer Test - giu nguyen.")
            }
            musicMuteAppliedByMixerTest = false
        }
        if (savedStreamSystemVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, savedStreamSystemVolume, 0)
            logBoth("Da khoi phuc STREAM_SYSTEM ve muc goc=$savedStreamSystemVolume")
            savedStreamSystemVolume = -1
        }

        logBoth("🛑 Da dung Mixer Test (Phase 3). MusicInput van tiep tuc chay.")
    }

    private fun reassertStreamSystemVolumeIfMixerRunning() {
        if (savedStreamSystemVolume < 0) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)
        val isMuted = audioManager.isStreamMute(AudioManager.STREAM_SYSTEM)

        val musicCurrent = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val musicMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)

        guardTickCount++
        if (guardTickCount % GUARD_STATUS_LOG_EVERY_N_TICKS == 0) {
            logBoth(
                "[GuardTick] STREAM_SYSTEM current=$current/$max isMuted=$isMuted | " +
                    "STREAM_MUSIC current=$musicCurrent/$musicMax isMuted=$musicMuted"
            )
        }

        if (ENABLE_MUSIC_STREAM_MUTE_GUARD && musicMuteAppliedByMixerTest && !musicMuted) {
            logBoth("⚠️ [AutoReassert] STREAM_MUSIC bi GO MUTE FLAG ngoai y muon - mute lai.")
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }

        if (isMuted) {
            logBoth("⚠️ [AutoReassert] STREAM_SYSTEM dang bi MUTE FLAG (current=$current/$max) - go bang ADJUST_UNMUTE.")
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        }

        if (current < max) {
            logBoth("⚠️ [AutoReassert] STREAM_SYSTEM bi tut con $current/$max - ep lai ve max.")
        }

        audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, max, 0)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MIXER_TEST -> {
                startMixerTestInternal()
                return START_NOT_STICKY
            }
            ACTION_STOP_MIXER_TEST -> {
                cancelPendingReactivation()
                stopMixerTestInternal()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("Dang khoi dong..."))
        acquireWakeLock()

        logBoth("Service started. intent=$intent, hasExtras=${intent?.extras != null}")

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            logBoth("❌ Thieu resultCode/resultData, khong the tao MediaProjection.", isError = true)
            stopSelf()
            return START_NOT_STICKY
        }

        stopCurrentSessionIfAny()

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)

        if (projection == null) {
            logBoth("❌ getMediaProjection() tra ve null", isError = true)
            stopSelf()
            return START_NOT_STICKY
        }

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                logBoth("MediaProjection.onStop() - he thong da thu hoi quyen capture")
                cancelPendingReactivation()
                stopMixerTestInternal()
                musicInput?.stopCapture()
                musicInput = null
                capturingActive = false
                stopSelf()
            }
        }, null)

        mediaProjection = projection

        try {
            musicInput = MusicInput(
                mediaProjection = projection,
                onAmplitudeTick = { avgAmplitude ->
                    val now = timeFormat.format(java.util.Date())
                    updateNotification("Cap nhat luc $now - amplitude=$avgAmplitude")
                },
                onPcmChunk = { buffer, size ->
                    mixer?.pushMusic(buffer, size)
                }
            ).apply { startCapture() }
            capturingActive = true
        } catch (e: Exception) {
            logBoth("❌ Khoi tao MusicInput/startCapture() that bai: ${e.message}", isError = true)
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
        logBoth("Task removed. Dang capture=$stillCapturing")

        if (stillCapturing) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification("Dang tiep tuc capture sau khi app bi vuot xoa..."))
            } catch (e: Exception) {
                logBoth("❌ Loi khi tai khang dinh foreground service: ${e.message}", isError = true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Karaoke - Test Capture Nhac",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ✅ SUA (fix "nut Kich hoat lai tren thong bao khong bam duoc"): bo han
    // nut nay - da xac nhan qua thuc te khong bam duoc (co the do gioi han
    // cua notification action tren mot so ROM/Android version, hoac do
    // PendingIntent.getService() bi OS chan lai khi Service khong dang o
    // trang thai "gan day co tuong tac"), VA ban than co che nudge (phat 1
    // AudioTrack am luong 0 de "danh thuc" AudioFlinger) chua bao gio duoc
    // xac nhan hieu qua thuc su - khac han voi chuoi "Bat lai qua nut noi"
    // (beginOverlayReactivationSequence()) da xac nhan hieu qua qua thao tac
    // tay THUC SU (dua app len foreground / tat-bat lai / quay ve app
    // nguon), gio da duoc tu dong hoa day du va la duong duy nhat con lai.
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
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(contentText))
        } catch (e: Exception) {
            logBoth("❌ [NotifyDebug] notify() nem loi: ${e.message}", isError = true)
        }
    }
}