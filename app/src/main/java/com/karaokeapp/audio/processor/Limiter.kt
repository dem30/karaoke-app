package com.karaokeapp.audio.processor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 4 - Limiter + Noise Gate, dua len lam SOM hon thu tu goc trong
 * PLAN.md (PLAN xep Limiter cuoi chuoi EQ/Compressor/Reverb) vi ly do THUC
 * TE: khi test qua loa ngoai/Bluetooth (khong dung tai nghe), mic bat lai
 * chinh tieng loa phat ra -> cong vao mixer -> loa phat to hon -> mic bat to
 * hon... = vong lap phan hoi am hoc (feedback loop, "hu/rit"). Day la GIOI
 * HAN VAT LY (am thanh truyen qua khong khi), khong phai loi logic code -
 * xem giai thich chi tiet da co san trong LowLatencyMixer.kt (VOCAL_GAIN).
 *
 * Limiter KHONG loai bo duoc nguyen nhan goc (chi AEC that - adaptive
 * filter, ngoai pham vi Phase 4 - moi lam duoc), nhung CHAN dung vong lap
 * tang theo cap so nhan bang cach ep cung bien do o 1 nguong co dinh: du mic
 * co bat lai tieng loa va tin hieu co xu huong tang dan qua tung chu ky,
 * limiter khong cho no vuot nguong - bien "hu tang vo han" thanh "bi chan o
 * 1 muc on dinh, khong tang tiep".
 *
 * Noise Gate (tuy chon, bat qua constructor) cat han tin hieu ve 0 khi bien
 * do duoi 1 nguong rat thap trong 1 khoang thoi gian - muc dich: khi nguoi
 * dung KHONG dang hat/noi, mic khong nen bom bat ky tin hieu nen/vong vao
 * mixer, giam co hoi vong lap tu nuoi no ngay ca khi chua co giong that.
 *
 * ✅ Dung chung 1 class cho 2 vi tri (theo yeu cau "cach hieu qua nhat"):
 * 1. Ap cho VOCAL RIENG, TRUOC khi vao mixer (chan goc re vong lap phan
 *    hoi - day la vi tri QUAN TRONG NHAT cho van de hu hien tai).
 * 2. Ap cho MIX TONG, SAU KHI cong nhac+vocal (dung vi tri PLAN.md goc ghi
 *    "Limiter cuoi chuoi" - chi de chan clipping tong the, khong lien quan
 *    truc tiep toi hu).
 * Hai instance RIENG BIET (state noi bo khac nhau), KHONG dung chung 1
 * object cho 2 vi tri.
 */
class Limiter(
    sampleRate: Int = 44100,
    /**
     * Nguong limiter, tinh theo TY LE cua Short.MAX_VALUE (0f..1f). Vi du
     * 0.8f nghia la khong cho bien do vuot 80% muc toi da PCM 16-bit
     * (~26214 tren thang 32767). Mac dinh 0.85f - de lai margin an toan
     * truoc khi cham Short.MAX_VALUE that su (tranh clipping cung don).
     */
    private val thresholdRatio: Float = 0.85f,
    /**
     * Thoi gian "tha" (release) tinh bang ms - sau khi bien do vuot nguong
     * va bi limiter ep xuong, day la thoi gian de gain quay ve 1.0 (khong
     * limiter nua) MOT CACH TU TU thay vi dot ngot - tranh tieng "click/pop"
     * khi limiter ngung tac dung dot ngot. 50ms la muc pho bien cho limiter
     * audio (nhanh hon se de nghe "bom/pumping", cham hon se de "de lot"
     * peak tiep theo truoc khi kip tha xong).
     */
    releaseMs: Float = 50f,
    /**
     * Noise gate: nguong bien do (ty le 0f..1f) DUOI muc nay coi la "im
     * lang". null = TAT gate (mac dinh, vi gate co the cat mat tieng hat
     * nho/thi tham that neu chinh sai nguong - chi BAT khi truyen tham so
     * ro rang, sau khi da nghe thu it nhat 1 lan KHONG gate de biet muc nen
     * that su cua moi truong test).
     */
    private val noiseGateThresholdRatio: Float? = null,
    /**
     * So sample lien tiep DUOI nguong gate truoc khi THUC SU cat tieng (vi
     * ~10ms tai 44100Hz = ~441 sample) - tranh gate dong/mo lien tuc theo
     * tung sample rieng le khi tin hieu dao dong quanh nguong (gay tieng
     * "lach tach").
     */
    private val gateHoldSamples: Int = (sampleRate * 0.01f).toInt()
) {

    private val thresholdAbs = thresholdRatio * Short.MAX_VALUE
    private val noiseGateAbs = noiseGateThresholdRatio?.let { it * Short.MAX_VALUE }

    // He so tinh toc do "tha" gain ve 1.0 - cong thuc envelope follower kieu
    // "one-pole" pho bien trong DSP audio (exp decay theo thoi gian release).
    private val releaseCoeff = exp(-1f / (sampleRate * (releaseMs / 1000f)))

    // Gain hien tai dang ap dung (1.0 = khong limiter, nho hon 1.0 = dang bi
    // ep xuong) - day la STATE can giu qua nhieu lan goi process() lien
    // tiep, giong nhu state cua Biquad trong VocalProcessor.
    private var currentGain = 1f

    // Dem so sample lien tiep dang duoi nguong gate - dung cho gateHoldSamples.
    private var belowGateCount = 0

    companion object {
        // Khong cho gain giam thap hon muc nay du peak co manh bao nhieu -
        // tranh truong hop 1 xung dot bien (click/pop nhat thoi khong phai
        // giong that) ep gain ve gan 0, lam mat tieng that ngay sau do khi
        // gain chua kip tha ve 1.0.
        private const val MIN_GAIN = 0.05f
    }

    /**
     * Xu ly 1 buffer PCM mono TAI CHO (in-place), giong quy uoc cua
     * VocalProcessor.process() - khong cap phat mang moi trong vong lap
     * real-time.
     */
    fun process(buffer: ShortArray, size: Int) {
        for (i in 0 until size) {
            val raw = buffer[i].toInt()
            val absRaw = abs(raw).toFloat()

            // --- Noise gate (neu bat) ---
            if (noiseGateAbs != null) {
                if (absRaw < noiseGateAbs) {
                    belowGateCount++
                } else {
                    belowGateCount = 0
                }
                if (belowGateCount >= gateHoldSamples) {
                    buffer[i] = 0
                    continue // Da cat ve 0, khong can chay limiter cho sample nay.
                }
            }

            // --- Limiter (envelope follower don gian, kieu "look-behind") ---
            // Neu bien do hien tai vuot nguong, tinh gain CAN THIET de ep no
            // ve dung nguong (attack tuc thi - khong co attack time rieng,
            // chap nhan duoc vi day la limiter "cung", uu tien chan peak
            // ngay lap tuc hon la mem/tu nhien).
            val desiredGain = if (absRaw > thresholdAbs && absRaw > 0f) {
                thresholdAbs / absRaw
            } else {
                1f
            }

            currentGain = if (desiredGain < currentGain) {
                // Peak moi manh hon gain hien tai dang ap - GIAM NGAY (attack
                // tuc thi, khong lam tron) de chan peak nay khong lot qua.
                desiredGain
            } else {
                // Peak da qua, tha dan gain ve 1.0 theo toc do releaseCoeff -
                // "one-pole" smoothing chuan cho release cua limiter/compressor.
                max(MIN_GAIN, 1f - releaseCoeff * (1f - currentGain))
            }

            var result = (raw * currentGain)
            result = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), result))
            buffer[i] = result.toInt().toShort()
        }
    }

    /** Reset state (gain + gate) - goi khi bat dau 1 session moi, tranh tan du tu session truoc. */
    fun reset() {
        currentGain = 1f
        belowGateCount = 0
    }
}
