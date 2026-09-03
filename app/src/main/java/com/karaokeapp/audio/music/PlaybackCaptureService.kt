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
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.karaokeapp.NudgeTransitionActivity
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
 * va co che tu phuc hoi am luong (AutoReassert + Nudge/Recreate).
 *
 * Co che mute STREAM_MUSIC + phat mixer qua STREAM_SYSTEM (usage
 * ASSISTANCE_SONIFICATION) da duoc xac nhan qua test thuc te: capture tap
 * PCM TRUOC buoc ap volume he thong nen mute khong lam mat tin hieu capture.
 *
 * volumeGuardJob (~300ms/lan) chi xu ly duoc muc "index"/"mute-flag" ma
 * AudioManager bao cao - KHONG xu ly duoc "duck" noi bo o tang HAL/OEM (gain
 * giam nhung index/mute-flag van bao binh thuong). Duck chi duoc xoa bang
 * cach tao lai AudioTrack (OutputRouter.recreate()) SAU KHI da co 1 su kien
 * chuyen foreground/audio-focus THAT xay ra - xem NudgeTransitionActivity.kt
 * va ACTION_MANUAL_NUDGE_RECREATE.
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

    private val focusObserverListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val label = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
            else -> "UNKNOWN($focusChange)"
        }
        logBoth("🎧 [FocusObserver] onAudioFocusChange=$label (chi ghi log - khong tu dong nudge/recreate).")

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

        // Goi boi NudgeTransitionActivity SAU KHI da that su tao ra 1 su
        // kien chuyen foreground that (roi khoi YouTube trong choc lat) -
        // dung recreate() (tao lai AudioTrack) thay vi nudge beep, vi nudge
        // beep KHONG du de xoa duck khi khong co su kien chuyen foreground
        // that di kem (da xac nhan qua test thuc te).
        const val ACTION_MANUAL_NUDGE_RECREATE = "com.karaokeapp.action.MANUAL_NUDGE_RECREATE"

        private const val GUARD_STATUS_LOG_EVERY_N_TICKS = 10
        private const val ENABLE_MUSIC_STREAM_MUTE_GUARD = true

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
        stopMixerTestInternal()
        musicInput?.stopCapture()
        musicInput = null
        mediaProjection?.stop()
        mediaProjection = null
        capturingActive = false
        mixerToggleOverlay?.hide()
        mixerToggleOverlay = null
    }

    private fun toggleMixerTestFromOverlay() {
        if (mixer != null) {
            logBoth("👆 [OverlayToggle] Dang BAT -> chuyen sang TAT Mixer Test.")
            stopMixerTestInternal()
        } else {
            logBoth("👆 [OverlayToggle] Dang TAT -> chuyen sang BAT Mixer Test.")
            startMixerTestInternal()
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
            ACTION_MANUAL_NUDGE_RECREATE -> {
                if (mixerOutputRouter != null) {
                    logBoth("🔄 [ManualNudgeRecreate] Da co su kien chuyen foreground that - goi recreate().")
                    mixerOutputRouter?.recreate()
                } else {
                    logBoth("🔄 [ManualNudgeRecreate] Mixer Test dang tat - bo qua.")
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
        // Nut nay mo NudgeTransitionActivity (khong goi thang ACTION_MANUAL_NUDGE
        // nua) - can 1 su kien chuyen foreground THAT de xoa duck khi YouTube
        // dang la app foreground (xem NudgeTransitionActivity.kt).
        val nudgePendingIntent = PendingIntent.getActivity(
            this,
            0,
            NudgeTransitionActivity.buildLaunchIntent(this),
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
