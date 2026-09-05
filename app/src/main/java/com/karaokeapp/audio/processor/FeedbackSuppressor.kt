package com.karaokeapp.audio.processor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Phase 4, buoc 6/6 (dot cuoi cung theo ke hoach da thong nhat - flag rieng,
 * TAT MAC DINH, nghe ky truoc khi bat) - Bo chong hu chu dong (Anti-
 * Feedback) kieu vang so chuyen dung: dich toan bo pho am thanh mic len
 * +5Hz bang dieu che don bien (SSB - Single Sideband Modulation) qua mang
 * loc pha All-Pass xap xi Hilbert Transform. Y tuong: vong lap phan hoi am
 * hoc (loa -> mic -> loa...) bi "truot tan" lien tuc sau moi chu ky, khong
 * the cong huong du de gay ru rit, ma tai nguoi khong nhan ra dich 5Hz nay.
 *
 * ⚠️ CANH BAO QUAN TRONG - CHUA KIEM CHUNG duoc do chinh xac cua he so
 * allpass: ky thuat dich tan qua 4 tang allpass CHI xap xi dung lech pha
 * 90 do trong MOT DAI TAN GIOI HAN (thuong khong sat DC hay Nyquist) - he
 * so cang khop voi bo Hilbert-transformer bac 4 chuan thi dai tan xap xi
 * dung cang rong. He so trong file nay chua duoc doi chieu doc lap voi
 * nguon goc/tai lieu tham khao cu the nao - neu lech, ket qua KHONG PHAI la
 * "khong nghe thay dich tan" ma la giong hat bi MEO/PHA LOANG, nghe nhu
 * hieu ung "flanger nhe" thay vi trong hon. Vi vay module nay:
 * - MAC DINH TAT (VocalChannel.feedbackSuppressorEnabled = false) - phai
 *   nguoi dung/dev CHU DONG bat qua UI/code de test.
 * - Can nghe ky RIENG LE (tam tat cac module DSP khac) truoc khi quyet
 *   dinh bat mac dinh cho nguoi dung that.
 * - Neu nghe thay giong "mong"/"loang"/"flange" khi bat, nen TAT lai va coi
 *   day la bang chung he so chua chinh xac, can doi chieu lai cong thuc
 *   Hilbert-transformer bac 4 chuan tu nguon dang tin cay hon truoc khi
 *   dung tiep.
 */
class FeedbackSuppressor(
    private val sampleRate: Int = 44100,
    private val shiftHz: Float = 5.0f
) {
    // 4 tang All-Pass Filter cho nhanh 0 do
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

    // He so mang loc dich pha 90 do (Hilbert xap xi) cho 44.1kHz - xem canh
    // bao o KDoc dau class ve do tin cay cua bo he so nay.
    private val ap0 = arrayOf(
        AllPass(0.161758f), AllPass(0.733029f), AllPass(0.945350f), AllPass(0.990598f)
    )
    private val ap1 = arrayOf(
        AllPass(0.471692f), AllPass(0.874100f), AllPass(0.976599f), AllPass(0.997500f)
    )

    private var phase = 0.0
    private val phaseInc = 2.0 * PI * shiftHz / sampleRate

    fun process(buffer: ShortArray, size: Int) {
        for (i in 0 until size) {
            val inSample = buffer[i].toFloat()

            // Nhanh I (In-phase)
            var iSig = inSample
            for (ap in ap0) iSig = ap.process(iSig)

            // Nhanh Q (Quadrature - lech 90 do)
            var qSig = inSample
            for (ap in ap1) qSig = ap.process(qSig)

            // Dieu che SSB: x_shifted = I * cos(wt) - Q * sin(wt)
            val cosVal = cos(phase).toFloat()
            val sinVal = sin(phase).toFloat()

            phase += phaseInc
            if (phase >= 2.0 * PI) phase -= 2.0 * PI

            val shifted = (iSig * cosVal) - (qSig * sinVal)
            val clamped = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), shifted))
            buffer[i] = clamped.toInt().toShort()
        }
    }

    fun reset() {
        ap0.forEach { it.reset() }
        ap1.forEach { it.reset() }
        phase = 0.0
    }
}