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

/**
 * Foreground service bat buoc de dung MediaProjection cho AudioPlaybackCapture.
 * Duoc start tu MainActivity ngay sau khi nguoi dung dong y chia se man
 * hinh/audio qua dialog he thong (MediaProjectionManager.createScreenCaptureIntent()).
 *
 * KHONG tu y start service nay ma khong co resultCode/resultData hop le -
 * MediaProjection chi tao duoc tu ket qua that cua dialog xin quyen.
 *
 * ✅ CAP NHAT (chong OEM kill khi chay nen): them WakeLock (PARTIAL_WAKE_LOCK)
 * va onTaskRemoved(), tham khao tu ky thuat da dung trong aichatvn2's
 * WebhookGatewayService. Muc tieu la NGAN tien trinh bi kill tu dau, KHONG
 * PHAI tu phuc hoi capture sau khi chet that su - vi MediaProjection la
 * quyen dung 1 lan (one-time consent token), mot khi TIEN TRINH thuc su bi
 * giet (khong chi Activity bi dong), token nay khong the tai su dung duoc
 * nua du co Intent extras cu hay khong. Neu dieu do xay ra, cach duy nhat la
 * mo lai MainActivity de xin dialog moi (MainActivity da duoc cap nhat de tu
 * dong kich hoat lai flow nay ngay khi mo app, xem MainActivity.kt).
 */
class PlaybackCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var musicInput: MusicInput? = null

    // ✅ MOI: giu CPU thuc ngay ca khi man hinh tat / may idle, de coroutine
    // doc AudioRecord trong MusicInput khong bi he thong dong bang CPU giua
    // chung. Dung PARTIAL_WAKE_LOCK (chi giu CPU, khong giu man hinh sang) -
    // dung loai nay vi capture nhac khong can man hinh bat.
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "PlaybackCaptureService"
        private const val CHANNEL_ID = "playback_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "KaraokeApp::PlaybackCaptureWakeLock"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Phai goi startForeground() ngay lap tuc (trong vai giay), truoc khi
        // lam bat cu viec gi khac - yeu cau bat buoc cua Android voi foreground service.
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        logBoth(
            "Service started. intent=$intent, hasExtras=${intent?.extras != null}, " +
                "keys=${intent?.extras?.keySet()?.joinToString()}"
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        logBoth("resultCode doc duoc=$resultCode, resultData doc duoc=$resultData")

        // ✅ resultCode Int.MIN_VALUE lam gia tri "khong tim thay extra" - Activity.RESULT_OK
        // (-1) khong the trung voi gia tri nay, khac voi truoc day dung -1 gay nham lan.
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            logBoth(
                "❌ Thieu resultCode/resultData, khong the tao MediaProjection. " +
                    "Neu day la lan restart sau khi tien trinh bi kill (khong phai lan dau), " +
                    "day la gioi han khong the tranh cua Android: MediaProjection can duoc " +
                    "xin lai tu dau qua dialog he thong - mo lai MainActivity de xin lai.",
                isError = true
            )
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

        // ✅ GIU NGUYEN START_NOT_STICKY (khong doi sang START_STICKY): neu OS kill roi tu
        // restart service voi Intent RONG, van roi vao nhanh loi resultCode/resultData o tren
        // ngay lap tuc - khong co tac dung thuc te gi de "tu phuc hoi", chi lam log nhieu rac
        // roi tu stopSelf() lai. Muc tieu that su la NGAN bi kill tu dau (WakeLock +
        // onTaskRemoved ben duoi), khong phai co gang restart sau khi da chet.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        musicInput?.stopCapture()
        mediaProjection?.stop()
        mediaProjection = null
        releaseWakeLock()
        logBoth("Service destroyed")
        super.onDestroy()
    }

    // ✅ MOI: tham khao tu aichatvn2's WebhookGatewayService.onTaskRemoved(). Khi nguoi dung
    // vuot xoa app khoi man hinh da nhiem, nhieu OEM (bao gom Honor) mac dinh kill LUON tien
    // trinh dung nay tru khi service tu "phan ung" ngay tai day. CO GANG khoi dong lai
    // foreground service ngay lap tuc de giam kha nang bi OS don don tien trinh hoan toan -
    // NHUNG can hieu ro: Intent restartServiceIntent o day KHONG mang theo resultCode/
    // resultData cu (khong the dinh kem lai MediaProjection cu da bi thu hoi), nen sau khi
    // restart, service se roi vao nhanh "Thieu resultCode/resultData" va tu dung lai ngay -
    // day la KY VONG DUNG, khong phai bug. Tac dung thuc su cua ham nay la giu foreground
    // service instance (va do do ca tien trinh) song sot qua HANH DONG vuot xoa cu the, cho
    // truong hop nguoi dung vo tinh vuot nham trong khi dang hat (WakeLock + startForeground
    // lai ngay giup MediaProjection dang chay KHONG bi ngat giua chung boi chinh hanh dong
    // vuot xoa nay).
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val stillCapturing = mediaProjection != null && musicInput != null
        logBoth("Task removed (app bi vuot xoa khoi da nhiem). Dang capture=$stillCapturing")

        if (stillCapturing) {
            // Van dang capture that su - CHI can dam bao foreground service + WakeLock
            // khong bi OS don theo task, KHONG can/KHONG the tao Intent moi voi
            // resultCode/resultData (van con nguyen trong bien mediaProjection/musicInput
            // hien tai cua chinh instance Service nay, khong mat gi ca). Goi lai
            // startForeground() de "khang cao" uu tien voi OS, ep no khong don tien trinh
            // theo task nay.
            try {
                startForeground(NOTIFICATION_ID, buildNotification())
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

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Karaoke App")
            .setContentText("Dang test capture nhac (Phase 1)")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }
}