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
 *
 * ✅ MOI (fix "Mixer Test chi song ~5 giay roi im, phai thu app xuong moi to
 * lai" - xem giai thich chi tiet trong MixerToggleOverlayButton.kt): them flow xin
 * quyen "Hien thi tren ung dung khac" (SYSTEM_ALERT_WINDOW) - can de
 * PlaybackCaptureService co the hien nut noi "🔊 Kich hoat lai" DE TREN
 * YouTube trong luc Mixer Test dang chay. Day la 1 "special permission" cua
 * Android, KHONG xin duoc qua ActivityResultContracts.RequestPermission()
 * thong thuong nhu RECORD_AUDIO - bat buoc phai dan nguoi dung sang 1 man
 * hinh Settings rieng (Settings.ACTION_MANAGE_OVERLAY_PERMISSION) de ho tu
 * bat cong tac thu cong, roi tu quay lai app (khong co callback "granted"
 * truc tiep nhu request thuong - phai tu kiem tra lai Settings.canDrawOverlays()
 * sau khi quay lai).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // ✅ MOI (fix "chuoi Bat lai qua nut noi doan gio co dinh 1s la KHONG
        // du, vi startActivity() tu Service khong dam bao Activity da THAT
        // SU len foreground trong khoang do"): callback tuy chon, duoc
        // PlaybackCaptureService gan truoc khi goi startActivity() dua
        // MainActivity len - se duoc goi CHINH XAC luc onResume() THAT SU
        // chay (tuc Activity da o trang thai foreground/resumed that), thay
        // vi Service phai doan mo 1 khoang thoi gian co dinh. Dùng bien
        // static don gian (khong LiveData/Flow) vi chi co 1 Activity + 1
        // Service trong cung process, giong tinh than CaptureLogBus.
        @Volatile
        var onResumedCallback: (() -> Unit)? = null

        // ✅ MOI (fix "nut trong app hien sai trang thai vinh vien sau khi
        // chuoi tu dong bat/tat Mixer Test tu nut noi chay xong"): truoc day
        // MainActivity CHI dong bo lai chu cua nut trong onResume() - nhung
        // tu khi co chuoi "Bat lai qua nut noi" (PlaybackCaptureService),
        // mixerTestActive co the doi trang thai NGAY TRONG LUC Activity nay
        // dang o foreground/resumed (khong co onResume() moi nao xay ra nua
        // de kich hoat dong bo), dan den nut bi "lech" so voi trang thai
        // thuc te cho toi khi nguoi dung roi app roi quay lai lan nua.
        //
        // Dung WeakReference (KHONG giu tham chieu manh truc tiep toi
        // Activity) de Service co the goi refreshMixerTestButtonState() bat
        // ky luc nao ma khong lam leak Activity neu no da bi huy (xoay man
        // hinh, nguoi dung thoat app...) - luc do activityRef.get() se tra
        // null va ham nay tu bo qua an toan.
        @Volatile
        private var activityRef: java.lang.ref.WeakReference<MainActivity>? = null

        /**
         * ✅ MOI: goi tu PlaybackCaptureService moi lan mixerTestActive THAT
         * SU doi trang thai (bat hoac tat) - bat ke nguyen nhan tu dau (nut
         * trong app, nut noi tren man hinh, hay chuoi tu dong "Bat lai") -
         * de cap nhat lai chu cua nut trong app NGAY LAP TUC neu Activity
         * dang con song, thay vi doi den lan onResume() ke tiep (co the
         * khong bao gio xay ra neu nguoi dung khong tu quay lai app trong
         * luc dang xem YouTube).
         */
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

    // ✅ MOI: co danh dau da tu dong kich hoat flow xin quyen trong lan
    // onCreate() nay chua - tranh goi lai nhieu lan neu onCreate() bi goi lai
    // (vi du xoay man hinh) trong khi flow dang cho ket qua dialog.
    private var autoStartTriggered = false

    // ✅ MOI (Phase 2): mic loopback test doc lap, KHONG lien quan gi toi
    // PlaybackCaptureService/MusicInput cua Phase 1 - chi de do latency
    // mic -> loa theo dung task Phase 2 trong PLAN.md.
    private var micInput: MicInput? = null
    private var outputRouter: OutputRouter? = null
    private var micLoopbackRunning = false

    // ✅ MOI (Phase 3): trang thai bat/tat mixer test - chi gui Intent
    // action toi Service, KHONG tu giu MicInput/Mixer o Activity (khac voi
    // Mic Loopback cua Phase 2, vi mixer test can chay ben trong Service de
    // song cung MusicInput dang capture nhac ngam).
    private var mixerTestRunning = false

    // ✅ MOI: giu tham chieu toi nut "Bat/Tat Mixer Test" de dong bo lai chu
    // trong onResume() - can thiet vi GIO Mixer Test co the bi bat/tat tu
    // ben ngoai Activity (nut noi tren man hinh) trong luc Activity nay
    // dang o nen, xem giai thich chi tiet trong PlaybackCaptureService.kt
    // (isMixerTestActive()).
    private var mixerTestButtonRef: Button? = null

    // ✅ MOI (thu nghiem "co the mute YouTube nhung MusicInput van capture
    // duoc khong?"): CHI mute/unmute STREAM_MUSIC cua he thong - KHONG dung
    // gi toi Mixer/OutputRouter/PlaybackCaptureService. Muc dich duy nhat: xac
    // nhan AudioPlaybackCapture co tap tin hieu TRUOC hay SAU buoc ap dung
    // volume cua STREAM_MUSIC. Neu loa im nhung log amplitude cua MusicInput
    // van dao dong theo nhac -> capture tap TRUOC volume, mo ra huong kien
    // truc "app tu mute nguon, chi phat qua OutputRouter cua chinh minh".
    // streamMusicMuteTestActive=true nghia la dang trong trang thai da tu
    // ha volume xuong 0 do nut nay gay ra (de biet duong nao can khoi phuc).
    private var streamMusicMuteTestActive = false
    private var savedStreamMusicVolumeBeforeTest = -1

    // ✅ MOI: danh sach usage candidate de A/B test qua loa Bluetooth, dung
    // lai chinh nut "Mic Loopback" (Phase 2) da co san co che nghe + do
    // latency bang vo tay - khong viet lai UI/luong test tu dau.
    //
    // ⚠️ CO Y bo qua AudioAttributes.USAGE_VOICE_COMMUNICATION khoi danh sach
    // nay - xem giai thich chi tiet trong OutputRouter.kt (rui ro bi ep sang
    // Bluetooth SCO mono chat luong thoai thay vi A2DP stereo).
    // ✅ MOI: them legacyStream tuong ung voi moi usage - can de biet CHINH
    // XAC stream nao phai tam thoi day len max khi test, vi moi usage khac
    // MEDIA se di qua 1 thanh volume RIENG, doc lap voi "Am luong media" ma
    // nguoi dung quen chinh hang ngay (da xac nhan qua test thuc te: voice/
    // mixer nghe rat nho khi doi usage, KHONG phai do usage do te, ma do
    // thanh volume tuong ung dang o muc mac dinh thap). usage=MEDIA khong
    // can boost gi ca - giu nguyen thanh Media hien tai cua nguoi dung.
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

    // ✅ MOI: luu lai muc volume goc cua legacyStreamToBoost TRUOC khi boost,
    // de khoi phuc dung luc tat loopback - QUAN TRONG nhat voi STREAM_ALARM
    // (neu quen khoi phuc, bao thuc that cua may se keu rat to bat ngo lan
    // sau). Khoi phuc o CA 2 noi: tat Mic Loopback binh thuong VA onDestroy()
    // (phong truong hop Activity bi huy dot ngot).
    private var savedBoostedStreamVolume: Int? = null
    private var boostedLegacyStream: Int? = null

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

    // ✅ MOI (xem giai thich chi tiet o dau file/MixerToggleOverlayButton.kt): quyen
    // SYSTEM_ALERT_WINDOW KHONG co callback "granted=true/false" truc tiep nhu
    // RequestPermission() thong thuong - Settings.ACTION_MANAGE_OVERLAY_PERMISSION
    // chi mo 1 man hinh Settings, dong lai roi tra ve KHONG kem ket qua dang tin
    // cay. Cach dung duoc khuyen nghi chinh thuc: dung StartActivityForResult
    // chi de biet "nguoi dung da quay lai app", roi TU kiem tra lai
    // Settings.canDrawOverlays(this) tai thoi diem do de biet ket qua that su.
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

    /**
     * ✅ MOI: kiem tra quyen SYSTEM_ALERT_WINDOW - neu chua co, dan nguoi dung
     * sang man hinh Settings de tu bat (co gan san package cua app qua Uri
     * "package:..." de Settings mo dung trang cua app nay, khong phai trang
     * danh sach chung chung). Tra ve true/false NGAY LAP TUC (dua vao trang
     * thai HIEN TAI) de goi noi dung dung - lan dau goi thuong se tra false va
     * mo Settings, nguoi dung can bam lai nut sau khi cap quyen xong.
     */
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

        // ✅ MOI: dang ky instance nay vao WeakReference tinh (companion) -
        // de PlaybackCaptureService co the goi refreshMixerTestButtonState()
        // cap nhat UI nut Mixer Test NGAY LAP TUC bat ky luc nao, khong chi
        // doi den onResume() ke tiep. Gan lai moi lan onCreate() (ke ca khi
        // Activity bi tao lai do xoay man hinh) de luon tro toi instance
        // DANG SONG hien tai.
        activityRef = java.lang.ref.WeakReference(this)

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

        // ✅ MOI (Phase 2): nut bat/tat mic loopback (mic -> loa truc tiep,
        // chua co mixer) - dung de vo tay truoc mic va do do tre bang video
        // quay slow-motion, theo dung tieu chi Phase 2 trong PLAN.md.
        val micLoopbackButton = Button(this).apply {
            text = "Bat Mic Loopback"
            setOnClickListener { toggleMicLoopback(this) }
        }

        // ✅ MOI (Phase 3): nut bat/tat test tron Music + Mic. Chi co tac
        // dung khi PlaybackCaptureService dang capture nhac (Phase 1) - neu
        // chua, se bao loi qua log thay vi lam gi ca.
        val mixerTestButton = Button(this).apply {
            text = "Bat Mixer Test"
            setOnClickListener { toggleMixerTest(this) }
        }
        mixerTestButtonRef = mixerTestButton

        // ✅ MOI: nut thu nghiem rieng, KHONG lien quan Mixer/OutputRouter -
        // chi de kiem tra gia thuyet mute STREAM_MUSIC ve 0 co lam MusicInput
        // mat tin hieu capture hay khong (xem giai thich chi tiet o khai bao
        // bien streamMusicMuteTestActive ben tren).
        val muteTestButton = Button(this).apply {
            text = "Test Mute STREAM_MUSIC"
            setOnClickListener { toggleStreamMusicMuteTest(this) }
        }

        // ✅ MOI: nut chon usage candidate cho OutputRouter khi test qua Mic
        // Loopback - bam de doi vong qua danh sach usageCandidates, ten nut
        // hien usage dang chon. Bam "Bat Mic Loopback" NGAY SAU do se dung
        // dung usage nay de tao OutputRouter, test qua loa Bluetooth that.
        val usageSelectButton = Button(this).apply {
            text = "Usage test: ${usageCandidates[usageCandidateIndex].label}"
            setOnClickListener { cycleUsageCandidate(this) }
        }

        // ✅ MOI (xem giai thich chi tiet o dau file): nut xin quyen "Hien thi
        // tren ung dung khac" - can cho nut noi "Kich hoat lai" cua Mixer Test
        // (MixerToggleOverlayButton.kt). Doi ten nut theo trang thai hien tai moi lan
        // onCreate() chay, de nguoi dung biet ngay khong can bam neu da cap roi.
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

        // ✅ MOI: hang nut rieng cho quyen overlay - de tach biet ro rang voi
        // cac nut thu nghiem khac, tranh nguoi dung nham lan day chi la 1 test
        // nua trong so nhieu nut chan doan.
        val buttonRow4 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(overlayPermissionButton)
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

        // ✅ MOI: dong bo lai trang thai/chu cua nut "Bat/Tat Mixer Test" moi
        // lan quay lai app - can thiet vi nut noi tren man hinh (xem
        // MixerToggleOverlayButton.kt) co the da bat/tat Mixer Test trong
        // luc Activity nay dang o nen (vi du dang xem YouTube), khien bien
        // mixerTestRunning cu trong Activity khong con dung nua.
        val actuallyRunning = PlaybackCaptureService.isMixerTestActive()
        if (actuallyRunning != mixerTestRunning) {
            mixerTestRunning = actuallyRunning
            mixerTestButtonRef?.text = if (mixerTestRunning) "Tat Mixer Test" else "Bat Mixer Test"
            CaptureLogBus.log("[Activity] Da dong bo lai trang thai Mixer Test tu Service: dangChay=$actuallyRunning")
        }

        // ✅ MOI: bao hieu cho PlaybackCaptureService biet Activity nay VUA
        // THAT SU len foreground/resumed - xem giai thich chi tiet o khai
        // bao onResumedCallback trong companion object. Doc gia tri xong roi
        // GAN LAI null NGAY (khong dung callback nay cho nhung lan onResume()
        // binh thuong khac, vi du nguoi dung tu mo lai app - chi co tac dung
        // 1 LAN cho dung lan Service dang cho).
        onResumedCallback?.let { callback ->
            onResumedCallback = null
            callback.invoke()
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

    // ✅ MOI (Phase 2): bat/tat mic -> loa truc tiep de do latency. Doc lap
    // hoan toan voi flow Phase 1 (khong dung chung permission RECORD_AUDIO -
    // neu chua co, xin luon tai day).
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

        // ✅ MOI: chan chay chong 2 pipeline mic/output cung luc - da xac nhan
        // qua debug thuc te gay ra "re, rot rot" va mat tieng tung luc do 2
        // AudioRecord/AudioTrack tranh gianh tai nguyen doc quyen cua mic/loa.
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
        // ✅ MOI: truyen them onTransientDetected de tu dong tinh latency khi
        // phat hien tieng vo tay - xem giai thich chi tiet trong MicInput.kt
        // va OutputRouter.kt. Thu tu QUAN TRONG: doc router.totalFramesWritten
        // (frame TRUOC buffer nay) roi MOI goi router.write() cho buffer nay -
        // MicInput da dam bao goi onTransientDetected TRUOC onPcmChunk nen thu
        // tu nay tu nhien dung, khong can dong bo gi them.
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

    /**
     * ✅ MOI: neu usage dang test khac MEDIA (tuc di qua 1 stream RIENG, chua
     * tung duoc nguoi dung chinh to), tam thoi day volume cua stream do len
     * MUC MAX de A/B test cong bang voi STREAM_MUSIC - neu khong lam buoc
     * nay, moi lan doi usage se nghe "nho" khong phai do usage do te, ma do
     * thanh volume tuong ung dang o muc mac dinh thap (da xac nhan qua test
     * thuc te tren may Honor).
     */
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

    /**
     * ✅ MOI: khoi phuc volume goc cua stream vua boost - goi khi tat Mic
     * Loopback binh thuong VA trong onDestroy() (phong Activity bi huy dot
     * ngot). QUAN TRONG nhat voi STREAM_ALARM: neu khong khoi phuc, bao thuc
     * that cua nguoi dung se keu rat to bat ngo o lan sau.
     */
    private fun restoreBoostedStreamVolumeIfAny() {
        val stream = boostedLegacyStream ?: return
        val savedVolume = savedBoostedStreamVolume ?: return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(stream, savedVolume, 0)
        CaptureLogBus.log("[Activity] [UsageTest] Da khoi phuc stream=$stream ve muc goc=$savedVolume")
        boostedLegacyStream = null
        savedBoostedStreamVolume = null
    }

    /**
     * ✅ MOI: doi vong qua danh sach usageCandidates - CHI cho phep doi khi
     * Mic Loopback dang TAT (tranh doi usage giua chung 1 phien dang chay,
     * gay nham lan khi doi chieu ket qua nghe/latency voi usage nao).
     */
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

    override fun onDestroy() {
        super.onDestroy()

        // ✅ MOI: chi xoa activityRef neu no VAN DANG tro toi CHINH instance
        // nay - tranh truong hop hiem gap (onCreate() cua instance MOI da
        // chay va gan lai activityRef TRUOC KHI onDestroy() cua instance CU
        // kip chay xong, vi du xoay man hinh) vo tinh xoa mat tham chieu toi
        // instance moi dang song.
        if (activityRef?.get() === this) {
            activityRef = null
        }

        // ✅ Don dep mic loopback neu Activity bi huy trong luc dang bat -
        // day la test thu cong, khong can chay nen nhu PlaybackCaptureService.
        micInput?.stopCapture()
        outputRouter?.stop()

        // ✅ AN TOAN: neu nguoi dung thoat app trong luc dang bat "Test Mute
        // STREAM_MUSIC" ma quen bam nut khoi phuc, chu dong tra lai volume goc
        // o day - tranh de may bi cam am luon sau khi thoat app.
        if (streamMusicMuteTestActive && savedStreamMusicVolumeBeforeTest >= 0) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedStreamMusicVolumeBeforeTest, 0)
            CaptureLogBus.log("[Activity] [MuteTest] onDestroy: da tu dong khoi phuc STREAM_MUSIC (quen bam nut).")
        }

        // ✅ AN TOAN (dac biet voi STREAM_ALARM): neu Activity bi huy trong
        // luc dang boost volume test ma quen tat Mic Loopback, khoi phuc o
        // day - tranh de bao thuc that cua nguoi dung bi ket o muc max.
        restoreBoostedStreamVolumeIfAny()
    }

    // ✅ MOI (Phase 3): bat/tat mixer test qua Intent action gui toi Service
    // dang chay - xem PlaybackCaptureService.ACTION_START_MIXER_TEST/
    // ACTION_STOP_MIXER_TEST de biet Service xu ly the nao.
    private fun toggleMixerTest(button: Button) {
        // ✅ MOI: chan chay chong voi Mic Loopback (xem giai thich chi tiet
        // trong toggleMicLoopback()) - chi kiem tra khi dang BAT mixer test
        // (mixerTestRunning=false), khong chan luc TAT.
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

        // ✅ MOI (xem giai thich chi tiet o dau file): CHI kiem tra quyen
        // overlay khi dang BAT mixer test (giong het cach kiem tra
        // RECORD_AUDIO o tren) - khong chan luc TAT. Neu chua co quyen,
        // ensureOverlayPermission() se tu mo man hinh Settings VA return
        // false - dung lai o day, KHONG gui Intent bat Mixer Test, de
        // nguoi dung cap quyen xong roi tu bam lai nut nay 1 lan nua (luc do
        // Settings.canDrawOverlays() da tra true, se di tiep binh thuong).
        // Mixer Test VAN chay duoc du chua co quyen nay (chi la se khong co
        // nut noi "Kich hoat lai" tren man hinh) - nhung nen nhac nguoi dung
        // cap truoc de trai nghiem day du dung nhu thiet ke.
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

    /**
     * ✅ MOI: thu nghiem doc lap - CHI mute/unmute STREAM_MUSIC, khong dung gi
     * toi Mixer/OutputRouter. Yeu cau PlaybackCaptureService (Phase 1) dang
     * capture san (isCapturing=true) de co the doc log amplitude cua
     * MusicInput trong luc test.
     *
     * Cach doc ket qua: bam nut nay trong luc YouTube dang phat, quan sat 2
     * dieu:
     * 1) Loa co thuc su im khong (nghe bang tai).
     * 2) Dong log "[MusicInput] amplitude trung binh 1s qua: ..." co CON dao
     *    dong theo nhac (khac 0, thay doi lien tuc) hay tut ve 0/dung yen.
     *
     * Neu (1) im VA (2) van dao dong -> capture tap truoc buoc ap volume,
     * mo duong cho kien truc "app tu mute nguon". Neu (2) cung ve 0/dung -
     * capture tap SAU buoc ap volume, huong nay khong kha thi, phai chuyen
     * sang Huong B (PLAN.md - tu phat nhac trong app, khong capture app
     * ngoai nua).
     */
    private fun toggleStreamMusicMuteTest(button: Button) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (streamMusicMuteTestActive) {
            // Khoi phuc volume goc.
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