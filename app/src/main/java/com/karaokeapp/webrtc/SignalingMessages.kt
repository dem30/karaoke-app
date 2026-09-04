package com.karaokeapp.webrtc

import org.json.JSONObject

/**
 * Phase 5 - Cac loai message trao doi giua May A (Mixer) va May B (Mic) qua
 * WebSocket LAN:
 * - join: Mic xin tham gia
 * - join_ok / join_deny: Mixer phan hoi
 * - offer / answer: Trao doi SDP WebRTC
 * - ice: Trao doi ICE Candidate
 * - leave / room_closed: Roi phong hoac dong phong
 *
 * Dung truc tiep org.json.JSONObject (co san trong Android SDK, khong can
 * them thu vien JSON ngoai).
 */
object SignalingMessages {
    const val TYPE_JOIN = "join"
    const val TYPE_JOIN_OK = "join_ok"
    const val TYPE_JOIN_DENY = "join_deny"
    const val TYPE_OFFER = "offer"
    const val TYPE_ANSWER = "answer"
    const val TYPE_ICE = "ice"
    const val TYPE_LEAVE = "leave"
    const val TYPE_ROOM_CLOSED = "room_closed"

    fun createJoin(room: String, token: String, clientId: String): String {
        return JSONObject().apply {
            put("type", TYPE_JOIN)
            put("room", room)
            put("token", token)
            put("clientId", clientId)
        }.toString()
    }

    fun createJoinOk(room: String, clientId: String): String {
        return JSONObject().apply {
            put("type", TYPE_JOIN_OK)
            put("room", room)
            put("clientId", clientId)
        }.toString()
    }

    fun createJoinDeny(room: String, reason: String): String {
        return JSONObject().apply {
            put("type", TYPE_JOIN_DENY)
            put("room", room)
            put("reason", reason)
        }.toString()
    }

    fun createOffer(room: String, clientId: String, sdp: String): String {
        return JSONObject().apply {
            put("type", TYPE_OFFER)
            put("room", room)
            put("clientId", clientId)
            put("sdp", sdp)
        }.toString()
    }

    fun createAnswer(room: String, clientId: String, sdp: String): String {
        return JSONObject().apply {
            put("type", TYPE_ANSWER)
            put("room", room)
            put("clientId", clientId)
            put("sdp", sdp)
        }.toString()
    }

    fun createIce(room: String, clientId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String): String {
        return JSONObject().apply {
            put("type", TYPE_ICE)
            put("room", room)
            put("clientId", clientId)
            put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
            put("candidate", candidate)
        }.toString()
    }
}