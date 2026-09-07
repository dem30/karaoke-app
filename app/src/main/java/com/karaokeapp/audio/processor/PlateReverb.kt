package com.karaokeapp.audio.processor

/**
 * Phase 4 - Reverb thuat toan kieu Freeverb (Schroeder/Moorer):
 * 8 bo loc luoc (Comb Filter) song song + 4 bo loc All-Pass noi tiep.
 *
 * ✅ DA SUA:
 * 1. GO BO hoan toan ham tanh() o cuoi chuoi - nguyen nhan gay tieng re fuzzed
 *    va ngon hang chuc nghin phep tinh exp() moi giay gay lag audio.
 * 2. GO BO he so nhan chia COMB_INPUT_GAIN vo nghia.
 * 3. Can bang he so wet/dry theo ty le chuan, giu am luong on dinh khong clip.
 */
class PlateReverb(private val sampleRate: Int = 44100) {

    private class CombFilter(size: Int) {
        val buffer = FloatArray(size)
        var idx = 0
        var filterStore = 0f
        fun process(input: Float, feedback: Float, damp: Float): Float {
            val output = buffer[idx]
            filterStore = (output * (1f - damp)) + (filterStore * damp)
            buffer[idx] = input + (filterStore * feedback)
            if (++idx >= buffer.size) idx = 0
            return output
        }
    }

    private class AllPassFilter(size: Int) {
        val buffer = FloatArray(size)
        var idx = 0
        fun process(input: Float): Float {
            val bufOut = buffer[idx]
            val output = -input + bufOut
            buffer[idx] = input + (bufOut * 0.5f)
            if (++idx >= buffer.size) idx = 0
            return output
        }
    }

    private val combDelays = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allPassDelays = intArrayOf(556, 441, 341, 225)

    private val combs = combDelays.map { CombFilter((it * sampleRate / 44100)) }
    private val allpasses = allPassDelays.map { AllPassFilter((it * sampleRate / 44100)) }

    @Volatile var roomSize: Float = 0.5f
    @Volatile var damping: Float = 0.35f
    @Volatile var wet: Float = 0.22f

    fun process(buffer: ShortArray, size: Int) {
        if (wet <= 0.001f) return

        val dryGain = 1.0f - (wet * 0.25f)
        // outAllPass co bien do trung binh ~0.3x input -> he so 1.4f cho duoi vang muot ma ro chu
        val wetGain = wet * 1.4f

        for (i in 0 until size) {
            val input = buffer[i].toFloat()

            var outComb = 0f
            for (c in combs) {
                outComb += c.process(input, roomSize, damping)
            }

            // Chia trung binh 8 comb
            var outAllPass = outComb * 0.125f
            for (a in allpasses) {
                outAllPass = a.process(outAllPass)
            }

            val mixed = (input * dryGain) + (outAllPass * wetGain)
            buffer[i] = mixed.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }

    fun reset() {
        combs.forEach {
            it.buffer.fill(0f)
            it.filterStore = 0f
            it.idx = 0
        }
        allpasses.forEach {
            it.buffer.fill(0f)
            it.idx = 0
        }
    }
}