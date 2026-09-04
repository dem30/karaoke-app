package com.karaokeapp.webrtc

import android.util.Log
import com.karaokeapp.audio.music.CaptureLogBus
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

/**
 * Phase 5 - WebSocket Client chay tren May B/C (Mic khong day). Ket noi toi
 * May A theo thong tin quet duoc tu QR.
 */
class SignalingClient(
    serverUri: URI,
    private val roomId: String,
    private val token: String,
    val clientId: String,
    private val listener: Listener
) : WebSocketClient(serverUri) {

    interface Listener {
        fun onConnectedToMixer()
        fun onJoinedSuccess()
        fun onJoinFailed(reason: String)
        fun onAnswerReceived(sdp: String)
        fun onIceReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        fun onDisconnected()
    }

    override fun onOpen(handshakedata: ServerHandshake) {
        CaptureLogBus.log("[SignalingClient] Da noi WebSocket toi May A, gui lenh join...")
        listener.onConnectedToMixer()
        send(SignalingMessages.createJoin(roomId, token, clientId))
    }

    override fun onMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                SignalingMessages.TYPE_JOIN_OK -> {
                    CaptureLogBus.log("[SignalingClient] Da vao phong '$roomId' thanh cong!")
                    listener.onJoinedSuccess()
                }
                SignalingMessages.TYPE_JOIN_DENY -> {
                    val reason = json.optString("reason", "Bi tu choi")
                    CaptureLogBus.log("[SignalingClient] Vao phong that bai: $reason", isError = true)
                    listener.onJoinFailed(reason)
                }
                SignalingMessages.TYPE_ANSWER -> {
                    val sdp = json.optString("sdp")
                    listener.onAnswerReceived(sdp)
                }
                SignalingMessages.TYPE_ICE -> {
                    val sdpMid = json.optString("sdpMid")
                    val sdpMLineIndex = json.optInt("sdpMLineIndex")
                    val candidate = json.optString("candidate")
                    listener.onIceReceived(sdpMid, sdpMLineIndex, candidate)
                }
                SignalingMessages.TYPE_ROOM_CLOSED -> {
                    CaptureLogBus.log("[SignalingClient] Phong da dong boi may Mixer")
                    close()
                }
            }
        } catch (e: Exception) {
            Log.e("SignalingClient", "Loi phan tich JSON: ${e.message}")
        }
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        CaptureLogBus.log("[SignalingClient] Da ngat ket noi voi Mixer ($reason)")
        listener.onDisconnected()
    }

    override fun onError(ex: Exception) {
        Log.e("SignalingClient", "Loi Client: ${ex.message}")
    }

    fun sendOffer(sdp: String) {
        if (isOpen) {
            send(SignalingMessages.createOffer(roomId, clientId, sdp))
        }
    }

    fun sendIce(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        if (isOpen) {
            send(SignalingMessages.createIce(roomId, clientId, sdpMid, sdpMLineIndex, candidate))
        }
    }
}