package com.karaokeapp

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.karaokeapp.audio.music.CaptureLogBus
import com.karaokeapp.audio.music.PlaybackCaptureService

/**
 * Entry point tam thoi, chua co UI dep - chi du dung de test tung phase.
 *
 * ✅ CAP NHAT: khong con cho nguoi dung bam nut "Test Capture" nua - toan bo
 * flow xin quyen (RECORD_AUDIO -> dialog MediaProjection he thong -> start
 * PlaybackCaptureService) duoc tu dong kich hoat ngay trong onCreate(), moi
 * lan mo app. Ly do: MediaProjection la loai quyen Android BAT BUOC nguoi
 * dung phai tu tay bam dong y dialog he thong moi lan (khong co API nao cho
 * phep app tu xin ngam quyen nay, day la co che chong app len quay man hinh
 * cua chinh Android, khong lien quan gi toi code cua app). Vi vay day la muc
 * tu dong hoa GAN NHAT co the dat duoc: "mo app la thay dialog xin quyen
 * ngay", thay vi phai tim nut bam trong app truoc.
 *
 * Nut "Test Capture" cu van duoc GIU LAI (doi ten thanh "Xin quyen lai") de
 * nguoi dung chu dong bam lai neu vi ly do gi day flow tu dong luc mo app
 * khong chay (vi du: nguoi dung tu choi quyen luc dau, hoac quay lai app
 * sau khi service da bi OS kill va muon thu lai ma khong can khoi dong lai
 * app).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView

    // ✅ MOI: co danh dau da tu dong kich hoat flow xin quyen trong lan
    // onCreate() nay chua - tranh goi lai nhieu lan neu onCreate() bi goi lai
    // (vi du xoay man hinh) trong khi flow dang cho ket qua dialog.
    private var autoStartTriggered = false

    private val requestRecordAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        CaptureLogBus.log("[Activity] Ket qua xin RECORD_AUDIO: granted=$granted")
        if (granted) {
            requestNotificationPermissionIfNeeded()
        } else {
            Toast.makeText(this, "Can quyen RECORD_AUDIO de test capture", Toast.LENGTH_LONG).show()
        }
    }

    // ✅ MOI: xin rieng quyen POST_NOTIFICATIONS (bat buoc tu Android 13/API 33
    // tro len - chi khai bao trong AndroidManifest.xml la CHUA DU, phai xin
    // runtime permission nhu RECORD_AUDIO). Thieu quyen nay thi Service van
    // chay binh thuong (khong crash), nhung notification se bi AN HOAN TOAN -
    // day chinh la ly do khong thay thong bao nao ca du code da dung.
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        CaptureLogBus.log("[Activity] Ket qua xin POST_NOTIFICATIONS: granted=$granted")
        if (!granted) {
            Toast.makeText(
                this,
                "Khong co quyen thong bao - se khong thay duoc trang thai capture khi chay nen",
                Toast.LENGTH_LONG
            ).show()
        }
        launchScreenCapturePicker()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        launchScreenCapturePicker()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        CaptureLogBus.log(
            "[Activity] Ket qua MediaProjection dialog: resultCode=${result.resultCode} " +
                "(RESULT_OK=${Activity.RESULT_OK}), data=${result.data}"
        )
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, PlaybackCaptureService::class.java).apply {
                putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(PlaybackCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            CaptureLogBus.log(
                "[Activity] serviceIntent truoc khi start: extras keys=" +
                    "${serviceIntent.extras?.keySet()?.joinToString()}, " +
                    "resultCode trong extra=${serviceIntent.getIntExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, -999)}"
            )
            ContextCompat.startForegroundService(this, serviceIntent)
            statusText.text = "Dang capture... mo YouTube (qua Chrome neu can chay nen) roi xem log ben duoi"
        } else {
            CaptureLogBus.log("[Activity] ❌ Bi tu choi hoac data null - KHONG start service")
            Toast.makeText(this, "Da tu choi chia se man hinh/audio", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            text = "Karaoke App - Phase 1: AudioPlaybackCapture test"
            setPadding(24, 24, 24, 8)
        }

        // ✅ SUA: doi ten nut cho dung ban chat moi - day gio la nut "xin lai"
        // du phong, khong phai buoc bat buoc dau tien nua.
        val retryButton = Button(this).apply {
            text = "Xin quyen lai"
            setOnClickListener { onTestCaptureClicked() }
        }

        val copyButton = Button(this).apply {
            text = "Copy Log"
            setOnClickListener { copyLogToClipboard() }
        }

        val clearButton = Button(this).apply {
            text = "Xoa Log"
            setOnClickListener {
                CaptureLogBus.clear()
                logText.text = ""
            }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(retryButton)
            addView(copyButton)
            addView(clearButton)
        }

        logText = TextView(this).apply {
            setPadding(16, 16, 16, 16)
            textSize = 12f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }

        logScrollView = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(buttonRow)
            addView(logScrollView)
        }
        setContentView(rootLayout)

        logText.text = CaptureLogBus.getAllLogsText()
        scrollLogToBottom()

        // ✅ SUA LOI QUAN TRONG: chi tu dong kich hoat flow xin quyen NEU service
        // CHUA dang giu 1 session capture hop le - truoc day luon tu kich hoat
        // vo dieu kien, khien moi lan mo lai app (ke ca khi capture dang chay
        // tot tu truoc) deu tao 1 MediaProjection MOI, bo lai session cu chay
        // "zombie" gay loi -2 lap lai vo han (xem giai thich day du trong
        // PlaybackCaptureService.kt va MusicInput.kt).
        if (!autoStartTriggered) {
            autoStartTriggered = true
            if (PlaybackCaptureService.isCapturing()) {
                CaptureLogBus.log(
                    "[Activity] Service dang capture san (isCapturing=true) - " +
                        "BO QUA tu dong xin quyen lai, chi hien lai log cu."
                )
                statusText.text = "Dang capture (session cu van con song)... xem log ben duoi"
            } else {
                CaptureLogBus.log("[Activity] Chua co session capture nao - tu dong kich hoat flow xin quyen")
                onTestCaptureClicked()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CaptureLogBus.setListener { line ->
            runOnUiThread {
                logText.append("\n$line")
                scrollLogToBottom()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CaptureLogBus.setListener(null)
    }

    private fun scrollLogToBottom() {
        logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copyLogToClipboard() {
        val text = CaptureLogBus.getAllLogsText()
        if (text.isBlank()) {
            Toast.makeText(this, "Chua co log nao de copy", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("karaoke-app log", text))
        Toast.makeText(this, "Da copy log vao clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun onTestCaptureClicked() {
        CaptureLogBus.log("[Activity] Bat dau flow xin quyen (tu dong hoac bam lai thu cong)")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestNotificationPermissionIfNeeded()
        }
    }

    private fun launchScreenCapturePicker() {
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}