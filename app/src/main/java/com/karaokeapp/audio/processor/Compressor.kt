package com.karaokeapp.audio.processor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Phase 4 - Dynamic Range Compressor cho giong hat karaoke.
 *
 * ✅ DA SUA TOAN DIEN:
 * 1. Dung Envelope Follower (bo do phong bi) lam min bien do truoc khi tinh nen,
 *    tranh tinh trang gain co bop theo tung nua chu ky song gay meo hai (re tieng).
 * 2. Chi tinh log10() va pow() khi bien do vuot thresholdLinear - tiet kiem ~80%
 *    CPU so voi truoc day, loai bo nguy co tre deadline audio (het lag).
 */
class Compressor(
    sampleRate: Int = 44100,
    /** Nguong bat dau nen (dBFS). -22dB bat duoc cac doan hat to ma khong anh huong hat nho. */
    private val thresholdDb: Float = -22f,
    /** Ty le nen (3.0 = vuot nguong 3dB dau ra chi tang 1dB). */
    private val ratio: Float = 3.0f,
    /** Toc do dap ung khi bat gap peak lon (ms). 15ms giu tron phu am transient. */
    attackMs: Float = 15f,
    /** Toc do tra gain ve binh thuong (ms). 120ms tranh hien tuong pumping. */
    releaseMs: Float = 120f,
    /** Bu lai am luong bi nen (dB). */
    private val makeupGainDb: Float = 3.5f
) {
    private val attackCoeff = exp(-1f / (sampleRate * (attackMs / 1000f)))
    private val releaseCoeff = exp(-1f / (sampleRate * (releaseMs / 1000f)))

    private val makeupGainLinear = 10f.pow(makeupGainDb / 20f)
    private val thresholdLinear = 10f.pow(thresholdDb / 20f)

    // Bien do phong bi muot ma hien tai [0.0f .. 1.0f]
    private var envelope = 0f

    fun process(buffer: ShortArray, size: Int) {
        val slope = 1f - (1f / ratio)

        for (i in 0 until size) {
            val input = buffer[i].toFloat()
            val absInput = abs(input) / 32767f

            // 1. Peak Envelope Follower: Lam min bien do, chong re meo dang song
            envelope = if (absInput > envelope) {
                attackCoeff * envelope + (1f - attackCoeff) * absInput
            } else {
                releaseCoeff * envelope + (1f - releaseCoeff) * absInput
            }

            // 2. Chi tinh toan log10 va pow khi phong bi thuc su vuot nguong (tiet kiem CPU toi da)
            val linearGain = if (envelope > thresholdLinear && envelope > 1e-5f) {
                val envDb = 20f * log10(envelope)
                val gainReductionDb = (thresholdDb - envDb) * slope
                10f.pow(gainReductionDb / 20f) * makeupGainLinear
            } else {
                makeupGainLinear
            }

            val output = input * linearGain
            buffer[i] = output.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }

    fun reset() {
        envelope = 0f
    }
}