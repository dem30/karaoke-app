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
 */
class MainActivity : AppCompatActivity() {

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