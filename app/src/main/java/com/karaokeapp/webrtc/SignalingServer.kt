package com.karaokeapp.webrtc

import android.util.Log
import com.karaokeapp.audio.music.CaptureLogBus
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 5 - WebSocket Server chay cuc bo tren May A (Mixer). Lang nghe ket
 * noi tu cac may Mic trong cung mang Wi-Fi (khong can Internet/server cloud
 * - dung y PLAN.md muc 7: "chay thuan mang LAN").
 */
class SignalingServer(
    port: Int,
    private val expectedRoomId: String,
    private val expectedToken: String,
    private val listener: Listener
) : WebSocketServer(InetSocketAddress(port)) {

    interface Listener {
        fun onMicConnected(clientId: String)
        fun onMicDisconnected(clientId: String)
        fun onOfferReceived(clientId: String, sdp: String)
        fun onIceReceived(clientId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String)
    }

    // Map: ket noi WebSocket -> clientId. ConcurrentHashMap vi onOpen/onClose/
    // onMessage co the duoc thu vien goi tu nhieu thread khac nhau (moi client
    // 1 thread rieng theo mac dinh cua Java-WebSocket).
    private val clientMap = ConcurrentHashMap<WebSocket, String>()

    override fun onStart() {
        CaptureLogBus.log("[SignalingServer] Da khoi dong server tai cong $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d("SignalingServer", "Client mo ket noi: ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val clientId = clientMap.remove(conn)
        if (clientId != null) {
            CaptureLogBus.log("[SignalingServer] Mic '$clientId' da ngat ket noi")
            listener.onMicDisconnected(clientId)
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            val clientId = json.optString("clientId")

            when (type) {
                SignalingMessages.TYPE_JOIN -> {
                    val room = json.optString("room")
                    val token = json.optString("token")
                    if (room == expectedRoomId && token == expectedToken) {
                        clientMap[conn] = clientId
                        conn.send(SignalingMessages.createJoinOk(room, clientId))
                        CaptureLogBus.log("[SignalingServer] Mic '$clientId' da tham gia phong thanh cong")
                        listener.onMicConnected(clientId)
                    } else {
                        conn.send(SignalingMessages.createJoinDeny(room, "Sai room ID hoac token"))
                        conn.close()
                    }
                }
                SignalingMessages.TYPE_OFFER -> {
                    val sdp = json.optString("sdp")
                    listener.onOfferReceived(clientId, sdp)
                }
                SignalingMessages.TYPE_ICE -> {
                    val sdpMid = json.optString("sdpMid")
                    val sdpMLineIndex = json.optInt("sdpMLineIndex")
                    val candidate = json.optString("candidate")
                    listener.onIceReceived(clientId, sdpMid, sdpMLineIndex, candidate)
                }
                SignalingMessages.TYPE_LEAVE -> {
                    conn.close()
                }
            }
        } catch (e: Exception) {
            Log.e("SignalingServer", "Loi xu ly message: ${e.message}")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("SignalingServer", "Loi WebSocket: ${ex.message}")
    }

    fun sendAnswer(clientId: String, sdp: String) {
        val message = SignalingMessages.createAnswer(expectedRoomId, clientId, sdp)
        clientMap.forEach { (conn, id) ->
            if (id == clientId && conn.isOpen) {
                conn.send(message)
            }
        }
    }

    fun sendIce(clientId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val message = SignalingMessages.createIce(expectedRoomId, clientId, sdpMid, sdpMLineIndex, candidate)
        clientMap.forEach { (conn, id) ->
            if (id == clientId && conn.isOpen) {
                conn.send(message)
            }
        }
    }

    fun stopServer() {
        try {
            clientMap.forEach { (conn, _) ->
                if (conn.isOpen) conn.send(JSONObject().apply { put("type", SignalingMessages.TYPE_ROOM_CLOSED) }.toString())
            }
            stop()
        } catch (_: Exception) {}
    }
}