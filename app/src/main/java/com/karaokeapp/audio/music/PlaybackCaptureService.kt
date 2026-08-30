package com.karaokeapp.audio.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service bat buoc de dung MediaProjection cho AudioPlaybackCapture.
 * Duoc start tu MainActivity ngay sau khi nguoi dung dong y chia se man
 * hinh/audio qua dialog he thong (MediaProjectionManager.createScreenCaptureIntent()).
 *
 * KHONG tu y start service nay ma khong co resultCode/resultData hop le -
 * MediaProjection chi tao duoc tu ket qua that cua dialog xin quyen.
 */
class PlaybackCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var musicInput: MusicInput? = null

    companion object {
        private const val TAG = "PlaybackCaptureService"
        private const val CHANNEL_ID = "playback_capture_channel"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[Service] $msg")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Phai goi startForeground() ngay lap tuc (trong vai giay), truoc khi
        // lam bat cu viec gi khac - yeu cau bat buoc cua Android voi foreground service.
        startForeground(NOTIFICATION_ID, buildNotification())
        logBoth(
            "Service started. intent=$intent, hasExtras=${intent?.extras != null}, " +
                "keys=${intent?.extras?.keySet()?.joinToString()}"
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        logBoth("resultCode doc duoc=$resultCode, resultData doc duoc=$resultData")

        if (resultCode == -1 || resultData == null) {
            logBoth("❌ Thieu resultCode/resultData, khong the tao MediaProjection", isError = true)
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)

        if (projection == null) {
            logBoth("❌ getMediaProjection() tra ve null", isError = true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Bat buoc dang ky callback truoc khi dung MediaProjection de capture
        // (tu Android 14/API 34), neu khong se bi throw IllegalStateException.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                logBoth("MediaProjection.onStop() - he thong da thu hoi quyen capture")
                musicInput?.stopCapture()
                stopSelf()
            }
        }, null)

        mediaProjection = projection
        musicInput = MusicInput(projection).apply { startCapture() }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        musicInput?.stopCapture()
        mediaProjection?.stop()
        mediaProjection = null
        logBoth("Service destroyed")
        super.onDestroy()
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

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Karaoke App")
            .setContentText("Dang test capture nhac (Phase 1)")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }
}
