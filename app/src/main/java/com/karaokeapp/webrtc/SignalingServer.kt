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
    private val port: Int,
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

    // ✅ MOI (fix "May B ket noi lai bi Host tu dong ngat sau vai chuc giay" -
    // BUG phat sinh SAU KHI clientId phia Mic B duoc doi thanh ON DINH theo
    // thiet bi, thay vi random moi lan connect): truoc day chi co clientMap
    // (conn -> clientId) - neu 1 May B mat mang KHONG dong socket sach se
    // (rot Wi-Fi dot ngot, khong co goi FIN/Close), socket CU van nam trong
    // clientMap cho toi khi thu vien Java-WebSocket tu phat hien "connection
    // lost" (mac dinh ~60s). Trong luc do, neu nguoi dung da bam "Ket noi
    // lai" va tao 1 socket MOI voi CUNG 1 clientId on dinh do, ca 2 socket
    // (cu + moi) DEU nam trong clientMap voi cung 1 clientId. Khi socket CU
    // cuoi cung bi phat hien mat (~60s sau), onClose(connCu) chay ->
    // listener.onMicDisconnected(clientId) van duoc goi - XOA NHAM
    // VocalChannel/PeerConnection cua PHIEN MOI dang chay tot (vi ca 2 deu
    // dung chung 1 key clientId o phia Host).
    //
    // Sua: them 1 map RIENG chi luu socket nao dang la "chu" HIEN TAI cua 1
    // clientId. Khi co JOIN moi voi clientId da ton tai, cap nhat "chu" moi
    // + chu dong dong socket CU (khong cho no song vo ich nua). Khi 1 socket
    // dong, CHI bao onMicDisconnected() neu dung socket DANG la "chu" cua
    // clientId do bi dong - neu no da bi 1 socket moi hon thay the tu truoc,
    // coi day la tieng vong cua phien CU, bo qua.
    private val activeConnectionForClientId = ConcurrentHashMap<String, WebSocket>()

    override fun onStart() {
        CaptureLogBus.log("[SignalingServer] Da khoi dong server tai cong $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d("SignalingServer", "Client mo ket noi: ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val clientId = clientMap.remove(conn) ?: return

        // ✅ SUA (xem giai thich day du o khai bao activeConnectionForClientId
        // phia tren): remove(key, value) la thao tac ATOMIC, chi xoa VA tra ve
        // true neu gia tri hien tai dung bang conn dang dong nay. Neu clientId
        // do da duoc 1 socket MOI HON "chiem lai" (JOIN sau), gia tri trong map
        // se KHAC voi conn nay -> remove tra ve false -> ta biet day chi la
        // socket CU dang tu dong (do mat mang / timeout), KHONG phai phien
        // dang hoat dong that su -> bo qua, KHONG bao onMicDisconnected.
        val isStillActiveConnection = activeConnectionForClientId.remove(clientId, conn)
        if (isStillActiveConnection) {
            CaptureLogBus.log("[SignalingServer] Mic '$clientId' da ngat ket noi")
            listener.onMicDisconnected(clientId)
        } else {
            CaptureLogBus.log(
                "[SignalingServer] Socket CU cua Mic '$clientId' tu dong (da bi 1 lan " +
                    "ket noi lai moi hon thay the truoc do) - bo qua, khong bao ngat ket noi."
            )
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

                        // ✅ MOI (xem giai thich day du o khai bao
                        // activeConnectionForClientId phia tren): danh dau
                        // conn NAY la socket "chu" MOI cua clientId - neu
                        // truoc do co 1 socket KHAC (cu, vi du tu lan ket noi
                        // truoc chua kip dong sach vi mat mang) dang giu cung
                        // clientId nay, chu dong dong luon socket cu do (no da
                        // "het tac dung" - Mic that su dang o socket moi nay).
                        val previousConn = activeConnectionForClientId.put(clientId, conn)
                        if (previousConn != null && previousConn !== conn && previousConn.isOpen) {
                            CaptureLogBus.log(
                                "[SignalingServer] Mic '$clientId' ket noi lai bang socket MOI - " +
                                    "dong socket CU (co the dang treo do mat mang truoc do)."
                            )
                            previousConn.close()
                        }

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
        // ✅ FIX: nguoi test chi co dien thoai that, khong co ADB/Logcat -
        // loi bind port (vd dang co server cu chua dong, bind port 8765
        // lan 2 se That bai) truoc day chi roi vao day va bi "nuot am tham".
        // Ghi ra CaptureLogBus de thay ngay trong app.
        CaptureLogBus.log("[SignalingServer] ❌ Loi server (co the do port $port dang bi chiem boi phong cu chua dong): ${ex.message}")
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
        } catch (_: Exception) {
        } finally {
            // ✅ MOI: don sach map theo doi "socket chu" cung luc voi
            // clientMap - tranh giu tham chieu WebSocket cu qua nhieu lan
            // mo/dong phong lien tiep (rat co the xay ra khi test lap di lap
            // lai bug ket noi lai).
            activeConnectionForClientId.clear()
        }
    }
}