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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Foreground service bat buoc de dung MediaProjection cho AudioPlaybackCapture.
 *
 * ✅ CAP NHAT (sua bug MusicInput chay song song): onStartCommand() gio LUON
 * dung han session cu (neu co) truoc khi tao session moi - truoc day ghi de
 * truc tiep bien mediaProjection/musicInput, bo mac coroutine cu chay tiep tren
 * AudioRecord da bi vo hieu hoa ngam, sinh loi -2 lap lai vo han xen ke voi
 * session moi dang chay dung.
 *
 * ✅ CAP NHAT (chan MainActivity kich hoat lai flow khi da dang capture): them
 * companion isCapturing() de MainActivity kiem tra TRUOC khi tu dong bam lai
 * flow xin quyen trong onCreate() - tranh tao session MediaProjection thua
 * moi lan Activity duoc tao lai (xoay man hinh, mo lai app trong khi service
 * van con song...) trong khi capture hien tai van con dang chay tot.
 *
 * ✅ CAP NHAT (kiem chung "dong bang" khi chay nen): notification gio hien thi
 * thoi diem nhan amplitude GAN NHAT (cap nhat moi giay tu MusicInput). Neu
 * dong bang tien trinh that su xay ra, dong chu nay se NGUNG cap nhat va
 * "dung yen" trong notification shade - xem duoc TRUC TIEP tu man hinh khoa
 * hoac keo notification xuong, KHONG can mo lai app (mo lai app se lam mat co
 * hoi quan sat vi kich hoat lai flow xin quyen).
 */
class PlaybackCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var musicInput: MusicInput? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        private const val TAG = "PlaybackCaptureService"
        private const val CHANNEL_ID = "playback_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "KaraokeApp::PlaybackCaptureWakeLock"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        // ✅ MOI: co static don gian bao hieu service co dang giu 1 session
        // capture hop le hay khong - MainActivity doc co nay truoc khi tu dong
        // kich hoat lai flow xin quyen trong onCreate().
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

    /** Dung han session capture hien tai (neu co) mot cach an toan, day du. */
    private fun stopCurrentSessionIfAny() {
        if (musicInput != null || mediaProjection != null) {
            logBoth("⚠️ Phat hien session capture cu con song - dung han truoc khi tao session moi.")
        }
        musicInput?.stopCapture()
        musicInput = null
        mediaProjection?.stop()
        mediaProjection = null
        capturingActive = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        // ✅ SUA LOI CHINH: dung han session cu TRUOC KHI tao session moi - khong
        // con ghi de truc tiep len bien nhu truoc, tranh coroutine cu chay mai
        // (xem giai thich day du trong MusicInput.kt va comment dau file).
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
                musicInput?.stopCapture()
                musicInput = null
                capturingActive = false
                stopSelf()
            }
        }, null)

        mediaProjection = projection
        musicInput = MusicInput(projection) { avgAmplitude ->
            // ✅ MOI: cap nhat notification real-time moi giay - xem giai thich o
            // comment dau file ve muc dich kiem chung "dong bang" tien trinh.
            val now = timeFormat.format(java.util.Date())
            updateNotification("Cap nhat luc $now - amplitude=$avgAmplitude")
        }.apply { startCapture() }
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