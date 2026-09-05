package com.karaokeapp.audio.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.karaokeapp.MainActivity
import com.karaokeapp.audio.mic.MicInput
import com.karaokeapp.audio.mixer.LowLatencyMixer
import com.karaokeapp.audio.mixer.VocalChannel
import com.karaokeapp.audio.output.OutputRouter
import com.karaokeapp.audio.processor.Limiter
import com.karaokeapp.overlay.MixerBoardOverlay
import com.karaokeapp.overlay.MixerToggleOverlayButton
import com.karaokeapp.webrtc.QrJoinData
import com.karaokeapp.webrtc.SignalingServer
import com.karaokeapp.webrtc.WebRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Foreground service quan ly toan bo pipeline: capture nhac (Phase 1),
 * Mixer Test (Music + Mic -> LowLatencyMixer -> OutputRouter, Phase 3),
 * Phong Karaoke LAN (Phase 5 - xem giai thich chi tiet ben duoi), va co che
 * tu phuc hoi am luong khi bat lai Mixer Test.
 *
 * Co che mute STREAM_MUSIC + phat mixer qua STREAM_SYSTEM (usage
 * ASSISTANCE_SONIFICATION) da duoc xac nhan qua test thuc te: capture tap
 * PCM TRUOC buoc ap volume he thong nen mute khong lam mat tin hieu capture.
 *
 * volumeGuardJob (~300ms/lan) chi xu ly duoc muc "index"/"mute-flag" ma
 * AudioManager bao cao - KHONG xu ly duoc "duck" noi bo o tang HAL/OEM (gain
 * giam nhung index/mute-flag van bao binh thuong). Duck CHI duoc xoa khi co
 * 1 su kien chuyen foreground THAT xay ra.
 *
 * ✅ MOI (fix "tieng ret/giat khi 2+ May B/C cung hat qua Phong Karaoke LAN"
 * - xem giai thich chi tiet trong LowLatencyMixer.kt): truoc day
 * pushRemoteVocalChunk() KHONG phan biet clientId nao gui PCM - moi nguon
 * remote (va ca mic tai cho) deu bi don chung vao 1 hang doi FIFO DUY NHAT
 * cua mixer, gay interleave/giat khi co tu 2 nguon vocal tro len. GIO moi
 * clientId (va mic tai cho, dinh danh boi LowLatencyMixer.SOURCE_LOCAL_MIC)
 * co 1 "kenh" RIENG trong mixer (xem LowLatencyMixer.pushVocal(sourceId,...)).
 *
 * ✅ MOI (Phase 6 - "ban mixer" dieu chinh duoc, thay the HowlGuard tu dong
 * da go bo): moi nguon vocal (local hoac tung clientId remote) gio di qua 1
 * VocalChannel RIENG - CUNG mot chuoi xu ly (AutoGain nhe -> EQ -> Compressor
 * -> Echo -> volume nguoi dung -> Limiter an toan) cho CA HAI loai nguon,
 * khong con bat doi xung "local day du, remote chi co Limiter" nhu truoc.
 * Xem VocalChannel.kt va nhom ham setChannelXxx()/setMusicVolume()/
 * setMasterVolume() trong companion object ben duoi de dieu chinh tu UI.
 *
 * ✅ MOI (fix goc "May B mat ket noi moi lan bam play/pause tren May A" -
 * BUG QUAN TRONG vua phat hien): truoc day hostSignalingServer/hostWebRtcManager
 * (server WebSocket + WebRTC phia Host cua Phong Karaoke) la 2 field cua
 * MainActivity, va MainActivity.onDestroy() chu dong goi
 * signalingServer?.stopServer() + webRtcManager?.closeAll(). Van de: Activity
 * (khac voi foreground Service) KHONG duoc OS bao ve khoi bi thu hoi khi lui
 * xuong nen - nhieu OEM (dac biet Honor, da xac nhan qua hang loat bug
 * "duck HAL/OEM" trong OutputRouter.kt) chu dong HUY Activity dang o nen de
 * tiet kiem RAM NGAY CA KHI tien trinh van con song (nho co
 * PlaybackCaptureService dang chay foreground). Ket qua: moi lan nguoi dung
 * roi app Karaoke de bam play/pause tren YouTube, Android co the huy
 * MainActivity bat cu luc nao -> onDestroy() chay -> phong Karaoke bi dong
 * ngay lap tuc, du nguoi dung khong he chu dong bam "Dung phong".
 *
 * Sua: chuyen TOAN BO logic Host (SignalingServer + WebRtcManager phia May
 * A) vao day (Service) - noi da duoc bao ve boi foreground service tu truoc
 * (Mixer/MusicInput cung song trong Service vi ly do tuong tu), NEN se song
 * bang vong doi voi Mixer, khong con phu thuoc vao viec MainActivity co bi
 * OS don dep hay khong. MainActivity gio CHI con gui Intent
 * (ACTION_START_HOST_ROOM/ACTION_STOP_HOST_ROOM) va nhan ket qua qua 3
 * callback tinh (onRoomReadyCallback/onRoomErrorCallback/onRoomMicStatusCallback)
 * - giong tinh than onResumedCallback/refreshMixerTestButtonState() da co san.
 */
class PlaybackCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var musicInput: MusicInput? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var micInput: MicInput? = null
    private var mixer: LowLatencyMixer? = null
    private var mixerOutputRouter: OutputRouter? = null
    private var mixerToggleOverlay: MixerToggleOverlayButton? = null
    // ✅ MOI (Phase 6 - "ban mixer on fly"): nut noi THU HAI, mo bang mixer
    // thu nho de chinh volume nhac nen/tong the/mic tai cho NGAY trong luc
    // dang xem YouTube toan man hinh - xem giai thich day du trong
    // MixerBoardOverlay.kt ve ly do can nut nay (STREAM_MUSIC bi mute khi
    // Mixer Test BAT, nhac gio chi con phat qua duong Mixer).
    private var mixerBoardOverlay: MixerBoardOverlay? = null
    private var finalMixLimiter: Limiter? = null

    // ✅ DA GO BO (Phase 6) HowlGuard - co che "leo thang" tu dong cam/ha
    // gain mic khi nghi ngo hu phan hoi am hoc. Ly do go bo: co che nay tu
    // QUYET DINH luc nao lam vocal nho di/im han theo phat hien RIENG cua
    // no (RMS tang lien tuc N buffer) - trong thuc te dieu nay khien nguoi
    // hat nghe thay giong minh LUC TO LUC NHO/mat tieng tam thoi MA KHONG
    // RO LY DO, va cang o cap do leo thang cao, mic cang bi giu o muc rat
    // thap rat lau (toi 12s) - trai nghiem te hon ca chinh tieng hu ban dau.
    // Chong hu phan hoi am hoc VAT LY (loa phat lai vao mic) gio la trach
    // nhiem cua nguoi dung: giam am luong loa, dua mic ra xa loa hon, hoac
    // dung tai nghe - day la GIOI HAN VAT LY (am truyen qua khong khi),
    // khong phai thu phan mem co the tu giai quyet an toan ma khong danh
    // doi bang trai nghiem hat that that. Thay vao do, tung nguon vocal gio
    // co 1 VocalChannel (xem VocalChannel.kt) voi AutoGainControl - CHI san
    // bang am luong GIUA cac nguon hat 1 cach CHAM va co gioi han, khong
    // bao gio cam mic - cong voi cac tham so nguoi dung TU dieu chinh duoc
    // (volume, EQ, Compressor, Echo) qua cac ham setChannelXxx() ben duoi.

    // ✅ MOI (xem giai thich day du o dau file): Host Room (SignalingServer +
    // WebRtcManager phia May A) chuyen vao Service de song bang vong doi voi
    // Mixer/MusicInput, KHONG con bi huy theo MainActivity.onDestroy() nua.
    private var hostSignalingServer: SignalingServer? = null
    private var hostWebRtcManager: WebRtcManager? = null

    // ✅ MOI: theo doi cac clientId (May B/C) dang ket noi vao phong hien
    // tai - dung de don sach dung "kenh" vocal cua tung may (xem
    // removeRemoteVocalSource()) khi dong ca phong (khong chi khi tung may
    // rieng le ngat ket noi).
    private val connectedRemoteClientIds = java.util.concurrent.CopyOnWriteArraySet<String>()

    private var savedStreamSystemVolume = -1

    private var focusObserverRequest: android.media.AudioFocusRequest? = null

    private val reactivationHandler = Handler(Looper.getMainLooper())
    private var reactivationRunnable: Runnable? = null

    private val focusObserverListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val label = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
            else -> "UNKNOWN($focusChange)"
        }
        logBoth("🎧 [FocusObserver] onAudioFocusChange=$label (chi ghi log).")

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
        const val ACTION_STOP_ALL = "com.karaokeapp.action.STOP_ALL"

        // ✅ MOI (fix "May B mat ket noi khi bam play/pause tren May A"): 2
        // action moi de MainActivity YEU CAU Service tu mo/dong Phong
        // Karaoke, THAY VI Activity tu lam lay - xem giai thich chi tiet o
        // khai bao hostSignalingServer/hostWebRtcManager phia tren.
        const val ACTION_START_HOST_ROOM = "com.karaokeapp.action.START_HOST_ROOM"
        const val ACTION_STOP_HOST_ROOM = "com.karaokeapp.action.STOP_HOST_ROOM"

        private const val GUARD_STATUS_LOG_EVERY_N_TICKS = 10
        private const val ENABLE_MUSIC_STREAM_MUTE_GUARD = true

        private const val REACTIVATION_STEP_DELAY_MS = 1000L
        private const val FALLBACK_TIMEOUT_MS = 3000L
        private const val AUDIO_FOREGROUND_SETTLE_DELAY_MS = 500L
        private const val OFF_ON_CYCLE_DELAY_MS = 400L
        private const val NUDGE_AFTER_RETURN_DELAY_MS = 600L

        private val TARGET_APP_CANDIDATES = listOf(
            "com.google.android.youtube",
            "com.android.chrome"
        )

        @Volatile
        private var capturingActive = false

        @Volatile
        private var mixerTestActive = false

        fun isCapturing(): Boolean = capturingActive
        fun isMixerTestActive(): Boolean = mixerTestActive

        @Volatile
        private var activeMixerInstance: LowLatencyMixer? = null

        // ✅ MOI: luu QrJoinData cua phong Karaoke DANG CHAY (null = khong co
        // phong nao) - dung de MainActivity (co the bi tao lai bat ky luc
        // nao do xoay man hinh hoac OS don dep) hoi lai trang thai HIEN TAI
        // cua phong ma khong can tu giu bien rieng (von se mat khi Activity
        // bi huy/tao lai, chinh la nguyen nhan goc cua bug nay).
        @Volatile
        private var activeRoomQrData: QrJoinData? = null

        fun isHostRoomActive(): Boolean = activeRoomQrData != null
        fun getActiveRoomQrData(): QrJoinData? = activeRoomQrData

        private val vocalPushLock = Any()

        @Volatile
        private var localMicMutedForMixer = false

        fun isLocalMicMutedForMixer(): Boolean = localMicMutedForMixer

        fun setLocalMicMutedForMixer(muted: Boolean) {
            localMicMutedForMixer = muted
        }

        // ✅ SUA (Phase 6 - dong bo chat luong local/remote): truoc day chi
        // co remoteVocalLimiters (moi clientId 1 Limiter RIENG, KHONG co
        // EQ/Compressor/Echo - PCM tu WebRTC la RAW). Gio dung 1 map DUY
        // NHAT vocalChannels cho CA mic vat ly cua May A (key =
        // LowLatencyMixer.SOURCE_LOCAL_MIC) LAN tung clientId cua May B/C -
        // moi nguon co 1 VocalChannel rieng nhung CUNG mot chuoi xu ly va
        // cung bo tham so mac dinh (xem VocalChannel.kt), dam bao giong hat
        // tu xa nghe "day dan" nhu giong hat tai cho, khong con "mộc" hon.
        private val vocalChannels = ConcurrentHashMap<String, VocalChannel>()

        /** Lay (hoac tao moi neu chua co) VocalChannel cho 1 sourceId - dung chung cho ca local ("local_mic") va remote (clientId). */
        private fun getOrCreateVocalChannel(sourceId: String): VocalChannel =
            vocalChannels.getOrPut(sourceId) { VocalChannel(sampleRate = 44100, label = sourceId) }

        // ================= "Ban mixer" - API dieu chinh tu UI =================
        // ✅ MOI (Phase 6): thay the HowlGuard tu dong bang cac ham nguoi
        // dung/UI goi truc tiep de dieu chinh TUNG nguon vocal (hoac nhac
        // nen / tong the qua Mixer). An toan goi voi sourceId chua ton tai -
        // se tu tao 1 VocalChannel moi voi tham so mac dinh.

        /** Danh sach sourceId dang co kenh (da tung nhan PCM it nhat 1 lan) - dung de UI liet ke cac thanh truot. */
        fun listActiveChannelIds(): List<String> = vocalChannels.keys.toList()

        /** He so am luong THU CONG cho 1 kenh vocal (0f..2f). */
        fun setChannelVolume(sourceId: String, volume: Float) {
            getOrCreateVocalChannel(sourceId).volume = volume
        }

        fun getChannelVolume(sourceId: String): Float = getOrCreateVocalChannel(sourceId).volume

        /** Cau/mo 1 kenh vocal cu the (vd nut "Tat mic May B"). */
        fun setChannelMuted(sourceId: String, muted: Boolean) {
            getOrCreateVocalChannel(sourceId).muted = muted
        }

        fun isChannelMuted(sourceId: String): Boolean = getOrCreateVocalChannel(sourceId).muted

        /** EQ 3 dai cho 1 kenh - moi tham so trong [-12f, 12f] dB. */
        fun setChannelEQ(sourceId: String, bassDb: Float, midDb: Float, trebleDb: Float) {
            getOrCreateVocalChannel(sourceId).setEQGains(bassDb, midDb, trebleDb)
        }

        // ✅ MOI: getter doc lai dung 3 gia tri EQ HIEN TAI cua 1 kenh - dung
        // de UI (dialog Ban mixer) hien dung gia tri da luu moi lan mo lai,
        // thay vi luon hien mac dinh -2/1/3 dB nhu truoc (gia tri HIEN THI
        // sai nhung gia tri THAT trong VocalChannel van dung).
        fun getChannelEQBass(sourceId: String): Float = getOrCreateVocalChannel(sourceId).eq.bassGainDb
        fun getChannelEQMid(sourceId: String): Float = getOrCreateVocalChannel(sourceId).eq.midGainDb
        fun getChannelEQTreble(sourceId: String): Float = getOrCreateVocalChannel(sourceId).eq.trebleGainDb

        fun setChannelAutoGainEnabled(sourceId: String, enabled: Boolean) {
            getOrCreateVocalChannel(sourceId).autoGainEnabled = enabled
        }

        fun isChannelAutoGainEnabled(sourceId: String): Boolean = getOrCreateVocalChannel(sourceId).autoGainEnabled

        fun setChannelEQEnabled(sourceId: String, enabled: Boolean) {
            getOrCreateVocalChannel(sourceId).eqEnabled = enabled
        }

        fun isChannelEQEnabled(sourceId: String): Boolean = getOrCreateVocalChannel(sourceId).eqEnabled

        fun setChannelCompressorEnabled(sourceId: String, enabled: Boolean) {
            getOrCreateVocalChannel(sourceId).compressorEnabled = enabled
        }

        fun isChannelCompressorEnabled(sourceId: String): Boolean = getOrCreateVocalChannel(sourceId).compressorEnabled

        fun setChannelEchoEnabled(sourceId: String, enabled: Boolean) {
            getOrCreateVocalChannel(sourceId).echoEnabled = enabled
        }

        fun isChannelEchoEnabled(sourceId: String): Boolean = getOrCreateVocalChannel(sourceId).echoEnabled

        // ✅ MOI: 2 module Phase 4 dot cuoi (Anti-Feedback dich tan +5Hz,
        // Reverb Freeverb) - MAC DINH TAT trong VocalChannel (xem canh bao
        // trong FeedbackSuppressor.kt/PlateReverb.kt), them getter/setter
        // dung y het pattern 4 cong tac AutoGain/EQ/Compressor/Echo o tren
        // de UI (MixerBoardUiBuilder) co the doc/ghi.
        fun setChannelFeedbackSuppressorEnabled(sourceId: String, enabled: Boolean) {
            getOrCreateVocalChannel(sourceId).feedbackSuppressorEnabled = enabled
        }

        fun isChannelFeedbackSuppressorEnabled(sourceId: String): Boolean =
            getOrCreateVocalChannel(sourceId).feedbackSuppressorEnabled

        fun setChannelReverbEnabled(sourceId: String, enabled: Boolean) {
            getOrCreateVocalChannel(sourceId).reverbEnabled = enabled
        }

        fun isChannelReverbEnabled(sourceId: String): Boolean =
            getOrCreateVocalChannel(sourceId).reverbEnabled

        // ✅ MOI: Pitch Correction ("auto-tune nhe" - xem canh bao ve do tre/
        // octave-error trong PitchCorrector.kt). MAC DINH TAT, dung field
        // `enabled` co san cua PitchCorrector (khong them bien rieng trong
        // VocalChannel de tranh 2 nguon su that).
        fun setChannelPitchCorrectorEnabled(sourceId: String, enabled: Boolean) {
            getOrCreateVocalChannel(sourceId).pitchCorrector.enabled = enabled
        }

        fun isChannelPitchCorrectorEnabled(sourceId: String): Boolean =
            getOrCreateVocalChannel(sourceId).pitchCorrector.enabled

        /** Muc do "ep" ve dung tone: 0f = giu nguyen giong that, 1f = ep het muc. */
        fun setChannelPitchCorrectorStrength(sourceId: String, strength: Float) {
            getOrCreateVocalChannel(sourceId).pitchCorrector.correctionStrength = strength
        }

        fun getChannelPitchCorrectorStrength(sourceId: String): Float =
            getOrCreateVocalChannel(sourceId).pitchCorrector.correctionStrength

        /**
         * He so am luong cho rieng nhac nen (0f..2f).
         * ✅ SUA: goi thang vao LowLatencyMixer.musicVolume (companion/static),
         * KHONG con qua activeMixerInstance?.musicVolume - vi cach cu chi co
         * tac dung khi mixer DANG chay (activeMixerInstance != null), set luc
         * mixer dang tat se bi am tham bo qua. Truy cap thang companion luon
         * co tac dung bat ke mixer bat/tat, va gia tri se duoc ap dung ngay
         * khi mixer khoi dong lai (doc lai o dong 388-389 cua LowLatencyMixer).
         */
        fun setMusicVolume(volume: Float) {
            LowLatencyMixer.musicVolume = volume
        }

        /** ✅ MOI: doc lai volume nhac nen HIEN TAI truc tiep tu companion - luon dung du Mixer Test dang bat hay tat. */
        fun getMusicVolume(): Float = LowLatencyMixer.musicVolume

        /** He so am luong cho TOAN BO ban mix cuoi (nhac + tat ca vocal da cong), 0f..2f. */
        fun setMasterVolume(volume: Float) {
            LowLatencyMixer.masterVolume = volume
        }

        /** ✅ MOI: doc lai volume tong the (master) HIEN TAI truc tiep tu companion - luon dung du Mixer Test dang bat hay tat. */
        fun getMasterVolume(): Float = LowLatencyMixer.masterVolume
        // ================= Het API dieu chinh tu UI =================

        // ✅ MOI (CHAN DOAN TAM THOI - do nhip nhan PCM thuc te tu May B/C
        // qua WebRTC DataChannel, TRUOC khi sua bat ky gi): muc dich la xac
        // nhan CHINH XAC "giat dut quang" la do MAT GOI/TRE MANG (khoang
        // cach giua 2 lan nhan PCM lien tiep > binh thuong ~40ms rat nhieu,
        // vi du 200ms-vai giay) hay do nguyen nhan khac (vi du logic mixer/
        // buffer). Ghi lai: (1) thoi diem lan nhan GAN NHAT cho tung
        // clientId, (2) khoang cach (gap) so voi lan truoc, (3) neu gap vuot
        // qua nguong bat thuong thi log NGAY LAP TUC (khong doi 3s) kem gia
        // tri gap chinh xac, (4) moi 3s tong ket so chunk da nhan + gap lon
        // nhat trong khoang do - du kien go bo sau khi xac dinh xong nguyen
        // nhan, KHONG phai code san xuat lau dai.
        private val lastRemoteChunkNanoTime = ConcurrentHashMap<String, Long>()
        private val remoteChunkCountInWindow = ConcurrentHashMap<String, Int>()
        private val remoteMaxGapMsInWindow = ConcurrentHashMap<String, Long>()
        private val remoteWindowStartNanoTime = ConcurrentHashMap<String, Long>()
        private const val REMOTE_CHUNK_GAP_LOG_THRESHOLD_MS = 150L
        private const val REMOTE_CHUNK_WINDOW_MS = 3000L

        private fun logRemoteChunkTiming(clientId: String) {
            val now = System.nanoTime()
            val lastTime = lastRemoteChunkNanoTime.put(clientId, now)
            if (lastTime != null) {
                val gapMs = (now - lastTime) / 1_000_000L
                if (gapMs >= REMOTE_CHUNK_GAP_LOG_THRESHOLD_MS) {
                    CaptureLogBus.log(
                        "[RemoteTiming] ⚠️ '$clientId' KHOANG TRONG giua 2 lan nhan PCM = ${gapMs}ms " +
                            "(binh thuong ~40ms/lan) - nghi van MAT GOI/TRE MANG tai thoi diem nay."
                    )
                }
                remoteMaxGapMsInWindow.merge(clientId, gapMs) { old, new -> max(old, new) }
            }
            remoteChunkCountInWindow.merge(clientId, 1) { old, new -> old + new }

            val windowStart = remoteWindowStartNanoTime.getOrPut(clientId) { now }
            val windowElapsedMs = (now - windowStart) / 1_000_000L
            if (windowElapsedMs >= REMOTE_CHUNK_WINDOW_MS) {
                val count = remoteChunkCountInWindow[clientId] ?: 0
                val maxGap = remoteMaxGapMsInWindow[clientId] ?: 0L
                val expectedCount = (windowElapsedMs / 40L).toInt()
                CaptureLogBus.log(
                    "[RemoteTiming] 📊 '$clientId' trong ${windowElapsedMs}ms qua: " +
                        "nhan duoc $count chunk (ky vong ~$expectedCount neu khong mat goi), " +
                        "gap lon nhat=${maxGap}ms."
                )
                remoteChunkCountInWindow[clientId] = 0
                remoteMaxGapMsInWindow[clientId] = 0L
                remoteWindowStartNanoTime[clientId] = now
            }
        }

        /**
         * ✅ SUA (ho tro nhieu May B/C cung hat - song ca, + Phase 6 dong
         * bo chat luong): moi May B/C day PCM vao 1 "kenh" RIENG cua
         * LowLatencyMixer (dinh danh boi chinh clientId cua no), qua 1
         * VocalChannel RIENG (EQ/Compressor/Echo/AutoGain/volume - CUNG
         * chuoi xu ly voi mic vat ly, xem VocalChannel.kt) truoc khi vao
         * mixer - khong con chi co Limiter "mộc" nhu truoc.
         */
        fun pushRemoteVocalChunk(clientId: String, buffer: ShortArray, size: Int) {
            logRemoteChunkTiming(clientId)
            synchronized(vocalPushLock) {
                val mix = activeMixerInstance ?: return
                val channel = getOrCreateVocalChannel(clientId)
                channel.process(buffer, size)
                mix.pushVocal(clientId, buffer, size)
            }
        }

        /**
         * ✅ MOI: goi khi 1 May B/C ngat ket noi (SignalingServer.Listener.
         * onMicDisconnected) - don sach VocalChannel rieng cua no khoi
         * mixer, tranh giu lai 1 nguon "ma" (khong con push nua nhung van
         * chiem cho trong vocalBuffers cua mixer).
         */
        fun removeRemoteVocalSource(clientId: String) {
            vocalChannels.remove(clientId)
            activeMixerInstance?.removeVocalSource(clientId)
        }
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
        cancelPendingReactivation()
        // ✅ MOI: dong Phong Karaoke (neu co) TRUOC khi dung Mixer Test - ca
        // phien Phase 1 ket thuc nghia la khong con noi nao de PCM cua Mic
        // tu xa hoa vao nua, nen phong cung khong con y nghia gi de giu lai.
        stopHostRoomInternal()
        stopMixerTestInternal()
        musicInput?.stopCapture()
        musicInput = null
        mediaProjection?.stop()
        mediaProjection = null
        capturingActive = false
        mixerToggleOverlay?.hide()
        mixerToggleOverlay = null
        mixerBoardOverlay?.hide()
        mixerBoardOverlay = null
    }

    private fun cancelPendingReactivation() {
        reactivationRunnable?.let { reactivationHandler.removeCallbacks(it) }
        reactivationRunnable = null
        MainActivity.onResumedCallback = null
    }

    private fun toggleMixerTestFromOverlay() {
        if (mixer != null) {
            logBoth("👆 [OverlayToggle] Dang BAT -> chuyen sang TAT Mixer Test (tuc thi, khong doi).")
            cancelPendingReactivation()
            stopMixerTestInternal()
        } else {
            logBoth("👆 [OverlayToggle] Dang TAT -> bat dau chuoi 'Kich hoat lai' (dua app len foreground truoc).")
            beginOverlayReactivationSequence()
        }
    }

    private fun beginOverlayReactivationSequence() {
        cancelPendingReactivation()

        var alreadyProceeded = false

        val proceedToStep2: () -> Unit = {
            if (!alreadyProceeded) {
                alreadyProceeded = true
                cancelPendingReactivation()

                logBoth(
                    "⏳ [Reactivation] Da co tin hieu onResume() (hoac fallback) - " +
                        "doi them ${AUDIO_FOREGROUND_SETTLE_DELAY_MS}ms de tang AudioPolicy/AudioFlinger " +
                        "kip xu ly xong viec chuyen foreground."
                )

                val offStepRunnable = Runnable {
                    logBoth("🔁 [Reactivation] Buoc 'tat' (dam bao trang thai sach truoc khi bat lai that su).")
                    stopMixerTestInternal()

                    val onStepRunnable = Runnable {
                        logBoth("🔁 [Reactivation] Buoc 'bat lai' - goi startMixerTestInternal() that su.")
                        startMixerTestInternal()
                        logBoth(
                            "✅ [Reactivation] Da bat Mixer Test that - cho ${REACTIVATION_STEP_DELAY_MS}ms roi mo lai YouTube."
                        )

                        val step3 = Runnable { returnToSourceApp() }
                        reactivationRunnable = step3
                        reactivationHandler.postDelayed(step3, REACTIVATION_STEP_DELAY_MS)
                    }
                    reactivationRunnable = onStepRunnable
                    reactivationHandler.postDelayed(onStepRunnable, OFF_ON_CYCLE_DELAY_MS)
                }
                reactivationRunnable = offStepRunnable
                reactivationHandler.postDelayed(offStepRunnable, AUDIO_FOREGROUND_SETTLE_DELAY_MS)
            }
        }

        MainActivity.onResumedCallback = {
            logBoth("✅ [Reactivation] MainActivity.onResume() THAT SU da chay - tiep tuc bat Mixer Test ngay.")
            proceedToStep2()
        }

        val fallback = Runnable {
            logBoth("⚠️ [Reactivation] Khong nhan duoc tin hieu onResume() sau ${FALLBACK_TIMEOUT_MS}ms - tiep tuc bat Mixer Test du sao (fallback).", isError = true)
            MainActivity.onResumedCallback = null
            proceedToStep2()
        }
        reactivationRunnable = fallback
        reactivationHandler.postDelayed(fallback, FALLBACK_TIMEOUT_MS)

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        try {
            startActivity(activityIntent)
            logBoth("✅ [Reactivation] Da goi startActivity() dua MainActivity len foreground - dang cho tin hieu onResume() that.")
        } catch (e: Exception) {
            logBoth("❌ [Reactivation] Loi khi dua MainActivity len foreground: ${e.message}", isError = true)
            cancelPendingReactivation()
            MainActivity.onResumedCallback = null
        }
    }

    private fun returnToSourceApp() {
        reactivationRunnable = null

        for (candidatePackage in TARGET_APP_CANDIDATES) {
            val launchIntent = try {
                packageManager.getLaunchIntentForPackage(candidatePackage)
            } catch (e: Exception) {
                Log.e(TAG, "[Reactivation] Loi khi resolve launch intent cho $candidatePackage", e)
                logBoth(
                    "❌ [Reactivation] Loi khi tim launch intent cho $candidatePackage: " +
                        "${e::class.java.simpleName} - ${e.message}",
                    isError = true
                )
                null
            }

            if (launchIntent == null) {
                logBoth("⚠️ [Reactivation] $candidatePackage khong resolve duoc (co the chua cai) - thu candidate ke tiep.")
                continue
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            logBoth(
                "ℹ️ [Reactivation] $candidatePackage resolve duoc: component=${launchIntent.component} " +
                    "flags=${launchIntent.flags}"
            )
            try {
                startActivity(launchIntent)
                logBoth("✅ [Reactivation] Da goi startActivity() cho $candidatePackage - chuoi hoan tat.")

                val step4 = Runnable {
                    logBoth("🔔 [Reactivation] Buoc 4 - nudge de don duck HAL/OEM (neu co) sau khi da quay lai app nguon.")
                    mixerOutputRouter?.nudgeAudioMixerToClearDuck()
                }
                reactivationRunnable = step4
                reactivationHandler.postDelayed(step4, NUDGE_AFTER_RETURN_DELAY_MS)

                return
            } catch (e: Exception) {
                Log.e(TAG, "[Reactivation] Loi khi startActivity() cho $candidatePackage", e)
                logBoth(
                    "❌ [Reactivation] Loi khi mo $candidatePackage: " +
                        "${e::class.java.simpleName} - ${e.message} - thu candidate ke tiep.",
                    isError = true
                )
            }
        }

        logBoth(
            "❌ [Reactivation] KHONG mo duoc bat ky candidate nao trong danh sach: $TARGET_APP_CANDIDATES. " +
                "Chuoi 'Bat lai' se dung o day - Mixer Test van BAT nhung co the van nho tieng vi khong " +
                "co su kien chuyen foreground THAT nao xay ra.",
            isError = true
        )
    }

    /**
     * ✅ MOI (fix goc "May B mat ket noi moi lan bam play/pause tren May A"):
     * chuyen TOAN BO logic mo Phong Karaoke (truoc day nam trong
     * MainActivity.startHostRoom()) vao chay trong Service - noi duoc bao
     * ve boi foreground service, KHONG bi OS chu dong huy khi nguoi dung
     * chuyen sang app khac (YouTube) nhu Activity truoc day. Logic ben
     * trong (tao QrJoinData, tao WebRtcManager, tao SignalingServer voi
     * Listener) giu NGUYEN so voi ban goc trong MainActivity, chi doi noi
     * chay va cach bao ket qua ve UI (qua callback tinh trong MainActivity:
     * onRoomReadyCallback/onRoomErrorCallback/onRoomMicStatusCallback thay
     * vi goi truc tiep AlertDialog/Toast tai cho, vi Service KHONG co quyen
     * truy cap UI truc tiep).
     */
    private fun startHostRoomInternal() {
        if (hostSignalingServer != null || hostWebRtcManager != null) {
            logBoth("[PhongKaraoke] Dang co phong cu chay - tu dong dong truoc khi tao phong moi")
            stopHostRoomInternal()
        }

        if (!mixerTestActive) {
            logBoth(
                "[PhongKaraoke] ⚠️ Chua bat Mixer Test - Mic tu xa se khong co cho de hoa vao " +
                    "cho toi khi ban bat Mixer Test."
            )
        }

        val qrData = QrJoinData.generate(applicationContext)
        if (qrData == null) {
            logBoth("[PhongKaraoke] ❌ Khong tim thay IP Wi-Fi - khong the tao phong.", isError = true)
            MainActivity.onRoomErrorCallback?.invoke(
                "Khong tim thay IP Wi-Fi! Hay ket noi cung mang Wi-Fi (hoac bat Hotspot)."
            )
            return
        }

        val manager = WebRtcManager(applicationContext).apply {
            onRemotePcmChunk = { clientId, buffer, size ->
                pushRemoteVocalChunk(clientId, buffer, size)
            }
        }

        val server = SignalingServer(
            port = qrData.port,
            expectedRoomId = qrData.roomId,
            expectedToken = qrData.token,
            listener = object : SignalingServer.Listener {
                override fun onMicConnected(clientId: String) {
                    connectedRemoteClientIds.add(clientId)
                    MainActivity.onRoomMicStatusCallback?.invoke(clientId, true)
                }

                override fun onMicDisconnected(clientId: String) {
                    connectedRemoteClientIds.remove(clientId)
                    hostWebRtcManager?.removeClient(clientId)
                    // ✅ Don sach dung kenh vocal cua clientId nay khoi mixer
                    // (xem removeRemoteVocalSource() da co san o companion).
                    removeRemoteVocalSource(clientId)
                    MainActivity.onRoomMicStatusCallback?.invoke(clientId, false)
                }

                override fun onOfferReceived(clientId: String, sdp: String) {
                    hostWebRtcManager?.handleRemoteOffer(
                        clientId = clientId,
                        sdp = sdp,
                        onAnswerCreated = { answerSdp -> hostSignalingServer?.sendAnswer(clientId, answerSdp) },
                        onIceCandidateGenerated = { mid, idx, cand -> hostSignalingServer?.sendIce(clientId, mid, idx, cand) }
                    )
                }

                override fun onIceReceived(clientId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    hostWebRtcManager?.addRemoteIceCandidate(clientId, sdpMid, sdpMLineIndex, candidate)
                }
            }
        ).apply { start() }

        hostWebRtcManager = manager
        hostSignalingServer = server
        activeRoomQrData = qrData

        logBoth(
            "[PhongKaraoke] ✅ Da mo phong '${qrData.roomId}' tai ${qrData.host}:${qrData.port} " +
                "- CHAY TRONG SERVICE (khong con bi dong khi Activity bi OS don dep luc backgrounded)."
        )
        MainActivity.onRoomReadyCallback?.invoke(qrData)
    }

    /**
     * ✅ MOI: dong Phong Karaoke - goi khi nguoi dung bam "Dung phong" (qua
     * Intent tu MainActivity), hoac tu dong khi ca phien Phase 1 ket thuc
     * (xem stopCurrentSessionIfAny()). An toan goi nhieu lan / khi chua co
     * phong nao (tu kiem tra null o dau ham).
     */
    private fun stopHostRoomInternal() {
        if (hostSignalingServer == null && hostWebRtcManager == null) return

        hostSignalingServer?.stopServer()
        hostSignalingServer = null
        hostWebRtcManager?.closeAll()
        hostWebRtcManager = null

        // Don sach het cac "kenh" vocal cua tat ca May B/C con lai cua
        // phien phong vua dong - tranh mixer giu lai nguon "ma".
        connectedRemoteClientIds.forEach { removeRemoteVocalSource(it) }
        connectedRemoteClientIds.clear()
        activeRoomQrData = null

        logBoth("[PhongKaraoke] 🛑 Da dong Phong Karaoke.")
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

        val finalLimiterInstance = Limiter(sampleRate = 44100, thresholdRatio = 0.9f, releaseMs = 50f)
        val router = OutputRouter(this, AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).apply { start() }
        val mix = LowLatencyMixer(router, finalLimiter = finalLimiterInstance).apply { start() }
        val mic = MicInput(this)

        mixerOutputRouter = router
        mixer = mix
        micInput = mic
        finalMixLimiter = finalLimiterInstance
        activeMixerInstance = mix

        // ✅ SUA (Phase 6 - bo HowlGuard): khong con reset bien howl* (da go
        // bo hoan toan). Thay vao do, reset state DSP NOI BO (filter/
        // compressor/echo/auto-gain/limiter) cua CAC kenh vocal DA TON TAI
        // tu truoc (vd sau 1 chu ky "Tat roi Bat lai" cua overlay) - tranh
        // tan du (click/pop) tu session truoc, nhung GIU NGUYEN volume/EQ/
        // cong tac nguoi dung da tu dieu chinh (khong reset ve mac dinh moi
        // lan bat lai Mixer Test - xem VocalChannel.reset()).
        vocalChannels.values.forEach { it.reset() }

        // ✅ SUA LOI GOC RE (fix "tieng ret/giat 60ms-1119ms dinh ky, dac
        // biet NGAY LUC vua bat Mixer Test" - xac nhan qua chan doan log
        // thuc te: doi chieu thoi diem "Bat dau mixer loop" voi
        // "CpuJitterProbe TRE 1119ms" xay ra CHI VAI TRAM ms sau, dung luc
        // mic.startCapture() dang chay): TRUOC DAY mic.startCapture() (mo
        // AudioRecord - co the thu toi 2 lan: UNPROCESSED roi fallback MIC
        // neu lan dau that bai, MOI lan la 1 loi goi native/IPC toi
        // AudioFlinger co the mat vai chuc ms) chay DONG BO, TRUC TIEP tren
        // thread goi ham nay - MA thread do la MAIN THREAD (startMixerTestInternal
        // duoc goi tu Handler.postDelayed trong chuoi Reactivation, khong
        // phai tu 1 coroutine rieng). Dung luc DO, OutputRouter/AudioTrack o
        // tren CUNG vua duoc mo (IPC AudioFlinger khac) - 2 chuoi IPC audio
        // nang dong bo, LIEN TIEP tren main thread, xay ra CHINH XAC luc
        // LowLatencyMixer (thread rieng, URGENT_AUDIO) vua bat dau vong lap
        // dau tien va nhay cam nhat voi tranh chap CPU/scheduler - giai
        // thich dung hien tuong "tre 1119ms" chi xay ra 0 den vai giay SAU
        // khi mixer start(), khong phai ngau nhien luc nao cung the.
        //
        // Sua: chuyen mic.startCapture() sang chay tren serviceScope
        // (Dispatchers.Default, THREAD POOL RIENG, khong phai main thread)
        // thay vi goi truc tiep - giai phong main thread NGAY LAP TUC (khong
        // con phai cho AudioRecord khoi tao xong moi tiep tuc ham nay), giam
        // manh khoang thoi gian main thread bi "chiem dung" lien tuc boi
        // chuoi IPC audio, tu do giam co hoi scheduler tranh CPU cua mixer
        // thread dung luc nhay cam nhat. AN TOAN: `mic`/`mix` la local val,
        // closure ben trong startCapture() giu dung tham chieu bat ke chay
        // tren thread nao; `micInput = mic` (o tren) DA duoc gan TRUOC khi
        // launch, nen neu nguoi dung bam TAT Mixer Test ngay lap tuc,
        // stopMixerTestInternal() van thay dung instance de goi stopCapture()
        // (huy AudioRecord dang khoi tao dang do mot cach an toan, xem
        // MicInput.stopCapture()).
        serviceScope.launch {
            mic.startCapture(onPcmChunk = { buffer, size ->
                if (localMicMutedForMixer) return@startCapture

                // ✅ SUA (Phase 6 - bo HowlGuard, xem giai thich day du o khai
                // bao truong howl* cu da bi xoa phia tren): mic vat ly gio chi
                // con di qua 1 VocalChannel DUY NHAT (dung chung logic voi tung
                // nguon remote - xem VocalChannel.kt), KHONG con co che tu dong
                // "cam mic" khi nghi ngo hu nua. Chong hu vat ly la trach nhiem
                // cua nguoi dung (giam am loa / dua mic ra xa / dung tai nghe).
                val channel = getOrCreateVocalChannel(LowLatencyMixer.SOURCE_LOCAL_MIC)
                channel.process(buffer, size)

                synchronized(vocalPushLock) {
                    mix.pushVocal(LowLatencyMixer.SOURCE_LOCAL_MIC, buffer, size)
                }
            })
        }

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

        // ✅ MOI (SUA - ban day du): hien cung luc nut "Ban mixer on fly" -
        // gio MixerBoardOverlay tu goi thang PlaybackCaptureService companion
        // object (giong dialog trong MainActivity, qua MixerBoardUiBuilder
        // dung chung) thay vi nhan 6 lambda getter/setter rieng le nhu ban
        // 3-slider cu - dam bao ban mixer nhanh nay va dialog day du trong
        // MainActivity LUON dong bo VOI NHAU (cung 1 nguon du lieu duy nhat).
        if (mixerBoardOverlay == null) {
            mixerBoardOverlay = MixerBoardOverlay(appContext = applicationContext)
        }
        mixerBoardOverlay?.show()

        mixerTestActive = true

        MainActivity.refreshMixerTestButtonState(true)

        logBoth("✅ Da bat dau Mixer Test (Phase 3).")
    }

    private fun stopMixerTestInternal() {
        if (mixer == null && micInput == null && mixerOutputRouter == null) return

        volumeGuardJob?.cancel()
        volumeGuardJob = null

        mixerToggleOverlay?.updateState(isRunning = false)
        // ✅ Dong bang mixer nhanh (neu dang mo) khi Mixer Test TAT - cac
        // slider (musicVolume/masterVolume/volume mic) khong con y nghia
        // gi khi Mixer khong chay. GIU LAI nut "🎚️" tren man hinh (giong
        // tinh than mixerToggleOverlay chi doi trang thai, khong hide())
        // de nguoi dung van bam duoc khi Mixer Test BAT LAI sau do.
        mixerBoardOverlay?.closeBoardIfOpen()
        mixerTestActive = false

        MainActivity.refreshMixerTestButtonState(false)

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
        finalMixLimiter?.reset()
        finalMixLimiter = null
        activeMixerInstance = null

        // ✅ SUA (Phase 6): KHONG con xoa vocalChannels o day - lam vay se
        // mat het volume/EQ nguoi dung da chinh moi lan Tat/Bat Mixer Test
        // (vd trong chuoi "Kich hoat lai" cua overlay, xay ra rat thuong
        // xuyen). Cac kenh remote van duoc don rieng khi TUNG clientId that
        // su ngat ket noi (removeRemoteVocalSource(), goi tu
        // onMicDisconnected/stopHostRoomInternal) - dung noi de don, khong
        // phai o day.

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
                cancelPendingReactivation()
                stopMixerTestInternal()
                return START_NOT_STICKY
            }
            ACTION_START_HOST_ROOM -> {
                startHostRoomInternal()
                return START_NOT_STICKY
            }
            ACTION_STOP_HOST_ROOM -> {
                stopHostRoomInternal()
                return START_NOT_STICKY
            }
            ACTION_STOP_ALL -> {
                logBoth("🛑 [StopAll] Nguoi dung yeu cau tat hoan toan - dung capture + an nut noi + huy Service.")
                stopCurrentSessionIfAny()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
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
                cancelPendingReactivation()
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Karaoke App - Phase 1")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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