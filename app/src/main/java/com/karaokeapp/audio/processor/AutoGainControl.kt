package com.karaokeapp.audio.processor

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phase 6 - "San bang am luong tu dong" (Auto Level) cho 1 nguon vocal.
 *
 * ⚠️ KHONG PHAI bo chong hu/phan hoi am hoc (HowlGuard cu da bi go bo - xem
 * ghi chu trong PlaybackCaptureService.kt). Muc dich cua class nay hoan toan
 * khac: nhieu nguoi hat khac nhau (hoac cung 1 nguoi luc hat gan/xa mic) co
 * bien do mic rat khac nhau - AutoGainControl tu tu keo bien do trung binh
 * ve quanh 1 muc muc tieu (targetRms) de nguoi nghe khong phai tu tay chinh
 * volume lien tuc, TRUOC KHI ap EQ/Compressor/Echo va he so volume thu cong
 * cua nguoi dung (VocalChannel.volume).
 *
 * Khac biet quan trong voi HowlGuard cu:
 * - Attack/release CHAM (hang tram ms - vai giay), khong phai "phat hien
 *   leo thang roi cham mic" - khong bao gio dot ngot cam/ha am luong ve gan
 *   0, chi keo NHE NHANG ve muc muc tieu.
 * - Khong co khai niem "suppress window"/"escalation level" - hanh vi luon
 *   ON DINH, du doan duoc, khong phu thuoc lich su cac lan hat truoc.
 * - Gioi han gain trong [minGain, maxGain] - khong bao gio khuech dai vo
 *   han (an toan voi Limiter phia sau lam luoi chan cuoi).
 * - Co the TAT hoan toan (VocalChannel.autoGainEnabled = false) neu nguoi
 *   dung muon tu chinh volume 100% thu cong.
 */
class AutoGainControl(
    sampleRate: Int = 44100,
    /** Muc bien do RMS muon dat toi (thang Short, 0..32767). ~6000 la muc hat vua phai, ro loi. */
    private val targetRms: Float = 6000f,
    /** Gain toi thieu/toi da cho phep - chan khuech dai/giam qua tay du muc tieu co lech xa. */
    private val minGain: Float = 0.4f,
    /**
     * ✅ CAP NHAT (tang do nhay cho mic dien thoai UNPROCESSED, thuong rat
     * be khi de mac dinh): tang tu 3.0 len 6.0, roi tiep tuc tang len 9.0
     * sau khi test thuc te cho thay 6.0 VAN CHUA DU (RMS mic thuc te thuong
     * ~800-2000, trong khi targetRms=6000 - can gain ~3-7.5x, 6.0 la
     * nguong tren VUA DU nhung khong CHAM den target o nhung doan hat rat
     * nho). TANG DAN tung nac, KHONG nhay thang len 18.0 nhu de xuat ban
     * dau. Ly do khong dung 18.0: +25dB gain tran la rat lon, ket hop voi 1
     * cau hat to dot ngot se de bi cat cung ngay tai buoc AGC nay (truoc ca
     * Compressor/Limiter phia sau) gay meo ro ret, va khuech dai manh ca
     * noise floor cua mic khi nguoi dung hat nho/chua hat toi. Neu 9.0 van
     * chua du nhay, nen tang tiep tung nac (vd 9.0 -> 12.0) va nghe lai,
     * khong nhay thang len muc cao nhat ngay - dac biet CAN THAN neu dang
     * bat ca FeedbackSuppressor/Reverb cung luc, vi AGC cang cao cang cong
     * them gain vao vong lap phan hoi am hoc (loa - mic), de hu hon.
     */
    private val maxGain: Float = 9.0f,
    /** Thoi gian dap ung khi CAN GIAM gain (tin hieu dang to hon muc tieu). */
    attackMs: Float = 400f,
    /** Thoi gian dap ung khi CAN TANG gain (tin hieu dang nho hon muc tieu) - co tinh cham hon attack, tranh "duoi" theo tung cau hat nho lam nghe khong tu nhien. */
    releaseMs: Float = 1500f,
    /**
     * Duoi muc RMS nay coi nhu im lang - khong dieu chinh gain (tranh gain
     * "boi" len rat cao khi khong ai hat).
     * ⚠️ GIU NGUYEN 60f (KHONG ha xuong 25f nhu de xuat ban dau): ha nguong
     * nay xuong thap hon dong nghia AGC se coi ca tieng on nen/hoi tho nhe
     * cua mic la "tin hieu that" va bat dau khuech dai no - ket hop voi
     * maxGain da tang len 6.0, rui ro "boi" noise nen len ro ret se cao hon
     * nhieu so voi khi con o maxGain=3.0. Giu nguyen 60f de chi thay doi 1
     * bien so (maxGain) trong dot test nay, de dang xac dinh nguyen nhan
     * neu nghe thay van de gi phat sinh.
     */
    private val silenceRmsFloor: Float = 60f
) {
    private val attackCoeff = 1f - exp(-1f / (sampleRate * (attackMs / 1000f)))
    private val releaseCoeff = 1f - exp(-1f / (sampleRate * (releaseMs / 1000f)))

    @Volatile
    private var currentGain = 1f

    /** Gia tri gain dang ap dung - chi de hien thi debug/UI (vd 1 thanh meter nho), khong dung de tinh toan ben ngoai. */
    fun currentGainValue(): Float = currentGain

    fun process(buffer: ShortArray, size: Int) {
        if (size <= 0) return

        var sumSq = 0.0
        for (i in 0 until size) {
            val v = buffer[i].toDouble()
            sumSq += v * v
        }
        val rms = sqrt(sumSq / size).toFloat()

        if (rms > silenceRmsFloor) {
            val desiredGain = (targetRms / rms).coerceIn(minGain, maxGain)
            currentGain += if (desiredGain < currentGain) {
                attackCoeff * (desiredGain - currentGain)
            } else {
                releaseCoeff * (desiredGain - currentGain)
            }
            currentGain = currentGain.coerceIn(minGain, maxGain)
        }
        // Neu im lang (duoi silenceRmsFloor): giu nguyen currentGain, khong
        // "boi" gain len khi khong co tin hieu that (tranh vot amplitude
        // manh khi nguoi hat vua cat tieng tro lai).

        if (currentGain == 1f) return
        for (i in 0 until size) {
            var out = buffer[i] * currentGain
            out = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), out))
            buffer[i] = out.toInt().toShort()
        }
    }

    /** Reset gain ve 1.0 - goi khi bat dau 1 session moi (mic vua duoc bat lai, hoac 1 nguoi hat moi vua noi vao). */
    fun reset() {
        currentGain = 1f
    }
}