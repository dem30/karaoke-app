package com.karaokeapp.audio.processor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Phase 4, buoc 2/5 - Dynamic Range Compressor cho giong hat karaoke.
 *
 * Nhiem vu: khi hat nho/thi tham, giu nguyen (hoac nang nhe qua makeupGainDb);
 * khi len not cao/hat lon, tu dong nen dai dong xuong theo ty le (ratio), giup
 * giong hat day dan, deu dan, khong bi giat minh to/nho lien tuc giua cac cau.
 *
 * ✅ Nguon goc: dua tren code mau nguoi dung cung cap (tham khao file
 * huong_dan.txt) - da kiem tra logic dung (cong thuc GR = (Threshold - Input)
 * * (1 - 1/Ratio) la cong thuc nen chuan), giu nguyen thuat toan, chi doi lai
 * VI TRI trong chuoi xu ly khi wire vao PlaybackCaptureService.kt - xem
 * giai thich o do, KHONG doi gi trong file nay.
 *
 * Thiet ke real-time: xu ly in-place tren ShortArray, khong cap phat mang moi
 * trong process() - giong tinh than Limiter.kt/VocalProcessor.kt.
 */
class Compressor(
    sampleRate: Int = 44100,
    /**
     * Nguong nen (dB, tinh theo dBFS - 0dB la Short.MAX_VALUE). Tin hieu VUOT
     * nguong nay moi bi nen. -18dB mac dinh - vua voi dynamic range thuc te
     * cua mic dien thoai khi hat gan/xa mic khac nhau.
     */
    private val thresholdDb: Float = -18f,
    /**
     * Ty le nen. VD 3.0 nghia la tin hieu vuot nguong 3dB thi dau ra chi tang
     * 1dB. 3:1 - 4:1 la muc pho bien, an toan cho vocal (khong nen qua tay
     * lam mat tu nhien giong hat).
     */
    private val ratio: Float = 3.0f,
    /**
     * Thoi gian dap ung (attack, ms) - toc do bop gain xuong khi gap peak
     * lon. 12ms du nhanh de bat peak nhung khong cat cut am bat cua phu am
     * (transient), tranh nghe "nghen".
     */
    attackMs: Float = 12f,
    /**
     * Thoi gian nha (release, ms) - toc do tra gain ve binh thuong sau khi
     * peak qua. 100ms tranh hien tuong "pumping" (gain nhap nhay theo nhip
     * nhac nghe rat kho chiu).
     */
    releaseMs: Float = 100f,
    /**
     * Bu gain sau nen (dB) - vi tin hieu bi nen bot o doan to, bu lai 1
     * luong de tong the giong hat khong bi nho hon truoc khi nen.
     */
    private val makeupGainDb: Float = 2.0f
) {

    // He so lam min attack/release (one-pole, giong tinh than releaseCoeff
    // cua Limiter.kt nhung ap dung cho CA 2 chieu attack va release rieng).
    private val attackCoeff = 1f - exp(-1f / (sampleRate * (attackMs / 1000f)))
    private val releaseCoeff = 1f - exp(-1f / (sampleRate * (releaseMs / 1000f)))

    private val makeupGainLinear = 10f.pow(makeupGainDb / 20f)

    // State: muc giam gain hien tai (dB, <= 0f). 0f = khong nen gi.
    private var currentGainReductionDb = 0f

    companion object {
        // Tranh log10(0) = -Infinity khi tin hieu im lang tuyet doi.
        private const val MIN_INPUT_FOR_LOG = 1e-4f
        private const val MAX_PCM_FLOAT = 32767f
    }

    fun process(buffer: ShortArray, size: Int) {
        for (i in 0 until size) {
            val raw = buffer[i].toInt()
            val absNorm = abs(raw) / MAX_PCM_FLOAT

            val inputDb = if (absNorm > MIN_INPUT_FOR_LOG) {
                20f * log10(absNorm)
            } else {
                -80f
            }

            val targetGainReductionDb = if (inputDb > thresholdDb) {
                (thresholdDb - inputDb) * (1f - 1f / ratio)
            } else {
                0f
            }

            currentGainReductionDb += if (targetGainReductionDb < currentGainReductionDb) {
                // Can nen MANH hon hien tai -> dung toc do attack (nhanh).
                attackCoeff * (targetGainReductionDb - currentGainReductionDb)
            } else {
                // Peak da qua, tha dan ve 0 -> dung toc do release (cham hon).
                releaseCoeff * (targetGainReductionDb - currentGainReductionDb)
            }

            val compressionLinear = 10f.pow(currentGainReductionDb / 20f)
            val totalLinearGain = compressionLinear * makeupGainLinear

            var output = raw * totalLinearGain
            output = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), output))
            buffer[i] = output.toInt().toShort()
        }
    }

    /** Reset state - goi khi bat dau/dung 1 session moi, tranh tan du gain-reduction tu session truoc. */
    fun reset() {
        currentGainReductionDb = 0f
    }
}