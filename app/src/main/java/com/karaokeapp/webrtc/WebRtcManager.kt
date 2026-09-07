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
 * nhu PLAN.md muc 7 mo ta - moi client co 1 scratch buffer PCM RIENG
 * (ConcurrentHashMap theo clientId) de tranh dua du lieu (race) NEU sau nay
 * mo rong len 3+ may gui PCM dong thoi; nhung cac phan khac (vi du
 * WebRtcManager dung 1 `localDataChannel` DUY NHAT o phia May B) van gia
 * dinh 1-mic-1-peer, chua ho tro 1 may B gui toi NHIEU May A cung luc (khong
 * nam trong pham vi Phase 5 theo PLAN).
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

    // ✅ SUA (khac code mau goc): MOI clientId co 1 scratch buffer RIENG,
    // KHONG dung chung 1 buffer cho moi client - buffer dung chung se bi
    // GHI DE/DUA DU LIEU neu 2 client gui PCM gan nhu dong thoi (callback
    // onMessage cua WebRTC co the chay tren cac thread khac nhau tuy
    // PeerConnection). Voi dung 2 may (1 mic tu xa) nhu Phase 5 mo ta thi
    // khong xay ra dua, nhung sua san de an toan neu mo rong len 3+ may.
    private val pcmScratchBuffers = ConcurrentHashMap<String, ShortArray>()

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

    init {
        initializeFactory()
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

        val byteBuffer = ByteBuffer.allocateDirect(size * 2).order(ByteOrder.LITTLE_ENDIAN)
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

    private fun unpackAndDeliverPcm(clientId: String, byteBuffer: ByteBuffer) {
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val shortCount = byteBuffer.remaining() / 2

        // ✅ SUA: lay/tao scratch buffer RIENG cho clientId nay - xem giai
        // thich day du o khai bao pcmScratchBuffers phia tren.
        var scratch = pcmScratchBuffers[clientId]
        if (scratch == null || scratch.size < shortCount) {
            scratch = ShortArray(shortCount)
            pcmScratchBuffers[clientId] = scratch
        }
        for (i in 0 until shortCount) {
            scratch[i] = byteBuffer.short
        }
        onRemotePcmChunk?.invoke(clientId, scratch, shortCount)
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
        // ✅ MOI: don luon scratch buffer cua client vua roi phong, tranh ro
        // ri nho neu co nhieu client noi/roi lien tuc trong 1 phien dai.
        pcmScratchBuffers.remove(clientId)
    }

    fun closeAll() {
        localDataChannel?.close()
        localDataChannel = null
        peerConnections.forEach { (_, pc) ->
            pc.close()
            pc.dispose()
        }
        peerConnections.clear()
        pcmScratchBuffers.clear()

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