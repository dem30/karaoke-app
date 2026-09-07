package com.karaokeapp.audio.processor

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phase 6 - "San bang am luong tu dong" (Auto Level) cho 1 nguon vocal.
 *
 * ✅ DA SUA:
 * 1. Tinh attackCoeff/releaseCoeff theo DUNG chu ky buffer (~40ms) thay vi
 *    theo sampleRate. Truoc day bi cham 1764 lan khien gain bi "dong bang".
 * 2. Ha maxGain tu 9.0x (+19dB) xuong 2.8x (~+9dB) an toan, tranh hard-clip.
 * 3. Noi suy gain tuyen tinh (linear gain ramp) qua tung mau trong buffer,
 *    loai bo hoan toan tieng click/pop tai ranh gioi giua 2 buffer.
 */
class AutoGainControl(
    private val sampleRate: Int = 44100,
    /** Muc bien do RMS muon dat toi (thang Short, 0..32767). ~6000 la muc hat vua phai, ro loi. */
    private val targetRms: Float = 6000f,
    /** Gain toi thieu cho phep. */
    private val minGain: Float = 0.5f,
    /** Gain toi da an toan - chan khuech dai qua tay gay vỡ tieng. */
    private val maxGain: Float = 2.8f,
    /** Thoi gian dap ung khi can giam gain (ms). */
    private val attackMs: Float = 300f,
    /** Thoi gian dap ung khi can tang gain (ms). */
    private val releaseMs: Float = 1200f,
    /** Nguong RMS coi nhu im lang - khong tang gain tren noise nen. */
    private val silenceRmsFloor: Float = 60f
) {
    @Volatile
    private var currentGain = 1f

    fun currentGainValue(): Float = currentGain

    fun process(buffer: ShortArray, size: Int) {
        if (size <= 0) return

        var sumSq = 0.0
        for (i in 0 until size) {
            val v = buffer[i].toDouble()
            sumSq += v * v
        }
        val rms = sqrt(sumSq / size).toFloat()

        val startGain = currentGain
        var targetGain = currentGain

        if (rms > silenceRmsFloor) {
            val desiredGain = (targetRms / rms).coerceIn(minGain, maxGain)
            // Tinh he so lam min theo thoi gian thuc cua buffer nay (size / sampleRate)
            val dt = size.toFloat() / sampleRate
            val coeff = if (desiredGain < currentGain) {
                1f - exp(-dt / (attackMs / 1000f))
            } else {
                1f - exp(-dt / (releaseMs / 1000f))
            }
            targetGain = currentGain + coeff * (desiredGain - currentGain)
            targetGain = targetGain.coerceIn(minGain, maxGain)
        }
        currentGain = targetGain

        // Noi suy gain muot ma tu startGain den targetGain de tranh tieng click
        val gainStep = (targetGain - startGain) / size
        var runningGain = startGain

        for (i in 0 until size) {
            runningGain += gainStep
            val out = buffer[i] * runningGain
            buffer[i] = out.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }

    fun reset() {
        currentGain = 1f
    }
}