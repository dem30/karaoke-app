package com.karaokeapp.audio.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 * se chay 1 chuoi TU DONG:
 *   1. Dua MainActivity len foreground (FLAG_ACTIVITY_REORDER_TO_FRONT -
 *      dung Activity DA TON TAI san trong task, KHONG tao Activity/task moi,
 *      tranh dung toi vong doi cua MediaProjection dang song trong Service
 *      nay - bai hoc rut ra tu lan thu truoc voi 1 Activity rieng biet gay
 *      dut MediaProjection).
 *   2. Doi ~1 giay (nguoi dung THAT SU thay giao dien MainActivity, day la
 *      su kien chuyen foreground CAN THIET).
 *   3. Goi startMixerTestInternal() that su.
 *   4. Doi ~1 giay.
 *   5. Tu mo lai YouTube (FLAG_ACTIVITY_REORDER_TO_FRONT).
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
        const val ACTION_MANUAL_NUDGE = "com.karaokeapp.action.MANUAL_NUDGE"

        private const val GUARD_STATUS_LOG_EVERY_N_TICKS = 10
        private const val ENABLE_MUSIC_STREAM_MUTE_GUARD = true

        // ✅ MOI: thoi gian cho o moi buoc cua chuoi "Bat lai qua nut noi" -
        // xem giai thich chi tiet o dau file. Co the can chinh lai sau khi
        // test thuc te tren thiet bi that.
        private const val REACTIVATION_STEP_DELAY_MS = 1000L

        // Package app nhac nguon se tu dong mo lai sau buoc 5 cua chuoi -
        // hardcode YouTube (dung nhat voi use-case chinh). Neu sau nay ho
        // tro nhieu nguon nhac khac nhau, can doi thanh doc dong tu cau hinh
        // (vi du SongManager) thay vi hang so co dinh.
        private const val TARGET_PACKAGE_YOUTUBE = "com.google.android.youtube"

        @Volatile
        private var capturingActive = false

        @Volatile
        private var mixerTestActive = false

        fun isCapturing(): Boolean = capturingActive
        fun isMixerTestActive(): Boolean = mixerTestActive
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
     * ✅ MOI: buoc 1/5 cua chuoi "Bat lai qua nut noi" - dua MainActivity len
     * foreground bang Intent thuong (KHONG tao Activity/task moi, dung lai
     * chinh MainActivity da khai bao san trong Manifest voi ACTION_MAIN/
     * LAUNCHER) - tranh dung toi vong doi MediaProjection dang song trong
     * chinh Service nay.
     */
    private fun beginOverlayReactivationSequence() {
        cancelPendingReactivation()

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        try {
            startActivity(activityIntent)
            logBoth("✅ [Reactivation] Da dua MainActivity len foreground - cho ${REACTIVATION_STEP_DELAY_MS}ms roi bat Mixer Test that.")
        } catch (e: Exception) {
            logBoth("❌ [Reactivation] Loi khi dua MainActivity len foreground: ${e.message}", isError = true)
            return
        }

        val step2 = Runnable {
            startMixerTestInternal()
            logBoth("✅ [Reactivation] Da bat Mixer Test that - cho ${REACTIVATION_STEP_DELAY_MS}ms roi mo lai YouTube.")

            val step3 = Runnable { returnToSourceApp() }
            reactivationRunnable = step3
            reactivationHandler.postDelayed(step3, REACTIVATION_STEP_DELAY_MS)
        }
        reactivationRunnable = step2
        reactivationHandler.postDelayed(step2, REACTIVATION_STEP_DELAY_MS)
    }

    /** ✅ MOI: buoc cuoi cua chuoi - tu mo lai app nhac nguon (YouTube). */
    private fun returnToSourceApp() {
        reactivationRunnable = null
        val launchIntent = try {
            packageManager.getLaunchIntentForPackage(TARGET_PACKAGE_YOUTUBE)
        } catch (e: Exception) {
            logBoth("❌ [Reactivation] Loi khi tim launch intent cho $TARGET_PACKAGE_YOUTUBE: ${e.message}", isError = true)
            null
        }

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try {
                startActivity(launchIntent)
                logBoth("✅ [Reactivation] Da mo lai $TARGET_PACKAGE_YOUTUBE - chuoi hoan tat.")
            } catch (e: Exception) {
                logBoth("❌ [Reactivation] Loi khi mo lai $TARGET_PACKAGE_YOUTUBE: ${e.message}", isError = true)
            }
        } else {
            logBoth("⚠️ [Reactivation] Khong tim thay $TARGET_PACKAGE_YOUTUBE da cai dat.", isError = true)
        }
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

        val router = OutputRouter(this, AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).apply { start() }
        val mix = LowLatencyMixer(router).apply { start() }
        val mic = MicInput(this)
        val limiter = Limiter(sampleRate = 44100, thresholdRatio = 0.85f, releaseMs = 50f)

        mixerOutputRouter = router
        mixer = mix
        micInput = mic
        vocalLimiter = limiter

        mic.startCapture(onPcmChunk = { buffer, size ->
            limiter.process(buffer, size)
            mix.pushVocal(buffer, size)
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

        logBoth("✅ Da bat dau Mixer Test (Phase 3).")
    }

    private fun stopMixerTestInternal() {
        if (mixer == null && micInput == null && mixerOutputRouter == null && vocalLimiter == null) return

        volumeGuardJob?.cancel()
        volumeGuardJob = null

        mixerToggleOverlay?.updateState(isRunning = false)
        mixerTestActive = false

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
            ACTION_MANUAL_NUDGE -> {
                if (mixerOutputRouter != null) {
                    logBoth("👆 [ManualNudge] Nguoi dung bam nut 'Kich hoat lai' tren notification.")
                    mixerOutputRouter?.nudgeAudioMixerToClearDuck()
                } else {
                    logBoth("👆 [ManualNudge] Bam nut nhung Mixer Test dang tat - bo qua.")
                }
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

    private fun buildNotification(contentText: String): Notification {
        val nudgeIntent = Intent(this, PlaybackCaptureService::class.java).apply {
            action = ACTION_MANUAL_NUDGE
        }
        val nudgePendingIntent = PendingIntent.getService(
            this,
            0,
            nudgeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Karaoke App - Phase 1")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "🔊 Kích hoạt lại", nudgePendingIntent)
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
