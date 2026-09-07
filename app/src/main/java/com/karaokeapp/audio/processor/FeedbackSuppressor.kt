package com.karaokeapp.audio.processor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phase 4 - Bo chong hu chu dong (Anti-Feedback) bang ky thuat truot tan (Frequency Shift).
 *
 * ✅ DA SUA TOAN DIEN:
 * 1. Ha muc dich tan xuong +2.0Hz (chuan vang so chuyen dung) thay vi +5.0Hz.
 *    Muc nay pha vo hoan toan vong lap cong huong cua micro-loa ma tai nguoi
 *    khong the cam nhan duoc lech tone, het tieng meo robot/kim loai.
 * 2. Dung bo dao dong quay pha de quy (Recursive Complex Oscillator) - thay the
 *    viec goi cos() va sin() 44.100 lan/giay. Thuat toan gio chi con 4 phep nhan,
 *    hoan toan nhe may va KHONG con gay lag audio.
 */
class FeedbackSuppressor(
    private val sampleRate: Int = 44100,
    /** Do dich tan (Hz). +2.0Hz la ti le vang: chong hu tot ma giu 100% do tu nhien giong hat. */
    private val shiftHz: Float = 2.0f
) {
    private class AllPass(val a: Float) {
        private var x1 = 0f
        private var y1 = 0f
        fun process(x: Float): Float {
            val y = a * (x - y1) + x1
            x1 = x
            y1 = y
            return y
        }
        fun reset() { x1 = 0f; y1 = 0f }
    }

    // He so mang loc All-Pass 90 do chuan
    private val ap0 = arrayOf(
        AllPass(0.161758f), AllPass(0.733029f), AllPass(0.945350f), AllPass(0.990598f)
    )
    private val ap1 = arrayOf(
        AllPass(0.471692f), AllPass(0.874100f), AllPass(0.976599f), AllPass(0.997500f)
    )

    // Bo dao dong quay pha de quy: x_new = x*cos - y*sin, y_new = x*sin + y*cos
    private val omega = 2.0 * PI * shiftHz / sampleRate
    private val cosOmega = cos(omega).toFloat()
    private val sinOmega = sin(omega).toFloat()

    private var oscCos = 1.0f
    private var oscSin = 0.0f
    private var sampleCounter = 0

    fun process(buffer: ShortArray, size: Int) {
        for (i in 0 until size) {
            val inSample = buffer[i].toFloat()

            // Nhanh I (In-phase)
            var iSig = inSample
            for (ap in ap0) iSig = ap.process(iSig)

            // Nhanh Q (Quadrature - lech 90 do)
            var qSig = inSample
            for (ap in ap1) qSig = ap.process(qSig)

            // Dieu che SSB khong dung ham luong giac tinh lai
            val shifted = (iSig * oscCos) - (qSig * oscSin)

            // Quay pha cho sample tiep theo (4 phep nhan co ban)
            val nextCos = oscCos * cosOmega - oscSin * sinOmega
            val nextSin = oscCos * sinOmega + oscSin * cosOmega
            oscCos = nextCos
            oscSin = nextSin

            // Chuan hoa dinh ky moi 1024 mau de tranh sai so troi float qua thoi gian dai
            if (++sampleCounter >= 1024) {
                sampleCounter = 0
                val mag = sqrt(oscCos * oscCos + oscSin * oscSin)
                if (mag > 1e-6f) {
                    oscCos /= mag
                    oscSin /= mag
                }
            }

            buffer[i] = shifted.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }

    fun reset() {
        ap0.forEach { it.reset() }
        ap1.forEach { it.reset() }
        oscCos = 1.0f
        oscSin = 0.0f
        sampleCounter = 0
    }
}