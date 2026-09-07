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
 * ✅ FIX THUC SU (xem startClientPeer() ben duoi):
 * doi sang ordered=true, maxRetransmits=0. ordered=true buoc WebRTC/SCTP
 * giao dung thu tu da gui, loai bo hoan toan nguyen nhan dao lon dang song do
 * goi bi chia manh qua MTU Wi-Fi. maxRetransmits=0 (KHONG retry) de bu lai -
 * voi audio realtime, mat 1 chunk ~40ms roi bo qua (drop) va tiep tuc bang
 * chunk moi nhat luon tot hon la cho retry gay tre day chuyen.
 *
 * ⚠️ GIOI HAN HIEN TAI: chi thiet ke cho DUNG 2 MAY (1 Mixer + 1 Mic tu xa)
 * nhu PLAN.md muc 7 mo ta - moi client co 1 scratch buffer PCM RIENG
 * (ConcurrentHashMap theo clientId) de tranh dua du lieu (race) NEU sau nay
 * mo rong len 3+ may gui PCM dong thoi; nhung cac phan khac (vi du
 * WebRtcManager dung 1 `localDataChannel` DUY NHAT o phia May B) van gia
 * dinh 1-mic-1-peer, chua ho tro 1 may B gui toi NHIEU May A cung luc.
 */
class WebRtcManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
        private const val CHANNEL_LABEL = "karaoke_pcm_stream"
    }

    private var factory: PeerConnectionFactory? = null

    // Giữ tham chiếu ADM để closeAll() có thể release() nó
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    // May A luu danh sach PeerConnection cua cac Mic con: clientId -> PeerConnection
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    // May B luu DataChannel gui audio ve A
    private var localDataChannel: DataChannel? = null

    // ✅ Tái sử dụng 1 DirectByteBuffer duy nhất, tránh malloc/GC pause liên tục
    private var sendByteBuffer: ByteBuffer? = null

    // MOI clientId co 1 scratch buffer RIENG de tranh dua du lieu
    private val pcmScratchBuffers = ConcurrentHashMap<String, ShortArray>()

    // Callback nhan PCM tu mic remote tren May A
    var onRemotePcmChunk: ((clientId: String, buffer: ShortArray, size: Int) -> Unit)? = null

    // Theo dõi nhịp gửi PCM từ máy B
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

        // ordered = true để không bị đảo lộn thứ tự mảnh sóng âm
        // maxRetransmits = 0 để tránh tích lũy độ trễ khi mất gói
        val init = DataChannel.Init().apply {
            ordered = true
            maxRetransmits = 0
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
            sendChannelNotOpenSkipCount++
            if (sendChannelNotOpenSkipCount % 25 == 0) {
                CaptureLogBus.log(
                    "[RemoteTiming-SendSide] ⚠️ DataChannel KHONG o trang thai OPEN " +
                        "(state=${channel.state()}) - da bo qua $sendChannelNotOpenSkipCount lan gui."
                )
            }
            return
        }

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

        // Tái sử dụng buffer an toàn, dùng val với if-else để đảm bảo kiểu non-null
        val bytesNeeded = size * 2
        val currentBuf = sendByteBuffer
        val bBuf = if (currentBuf == null || currentBuf.capacity() < bytesNeeded) {
            val newBuf = ByteBuffer.allocateDirect(bytesNeeded).order(ByteOrder.LITTLE_ENDIAN)
            sendByteBuffer = newBuf
            newBuf
        } else {
            currentBuf
        }

        bBuf.clear()
        for (i in 0 until size) {
            bBuf.putShort(buffer[i])
        }
        bBuf.flip()
        channel.send(DataChannel.Buffer(bBuf, true))
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