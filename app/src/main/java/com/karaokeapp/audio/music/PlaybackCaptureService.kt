package com.karaokeapp.audio.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

        val router = OutputRouter(this).apply { start() }
        val mix = LowLatencyMixer(router).apply { start() }
        val mic = MicInput(this)

        mixerOutputRouter = router
        mixer = mix
        micInput = mic

        // ✅ Noi MusicInput hien tai vao mixer: vi MusicInput da duoc tao TU
        // TRUOC (o onStartCommand chinh) voi onPcmChunk da tro ve mixerRef
        // (xem ham buildMusicPcmForwarder() ben duoi) - khong can lam gi them
        // o day, chi can gan bien mixer o tren la MusicInput's callback se tu
        // dong bat dau day du lieu vao no.

        mic.startCapture(onPcmChunk = { buffer, size ->
            mix.pushVocal(buffer, size)
        })

        logBoth("✅ Da bat dau Mixer Test (Phase 3) - dang tron Music + Mic, phat qua loa.")
    }

    private fun stopMixerTestInternal() {
        if (mixer == null && micInput == null && mixerOutputRouter == null) return
        micInput?.stopCapture()
        micInput = null
        mixer?.stop()
        mixer = null
        mixerOutputRouter?.stop()
        mixerOutputRouter = null
        logBoth("🛑 Da dung Mixer Test (Phase 3). MusicInput (Phase 1) khong bi anh huong, van tiep tuc chay.")
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