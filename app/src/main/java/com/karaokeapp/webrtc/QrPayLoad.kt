package com.karaokeapp.webrtc

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.util.UUID

/**
 * Phase 5 - Dinh dang du lieu ma QR ghep phong:
 * karaoke://join?room=ABC123&host=192.168.1.10&port=8765&token=xxxx
 *
 * ✅ Nguon goc: dua tren code mau nguoi dung cung cap (huong_dan.txt) - giu
 * nguyen logic, chi Viet hoa toan bo comment cho dong bo voi phan con lai
 * cua repo (khong dau, giong quy uoc CaptureLogBus/MusicInput...).
 */
data class QrJoinData(
    val roomId: String,
    val host: String,
    val port: Int,
    val token: String
) {
    fun toUriString(): String {
        return "karaoke://join?room=$roomId&host=$host&port=$port&token=$token"
    }

    companion object {
        fun parse(rawUri: String): QrJoinData? {
            return try {
                val uri = URI(rawUri)
                if (uri.scheme != "karaoke" || uri.host != "join") return null

                val query = uri.query ?: return null
                val params = query.split("&").associate {
                    val parts = it.split("=")
                    if (parts.size == 2) parts[0] to parts[1] else "" to ""
                }

                val roomId = params["room"] ?: return null
                val host = params["host"] ?: return null
                val port = params["port"]?.toIntOrNull() ?: 8765
                val token = params["token"] ?: return null

                QrJoinData(roomId, host, port, token)
            } catch (e: Exception) {
                null
            }
        }

        fun generate(context: Context, port: Int = 8765): QrJoinData? {
            val ip = getLocalIpAddress(context) ?: return null
            val shortRoomId = UUID.randomUUID().toString().substring(0, 6).uppercase()
            val shortToken = UUID.randomUUID().toString().substring(0, 8)
            return QrJoinData(shortRoomId, ip, port, shortToken)
        }

        /**
         * Lay dia chi IPv4 noi bo dang ket noi Wi-Fi. Fallback quet
         * NetworkInterface neu WifiManager tra ve 0 (vi du dang dung Hotspot
         * thay vi ket noi Wi-Fi thuong).
         */
        @Suppress("DEPRECATION")
        fun getLocalIpAddress(context: Context): String? {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    return Formatter.formatIpAddress(ipInt)
                }

                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (intf in interfaces) {
                    val addrs = intf.inetAddresses
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            } catch (_: Exception) {}
            return null
        }
    }
}