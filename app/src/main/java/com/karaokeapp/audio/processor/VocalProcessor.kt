package com.karaokeapp.audio.processor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phase 4 - EQ 3-Band (Bass/Mid/Treble) + HPF 90Hz chuan RBJ Audio EQ Cookbook.
 *
 * ✅ DA SUA:
 * Sửa cong thuc dbToLinear ve 10^(db/20) thay vi 10^(db/40). Truoc day do
 * db/40 roi lai sqrt() them lan nua lam do nhay EQ bi yeu di 4 lan so voi thuc te.
 */
class VocalProcessor(
    sampleRate: Int = 44100,
    bassGainDb: Float = 0f,
    midGainDb: Float = 0f,
    trebleGainDb: Float = 0f
) {

    private class Biquad {
        var b0 = 1f; var b1 = 0f; var b2 = 0f
        var a1 = 0f; var a2 = 0f

        private var x1 = 0f; private var x2 = 0f
        private var y1 = 0f; private var y2 = 0f

        fun process(input: Float): Float {
            val y = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = input
            y2 = y1; y1 = y
            return y
        }

        fun reset() {
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
        }
    }

    private val hpfFilter = Biquad()
    private val bassFilter = Biquad()
    private val midFilter = Biquad()
    private val trebleFilter = Biquad()

    companion object {
        private const val HPF_FREQ = 90f
        private const val BASS_FREQ = 220f
        private const val MID_FREQ = 3200f
        private const val TREBLE_FREQ = 8000f
        private const val MID_Q = 1.2f

        /**
         * Chuan RBJ: A = sqrt(10^(dB/20)) = 10^(dB/40).
         */
        private fun dbToA(gainDb: Float): Float = Math.pow(10.0, gainDb / 40.0).toFloat()

        private fun lowShelf(target: Biquad, freq: Float, sampleRate: Int, gainDb: Float) {
            val a = dbToA(gainDb)
            val w0 = 2f * PI.toFloat() * freq / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / 2f * sqrt((a + 1f / a) * (1f / 0.9f - 1f) + 2f)

            val twoSqrtAAlpha = 2f * sqrt(a) * alpha

            val b0 = a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha)
            val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
            val b2 = a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha)
            val a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
            val a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
            val a2 = (a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha

            normalizeAndAssign(target, b0, b1, b2, a0, a1, a2)
        }

        private fun highShelf(target: Biquad, freq: Float, sampleRate: Int, gainDb: Float) {
            val a = dbToA(gainDb)
            val w0 = 2f * PI.toFloat() * freq / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / 2f * sqrt((a + 1f / a) * (1f / 0.9f - 1f) + 2f)

            val twoSqrtAAlpha = 2f * sqrt(a) * alpha

            val b0 = a * ((a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha)
            val b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW0)
            val b2 = a * ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha)
            val a0 = (a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha
            val a1 = 2f * ((a - 1f) - (a + 1f) * cosW0)
            val a2 = (a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha

            normalizeAndAssign(target, b0, b1, b2, a0, a1, a2)
        }

        private fun peaking(target: Biquad, freq: Float, sampleRate: Int, q: Float, gainDb: Float) {
            val a = dbToA(gainDb)
            val w0 = 2f * PI.toFloat() * freq / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2f * q)

            val b0 = 1f + alpha * a
            val b1 = -2f * cosW0
            val b2 = 1f - alpha * a
            val a0 = 1f + alpha / a
            val a1 = -2f * cosW0
            val a2 = 1f - alpha / a

            normalizeAndAssign(target, b0, b1, b2, a0, a1, a2)
        }

        private fun highPass(target: Biquad, freq: Float, sampleRate: Int) {
            val w0 = 2f * PI.toFloat() * freq / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2f * 0.707f)

            val b0 = (1f + cosW0) / 2f
            val b1 = -(1f + cosW0)
            val b2 = (1f + cosW0) / 2f
            val a0 = 1f + alpha
            val a1 = -2f * cosW0
            val a2 = 1f - alpha

            normalizeAndAssign(target, b0, b1, b2, a0, a1, a2)
        }

        private fun normalizeAndAssign(
            target: Biquad,
            b0: Float, b1: Float, b2: Float,
            a0: Float, a1: Float, a2: Float
        ) {
            target.b0 = b0 / a0
            target.b1 = b1 / a0
            target.b2 = b2 / a0
            target.a1 = a1 / a0
            target.a2 = a2 / a0
        }
    }

    @Volatile var bassGainDb: Float = clampGain(bassGainDb)
        private set
    @Volatile var midGainDb: Float = clampGain(midGainDb)
        private set
    @Volatile var trebleGainDb: Float = clampGain(trebleGainDb)
        private set

    private val currentSampleRate = sampleRate

    init {
        recomputeAllFilters()
    }

    private fun clampGain(db: Float): Float = max(-12f, min(12f, db))

    private fun recomputeAllFilters() {
        highPass(hpfFilter, HPF_FREQ, currentSampleRate)
        lowShelf(bassFilter, BASS_FREQ, currentSampleRate, bassGainDb)
        peaking(midFilter, MID_FREQ, currentSampleRate, MID_Q, midGainDb)
        highShelf(trebleFilter, TREBLE_FREQ, currentSampleRate, trebleGainDb)
    }

    fun setGains(bassDb: Float = bassGainDb, midDb: Float = midGainDb, trebleDb: Float = trebleGainDb) {
        bassGainDb = clampGain(bassDb)
        midGainDb = clampGain(midDb)
        trebleGainDb = clampGain(trebleDb)
        recomputeAllFilters()
    }

    fun process(buffer: ShortArray, size: Int) {
        for (i in 0 until size) {
            var sample = buffer[i].toFloat()
            sample = hpfFilter.process(sample)
            sample = bassFilter.process(sample)
            sample = midFilter.process(sample)
            sample = trebleFilter.process(sample)

            buffer[i] = sample.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }

    fun reset() {
        hpfFilter.reset()
        bassFilter.reset()
        midFilter.reset()
        trebleFilter.reset()
    }
}