package com.karaokeapp.webrtc

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.karaokeapp.audio.music.CaptureLogBus
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Phase 5 - Quan tri ket noi WebRTC LAN cho karaoke.
 *
 * ⚠️ LUA CHON KIEN TRUC: dung DataChannel (khong dung AudioTrack/MediaStreamTrack
 * chuan cua WebRTC) de truyen PCM THO (ShortArray) truc tiep. Ly do: toan bo
 * pipeline hien tai (Mixer, Limiter, EQ...) deu thao tac truc tiep tren
 * ShortArray PCM tho - dung AudioTrack chuan cua WebRTC se bat buoc phai
 * giai ma Opus roi tu tay lay lai PCM qua 1 lop API rieng (AudioDeviceModule
 * tuy bien), phuc tap hon nhieu so voi loi ich mang lai o quy mo 2-3 may LAN.
 *
 * ⚠️ DANH DOI CAN BIET: PCM 44.1kHz/16-bit khong nen chiem ~688kbps lien tuc
 * (so voi Opus nen duoc con ~24-32kbps) - chap nhan duoc tren Wi-Fi LAN.
 *
 * ✅ CAP NHAT (fix "tieng ret ret cua Mic B qua mang, trong khi Mic A tai
 * cho luon muot" - phat hien qua so sanh thuc te 2 nguon): truoc day
 * DataChannel.Init() dat ordered=false, maxRetransmits=0 - nghia la UDP
 * THUAN TUYET DOI: BAT KY goi PCM ~40ms nao bi rot tren Wi-Fi (rat thuong
 * xay ra tren mang thuc te, dac biet qua Hotspot hoac Wi-Fi dong nguoi dung)
 * se KHONG BAO GIO duoc gui lai - tao thanh 1 khoang trong PCM dot ngot
 * (thay vi noi tiep lien tuc) o dung diem do, nghe nhu tieng "ret/tach" ro
 * rang. Day chinh la nguyen nhan khien Mic B (qua mang) co tieng ret ret
 * con Mic A (tai cho, khong qua mang) thi luon on dinh - vi Mic A khong he
 * di qua DataChannel/mang, khong co co hoi mat goi.
 *
 * Sua: doi maxRetransmits=0 -> maxRetransmits=1 (giu ordered=false) - cho
 * phep gui lai TOI DA 1 LAN neu goi dau bi mat, ma KHONG bat "ordered" (vi
 * ordered=true se bat WebRTC PHAI cho goi truoc den du, gay tich luy do tre
 * neu co goi bi mat lien tuc - hoan toan sai voi muc tieu do tre thap cua
 * karaoke). 1 lan retransmit la muc can bang: du tang do tre trung binh
 * len 1 chieu round-trip (thuong chi vai ms tren LAN cung Wi-Fi), nhung du
 * de "cuu" phan lon cac goi bi rot ngau nhien don le - loai bo nay khong
 * loai het duoc tieng ret (neu mang thuc su te lien tuc, van se con mat
 * goi sau ca lan retry), nhung giam dang ke tan suat so voi khong retry gi
 * ca. Neu sau nay van con nghe ret ret ro sau khi test, co the thu tang
 * len maxRetransmits=2 (danh doi them chut do tre de on dinh hon nua).
 *
 * ⚠️ GIOI HAN HIEN TAI: chi thiet ke cho DUNG 2 MAY (1 Mixer + 1 Mic tu xa)
 * nhu PLAN.md muc 7 mo ta - moi client co 1 JitterQueue PCM RIENG
 * (ConcurrentHashMap theo clientId) de tranh dua du lieu (race) NEU sau nay
 * mo rong len 3+ may gui PCM dong thoi; nhung cac phan khac (vi du
 * WebRtcManager dung 1 `localDataChannel` DUY NHAT o phia May B) van gia
 * dinh 1-mic-1-peer, chua ho tro 1 may B gui toi NHIEU May A cung luc (khong
 * nam trong pham vi Phase 5 theo PLAN).
 *
 * ✅ CAP NHAT (fix "loa nghe co luc bi cham" - jitter buffer + PLC): phia
 * May A (Host) GIO day KHONG con phat PCM ngay lap tuc khi DataChannel
 * nhan duoc (nhip nhan bat dinh, phu thuoc mang) - thay vao do chunk duoc
 * xep vao 1 hang doi (JitterQueue) va mot ticker rieng (playoutExecutor)
 * phat ra DEU DAN moi ~40ms, "bu" (Packet Loss Concealment) bang du lieu
 * cu khi chunk chua kip den thay vi de loa cam giac "khuyu/cham" dot ngot.
 * ⚠️ HE QUA: onRemotePcmChunk GIO duoc goi tu thread cua playoutExecutor
 * (mot ScheduledExecutorService rieng), KHONG con la thread callback goc
 * cua DataChannel.Observer.onMessage() nhu truoc - noi nao dang lang nghe
 * callback nay (vi du Mixer) can dam bao code cua no thread-safe cho truong
 * hop nay (thuong da dung vi PCM van la du lieu tho can duoc xu ly ngay,
 * khong lien quan UI thread).
 */
class WebRtcManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
        private const val CHANNEL_LABEL = "karaoke_pcm_stream"

        // ✅ MOI (xem giai thich chi tiet o dau file): cho phep gui lai TOI
        // DA 1 lan neu goi PCM dau bi rot tren mang - can bang giua do tre
        // thap (khong dung ordered=true) va giam tieng ret do mat goi don
        // le. Dat thanh hang so o day de de dang chinh lai (vi du thu 2)
        // neu test thuc te van con nghe ret sau ban sua nay.
        private const val DATA_CHANNEL_MAX_RETRANSMITS = 1

        // ✅ MOI (fix "loa nghe cham/giat lup bup" khi co jitter mang):
        // JITTER_BUFFER_TARGET_CHUNKS = so chunk PCM (~40ms/chunk) giu lai
        // TRONG HANG DOI truoc khi bat dau phat, thay vi phat NGAY chunk dau
        // tien vua nhan duoc. Muc dich: hap thu dao dong do tre mang
        // (jitter) - neu 1 chunk den tre ~40-80ms do Wi-Fi nghen tam thoi,
        // hang doi da co san du du lieu de "lap khoang trong" ma KHONG lam
        // rong tai audio callback. Danh doi: them ~2*40ms=80ms do tre co
        // dinh (dat chap nhan duoc cho karaoke LAN, van << 200ms nguong
        // nghe ro tre). Neu can giam do tre hon nua (chi mang rat on dinh),
        // co the giam xuong 1; neu van con nghe giat tren mang xau, tang len 3.
        private const val JITTER_BUFFER_TARGET_CHUNKS = 2

        // Chu ky "tick" phat 1 chunk tu hang doi ra ngoai (khop voi nhip
        // gui thuc te ~40ms/chunk cua MicInput ben May B).
        private const val PLAYOUT_TICK_MS = 40L

        // ✅ MOI (Packet Loss Concealment - PLC don gian): khi den luot phat
        // nhung hang doi RONG (chua kip nhan chunk moi, hoac chunk that su
        // bi mat vinh vien du da retransmit), thay vi phat im lang dot ngot
        // (nghe nhu "tach") hoac bo qua hoan toan (nghe nhu "giat/nhay
        // thoi gian"), PHAT LAP LAI chunk GAN NHAT da phat, nhan bien do
        // dan xuong qua moi lan lap (tranh tieng "ru ru" deu deu neu mat
        // nhieu chunk lien tiep). Toi da lap PLC_MAX_CONCEALED_CHUNKS lan
        // truoc khi chuyen han sang im lang (mat qua lau thi im lang van
        // tot hon la phat 1 am thanh lap lai khong lien quan keo dai).
        private const val PLC_MAX_CONCEALED_CHUNKS = 3
        // He so nhan bien do moi lan PLC lap lai (0.6 = giam ~4dB/lan).
        private const val PLC_ATTENUATION_FACTOR = 0.6
    }

    private var factory: PeerConnectionFactory? = null

    // ✅ FIX ("dễ rớt và không kết nối lại được" sau vài lần reconnect): giữ
    // tham chiếu ADM để closeAll() có thể release() nó - trước đây ADM được
    // tạo local trong initializeFactory() rồi bỏ luôn, không ai giữ để dọn.
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    // May A luu danh sach PeerConnection cua cac Mic con: clientId -> PeerConnection
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    // May B luu DataChannel gui audio ve A
    private var localDataChannel: DataChannel? = null

    // ⚠️ Bo dem so goi bi loai do den QUA TRE (retransmit den sau khi da
    // phat qua jitter buffer) - xem chi tiet trong unpackAndDeliverPcm().
    private var outOfOrderDropCount = 0

    // Callback nhan PCM tu mic remote tren May A
    var onRemotePcmChunk: ((clientId: String, buffer: ShortArray, size: Int) -> Unit)? = null

    // ✅ MOI (CHAN DOAN TAM THOI - do nhip GUI PCM thuc te tu chinh May B,
    // TRUOC khi bat cu qua DataChannel): so sanh voi log nhan o
    // PlaybackCaptureService.logRemoteChunkTiming() de biet giat dut quang
    // la do MAY B GUI KHONG DEU (vi du chinh MicInput cua May B bi nghen)
    // hay do MANG/DataChannel lam tre/rot giua duong (May B gui deu nhung
    // May A nhan khong deu). Du kien go bo sau khi xac dinh xong nguyen
    // nhan, KHONG phai code san xuat lau dai.
    private var lastSendNanoTime = 0L
    private var sendCountInWindow = 0
    private var sendMaxGapMsInWindow = 0L
    private var sendWindowStartNanoTime = 0L
    private var sendChannelNotOpenSkipCount = 0

    // ⚠️ MOI (fix loi phat hien khi phan tich maxRetransmits=1 + ordered=false
    // - xem giai thich day du o unpackAndDeliverPcm()): DataChannel voi
    // ordered=false KHONG dam bao thu tu den. Khi 1 goi bi mat va duoc gui
    // lai (retransmit), goi KE TIEP (gui sau nhung khong bi mat) rat co the
    // den TRUOC goi vua duoc gui lai - neu khong co gi danh dau thu tu, phia
    // nhan se PHAT SAI THU TU 2 chunk PCM lien tiep (nghe nhu giat/dao am
    // thanh), te hon ca 1 khoang trong don thuan. Dem tang don dieu moi lan
    // gui (May B, 1 chieu duy nhat -> khong can AtomicInteger/lock).
    private var outgoingSeq: Int = 0

    // ✅ MOI (Jitter Buffer - xem giai thich day du o JITTER_BUFFER_TARGET_CHUNKS):
    // moi clientId co 1 hang doi RIENG, sap xep theo seq (java.util.TreeMap
    // trong long PriorityQueue-nhu), giu cac chunk PCM da nhan nhung CHUA
    // phat ra ngoai. mot "playout scheduler" rieng (xem playoutExecutor ben
    // duoi) se tick dinh ky ~40ms/lan, lay 1 chunk ra khoi hang doi (theo
    // dung seq) va goi onRemotePcmChunk - TACH RIENG nhip NHAN (bat dinh,
    // phu thuoc mang) khoi nhip PHAT (deu dan, tu hang doi), day chinh la
    // co che hap thu jitter.
    private val jitterQueues = ConcurrentHashMap<String, JitterQueue>()

    // Scheduler dung chung cho TAT CA client (moi client 1 task rieng, chia
    // se 1 thread pool nho - khong can 1 thread/client vi cong viec rat nhe).
    private var playoutExecutor: ScheduledExecutorService? = null
    private val playoutTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /**
     * Trang thai jitter-buffer + PLC (Packet Loss Concealment) cho 1 clientId.
     * KHONG thread-safe noi bo (moi field chi duoc doc/ghi tu 1 thread duy
     * nhat: onMessage cua DataChannel ghi vao pendingChunks, playoutExecutor
     * tick doc/xoa) - dung 1 lock don gian (synchronized tren chinh object
     * nay) de tranh dua giua 2 nguon do WebRTC co the goi onMessage tren
     * thread khac voi thread cua ScheduledExecutorService.
     */
    private class JitterQueue {
        // seq -> PCM data, TU DONG sap xep theo seq tang dan (can cho viec
        // lay ra DUNG thu tu du chunk den khong dung thu tu do mang).
        val pendingChunks = sortedMapOf<Int, ShortArray>()
        var nextSeqToPlay: Int? = null // null = chua bat dau phat (dang cho du JITTER_BUFFER_TARGET_CHUNKS)
        var lastPlayedChunk: ShortArray? = null
        var lastPlayedSize: Int = 0
        var concealedCountInARow: Int = 0
        var hasStartedPlayback: Boolean = false
    }

    init {
        initializeFactory()
        playoutExecutor = Executors.newScheduledThreadPool(1)
    }

    private fun initializeFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // ✅ FIX ("May A bi nho tieng khi May B ket noi"): TRUOC DAY khong
        // truyen AudioDeviceModule (ADM) tuong minh -> WebRTC tu dung ADM
        // mac dinh (JavaAudioDeviceModule). Du ca app CHI dung DataChannel
        // de truyen PCM tho (KHONG he tao AudioTrack/MediaStreamTrack audio
        // nao), ADM mac dinh van co the tu xin AudioFocus va/hoac doi
        // AudioManager.mode sang MODE_IN_COMMUNICATION ngay khi PeerConnection
        // that su thiet lap (dung luc May B connect) - day la hanh vi NOI BO
        // cua thu vien WebRTC, KHONG phai code cua app chu dong lam. Hau qua:
        // giong het kieu "duck HAL/OEM" da ghi chu trong PlaybackCaptureService
        // - lam STREAM_MUSIC (MusicInput dang capture) hoac STREAM_SYSTEM
        // (Mixer dang phat) bi nho tieng.
        //
        // Sua: tu tao ADM tuong minh, tat het xu ly hardware AEC/NS (khong
        // can thiet vi app khong dung duong audio chuan cua WebRTC) - giam
        // toi da kha nang ADM dung cham vao AudioManager.
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        this.audioDeviceModule = audioDeviceModule

        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    // ✅ MOI (lop phong thu thu 2 - phong truong hop set ADM tuong minh o
    // tren van chua chan het): mot so ban WebRTC van co the doi
    // AudioManager.mode ngay khi PeerConnection dat trang thai ICE CONNECTED,
    // bat ke ADM duoc cau hinh the nao. Ep tra ve MODE_NORMAL ngay khi phat
    // hien bi doi - giong tinh than [AutoReassert] da co san trong
    // PlaybackCaptureService cho vu "duck HAL/OEM" cua Honor.
    private fun reassertNormalAudioModeIfNeeded(tag: String) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                CaptureLogBus.log(
                    "[WebRtcManager-$tag] ⚠️ AudioManager.mode bi doi thanh " +
                        "${audioManager.mode} (co the do ADM cua WebRTC) - tra ve MODE_NORMAL."
                )
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
            CaptureLogBus.log("[WebRtcManager-$tag] ❌ Loi khi kiem tra/reset AudioManager.mode: ${e.message}")
        }
    }

    private fun getRtcConfig(): PeerConnection.RTCConfiguration {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }

    // =========================================================================
    // PHIA MAY B (MIC KHONG DAY)
    // =========================================================================

    fun startClientPeer(
        signalingClient: SignalingClient,
        onIceCandidateGenerated: (sdpMid: String, sdpMLineIndex: Int, candidate: String) -> Unit,
        onConnected: () -> Unit
    ) {
        val pc = factory?.createPeerConnection(getRtcConfig(), object : PeerConnectionAdapter("ClientMic") {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    onIceCandidateGenerated(it.sdpMid, it.sdpMLineIndex, it.sdp)
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                CaptureLogBus.log("[WebRTC-Client] Trang thai ket noi ICE: $newState")
                if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                    reassertNormalAudioModeIfNeeded("Client")
                    onConnected()
                }
            }
        }) ?: return

        peerConnections[signalingClient.clientId] = pc

        // ✅ SUA (fix tieng ret ret - xem giai thich chi tiet o dau file):
        // doi maxRetransmits tu 0 (UDP thuan, khong retry) -> 1 (cho phep
        // gui lai 1 lan) - giu nguyen ordered=false (KHONG doi thanh true,
        // tranh gay tich luy do tre neu goi bi mat lien tuc).
        val init = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = DATA_CHANNEL_MAX_RETRANSMITS
        }
        localDataChannel = pc.createDataChannel(CHANNEL_LABEL, init)

        val constraints = MediaConstraints()
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    pc.setLocalDescription(SimpleSdpObserver(), it)
                    signalingClient.sendOffer(it.description)
                }
            }
        }, constraints)
    }

    /**
     * May B gui truc tiep tung chunk PCM thu duoc tu Mic sang May A qua WebRTC.
     */
    fun sendPcmChunkFromMic(buffer: ShortArray, size: Int) {
        val channel = localDataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) {
            // ✅ MOI (chan doan): dem so lan bi bo qua do channel CHUA/KHONG
            // con o trang thai OPEN - neu con so nay lon bat thuong trong 1
            // phien dang chay binh thuong, nghia la chinh DataChannel bi
            // rot/dong lai giua chung (khac voi mat goi UDP don le).
            sendChannelNotOpenSkipCount++
            if (sendChannelNotOpenSkipCount % 25 == 0) {
                CaptureLogBus.log(
                    "[RemoteTiming-SendSide] ⚠️ DataChannel KHONG o trang thai OPEN " +
                        "(state=${channel.state()}) - da bo qua $sendChannelNotOpenSkipCount lan gui."
                )
            }
            return
        }

        // ✅ MOI (chan doan - xem giai thich day du o khai bao cac bien
        // lastSendNanoTime/sendCountInWindow phia tren): do nhip GUI thuc te
        // tu chinh May B, TRUOC khi du lieu di vao DataChannel/mang.
        val now = System.nanoTime()
        if (lastSendNanoTime != 0L) {
            val gapMs = (now - lastSendNanoTime) / 1_000_000L
            if (gapMs >= 150L) {
                CaptureLogBus.log(
                    "[RemoteTiming-SendSide] ⚠️ May B: khoang trong giua 2 lan GUI PCM = ${gapMs}ms " +
                        "(binh thuong ~40ms/lan) - neu thay dong nay, nghia la CHINH MicInput/thread " +
                        "cua May B bi nghen, KHONG phai loi mang/DataChannel."
                )
            }
            sendMaxGapMsInWindow = max(sendMaxGapMsInWindow, gapMs)
        }
        lastSendNanoTime = now
        sendCountInWindow++
        if (sendWindowStartNanoTime == 0L) sendWindowStartNanoTime = now
        val windowElapsedMs = (now - sendWindowStartNanoTime) / 1_000_000L
        if (windowElapsedMs >= 3000L) {
            val expectedCount = (windowElapsedMs / 40L).toInt()
            CaptureLogBus.log(
                "[RemoteTiming-SendSide] 📊 May B trong ${windowElapsedMs}ms qua: " +
                    "da GUI $sendCountInWindow chunk (ky vong ~$expectedCount), " +
                    "gap lon nhat=${sendMaxGapMsInWindow}ms."
            )
            sendCountInWindow = 0
            sendMaxGapMsInWindow = 0L
            sendWindowStartNanoTime = now
        }

        // ⚠️ SUA: them 4 byte seq (Int) o DAU buffer, TRUOC phan PCM - xem
        // giai thich day du o khai bao outgoingSeq/unpackAndDeliverPcm().
        val seq = outgoingSeq
        outgoingSeq++ // tran (overflow) ve Int.MIN_VALUE sau ~2.7 ty goi la
        // BINH THUONG va AN TOAN - phia nhan so sanh bang phep tru co dau
        // (wraparound-safe), khong so sanh truc tiep seq1 > seq2.

        val byteBuffer = ByteBuffer.allocateDirect(4 + size * 2).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.putInt(seq)
        for (i in 0 until size) {
            byteBuffer.putShort(buffer[i])
        }
        byteBuffer.flip()
        channel.send(DataChannel.Buffer(byteBuffer, true))
    }

    fun handleRemoteAnswer(clientId: String, sdp: String) {
        val pc = peerConnections[clientId] ?: return
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(SimpleSdpObserver(), sessionDescription)
    }

    // =========================================================================
    // PHIA MAY A (MIXER CHINH)
    // =========================================================================

    fun handleRemoteOffer(
        clientId: String,
        sdp: String,
        onAnswerCreated: (sdp: String) -> Unit,
        onIceCandidateGenerated: (sdpMid: String, sdpMLineIndex: Int, candidate: String) -> Unit
    ) {
        val pc = factory?.createPeerConnection(getRtcConfig(), object : PeerConnectionAdapter("Host-Peer-$clientId") {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    onIceCandidateGenerated(it.sdpMid, it.sdpMLineIndex, it.sdp)
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                CaptureLogBus.log("[WebRTC-Host] Trang thai ket noi ICE ($clientId): $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    reassertNormalAudioModeIfNeeded("Host-$clientId")
                }
            }

            override fun onDataChannel(dataChannel: DataChannel?) {
                CaptureLogBus.log("[WebRTC-Host] Nhan DataChannel tu Mic: $clientId")
                dataChannel?.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        Log.d(TAG, "Host DataChannel state: ${dataChannel.state()}")
                    }

                    override fun onMessage(buffer: DataChannel.Buffer?) {
                        buffer?.let {
                            unpackAndDeliverPcm(clientId, it.data)
                        }
                    }
                })
            }
        }) ?: return

        peerConnections[clientId] = pc

        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        desc?.let {
                            pc.setLocalDescription(SimpleSdpObserver(), it)
                            onAnswerCreated(it.description)
                        }
                    }
                }, MediaConstraints())
            }
        }, remoteDesc)
    }

    /**
     * ✅ SUA (nang cap tu ban "phat ngay lap tuc + drop-neu-sai-thu-tu" cu
     * len JITTER BUFFER + PLC - xem giai thich day du o JITTER_BUFFER_TARGET_CHUNKS,
     * class JitterQueue, ensurePlayoutTicker() va playoutTick() phia tren):
     *
     * VAN DE CUA BAN CU: ordered=false KHONG dam bao chunk den DUNG thu tu
     * da GUI, va moi khi phat hien sai thu tu (thuong do 1 goi bi
     * retransmit), ban cu DROP HOAN TOAN chunk do - tao ra 1 khoang trong
     * PCM dot ngot (nghe nhu "tach/khuyu" 1 nhip), CHINH LA nguyen nhan
     * chinh gay cam giac "loa bi cham" nguoi dung phan anh.
     *
     * HAM NAY GIO CHI LAM 1 VIEC: giai ma seq + PCM tho tu byteBuffer, roi
     * XEP VAO JitterQueue cua clientId tuong ung (co kiem tra chong chen
     * nguoc chunk qua cu). KHONG con phat truc tiep onRemotePcmChunk tai
     * day nua - viec do da chuyen sang playoutTick() chay dinh ky rieng
     * biet, cho phep "dem" mot chut du lieu (JITTER_BUFFER_TARGET_CHUNKS)
     * truoc khi phat, va "bu" (PLC) khi chunk chua kip den thay vi im lang
     * dot ngot.
     */
    private fun unpackAndDeliverPcm(clientId: String, byteBuffer: ByteBuffer) {
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (byteBuffer.remaining() < 4) {
            CaptureLogBus.log("[WebRTC-Host] ⚠️ Chunk PCM tu $clientId qua ngan (thieu header seq) - bo qua.")
            return
        }
        val seq = byteBuffer.int
        val shortCount = byteBuffer.remaining() / 2
        val chunk = ShortArray(shortCount)
        for (i in 0 until shortCount) {
            chunk[i] = byteBuffer.short
        }

        // ✅ SUA (thay the co che "phat ngay lap tuc" cu bang jitter buffer -
        // xem giai thich day du o JITTER_BUFFER_TARGET_CHUNKS va class
        // JitterQueue phia tren): CHUNK NHAN duoc GIO CHI duoc XEP VAO HANG
        // DOI theo seq, KHONG con goi onRemotePcmChunk truc tiep tai day
        // nua - viec PHAT thuc su duoc mot ticker rieng (ensurePlayoutTicker())
        // dam nhiem theo nhip DEU ~40ms, doc hoc tu hang doi nay.
        val queue = jitterQueues.getOrPut(clientId) { JitterQueue() }
        synchronized(queue) {
            // Neu chunk nay qua CU (seq <= seq da tung phat), day la ban
            // retransmit den SAU 1 chunk moi hon da duoc phat roi - khong
            // the "chen nguoc thoi gian" vao hang doi nua, bo di (giu dung
            // tinh than OrderGuard cu, chi khac la kiem tra so voi seq DA
            // PHAT thay vi seq DA NHAN).
            val playedBoundary = queue.nextSeqToPlay
            if (playedBoundary != null && (seq - playedBoundary) < 0) {
                outOfOrderDropCount++
                if (outOfOrderDropCount % 25 == 0) {
                    CaptureLogBus.log(
                        "[RemoteTiming-OrderGuard] ⚠️ Da bo $outOfOrderDropCount chunk PCM tu $clientId " +
                            "den QUA TRE (seq=$seq, da phat toi seq=$playedBoundary) - " +
                            "ban retransmit den sau chunk moi hon da phat, khong the chen nguoc."
                    )
                }
                return
            }
            queue.pendingChunks[seq] = chunk
        }
        ensurePlayoutTicker(clientId)
    }

    /**
     * ✅ MOI (Playout Ticker - phan "phat ra" cua jitter buffer): dam bao co
     * 1 task dinh ky (~PLAYOUT_TICK_MS/lan) dang chay cho clientId nay, doc
     * TUAN TU tung chunk PCM tu JitterQueue va goi onRemotePcmChunk - nhip
     * phat nay DEU DAN, TACH BIET hoan toan khoi nhip NHAN chunk qua mang
     * (von co the dao dong do jitter). Idempotent: goi nhieu lan chi tao 1
     * task duy nhat cho moi clientId (putIfAbsent).
     */
    private fun ensurePlayoutTicker(clientId: String) {
        val executor = playoutExecutor ?: return
        if (playoutTasks.containsKey(clientId)) return
        val future = executor.scheduleAtFixedRate({
            playoutTick(clientId)
        }, 0L, PLAYOUT_TICK_MS, TimeUnit.MILLISECONDS)
        val existing = playoutTasks.putIfAbsent(clientId, future)
        if (existing != null) {
            // Task khac da tao truoc do trong luc ta dang tao - huy ban thua.
            future.cancel(false)
        }
    }

    /**
     * 1 "nhip" phat cho 1 clientId, chay tren playoutExecutor (KHONG chay
     * tren thread WebRTC nhan goi). Logic:
     *  1) Chua du du lieu de bat dau (< JITTER_BUFFER_TARGET_CHUNKS chunk
     *     dau tien) -> cho, khong phat gi ca (tranh bat dau qua som roi
     *     ngay lap tuc bi doi/PLC do chua kip tich luy dem).
     *  2) Co chunk dung seq can phat -> phat that (PLC counter ve 0).
     *  3) KHONG co chunk dung seq (dang cho, hoac chunk that su da mat vinh
     *     vien sau ca retransmit) -> PLC: phat lap chunk gan nhat, giam dan
     *     bien do, toi da PLC_MAX_CONCEALED_CHUNKS lan lien tiep; qua nguong
     *     do thi ngung han (khong con gi de "lap" tranh tieng on lap vo nghia).
     */
    private fun playoutTick(clientId: String) {
        val queue = jitterQueues[clientId] ?: return
        val (toPlay, size, isConcealed) = synchronized(queue) {
            if (!queue.hasStartedPlayback) {
                if (queue.pendingChunks.size < JITTER_BUFFER_TARGET_CHUNKS) {
                    return@synchronized Triple(null, 0, false)
                }
                queue.hasStartedPlayback = true
                queue.nextSeqToPlay = queue.pendingChunks.firstKey()
            }

            val seqToPlay = queue.nextSeqToPlay
            val exact = if (seqToPlay != null) queue.pendingChunks.remove(seqToPlay) else null
            if (exact != null) {
                queue.lastPlayedChunk = exact
                queue.lastPlayedSize = exact.size
                queue.concealedCountInARow = 0
                queue.nextSeqToPlay = (seqToPlay!!) + 1
                return@synchronized Triple(exact, exact.size, false)
            }

            // Khong co chunk dung seq: thu PLC neu con "quota" va co du lieu
            // gan nhat de lap lai.
            val lastChunk = queue.lastPlayedChunk
            if (lastChunk != null && queue.concealedCountInARow < PLC_MAX_CONCEALED_CHUNKS) {
                queue.concealedCountInARow++
                val attenuation = Math.pow(PLC_ATTENUATION_FACTOR, queue.concealedCountInARow.toDouble())
                val concealed = ShortArray(queue.lastPlayedSize) { i ->
                    (lastChunk[i] * attenuation).toInt().toShort()
                }
                // Van tang nextSeqToPlay de khi chunk that (seq bi "bo lo")
                // cuoi cung cung den (vi du qua retransmit tre), no se bi
                // OrderGuard loai vi qua cu - dung, vi ta da "bu" no bang PLC
                // roi, khong the phat lai lan 2 (se nghe nhu echo/lap).
                queue.nextSeqToPlay = (seqToPlay ?: 0) + 1
                return@synchronized Triple(concealed, concealed.size, true)
            }

            // Het quota PLC va/hoac chua co du lieu nao de lap - im lang,
            // nhung VAN tang nextSeqToPlay de khong bi ket cung 1 vi tri mai.
            queue.nextSeqToPlay = (seqToPlay ?: 0) + 1
            Triple(null, 0, false)
        }

        if (toPlay == null) {
            if (isConcealed) {
                CaptureLogBus.log("[JitterBuffer-PLC] ⚠️ $clientId: mat chunk, dang bu bang du lieu cu.")
            }
            return
        }
        if (isConcealed) {
            CaptureLogBus.log("[JitterBuffer-PLC] 🩹 $clientId: phat chunk BU (lap+giam bien do) do chunk that chua den kip.")
        }
        onRemotePcmChunk?.invoke(clientId, toPlay, size)
    }

    fun addRemoteIceCandidate(clientId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val pc = peerConnections[clientId] ?: return
        pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun removeClient(clientId: String) {
        peerConnections.remove(clientId)?.apply {
            close()
            dispose()
        }
        // ✅ SUA (thay the don pcmScratchBuffers/lastDeliveredSeq cu bang don
        // jitter buffer + playout task moi): huy task phat dinh ky va xoa
        // hang doi cua client vua roi phong, tranh ro ri nho/task chay vo
        // ich neu co nhieu client noi/roi lien tuc trong 1 phien dai.
        playoutTasks.remove(clientId)?.cancel(false)
        jitterQueues.remove(clientId)
    }

    fun closeAll() {
        localDataChannel?.close()
        localDataChannel = null
        peerConnections.forEach { (_, pc) ->
            pc.close()
            pc.dispose()
        }
        peerConnections.clear()
        playoutTasks.forEach { (_, future) -> future.cancel(false) }
        playoutTasks.clear()
        jitterQueues.clear()

        // ✅ FIX (xem giai thich o khai bao truong `factory`/`audioDeviceModule`
        // phia tren): TRUOC DAY closeAll() chi don PeerConnection/DataChannel,
        // KHONG BAO GIO giai phong chinh PeerConnectionFactory hay ADM da tao
        // trong initializeFactory() - moi lan connectToRoomAsMic() tao 1
        // WebRtcManager MOI (xem MainActivity), nghia la moi lan "Ket noi lai"
        // hoac quet QR lai la 1 factory+ADM native MOI bi "mo cong" trong khi
        // ban CU khong bao gio duoc dong - tich luy dan qua nhieu lan roi/ket
        // noi lai, cuoi cung gay ket noi that bai/khong on dinh. dispose()
        // factory TRUOC, roi release() ADM SAU (dung thu tu WebRTC yeu cau -
        // factory co the con giu tham chieu toi ADM ben trong).
        try {
            factory?.dispose()
        } catch (e: Exception) {
            CaptureLogBus.log("[WebRtcManager] ⚠️ Loi khi dispose PeerConnectionFactory: ${e.message}")
        }
        factory = null

        try {
            audioDeviceModule?.release()
        } catch (e: Exception) {
            CaptureLogBus.log("[WebRtcManager] ⚠️ Loi khi release AudioDeviceModule: ${e.message}")
        }
        audioDeviceModule = null

        // ✅ MOI (dong bo voi fix ro ri factory/ADM da co san o tren): don
        // luon thread pool cua jitter-buffer playout ticker - neu khong,
        // moi lan tao WebRtcManager moi (Ket noi lai/quet QR lai) se "mo"
        // 1 thread pool moi ma KHONG BAO GIO tat ban cu, tich luy dan qua
        // nhieu lan ket noi lai giong nhu van de factory/ADM da tung gap.
        try {
            playoutExecutor?.shutdownNow()
        } catch (e: Exception) {
            CaptureLogBus.log("[WebRtcManager] ⚠️ Loi khi shutdown playoutExecutor: ${e.message}")
        }
        playoutExecutor = null
    }
}

// Lop tien ich boc cac interface rom ra cua WebRTC
open class PeerConnectionAdapter(private val tag: String) : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(candidate: IceCandidate?) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
    override fun onAddStream(stream: MediaStream?) {}
    override fun onRemoveStream(stream: MediaStream?) {}
    override fun onDataChannel(dataChannel: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(err: String?) { Log.e("SimpleSdpObserver", "Loi tao SDP: $err") }
    override fun onSetFailure(err: String?) { Log.e("SimpleSdpObserver", "Loi nap SDP: $err") }
}