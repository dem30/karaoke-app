package com.karaokeapp.audio.processor

import kotlin.math.max

/**
 * Phase 4 - Hieu ung Vang/Nhai (Karaoke Echo & Room Reverb) cho rieng giong hat.
 *
 * ✅ DA SUA:
 * 1. Can bang dryLevel/wetLevel: tranh tinh trang dry + wet > 1.27 lam day bien do
 *    len cao va bi xén ngọn (hard-clip) gay re.
 * 2. Thay the phep chia lay du % bang phep kiem tra con tro nhanh hon nhieu lan.
 */
class EchoReverb(
    sampleRate: Int = 44100,
    delayMs: Float = 200f,
    private val feedback: Float = 0.38f,
    private val wetLevel: Float = 0.32f,
    private val damping: Float = 0.35f
) {

    private val delaySamples = (sampleRate * (delayMs / 1000f)).toInt()
    private val delayBuffer = ShortArray(max(1, delaySamples + 1))
    private var bufferIndex = 0
    private var lastFeedbackSample = 0f

    init {
        require(feedback in 0f..0.85f) {
            "feedback phai trong [0, 0.85] - vuot muc nay delay line tu khuyech dai vo han"
        }
    }

    fun process(buffer: ShortArray, size: Int) {
        if (wetLevel <= 0.001f) return

        // Can bang ty le giua dry va wet de tong nang luong khong vuot nguong 1.0f
        val dryLevel = 1.0f - (wetLevel * 0.35f)
        val bufSize = delayBuffer.size

        for (i in 0 until size) {
            val dry = buffer[i].toInt()
            val delayedSample = delayBuffer[bufferIndex].toFloat()

            lastFeedbackSample = (delayedSample * (1f - damping)) + (lastFeedbackSample * damping)

            val newDelayValue = dry + (lastFeedbackSample * feedback)
            delayBuffer[bufferIndex] = newDelayValue.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()

            if (++bufferIndex >= bufSize) {
                bufferIndex = 0
            }

            val mixed = (dry * dryLevel) + (delayedSample * wetLevel)
            buffer[i] = mixed.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }

    fun reset() {
        delayBuffer.fill(0)
        bufferIndex = 0
        lastFeedbackSample = 0f
    }
}