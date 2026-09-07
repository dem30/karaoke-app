package com.karaokeapp.webrtc

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import com.karaokeapp.audio.music.CaptureLogBus
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 5 (BAN VIET LAI - bo DataChannel) - Quan tri ket noi WebRTC LAN cho
 * karaoke.
 *
 * ⚠️ THAY DOI KIEN TRUC LON (thay the toan bo cach lam cu): BAN CU dung
 * DataChannel de tu tay dong goi/gui ShortArray PCM tho, tu gom chunk
 * (SEND_BATCH_SAMPLES) de "nuong chieu" SCTP, tu code logic retransmit
 * (maxRetransmits=1). Van de: sau nhieu vong sua (retransmit, gom chunk,
 * WifiLock...), hien tuong "giat cum 150-400ms" van khong het - vi goc re
 * that su la PCM tho qua DataChannel hoan toan KHONG co bat ky co che nao
 * de xu ly mat goi/jitter mang THAT (khong giong RTP/Opus): 1 goi PCM ~80ms
 * bi mat/tre la 1 "lo hong" cung trong am thanh, cho du co retransmit 1 lan
 * thi cung chi giup voi mat goi DON LE ngau nhien, khong giup duoc khi
 * mang dao dong lien tuc (jitter tich luy).
 *
 * ✅ CACH LAM MOI: dung dung AudioTrack/MediaStreamTrack CHUAN cua WebRTC -
 * de chinh libwebrtc lo:
 * - Ma hoa Opus (nen ~24-40kbps thay vi ~688kbps PCM tho, on dinh hon
 *   nhieu tren Wi-Fi dong nguoi dung/hotspot).
 * - Jitter buffer thich ung (NetEQ) - tu dong gian/nen phat lai theo dieu
 *   kien mang THAT thoi, khong phai gia tri gom-chunk co dinh (80ms) dat
 *   tay nhu ban cu.
 * - PLC (Packet Loss Concealment) - khi mat goi, NetEQ "doan" va lap day
 *   khoang trong bang noi suy tin hieu am thanh THAT, nghe muot hon han 1
 *   khoang PCM = 0 hoan toan (im lang cung) nhu cach cu khi mat 1 batch.
 * - FEC/RTX o tang RTP - co san, khong phai tu code retransmit logic tay
 *   nhu DATA_CHANNEL_MAX_RETRANSMITS truoc day.
 *
 * ⚠️ DANH DOI CAN BIET (so voi ban PCM tho cu):
 * 1) Do tre ma hoa/giai ma Opus (~20-60ms tuy cau hinh) cong them vao pipeline -
 *    BU LAI boi jitter buffer on dinh hon nhieu, nen do tre THUC TE nghe
 *    duoc (bao gom ca thoi gian "cho bu goi mat") thuong THAP HON ban PCM
 *    tho khi mang khong hoan hao 100%.
 * 2) Audio bi nen mat mat (lossy) qua Opus - giong hat co the mat 1 chut
 *    "sac net" cuc cao so voi PCM tho 44.1kHz/16-bit, nhung o bitrate thoai
 *    (~32kbps+) cho giong nguoi la khong dang ke, va Opus duoc thiet ke
 *    rieng cho tin hieu thoai/nhac chat luong cao.
 * 3) ⚠️ QUAN TRONG NHAT can xu ly: AudioTrackSink cua WebRTC tra PCM da
 *    giai ma o SAMPLE RATE THUC TE cua duong truyen (thuong 48000Hz vi Opus
 *    hoat dong noi bo o 48kHz), trong khi TOAN BO pipeline Mixer/VocalChannel/
 *    AutoGain/EQ/Compressor hien co deu gia dinh CUNG 44100Hz (xem
 *    SAMPLE_RATE trong LowLatencyMixer.kt). PHAI resample ve 44100Hz truoc
 *    khi day vao pushRemoteVocalChunk(), neu khong giong hat se bi sai toc
 *    do/cao do (nhanh hon ~8.8% neu khong resample voi nguon 48kHz). Xem
 *    resampleLinear() ben duoi - dung linear interpolation don gian (du
 *    dung cho thoai o do tre thap, KHONG phai bo resample "chuan studio" -
 *    neu nghe ra aliasing/mat chat luong ro ret, can thay bang 1 thu vien
 *    resample chuyen dung, vd Speex resampler qua JNI).
 *
 * ⚠️ MAY B (MIC) - FILE NGOAI PHAM VI SUA O DAY: sau thay doi nay, viec
 * "capture PCM tu mic" KHONG con do MicInput/AudioRecord tu code cua app
 * dam nhiem nua o phia gui - chinh AudioDeviceModule cua WebRTC (ben trong
 * factory) se TU mo AudioRecord rieng cua no de "nuoi" AudioSource/AudioTrack
 * duoc tao trong startClientPeer() ben duoi. Bat ky noi nao (Activity/Service
 * khac, KHONG nam trong danh sach file da xem lai) dang goi
 * `mic.startCapture(onPcmChunk = { ... webRtcManager.sendPcmChunkFromMic(...) ... })`
 * o phia May B PHAI DUOC SUA: xoa hoan toan mic.startCapture()/
 * sendPcmChunkFromMic() (ham nay da bi xoa khoi class nay), CHI con goi
 * startClientPeer() la du - WebRTC tu lo phan con lai.
 *
 * ⚠️ GIOI HAN HIEN TAI (giu nguyen tu ban cu): chi thiet ke cho DUNG 2 MAY
 * (1 Mixer + 1 Mic tu xa) nhu PLAN.md muc 7 mo ta.
 */
class WebRtcManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"

        // Sample rate CHUNG cua toan bo pipeline DSP hien co (Mixer/VocalChannel/
        // AutoGain/EQ/Compressor...) - xem SAMPLE_RATE trong LowLatencyMixer.kt.
        // AudioTrackSink co the giao PCM o sample rate KHAC (thuong 48000Hz) -
        // moi truong hop nhu vay PHAI duoc resample ve dung gia tri nay truoc
        // khi goi onRemotePcmChunk (xem deliverDecodedAudio()/resampleLinear()).
        private const val TARGET_SAMPLE_RATE = 44100

        private const val LOCAL_AUDIO_TRACK_ID = "karaoke_mic_audio"
        private const val LOCAL_STREAM_ID = "karaoke_stream"

        // ✅ MOI: gioi han bitrate Opus tren moi RtpSender (phia May B gui di) -
        // Wi-Fi LAN thua suc bang thong cao hon nhieu, nhung KHONG can thiet:
        // gioi han o muc "thoai chat luong cao" (~40kbps) giup on dinh nhip
        // goi tin hon la de WebRTC tu do len muc toi da mac dinh (co the toi
        // ~510kbps cho Opus stereo full bandwidth) - muc cao khong can thiet
        // cho karaoke mono, ma con lam tang rui ro dot bien bang thong tren
        // Wi-Fi dong nguoi dung/hotspot re tien.
        private const val OPUS_MAX_BITRATE_BPS = 40_000
    }

    private var factory: PeerConnectionFactory? = null

    // ✅ FIX ("dễ rớt và không kết nối lại được" sau vài lần reconnect): giữ
    // tham chiếu ADM để closeAll() có thể release() nó - trước đây ADM được
    // tạo local trong initializeFactory() rồi bỏ luôn, không ai giữ để dọn.
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    // May A luu danh sach PeerConnection cua cac Mic con: clientId -> PeerConnection
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()

    // ✅ MOI (thay the localDataChannel cu): May B giu tham chieu AudioSource/
    // AudioTrack CUC BO cua chinh no - can giu de dispose() dung cach trong
    // closeAll(), tranh ro ri native object cua WebRTC.
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: org.webrtc.AudioTrack? = null

    // Callback nhan PCM (DA GIAI MA, DA RESAMPLE ve TARGET_SAMPLE_RATE) tu
    // mic remote tren May A - CHU KY KHONG DOI so voi ban DataChannel cu, nen
    // PlaybackCaptureService.kt KHONG can sua gi o phia goi callback nay.
    var onRemotePcmChunk: ((clientId: String, buffer: ShortArray, size: Int) -> Unit)? = null

    // ✅ MOI (thay the pcmScratchBuffers cu): scratch buffer PCM MONO sau khi
    // da downmix+resample, RIENG cho tung clientId (tranh dua du lieu neu mo
    // rong len nhieu May B/C gui dong thoi - AudioTrackSink.onData() co the
    // duoc goi tu cac thread noi bo khac nhau cua WebRTC tuy peer).
    private val outputScratchBuffers = ConcurrentHashMap<String, ShortArray>()

    // ✅ MOI: state resample RIENG cho tung clientId - giu vi tri phan-so
    // (fractional position) giua 2 lan goi onData() lien tiep de resample
    // KHONG bi "giat/click" o ranh gioi buffer (xem resampleLinear()).
    private val resampleStates = ConcurrentHashMap<String, ResampleState>()

    private class ResampleState {
        var fracPos: Double = 0.0
        var lastSample: Short = 0
    }

    init {
        initializeFactory()
    }

    private fun initializeFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // ✅ GIU NGUYEN tu ban cu: tu tao ADM tuong minh, tat xu ly hardware
        // AEC/NS (van dung duoc du gio DA dung duong audio chuan cua WebRTC -
        // pipeline DSP rieng cua app (VocalChannel: AutoGain/EQ/Compressor/
        // Echo) van la noi xu ly "chat am", KHONG muon WebRTC tu y AEC/NS o
        // tang native/hardware truoc khi PCM toi duoc tay app).
        //
        // ✅ FIX (phat hien sau khi doc lai MicInput.kt): setUseHardware*(false)
        // o tren CHI tat AEC/NS o tang xu ly cua WebRTC (webrtc::AudioProcessing),
        // KHONG doi AudioSource ma AudioRecord noi bo cua ADM mo. Neu khong tu
        // set, JavaAudioDeviceModule mac dinh dung
        // MediaRecorder.AudioSource.VOICE_COMMUNICATION - nguon nay tren nhieu
        // may van bi HAL/audio driver ap AEC/NS/AGC PHAN CUNG truoc khi WebRTC
        // kip nhan duoc PCM, bat ke cac co setUseHardware*(false) da tat gi o
        // tang tren. Dieu nay khien Mic B (qua WebRTC) khong con "cung 1 tin
        // hieu tho" nhu Mic A (MicInput.kt, dung UNPROCESSED/fallback MIC) -
        // sai voi gia dinh kien truc ghi trong comment lop class. Dong bo bang
        // cach uu tien UNPROCESSED, fallback MIC neu thiet bi khong ho tro -
        // giong het logic tryBuildAudioRecord() trong MicInput.kt.
        val preferredAudioSource = if (
            context.getSystemService(Context.AUDIO_SERVICE) is AudioManager &&
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        ) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(preferredAudioSource)
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
    //
    // ⚠️ LUU Y KHAC VOI BAN CU: gio May B THAT SU dung duong audio chuan cua
    // WebRTC (AudioSource/AudioTrack that, khong chi PeerConnection "rong"
    // nhu khi con DataChannel) - kha nang ADM tu doi AudioManager.mode sang
    // MODE_IN_COMMUNICATION cao hon truoc (day la hanh vi binh thuong/mong
    // doi cua 1 audio call that qua WebRTC). Neu app can giu MODE_NORMAL
    // xuyen suot (vi ly do tuong thich voi OutputRouter/AudioTrack rieng cua
    // Mixer ben May A), ham nay van can thiet; nhung CAN NGHE THU rieng xem
    // co gay tac dung phu gi voi chat luong capture mic cua May B khong (vi
    // MODE_IN_COMMUNICATION thuong di kem 1 so xu ly hardware co the co ich
    // cho cuoc goi thoai that).
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

        // ✅ MOI (thay the toan bo DataChannel.Init/createDataChannel cu):
        // tao AudioSource/AudioTrack THAT - day la thay doi cot loi cua ban
        // viet lai nay. Tat CA xu ly am thanh noi bo (APM) cua WebRTC qua
        // constraints "goog*" - van muon giu tin hieu mic THO nhat co the
        // truoc khi Opus encode, vi Host van tu lam AutoGain/EQ/Compressor/
        // Echo rieng qua VocalChannel (giu dung tinh than "1 pipeline DSP
        // duy nhat, khong chong cheo" da co tu Phase 6).
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "false"))
        }
        val source = factory?.createAudioSource(audioConstraints)
        localAudioSource = source
        val track = factory?.createAudioTrack(LOCAL_AUDIO_TRACK_ID, source)
        localAudioTrack = track

        if (track != null) {
            val sender = pc.addTrack(track, listOf(LOCAL_STREAM_ID))

            // ✅ MOI: gioi han bitrate Opus - xem giai thich o khai bao
            // OPUS_MAX_BITRATE_BPS phia tren. Sua RtpParameters SAU khi
            // addTrack() (RtpSender chi ton tai tu diem nay).
            try {
                // ⚠️ SUA LOI BIEN DICH: RtpSender.setParameters() tra ve Boolean
                // (khong phai Unit), nen Kotlin KHONG tu sinh synthetic property
                // "var parameters" cho cap getParameters()/setParameters() nay -
                // viet "sender.parameters = params" se bi loi "Val cannot be
                // reassigned" (Kotlin chi coi day la 1 "val" doc duoc tu
                // getParameters()). PHAI goi thang setParameters() nhu ham binh
                // thuong. Da xac nhan qua API doc chinh thuc cua dung ban thu
                // vien dang dung (io.getstream:stream-webrtc-android:1.3.10).
                val params = sender.parameters
                if (params.encodings.isNotEmpty()) {
                    params.encodings[0].maxBitrateBps = OPUS_MAX_BITRATE_BPS
                    val applied = sender.setParameters(params)
                    if (!applied) {
                        CaptureLogBus.log("[WebRTC-Client] ⚠️ setParameters() tra ve false - gioi han bitrate Opus co the chua duoc ap dung.")
                    }
                }
            } catch (e: Exception) {
                CaptureLogBus.log("[WebRTC-Client] ⚠️ Khong the gioi han bitrate Opus: ${e.message}")
            }
        } else {
            CaptureLogBus.log("[WebRTC-Client] ❌ Khong tao duoc AudioTrack - factory co the chua san sang.")
        }

        val offerConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    pc.setLocalDescription(SimpleSdpObserver(), it)
                    signalingClient.sendOffer(it.description)
                }
            }
        }, offerConstraints)
    }

    /**
     * ✅ MOI (thay the cach kiem tra "isLocalMicMutedForMixer()" NGAY TRONG
     * callback onPcmChunk cua MicInput - cach do KHONG con dung duoc vi
     * MicInput/onPcmChunk khong con ton tai o duong gui nay nua, xem KDoc
     * dau file): tat/bat mic dang GUI DI qua WebRTC bang chinh API chuan
     * cua MediaStreamTrack - setEnabled(false) khien track ngung gui am
     * thanh THAT (WebRTC se gui "silence"/khong gui goi RTP audio, tuy
     * trien khai), KHONG can tu code logic "return som, khong gui" nhu
     * truoc. Goi ham nay TRUC TIEP tu noi xu ly click nut "Khoa mic may
     * nay" (MainActivity) - KHONG con phu thuoc vao 1 vong lap PCM dinh ky
     * de "phat hien" thay doi flag nhu cach cu.
     */
    fun setLocalMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
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

            // ✅ MOI (thay the onDataChannel() cu hoan toan): Unified Plan bao
            // ve track moi (audio) qua onTrack(transceiver), KHONG con qua
            // onDataChannel() nua vi khong con DataChannel nao duoc tao.
            override fun onTrack(transceiver: RtpTransceiver?) {
                val remoteTrack = transceiver?.receiver?.track()
                if (remoteTrack is org.webrtc.AudioTrack) {
                    CaptureLogBus.log("[WebRTC-Host] Nhan Audio Track tu Mic: $clientId")

                    // ✅ QUAN TRONG: tat phat qua loa CUA CHINH WebRTC (ADM
                    // dung chung cho ca factory) - Host KHONG dung duong phat
                    // mac dinh cua WebRTC de nghe, vi da co OutputRouter/
                    // LowLatencyMixer rieng de tron nhac+vocal roi phat qua 1
                    // AudioTrack khac do CHINH app quan ly. Neu KHONG tat,
                    // tieng se bi PHAT 2 LAN (1 lan qua WebRTC truc tiep ra
                    // loa, 1 lan qua Mixer sau khi xu ly DSP) - nghe "vang
                    // doi/echo" ro ret. setVolume(0.0) chi tat DUONG PHAT
                    // PHAN CUNG, KHONG anh huong du lieu PCM ma sink ben
                    // duoi nhan duoc (sink lay tin hieu truoc buoc phat ra
                    // loa trong pipeline noi bo cua WebRTC).
                    remoteTrack.setVolume(0.0)

                    remoteTrack.addSink(object : AudioTrackSink {
                        override fun onData(
                            audioData: ByteBuffer,
                            bitsPerSample: Int,
                            sampleRate: Int,
                            numberOfChannels: Int,
                            numberOfFrames: Int,
                            absoluteCaptureTimestampMs: Long
                        ) {
                            deliverDecodedAudio(
                                clientId, audioData, bitsPerSample, sampleRate,
                                numberOfChannels, numberOfFrames
                            )
                        }
                    })
                }
            }
        }) ?: return

        peerConnections[clientId] = pc

        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                val answerConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        desc?.let {
                            pc.setLocalDescription(SimpleSdpObserver(), it)
                            onAnswerCreated(it.description)
                        }
                    }
                }, answerConstraints)
            }
        }, remoteDesc)
    }

    /**
     * ✅ MOI (thay the unpackAndDeliverPcm() cu): nhan PCM DA GIAI MA truc
     * tiep tu AudioTrackSink cua WebRTC (sau NetEQ/jitter buffer/PLC - khac
     * hoan toan ve ban chat so voi PCM tho nhan qua DataChannel truoc day).
     *
     * Lam 2 viec bat buoc truoc khi giao cho pipeline DSP hien co:
     * 1) Downmix ve MONO (toan bo VocalChannel/Mixer la mono) - remote track
     *    co the la mono hoac stereo tuy cau hinh AudioSource ben May B.
     * 2) Resample ve TARGET_SAMPLE_RATE (44100) neu khac - xem canh bao chi
     *    tiet ve ly do bat buoc o KDoc dau file.
     */
    private fun deliverDecodedAudio(
        clientId: String,
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int
    ) {
        if (bitsPerSample != 16) {
            // Chua gap truong hop nay trong thuc te (WebRTC Android luon giao
            // 16-bit PCM qua AudioTrackSink), nhung phong thu de tranh doc sai
            // ByteBuffer neu 1 ban WebRTC sau nay doi mac dinh.
            CaptureLogBus.log(
                "[WebRTC-Host] ⚠️ Bo qua 1 frame audio tu '$clientId' - bitsPerSample=$bitsPerSample " +
                    "khong duoc ho tro (chi ho tro 16-bit)."
            )
            return
        }
        if (numberOfFrames <= 0) return

        audioData.order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = audioData.asShortBuffer()

        val monoFrames = ShortArray(numberOfFrames)
        if (numberOfChannels <= 1) {
            for (i in 0 until numberOfFrames) monoFrames[i] = shortBuffer.get(i)
        } else {
            for (i in 0 until numberOfFrames) {
                var sum = 0
                for (c in 0 until numberOfChannels) sum += shortBuffer.get(i * numberOfChannels + c)
                monoFrames[i] = (sum / numberOfChannels).toShort()
            }
        }

        val resampled = if (sampleRate == TARGET_SAMPLE_RATE) {
            monoFrames
        } else {
            resampleLinear(clientId, monoFrames, sampleRate, TARGET_SAMPLE_RATE)
        }
        if (resampled.isEmpty()) return

        var scratch = outputScratchBuffers[clientId]
        if (scratch == null || scratch.size < resampled.size) {
            scratch = ShortArray(resampled.size)
            outputScratchBuffers[clientId] = scratch
        }
        System.arraycopy(resampled, 0, scratch, 0, resampled.size)
        onRemotePcmChunk?.invoke(clientId, scratch, resampled.size)
    }

    /**
     * ✅ MOI: resample linear-interpolation don gian, GIU state (fracPos/
     * lastSample) RIENG cho tung clientId de lien tuc muot giua 2 lan goi
     * onData() lien tiep (khong bi "click" tai ranh gioi buffer).
     *
     * ⚠️ CHAT LUONG: day la resample "co ban" (khong loc chong-alias truoc
     * khi noi suy) - CHAP NHAN DUOC cho tin hieu thoai o bitrate nay (Opus
     * da gioi han bang thong ~40kbps/tan so <~8kHz hieu qua, it rui ro
     * alias ro ret khi ha tu 48kHz -> 44.1kHz, ty le doi rat gan 1:1.088).
     * Neu sau nay nghe ra ro/aliasing ro ret, thay the bang 1 bo resample
     * co loc (vd Speex resampler qua JNI) thay vi tu viet them loc FIR o
     * day.
     */
    private fun resampleLinear(clientId: String, input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (input.isEmpty()) return input
        val state = resampleStates.getOrPut(clientId) { ResampleState() }
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outCount = (input.size / ratio).toInt()
        if (outCount <= 0) return ShortArray(0)

        val output = ShortArray(outCount)
        var pos = state.fracPos
        for (i in 0 until outCount) {
            val idx = pos.toInt()
            val frac = pos - idx
            val s0 = if (idx < input.size) input[idx] else state.lastSample
            val s1 = if (idx + 1 < input.size) input[idx + 1] else s0
            output[i] = (s0 + (s1 - s0) * frac).toInt().toShort()
            pos += ratio
        }
        // Giu lai phan du (fractional position) cho lan goi sau - tranh
        // "giat"/trôi pha dan qua nhieu buffer lien tiep.
        state.fracPos = pos - input.size
        state.lastSample = input.last()
        return output
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
        // ✅ SUA (thay the pcmScratchBuffers.remove() cu): don ca scratch
        // buffer VA state resample cua client vua roi phong.
        outputScratchBuffers.remove(clientId)
        resampleStates.remove(clientId)
    }

    fun closeAll() {
        // ✅ MOI (thay the localDataChannel?.close() cu): dispose track/source
        // CUC BO cua May B (neu co) - tranh ro ri native object cua WebRTC.
        try {
            localAudioTrack?.dispose()
        } catch (e: Exception) {
            CaptureLogBus.log("[WebRtcManager] ⚠️ Loi khi dispose localAudioTrack: ${e.message}")
        }
        localAudioTrack = null
        try {
            localAudioSource?.dispose()
        } catch (e: Exception) {
            CaptureLogBus.log("[WebRtcManager] ⚠️ Loi khi dispose localAudioSource: ${e.message}")
        }
        localAudioSource = null

        peerConnections.forEach { (_, pc) ->
            pc.close()
            pc.dispose()
        }
        peerConnections.clear()
        outputScratchBuffers.clear()
        resampleStates.clear()

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
    // onTrack(transceiver) da co default no-op tu interface PeerConnection.Observer -
    // cac noi can xu ly (vd Host trong handleRemoteOffer) tu override rieng.
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(err: String?) { Log.e("SimpleSdpObserver", "Loi tao SDP: $err") }
    override fun onSetFailure(err: String?) { Log.e("SimpleSdpObserver", "Loi nap SDP: $err") }
}
