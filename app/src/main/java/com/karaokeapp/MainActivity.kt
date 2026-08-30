package com.karaokeapp

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
 * Phase 1: nut "Test Capture" xin quyen RECORD_AUDIO -> xin quyen
 * MediaProjection (dialog he thong) -> start PlaybackCaptureService.
 *
 * Man hinh co 1 khung log cuon duoc + nut "Copy Log" - vi nguoi dung build
 * app qua GitHub Actions va chi thao tac tren dien thoai, khong co
 * adb/Logcat de xem log ngoai app.
 *
 * ✅ DEBUG: da them log chi tiet o CA 2 nhanh (thanh cong/tu choi) cua
 * screenCaptureLauncher, va log noi dung serviceIntent TRUOC khi goi
 * startForegroundService - de xac dinh chinh xac du lieu bi mat o dau neu
 * PlaybackCaptureService bao "Thieu resultCode/resultData".
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView

    private val requestRecordAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        CaptureLogBus.log("[Activity] Ket qua xin RECORD_AUDIO: granted=$granted")
        if (granted) {
            launchScreenCapturePicker()
        } else {
            Toast.makeText(this, "Can quyen RECORD_AUDIO de test capture", Toast.LENGTH_LONG).show()
        }
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
            statusText.text = "Dang capture... mo YouTube phat nhac roi xem log ben duoi"
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

        val testButton = Button(this).apply {
            text = "Test Capture"
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
            addView(testButton)
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
        CaptureLogBus.log("[Activity] Bam Test Capture")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchScreenCapturePicker()
        }
    }

    private fun launchScreenCapturePicker() {
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
