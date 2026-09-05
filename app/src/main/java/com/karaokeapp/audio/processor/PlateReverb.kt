package com.karaokeapp.audio.processor

import kotlin.math.max
import kotlin.math.min

/**
 * Phase 4, buoc 6/6 (dot cuoi cung theo ke hoach da thong nhat - flag rieng,
 * TAT MAC DINH, nghe ky truoc khi bat) - Reverb thuat toan kieu Freeverb
 * (Schroeder/Moorer): 8 bo loc luoc (Comb Filter) song song + 4 bo loc
 * All-Pass noi tiep, tao duoi ngan muot cho vocal.
 *
 * ✅ Sua ten goi tu "Plate Reverb" (ten trong ban patch goc) thanh dung ten
 * ky thuat "Freeverb" trong KDoc nay - kien truc 8-comb + 4-allpass la
 * Freeverb/Schroeder-Moorer kinh dien, khong phai mo phong "plate" (mot
 * kieu reverb vat ly khac, thuat toan khac). Ten class/file giu nguyen
 * PlateReverb de khop voi ten da dung trong VocalChannel.kt, nhung ghi chu
 * lai o day de tranh nham lan khi doc code sau nay.
 *
 * ✅ Mix wet/dry ghi de truc tiep len buffer (khong tach mang wet/dry
 * rieng) - dung tinh than in-place cua toan bo codebase (Compressor,
 * Limiter, VocalProcessor deu xu ly in-place de tranh cap phat mang moi/
 * boxing trong vong lap real-time), khong phai loi kien truc.
 *
 * ⚠️ CHUA duoc nghe thu doc lap - theo dung ke hoach da thong nhat, module
 * nay phai duoc BAT qua 1 flag rieng (VocalChannel.reverbEnabled, mac dinh
 * FALSE) va nghe ky truoc khi ghep chung voi cac thay doi khac (dac biet
 * la EchoReverb chay TRUOC no trong chuoi - 2 bo tao duoi vang noi tiep de
 * gay "vang chong vang" neu ca 2 cung bat, nen test PlateReverb RIENG
 * (tam tat EchoReverb) truoc khi quyet dinh dung ca 2).
 *
 * ⚠️ CANH BAO MOI (xac nhan qua test thuc te - nghe "hu" khi bat cung luc
 * voi AGC/Compressor da tang gain): module nay CHAY TREN TIN HIEU MIC, nen
 * neu loa va mic o gan nhau (vong lap am hoc mic-loa co san, du chua ro
 * ret truoc do), MOI lan am thanh tu loa lot lai vao mic se di qua LAI
 * TOAN BO chuoi VocalChannel (AutoGain -> ... -> Reverb) - he so
 * "wet * X" cang lon, tong gain cong don qua moi vong lap cang cao, cang
 * de dat/vuot dieu kien gay hu (tieu chuan Barkhausen: gain vong lap >= 1).
 * Da HA he so nhan wet tu 2.2 xuong 1.3 va wet/roomSize mac dinh xuong thap
 * hon (xem gia tri ben duoi) de giam bot phan dong gop cua module nay vao
 * tong gain vong lap - nhung day KHONG thay the duoc giai phap vat ly
 * (giam am luong loa / dua mic ra xa loa / dung tai nghe). Neu van con hu
 * sau khi ha he so, TAT module nay lai va uu tien xu ly vat ly truoc.
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

    // Cac gia tri delay (sample, tai 44.1kHz goc) kinh dien cua Freeverb -
    // duoc chon vi la so nguyen to hoac gan nguyen to, tranh cac comb filter
    // trung pha voi nhau (gay cong huong khong tu nhien).
    private val combDelays = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allPassDelays = intArrayOf(556, 441, 341, 225)

    private val combs = combDelays.map { CombFilter((it * sampleRate / 44100)) }
    private val allpasses = allPassDelays.map { AllPassFilter((it * sampleRate / 44100)) }

    /** Kich thuoc "phong" ao - anh huong thoi gian ngan (decay), 0f..1f, gan 1f = ngan rat lau. */
    @Volatile var roomSize: Float = 0.6f

    /** Do "tat" cao tan trong duoi ngan - 0f..1f, cao hon = duoi ngan "toi"/am hon, tu nhien hon o phong lon. */
    @Volatile var damping: Float = 0.28f

    /** Ty le tin hieu da xu ly (wet) tron vao tin hieu goc (dry), 0f..1f. */
    @Volatile var wet: Float = 0.20f

    fun process(buffer: ShortArray, size: Int) {
        for (i in 0 until size) {
            val input = buffer[i].toFloat()
            var outComb = 0f

            for (c in combs) {
                outComb += c.process(input, roomSize, damping)
            }

            var outAllPass = outComb * 0.125f
            for (a in allpasses) {
                outAllPass = a.process(outAllPass)
            }

            // ✅ HA he so nhan wet tu 2.2 xuong 1.3 (xem canh bao ve vong lap
            // am hoc o KDoc dau class) - giam bot gain cong them tu module
            // nay, danh doi lay duoi vang mong hon 1 chut de doi lay it rui
            // ro hu hon khi mic/loa o gan nhau.
            val mixed = (input * (1f - wet * 0.18f)) + (outAllPass * wet * 1.3f)
            val clamped = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), mixed))
            buffer[i] = clamped.toInt().toShort()
        }
    }

    fun reset() {
        combs.forEach {
            it.buffer.fill(0f)
            it.filterStore = 0f
        }
        allpasses.forEach {
            it.buffer.fill(0f)
        }
    }
}