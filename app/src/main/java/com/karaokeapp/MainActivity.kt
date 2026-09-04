package com.karaokeapp

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.karaokeapp.audio.mic.MicInput
import com.karaokeapp.audio.music.CaptureLogBus
import com.karaokeapp.audio.music.PlaybackCaptureService
import com.karaokeapp.audio.output.OutputRouter
import com.karaokeapp.webrtc.QrJoinData
import com.karaokeapp.webrtc.SignalingClient
import com.karaokeapp.webrtc.WebRtcManager

/**
 * Entry point tam thoi, chua co UI dep - chi du dung de test tung phase.
 *
 * ✅ CAP NHAT: khong con cho nguoi dung bam nut "Test Capture" nua - toan bo
 * flow xin quyen (RECORD_AUDIO -> dialog MediaProjection he thong -> start
 * PlaybackCaptureService) duoc tu dong kich hoat ngay trong onCreate(), moi
 * lan mo app.
 *
 * ✅ MOI (fix "Mixer Test chi song ~5 giay roi im, phai thu app xuong moi to
 * lai"): them flow xin quyen "Hien thi tren ung dung khac" (SYSTEM_ALERT_WINDOW).
 *
 * ⚠️ CAP NHAT LON NHAT (fix "May B mat ket noi moi lan bam play/pause tren
 * May A" - xem giai thich chi tiet trong PlaybackCaptureService.kt):
 * TOAN BO logic mo/dong Phong Karaoke (SignalingServer + WebRtcManager phia
 * Host) da CHUYEN KHOI file nay, sang chay trong PlaybackCaptureService -
 * noi duoc bao ve boi foreground service, KHONG bi OS chu dong huy khi
 * nguoi dung chuyen sang app khac (YouTube) nhu chinh MainActivity nay
 * truoc day. MainActivity gio CHI con:
 * 1. Gui Intent ACTION_START_HOST_ROOM/ACTION_STOP_HOST_ROOM toi Service.
 * 2. Nhan ket qua qua 3 callback TINH (giong tinh than onResumedCallback/
 *    refreshMixerTestButtonState() da co san tu truoc):
 *    - onRoomReadyCallback: phong da mo xong, co QrJoinData de hien QR.
 *    - onRoomErrorCallback: mo phong that bai (vi du khong tim thay IP).
 *    - onRoomMicStatusCallback: 1 May B/C vua ket noi/ngat ket noi.
 * 3. Trong onResume(), tu hoi lai PlaybackCaptureService.getActiveRoomQrData()
 *    de biet phong CO DANG CHAY khong (co the da duoc mo tu truoc khi
 *    Activity nay bi tao lai do xoay man hinh/OS don dep) - dam bao dialog
 *    QR/trang thai nut khong bi "quen" mat du Service van dang giu phong.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var onResumedCallback: (() -> Unit)? = null

        @Volatile
        private var activityRef: java.lang.ref.WeakReference<MainActivity>? = null

        /**
         * ✅ MOI (Phong Karaoke - xem giai thich day du o dau file): goi tu
         * PlaybackCaptureService.startHostRoomInternal() khi phong VUA MO
         * THANH CONG - dua qrData ve de MainActivity (neu con song) hien
         * dialog QR. An toan neu Activity da bi huy (activityRef.get() null)
         * - Service se khong crash, chi la khong co dialog nao hien ra luc
         * do (nguoi dung co the tu mo lai app, onResume() se tu kiem tra lai
         * PlaybackCaptureService.getActiveRoomQrData() de bu lai).
         */
        @Volatile
        var onRoomReadyCallback: ((QrJoinData) -> Unit)? = null

        /**
         * ✅ MOI: goi tu Service khi mo phong THAT BAI (vi du khong tim thay
         * IP Wi-Fi) - dua ly do loi ve de hien Toast.
         */
        @Volatile
        var onRoomErrorCallback: ((String) -> Unit)? = null

        /**
         * ✅ MOI: goi tu Service moi khi 1 May B/C ket noi/ngat ket noi vao
         * phong hien tai - dua clientId + trang thai (true=vua ket noi,
         * false=vua ngat) de hien Toast tuong ung.
         */
        @Volatile
        var onRoomMicStatusCallback: ((String, Boolean) -> Unit)? = null

        fun refreshMixerTestButtonState(isRunning: Boolean) {
            val activity = activityRef?.get() ?: return
            activity.runOnUiThread {
                if (activity.mixerTestRunning != isRunning) {
                    activity.mixerTestRunning = isRunning
                    activity.mixerTestButtonRef?.text = if (isRunning) "Tat Mixer Test" else "Bat Mixer Test"
                    CaptureLogBus.log(
                        "[Activity] 🔄 Da dong bo lai trang thai Mixer Test NGAY LAP TUC " +
                            "(khong doi onResume): dangChay=$isRunning"
                    )
                }
            }
        }
    }

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView

    private var autoStartTriggered = false

    private var micInput: MicInput? = null
    private var outputRouter: OutputRouter? = null
    private var micLoopbackRunning = false

    private var mixerTestRunning = false

    private var mixerTestButtonRef: Button? = null

    private var streamMusicMuteTestActive = false
    private var savedStreamMusicVolumeBeforeTest = -1

    private data class UsageCandidate(val label: String, val usage: Int, val legacyStreamToBoost: Int?)
    private val usageCandidates = listOf(
        UsageCandidate("MEDIA (hien tai, STREAM_MUSIC)", AudioAttributes.USAGE_MEDIA, legacyStreamToBoost = null),
        UsageCandidate(
            "ASSISTANCE_SONIFICATION (STREAM_SYSTEM)",
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            legacyStreamToBoost = AudioManager.STREAM_SYSTEM
        ),
        UsageCandidate(
            "ALARM (STREAM_ALARM) ⚠️",
            AudioAttributes.USAGE_ALARM,
            legacyStreamToBoost = AudioManager.STREAM_ALARM
        )
    )
    private var usageCandidateIndex = 0

    private var savedBoostedStreamVolume: Int? = null
    private var boostedLegacyStream: Int? = null

    // ✅ SUA (Phase 5 - xem giai thich chi tiet o dau file): hostSignalingServer/
    // hostWebRtcManager DA CHUYEN vao PlaybackCaptureService - Activity nay
    // KHONG con giu 2 field do nua. wirelessMicInput (vai tro Mic B) VAN o
    // lai day - day la mic VAT LY cua chinh may dang cam, gan chat voi vong
    // doi cua thao tac "dang cam mic quet QR", khac ban chat voi vai tro
    // Host (Mixer) can song ben bi OS don dep Activity.
    private var signalingClient: SignalingClient? = null
    private var webRtcManager: WebRtcManager? = null
    private var wirelessMicInput: MicInput? = null

    // ✅ MOI: giu QrJoinData cua phong DANG hien dialog (neu co) - de biet
    // co can tu dong mo lai dialog QR trong onResume() hay khong (truong
    // hop phong da duoc mo tu truoc, Activity chi vua bi tao lai).
    private var currentRoomQrData: QrJoinData? = null
    private var roomStatusDialog: androidx.appcompat.app.AlertDialog? = null

    // ✅ MOI (fix "May B can nut de quet lai May A ma khong can mo lai QR"):
    // luu lai QrJoinData cua LAN QUET THANH CONG GAN NHAT (host/port/roomId/
    // token) - cho phep bam 1 nut de ket noi lai voi CUNG 1 phong, thay vi
    // bat buoc phai mo lai dialog QR tren May A moi lan May B bi rot mang/
    // mat ket noi. Chi mat tac dung neu May A DA DONG phong va mo phong MOI
    // (token/port co the doi) - luc do van can quet QR moi that.
    private var lastJoinedQrData: QrJoinData? = null

    private val qrScanLauncher = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        if (result.contents != null) {
            joinRoomFromQr(result.contents)
        } else {
            Toast.makeText(this, "Da huy quet ma QR", Toast.LENGTH_SHORT).show()
        }
    }

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

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = Settings.canDrawOverlays(this)
        CaptureLogBus.log("[Activity] Ket qua sau khi quay lai tu man hinh cap quyen overlay: granted=$granted")
        if (granted) {
            Toast.makeText(this, "Da co quyen hien thi noi - bam 'Bat Mixer Test' de tiep tuc", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Chua cap quyen - nut 'Kich hoat lai' se khong hien khi bat Mixer Test",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        CaptureLogBus.log("[Activity] Chua co quyen 'Hien thi tren ung dung khac' - mo man hinh Settings de xin.")
        Toast.makeText(
            this,
            "Can cap quyen 'Hien thi tren ung dung khac' de dung nut noi 'Kich hoat lai' - bam lai 'Bat Mixer Test' sau khi cap xong",
            Toast.LENGTH_LONG
        ).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        activityRef = java.lang.ref.WeakReference(this)

        statusText = TextView(this).apply {
            text = "Karaoke App - Phase 1: AudioPlaybackCapture test"
            setPadding(24, 24, 24, 8)
        }

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

        val micLoopbackButton = Button(this).apply {
            text = "Bat Mic Loopback"
            setOnClickListener { toggleMicLoopback(this) }
        }

        val mixerTestButton = Button(this).apply {
            text = "Bat Mixer Test"
            setOnClickListener { toggleMixerTest(this) }
        }
        mixerTestButtonRef = mixerTestButton

        val muteTestButton = Button(this).apply {
            text = "Test Mute STREAM_MUSIC"
            setOnClickListener { toggleStreamMusicMuteTest(this) }
        }

        val usageSelectButton = Button(this).apply {
            text = "Usage test: ${usageCandidates[usageCandidateIndex].label}"
            setOnClickListener { cycleUsageCandidate(this) }
        }

        val overlayPermissionButton = Button(this).apply {
            text = if (Settings.canDrawOverlays(this@MainActivity)) {
                "✅ Da co quyen hien thi noi"
            } else {
                "Cap quyen hien thi noi (cho nut Kich hoat lai)"
            }
            setOnClickListener {
                if (ensureOverlayPermission()) {
                    text = "✅ Da co quyen hien thi noi"
                    Toast.makeText(this@MainActivity, "Da co quyen hien thi noi roi", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(retryButton)
            addView(copyButton)
            addView(clearButton)
        }

        val buttonRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(micLoopbackButton)
            addView(mixerTestButton)
        }

        val buttonRow3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(muteTestButton)
            addView(usageSelectButton)
        }

        val buttonRow4 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(overlayPermissionButton)
        }

        // ✅ SUA (Phase 5): "Tao phong" gio CHI gui Intent ACTION_START_HOST_ROOM
        // toi Service - Service tu lam het viec con lai (tao QrJoinData,
        // SignalingServer, WebRtcManager) roi bao ket qua ve qua
        // onRoomReadyCallback/onRoomErrorCallback. KHONG con logic Host nao
        // chay truc tiep trong Activity nay nua.
        val createRoomButton = Button(this).apply {
            text = "🎤 Tao phong (Mixer A)"
            setOnClickListener { requestStartHostRoom() }
        }

        val joinRoomButton = Button(this).apply {
            text = "📷 Quet QR vao phong (Mic B)"
            setOnClickListener {
                val options = com.journeyapps.barcodescanner.ScanOptions().apply {
                    setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    setPrompt("Huong camera ve phia ma QR tren May A (Mixer)")
                    setBeepEnabled(true)
                    setOrientationLocked(true)
                }
                qrScanLauncher.launch(options)
            }
        }

        // ✅ MOI (fix "May B can nut de quet lai May A ma khong can mo lai
        // QR"): dung lai QrJoinData cua lan quet gan nhat (xem
        // reconnectToLastRoom()) - huu ich khi May B bi rot mang/bi OS don
        // dep giua chung, ma May A van dang mo CUNG 1 phong do.
        val reconnectButton = Button(this).apply {
            text = "🔄 Ket noi lai (Mic B)"
            setOnClickListener { reconnectToLastRoom() }
        }

        // ✅ FIX ("hoan toan khong thay nut Ket noi lai"): nguyen nhan THAT
        // SU la loi bo tri UI, khong phai loi logic - buttonRow5 truoc day
        // dat 3 nut CO TEXT DAI (vd "📷 Quet QR vao phong (Mic B)") vao 1
        // LinearLayout HORIZONTAL voi chieu rong MAC DINH (WRAP_CONTENT).
        // Khi tong chieu rong 3 nut vuot qua chieu rong man hinh,
        // LinearLayout KHONG tu xuong dong va KHONG the cuon ngang - noi
        // dung vuot qua chi don gian bi VE RA NGOAI vung nhin thay (mat
        // hoan toan, khong phai bi cat 1 phan) - dung 100% trieu chung
        // "hoan toan khong thay nut" da bao.
        //
        // Sua: ep moi nut trong hang nay chia deu chieu rong man hinh
        // (width=0 + weight=1, kieu "match_parent chia deu") - dam bao
        // LUON du 3 nut nam gon trong 1 hang bat ke text dai bao nhieu (chi
        // bi thu nho/xuong dong NOI BO trong nut, khong bao gio bi day ra
        // ngoai man hinh nua).
        val buttonRow5 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(createRoomButton, joinRoomButton, reconnectButton).forEach { btn ->
                btn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(btn)
            }
        }

        val muteLocalMicButton = Button(this).apply {
            text = if (PlaybackCaptureService.isLocalMicMutedForMixer()) "🔊 Bat lai mic may nay" else "🔇 Khoa mic may nay"
            setOnClickListener {
                val newState = !PlaybackCaptureService.isLocalMicMutedForMixer()
                PlaybackCaptureService.setLocalMicMutedForMixer(newState)
                text = if (newState) "🔊 Bat lai mic may nay" else "🔇 Khoa mic may nay"
                Toast.makeText(
                    this@MainActivity,
                    // ✅ SUA: truoc day chi dung cho vai tro May A ("chi nhan
                    // qua mang") - gio flag nay ap dung CA HAI vai tro (xem
                    // fix trong startWirelessMicStream()), nen doi thanh mo
                    // ta chung chung, dung cho ca May A (Mixer) lan May B
                    // (Mic khong day dang gui qua mang).
                    if (newState) "Da khoa mic vat ly cua may nay (Mixer se khong nhan tu mic tai cho / Mic khong day se ngung gui am thanh qua mang)"
                    else "Da bat lai mic vat ly cua may nay",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val stopAllButton = Button(this).apply {
            text = "⏹ Tat hoan toan (an nut noi)"
            setOnClickListener {
                val intent = Intent(this@MainActivity, PlaybackCaptureService::class.java).apply {
                    action = PlaybackCaptureService.ACTION_STOP_ALL
                }
                startService(intent)
                Toast.makeText(this@MainActivity, "Da gui lenh tat hoan toan", Toast.LENGTH_SHORT).show()
            }
        }

        val buttonRow6 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(muteLocalMicButton)
            addView(stopAllButton)
        }

        // ✅ MOI (Phase 6 - "ban mixer"): mo dialog dieu chinh volume/EQ/
        // Compressor/Echo/AutoGain rieng cho tung nguon vocal (mic tai cho +
        // tung May B/C dang hat) va volume nhac nen/tong the - thay the
        // HowlGuard tu dong da go bo, xem giai thich chi tiet trong
        // PlaybackCaptureService.kt va VocalChannel.kt.
        val mixerBoardButton = Button(this).apply {
            text = "🎚️ Ban mixer"
            setOnClickListener { showMixerBoardDialog() }
        }

        val buttonRow7 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(mixerBoardButton)
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
            addView(buttonRow2)
            addView(buttonRow3)
            addView(buttonRow4)
            addView(buttonRow5)
            addView(buttonRow6)
            addView(buttonRow7)
            addView(logScrollView)
        }
        setContentView(rootLayout)

        logText.text = CaptureLogBus.getAllLogsText()
        scrollLogToBottom()

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

        // ✅ MOI: dang ky 3 callback Phong Karaoke NGAY khi Activity len
        // foreground - giong tinh than CaptureLogBus.setListener() ben duoi.
        // Goi runOnUiThread ben trong vi Service co the goi cac callback nay
        // tu bat ky thread nao.
        onRoomReadyCallback = { qrData ->
            runOnUiThread {
                currentRoomQrData = qrData
                showQrCodeDialog(qrData)
            }
        }
        onRoomErrorCallback = { reason ->
            runOnUiThread {
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            }
        }
        onRoomMicStatusCallback = { clientId, connected ->
            runOnUiThread {
                val msg = if (connected) "Mic '$clientId' da ket noi!" else "Mic '$clientId' da ngat ket noi"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        // ✅ MOI: dong bo lai trang thai Phong Karaoke tu Service - can
        // thiet vi phong co the DA DUOC MO TU TRUOC khi Activity nay bi tao
        // lai (xoay man hinh, hoac OS don dep Activity nen roi nguoi dung
        // tu mo lai app) - xem giai thich chi tiet o dau file. Neu Service
        // bao co phong dang chay ma dialog QR chua hien (currentRoomQrData
        // con null), TU DONG hien lai dialog de nguoi dung khong bi "mat"
        // QR code khi quay lai app.
        val activeRoom = PlaybackCaptureService.getActiveRoomQrData()
        if (activeRoom != null && currentRoomQrData == null) {
            currentRoomQrData = activeRoom
            CaptureLogBus.log("[Activity] Phat hien Phong Karaoke dang chay tu Service (co the da mo truoc do) - hien lai QR.")
            showQrCodeDialog(activeRoom)
        } else if (activeRoom == null && currentRoomQrData != null) {
            // Phong da bi dong tu ben ngoai (vi du Service tu dong dong khi
            // ket thuc Phase 1) - don dep trang thai dialog cu neu con giu.
            currentRoomQrData = null
            roomStatusDialog?.dismiss()
            roomStatusDialog = null
        }

        CaptureLogBus.setListener { line ->
            runOnUiThread {
                logText.append("\n$line")
                scrollLogToBottom()
            }
        }

        val actuallyRunning = PlaybackCaptureService.isMixerTestActive()
        if (actuallyRunning != mixerTestRunning) {
            mixerTestRunning = actuallyRunning
            mixerTestButtonRef?.text = if (mixerTestRunning) "Tat Mixer Test" else "Bat Mixer Test"
            CaptureLogBus.log("[Activity] Da dong bo lai trang thai Mixer Test tu Service: dangChay=$actuallyRunning")
        }

        onResumedCallback?.let { callback ->
            onResumedCallback = null
            callback.invoke()
        }
    }

    override fun onPause() {
        super.onPause()
        CaptureLogBus.setListener(null)
        // ✅ MOI: go dang ky 3 callback Phong Karaoke khi Activity roi
        // foreground - tranh Service goi runOnUiThread() tren 1 Activity da
        // vao nen/sap bi huy (khong crash gi neu lo goi, nhung don sach cho
        // ro rang, giong tinh than CaptureLogBus.setListener(null) o tren).
        onRoomReadyCallback = null
        onRoomErrorCallback = null
        onRoomMicStatusCallback = null
    }

    /**
     * ✅ MOI (Phase 6 - "ban mixer"): dialog liet ke tat ca nguon vocal dang
     * hoat dong (mic tai cho "local_mic" + tung clientId May B/C dang hat -
     * lay tu PlaybackCaptureService.listActiveChannelIds(), tu dong rong
     * khi 1 May B/C ngat ket noi vi Service da tu don channel tuong ung) va
     * cho phep dieu chinh volume/EQ/AutoGain/Compressor/Echo cho tung
     * kenh, cong voi 2 thanh truot rieng cho volume Nhac nen va Tong the.
     * Thay the hoan toan HowlGuard tu dong da go bo (xem VocalChannel.kt).
     */
    private fun showMixerBoardDialog() {
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        scrollContent.addView(buildMasterVolumeSection())

        val channelIds = PlaybackCaptureService.listActiveChannelIds()
        if (channelIds.isEmpty()) {
            scrollContent.addView(TextView(this).apply {
                text = "\nChua co kenh vocal nao dang hoat dong (chua bat Mixer Test / chua co ai hat).\nMo lai 'Ban mixer' sau khi bat mic de thay danh sach."
                setPadding(0, 24, 0, 0)
            })
        } else {
            channelIds.forEach { sourceId ->
                scrollContent.addView(buildChannelSection(sourceId))
            }
        }

        val scrollView = ScrollView(this).apply { addView(scrollContent) }

        val refreshButton = Button(this).apply {
            text = "🔄 Lam moi danh sach kenh"
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🎚️ Ban mixer")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(refreshButton)
                addView(scrollView)
            })
            .setPositiveButton("Dong", null)
            .create()

        refreshButton.setOnClickListener {
            dialog.dismiss()
            showMixerBoardDialog()
        }

        dialog.show()
    }

    /** Nhan mo ta ngan gon cho 1 sourceId - "Mic tai cho (May nay)" cho local_mic, con lai hien nguyen clientId. */
    private fun channelDisplayLabel(sourceId: String): String {
        return if (sourceId == com.karaokeapp.audio.mixer.LowLatencyMixer.SOURCE_LOCAL_MIC) {
            "🎤 Mic tai cho (may nay)"
        } else {
            "🎤 May: $sourceId"
        }
    }

    /** Slider [-12f, 12f] dB dung chung cho ca 3 dai EQ (bass/mid/treble) - progress 0..240, 120 = 0dB. */
    private fun addDbSeekBar(
        container: LinearLayout,
        label: String,
        initialDb: Float,
        onChange: (Float) -> Unit
    ) {
        val valueLabel = TextView(this).apply {
            text = "$label: ${"%.1f".format(initialDb)} dB"
        }
        val seekBar = android.widget.SeekBar(this).apply {
            max = 240
            progress = ((initialDb + 12f) * 10f).toInt().coerceIn(0, 240)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val db = (progress / 10f) - 12f
                    valueLabel.text = "$label: ${"%.1f".format(db)} dB"
                    if (fromUser) onChange(db)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        container.addView(valueLabel)
        container.addView(seekBar)
    }

    /** Slider [0f, 2f] dung chung cho volume kenh/nhac nen/tong the - progress 0..200, 100 = 1.0x. */
    private fun addVolumeSeekBar(
        container: LinearLayout,
        label: String,
        initialVolume: Float,
        onChange: (Float) -> Unit
    ) {
        val valueLabel = TextView(this).apply {
            text = "$label: ${"%.0f".format(initialVolume * 100)}%"
        }
        val seekBar = android.widget.SeekBar(this).apply {
            max = 200
            progress = (initialVolume * 100f).toInt().coerceIn(0, 200)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val volume = progress / 100f
                    valueLabel.text = "$label: ${"%.0f".format(volume * 100)}%"
                    if (fromUser) onChange(volume)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        container.addView(valueLabel)
        container.addView(seekBar)
    }

    private fun buildMasterVolumeSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24)
            addView(TextView(this@MainActivity).apply {
                text = "— Tong the —"
                textSize = 16f
            })
            addVolumeSeekBar(this, "Nhac nen", PlaybackCaptureService.getMusicVolume()) { v -> PlaybackCaptureService.setMusicVolume(v) }
            addVolumeSeekBar(this, "Tong the (master)", PlaybackCaptureService.getMasterVolume()) { v -> PlaybackCaptureService.setMasterVolume(v) }
        }
    }

    private fun buildChannelSection(sourceId: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 24)

            addView(TextView(this@MainActivity).apply {
                text = "— ${channelDisplayLabel(sourceId)} —"
                textSize = 16f
            })

            val mutedCheckBox = android.widget.CheckBox(this@MainActivity).apply {
                text = "Cau kenh nay"
                isChecked = PlaybackCaptureService.isChannelMuted(sourceId)
                setOnCheckedChangeListener { _, checked ->
                    PlaybackCaptureService.setChannelMuted(sourceId, checked)
                }
            }
            addView(mutedCheckBox)

            addVolumeSeekBar(this, "Volume", PlaybackCaptureService.getChannelVolume(sourceId)) { v ->
                PlaybackCaptureService.setChannelVolume(sourceId, v)
            }

            // ✅ SUA: doc dung 3 gia tri EQ HIEN TAI tu Service (thay vi hang
            // so mac dinh -2/1/3 dB truoc day) - dialog gio hien DUNG nhung
            // gi nguoi dung da chinh o lan mo truoc, khong bi "quen" ve mac dinh.
            var bassDb = PlaybackCaptureService.getChannelEQBass(sourceId)
            var midDb = PlaybackCaptureService.getChannelEQMid(sourceId)
            var trebleDb = PlaybackCaptureService.getChannelEQTreble(sourceId)
            addDbSeekBar(this, "Bass", bassDb) { db -> bassDb = db; PlaybackCaptureService.setChannelEQ(sourceId, bassDb, midDb, trebleDb) }
            addDbSeekBar(this, "Mid", midDb) { db -> midDb = db; PlaybackCaptureService.setChannelEQ(sourceId, bassDb, midDb, trebleDb) }
            addDbSeekBar(this, "Treble", trebleDb) { db -> trebleDb = db; PlaybackCaptureService.setChannelEQ(sourceId, bassDb, midDb, trebleDb) }

            val toggleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(android.widget.CheckBox(this@MainActivity).apply {
                    text = "AutoGain"
                    isChecked = PlaybackCaptureService.isChannelAutoGainEnabled(sourceId)
                    setOnCheckedChangeListener { _, checked -> PlaybackCaptureService.setChannelAutoGainEnabled(sourceId, checked) }
                })
                addView(android.widget.CheckBox(this@MainActivity).apply {
                    text = "EQ"
                    isChecked = PlaybackCaptureService.isChannelEQEnabled(sourceId)
                    setOnCheckedChangeListener { _, checked -> PlaybackCaptureService.setChannelEQEnabled(sourceId, checked) }
                })
            }
            addView(toggleRow)

            val toggleRow2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(android.widget.CheckBox(this@MainActivity).apply {
                    text = "Compressor"
                    isChecked = PlaybackCaptureService.isChannelCompressorEnabled(sourceId)
                    setOnCheckedChangeListener { _, checked -> PlaybackCaptureService.setChannelCompressorEnabled(sourceId, checked) }
                })
                addView(android.widget.CheckBox(this@MainActivity).apply {
                    text = "Echo"
                    isChecked = PlaybackCaptureService.isChannelEchoEnabled(sourceId)
                    setOnCheckedChangeListener { _, checked -> PlaybackCaptureService.setChannelEchoEnabled(sourceId, checked) }
                })
            }
            addView(toggleRow2)
        }
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

    private fun toggleMicLoopback(button: Button) {
        if (micLoopbackRunning) {
            micInput?.stopCapture()
            outputRouter?.stop()
            micInput = null
            outputRouter = null
            micLoopbackRunning = false
            button.text = "Bat Mic Loopback"
            restoreBoostedStreamVolumeIfAny()
            CaptureLogBus.log("[Activity] Da tat Mic Loopback")
            return
        }

        if (mixerTestRunning) {
            Toast.makeText(
                this,
                "Dang chay Mixer Test - hay tat Mixer Test truoc khi bat Mic Loopback",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Can quyen RECORD_AUDIO - hay bam 'Xin quyen lai' truoc", Toast.LENGTH_LONG).show()
            return
        }

        CaptureLogBus.log("[Activity] Bat dau Mic Loopback (Phase 2 - do latency)")
        val selectedUsage = usageCandidates[usageCandidateIndex]
        CaptureLogBus.log("[Activity] [UsageTest] Dang test voi usage=${selectedUsage.label}")
        boostStreamVolumeForTestIfNeeded(selectedUsage)
        val router = OutputRouter(this, selectedUsage.usage).apply { start() }
        val mic = MicInput(this)
        outputRouter = router
        micInput = mic
        mic.startCapture(
            onPcmChunk = { buffer, size ->
                router.write(buffer, size)
            },
            onTransientDetected = { offsetInBuffer, captureNanoTime ->
                val targetFrame = router.totalFramesWritten + offsetInBuffer
                val presentationNanoTime = router.estimatePresentationNanoTime(targetFrame)
                if (presentationNanoTime != null) {
                    val latencyMs = (presentationNanoTime - captureNanoTime) / 1_000_000.0
                    CaptureLogBus.log("[LatencyProbe] 🎯 Do tre uoc tinh: %.1f ms".format(latencyMs))
                } else {
                    CaptureLogBus.log("[LatencyProbe] ⚠️ Chua tinh duoc (xem canh bao ben tren) - thu vo tay lai.")
                }
            }
        )
        micLoopbackRunning = true
        button.text = "Tat Mic Loopback"
    }

    private fun boostStreamVolumeForTestIfNeeded(candidate: UsageCandidate) {
        val stream = candidate.legacyStreamToBoost ?: return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedBoostedStreamVolume = audioManager.getStreamVolume(stream)
        boostedLegacyStream = stream
        val maxVolume = audioManager.getStreamMaxVolume(stream)
        audioManager.setStreamVolume(stream, maxVolume, 0)
        CaptureLogBus.log(
            "[Activity] [UsageTest] ⚠️ Da tam day stream=$stream len max=$maxVolume " +
                "(muc goc da luu=$savedBoostedStreamVolume) de test cong bang. " +
                "SE tu khoi phuc khi tat Mic Loopback."
        )
    }

    private fun restoreBoostedStreamVolumeIfAny() {
        val stream = boostedLegacyStream ?: return
        val savedVolume = savedBoostedStreamVolume ?: return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(stream, savedVolume, 0)
        CaptureLogBus.log("[Activity] [UsageTest] Da khoi phuc stream=$stream ve muc goc=$savedVolume")
        boostedLegacyStream = null
        savedBoostedStreamVolume = null
    }

    private fun cycleUsageCandidate(button: Button) {
        if (micLoopbackRunning) {
            Toast.makeText(
                this,
                "Hay tat Mic Loopback truoc khi doi usage test",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        usageCandidateIndex = (usageCandidateIndex + 1) % usageCandidates.size
        val selected = usageCandidates[usageCandidateIndex]
        button.text = "Usage test: ${selected.label}"
        CaptureLogBus.log("[Activity] [UsageTest] Da doi sang usage=${selected.label} - bam 'Bat Mic Loopback' de test qua loa/BT that.")
    }

    // =========================================================================
    // PHASE 5 - PHONG KARAOKE LAN (WEBRTC + QR)
    // =========================================================================

    /**
     * ✅ SUA (fix goc "May B mat ket noi moi lan bam play/pause tren May A"):
     * truoc day ham nay TU LAM HET (tao QrJoinData, SignalingServer,
     * WebRtcManager ngay tai Activity). GIO chi gui 1 Intent
     * ACTION_START_HOST_ROOM toi Service - Service se tu lam toan bo phan
     * con lai va bao ket qua ve qua onRoomReadyCallback/onRoomErrorCallback
     * (da dang ky san trong onResume()). Xem giai thich day du trong
     * PlaybackCaptureService.startHostRoomInternal().
     */
    private fun requestStartHostRoom() {
        if (!PlaybackCaptureService.isCapturing()) {
            Toast.makeText(
                this,
                "Chua capture nhac (Phase 1) - hay bam 'Xin quyen lai' truoc khi tao phong",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (!PlaybackCaptureService.isMixerTestActive()) {
            Toast.makeText(
                this,
                "Chua bat Mixer Test - hay bat Mixer Test truoc khi tao phong, neu khong Mic tu xa se khong co cho de hoa vao",
                Toast.LENGTH_LONG
            ).show()
        }
        val intent = Intent(this, PlaybackCaptureService::class.java).apply {
            action = PlaybackCaptureService.ACTION_START_HOST_ROOM
        }
        ContextCompat.startForegroundService(this, intent)
        CaptureLogBus.log("[Activity] Da gui ACTION_START_HOST_ROOM toi Service - dang cho ket qua qua callback.")
    }

    private fun showQrCodeDialog(qrData: QrJoinData) {
        roomStatusDialog?.dismiss()

        val barcodeEncoder = com.journeyapps.barcodescanner.BarcodeEncoder()
        val bitmap = barcodeEncoder.encodeBitmap(qrData.toUriString(), com.google.zxing.BarcodeFormat.QR_CODE, 600, 600)

        val imageView = android.widget.ImageView(this).apply { setImageBitmap(bitmap) }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Phong: ${qrData.roomId} (IP: ${qrData.host})")
            .setView(imageView)
            .setPositiveButton("Dong") { d, _ -> d.dismiss() }
            .setNegativeButton("Dung phong") { _, _ -> requestStopHostRoom() }
            .create()
        roomStatusDialog = dialog
        dialog.show()
    }

    /**
     * ✅ SUA: gui Intent ACTION_STOP_HOST_ROOM toi Service thay vi tu dong
     * SignalingServer/WebRtcManager tai cho (2 object do khong con ton tai
     * o Activity nay nua - xem giai thich o dau file).
     */
    private fun requestStopHostRoom() {
        val intent = Intent(this, PlaybackCaptureService::class.java).apply {
            action = PlaybackCaptureService.ACTION_STOP_HOST_ROOM
        }
        startService(intent)
        currentRoomQrData = null
        roomStatusDialog = null
        Toast.makeText(this, "Da gui lenh dong phong Karaoke", Toast.LENGTH_SHORT).show()
    }

    /**
     * MAY B/C: quet ma QR va gia nhap phong cua May A.
     */
    private fun joinRoomFromQr(rawUri: String) {
        val qrData = QrJoinData.parse(rawUri)
        if (qrData == null) {
            Toast.makeText(this, "Ma QR khong dung dinh dang phong Karaoke!", Toast.LENGTH_LONG).show()
            return
        }
        lastJoinedQrData = qrData
        connectToRoomAsMic(qrData)
    }

    /**
     * ✅ MOI (fix "May B can nut de quet lai May A ma khong can mo lai QR"):
     * bam nut nay se ket noi lai voi phong CUOI CUNG da quet thanh cong
     * (lastJoinedQrData) - dung khi May B bi rot ket noi (vi du doi Wi-Fi,
     * app bi OS don dep) nhung May A van dang mo CUNG 1 phong do, khong can
     * phai cam May A len de hien lai dialog QR roi quet lai tu dau.
     */
    private fun reconnectToLastRoom() {
        val qrData = lastJoinedQrData
        if (qrData == null) {
            Toast.makeText(
                this,
                "Chua tung quet QR lan nao - hay bam 'Quet QR vao phong (Mic B)' truoc",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        // Don sach ket noi cu (neu con) truoc khi thu lai, tranh giu 2
        // SignalingClient/WebRtcManager song song cung luc.
        stopWirelessMicStream()
        Toast.makeText(this, "Dang ket noi lai voi phong cu...", Toast.LENGTH_SHORT).show()
        connectToRoomAsMic(qrData)
    }

    /**
     * Logic thiet lap ket noi Mic B toi May A - tach rieng khoi
     * joinRoomFromQr() de dung chung duoc voi reconnectToLastRoom() (khong
     * can parse lai chuoi QR, chi can QrJoinData da co san).
     */
    private fun connectToRoomAsMic(qrData: QrJoinData) {
        val myClientId = "Mic-" + (100..999).random()
        webRtcManager = WebRtcManager(this)

        val serverUri = java.net.URI("ws://${qrData.host}:${qrData.port}")
        signalingClient = SignalingClient(
            serverUri = serverUri,
            roomId = qrData.roomId,
            token = qrData.token,
            clientId = myClientId,
            listener = object : SignalingClient.Listener {
                override fun onConnectedToMixer() {}
                override fun onJoinedSuccess() {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Da vao phong! Dang truyen am thanh...", Toast.LENGTH_SHORT).show()
                        startWirelessMicStream()
                    }
                }
                override fun onJoinFailed(reason: String) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Khong the vao phong: $reason", Toast.LENGTH_LONG).show() }
                }
                override fun onAnswerReceived(sdp: String) {
                    webRtcManager?.handleRemoteAnswer(myClientId, sdp)
                }
                override fun onIceReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    webRtcManager?.addRemoteIceCandidate(myClientId, sdpMid, sdpMLineIndex, candidate)
                }
                override fun onDisconnected() {
                    runOnUiThread { stopWirelessMicStream() }
                }
            }
        ).apply { connect() }
    }

    private fun startWirelessMicStream() {
        if (micLoopbackRunning || mixerTestRunning) {
            Toast.makeText(
                this,
                "May nay dang chay Mic Loopback hoac Mixer Test - hay tat truoc khi lam Mic khong day cho phong",
                Toast.LENGTH_LONG
            ).show()
            stopWirelessMicStream()
            return
        }

        val client = signalingClient ?: return
        webRtcManager?.startClientPeer(
            signalingClient = client,
            onIceCandidateGenerated = { mid, idx, cand -> client.sendIce(mid, idx, cand) },
            onConnected = {
                CaptureLogBus.log("[Mic] WebRTC da thong mang! Bat dau thu am gui di...")
                val mic = MicInput(this)
                wirelessMicInput = mic
                mic.startCapture(onPcmChunk = { buffer, size ->
                    // ✅ FIX ("May B: nut Khoa mic khong hoat dung"): TRUOC DAY
                    // nut "Khoa mic may nay" chi duoc kiem tra (localMicMutedForMixer)
                    // trong callback cua Mixer Test (vai tro May A) - callback GUI
                    // PCM qua WebRTC nay (vai tro May B, Mic tu xa) KHONG he doc
                    // co flag do, nen bam nut tren May B khong co tac dung gi -
                    // Mic van tiep tuc gui am thanh qua mang binh thuong. Them
                    // dieu kien return SOM o day de dung chung 1 flag/1 nut cho
                    // ca 2 vai tro (May A: khoa mic Mixer local; May B: khoa mic
                    // dang gui qua mang).
                    if (PlaybackCaptureService.isLocalMicMutedForMixer()) return@startCapture
                    webRtcManager?.sendPcmChunkFromMic(buffer, size)
                })
            }
        )
    }

    private fun stopWirelessMicStream() {
        wirelessMicInput?.stopCapture()
        wirelessMicInput = null
        signalingClient?.close()
        signalingClient = null
        webRtcManager?.closeAll()
        webRtcManager = null
        Toast.makeText(this, "Da ngat ket noi Mic", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()

        if (activityRef?.get() === this) {
            activityRef = null
        }

        micInput?.stopCapture()
        outputRouter?.stop()

        if (streamMusicMuteTestActive && savedStreamMusicVolumeBeforeTest >= 0) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedStreamMusicVolumeBeforeTest, 0)
            CaptureLogBus.log("[Activity] [MuteTest] onDestroy: da tu dong khoi phuc STREAM_MUSIC (quen bam nut).")
        }

        restoreBoostedStreamVolumeIfAny()

        // ✅ SUA (fix goc cua toan bo bug nay): KHONG con goi
        // signalingServer?.stopServer()/webRtcManager?.closeAll() o day nua
        // - 2 object do (vai tro Host/Mixer) da chuyen han vao
        // PlaybackCaptureService va se TU SONG qua moi lan MainActivity bi
        // huy/tao lai. Chi con don dep phan Mic khong day (wirelessMicInput/
        // signalingClient/webRtcManager cua vai tro Mic B/C) - day la vai
        // tro gan chat voi chinh thao tac dang cam mic cua NGUOI DUNG hien
        // tai, hop ly de dung khi Activity bi huy.
        wirelessMicInput?.stopCapture()
        wirelessMicInput = null
        signalingClient?.close()
        signalingClient = null
        webRtcManager?.closeAll()
        webRtcManager = null
    }

    private fun toggleMixerTest(button: Button) {
        if (!mixerTestRunning && micLoopbackRunning) {
            Toast.makeText(
                this,
                "Dang chay Mic Loopback - hay tat Mic Loopback truoc khi bat Mixer Test",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!PlaybackCaptureService.isCapturing()) {
            Toast.makeText(
                this,
                "Chua capture nhac (Phase 1) - bam 'Xin quyen lai' truoc khi test mixer",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Can quyen RECORD_AUDIO", Toast.LENGTH_LONG).show()
            return
        }

        if (!mixerTestRunning && !ensureOverlayPermission()) {
            return
        }

        val action = if (mixerTestRunning) {
            PlaybackCaptureService.ACTION_STOP_MIXER_TEST
        } else {
            PlaybackCaptureService.ACTION_START_MIXER_TEST
        }
        val intent = Intent(this, PlaybackCaptureService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(this, intent)

        mixerTestRunning = !mixerTestRunning
        button.text = if (mixerTestRunning) "Tat Mixer Test" else "Bat Mixer Test"
        CaptureLogBus.log("[Activity] Gui action=$action toi Service (Mixer Test Phase 3)")
    }

    private fun toggleStreamMusicMuteTest(button: Button) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (streamMusicMuteTestActive) {
            if (savedStreamMusicVolumeBeforeTest >= 0) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    savedStreamMusicVolumeBeforeTest,
                    0
                )
                CaptureLogBus.log(
                    "[Activity] [MuteTest] Da khoi phuc STREAM_MUSIC ve muc goc=$savedStreamMusicVolumeBeforeTest"
                )
            }
            savedStreamMusicVolumeBeforeTest = -1
            streamMusicMuteTestActive = false
            button.text = "Test Mute STREAM_MUSIC"
            return
        }

        if (!PlaybackCaptureService.isCapturing()) {
            Toast.makeText(
                this,
                "Chua capture nhac (Phase 1) - bam 'Xin quyen lai' va mo YouTube truoc khi test",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        savedStreamMusicVolumeBeforeTest = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        streamMusicMuteTestActive = true
        button.text = "Khoi phuc STREAM_MUSIC"
        CaptureLogBus.log(
            "[Activity] [MuteTest] ✅ Da ha STREAM_MUSIC ve 0 (muc goc da luu=$savedStreamMusicVolumeBeforeTest). " +
                "Quan sat: loa co im khong? Log amplitude cua MusicInput ben duoi co con dao dong theo nhac khong?"
        )
    }
}