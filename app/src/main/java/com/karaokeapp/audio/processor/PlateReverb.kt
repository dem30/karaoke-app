package com.karaokeapp.audio.processor

import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

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
 *
 * 🛠️ SUA LOI "RE" KHI BAT REVERB (ban cap nhat nay):
 *
 * Nguyen nhan da xac dinh: mang 8 comb filter chay song song voi feedback
 * = roomSize, nhung TRUOC BAN SUA nay, tin hieu goc (input) duoc dua THANG
 * (full-scale) vao tung comb, khong duoc ha gain truoc. O trang thai on
 * dinh (not ngan dai, hoac formant giong hat trung chu ky delay cua 1 vai
 * comb), bien do NOI BO trong 1 comb co the tiem can toi input/(1-feedback)
 * - vd feedback=0.6 -> ~2.5 lan input goc. Cong don 8 comb roi moi nhan
 * 0.125 (chia trung binh) KHONG bu du muc cong huong nay khi nhieu comb
 * cung cong huong 1 luc (thuong xay ra voi giong hat co formant/not ngan
 * ro) -> tin hieu vuot han thang PCM 16-bit -> bi cat cung (hard clip) o
 * dong `clamped = max(MIN, min(MAX, mixed))` cu -> sinh hai bac cao dot
 * ngot -> nghe "re/vo tieng". Day chinh la ly do Freeverb GOC luon ha gain
 * input xuong rat nho (~0.015) TRUOC khi dua vao mang comb - file ban dau
 * thieu buoc nay (chi scale SAU khi da cong tong, la chua du).
 *
 * 2 thay doi de sua:
 * 1) COMB_INPUT_GAIN (0.03f): nhan vao input truoc khi dua vao TUNG comb,
 *    chua headroom that su cho phan cong huong noi bo, thay vi chi trong
 *    cay vao he so 0.125 sau khi cong tong (khong du).
 * 2) Soft clip (tanh) thay cho hard clip o buoc mix cuoi cung: neu van co
 *    truong hop hiem gap gan/vuot ngudong, tanh() bo tron dinh tin hieu
 *    thay vi cat cut dot ngot -> meo (neu co) se "em" hon nhieu, khong con
 *    nghe ra tieng re gat.
 * Ngoai ra ha nhe them roomSize mac dinh (0.6 -> 0.5) de giam bien do noi
 * bo cua comb further, giam xac suat cham nguong ngay tu dau.
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

    companion object {
        /**
         * ✅ MOI: he so ha gain TRUOC khi dua tin hieu vao mang comb (giong
         * tinh than "fixedGain" cua Freeverb goc, thuong ~0.015 cho input
         * dang float -1..1). O day input la thang Short (~-32767..32767)
         * nen he so tuyet doi khac, nhung VAI TRO giong het: chua headroom
         * cho phan bien do "tiem can input/(1-feedback)" ben trong tung
         * comb khi cong huong, tranh tong sau khi cong 8 comb bi vuot
         * thang PCM roi moi bi cat cung o buoc mix cuoi.
         */
        private const val COMB_INPUT_GAIN = 0.03f
    }

    /** Kich thuoc "phong" ao - anh huong thoi gian ngan (decay), 0f..1f, gan 1f = ngan rat lau.
     * ✅ Ha mac dinh 0.6 -> 0.5: giam bien do noi bo cong huong trong comb,
     * giam them xac suat cham/vuot nguong ngay ca sau khi da them
     * COMB_INPUT_GAIN o tren. */
    @Volatile var roomSize: Float = 0.5f

    /** Do "tat" cao tan trong duoi ngan - 0f..1f, cao hon = duoi ngan "toi"/am hon, tu nhien hon o phong lon. */
    @Volatile var damping: Float = 0.28f

    /** Ty le tin hieu da xu ly (wet) tron vao tin hieu goc (dry), 0f..1f. */
    @Volatile var wet: Float = 0.20f

    fun process(buffer: ShortArray, size: Int) {
        for (i in 0 until size) {
            val input = buffer[i].toFloat()

            // ✅ Ha gain TRUOC khi vao mang comb (xem COMB_INPUT_GAIN o tren)
            // - day la thay doi chinh de sua tieng "re".
            val combInput = input * COMB_INPUT_GAIN

            var outComb = 0f
            for (c in combs) {
                outComb += c.process(combInput, roomSize, damping)
            }

            var outAllPass = outComb * 0.125f
            for (a in allpasses) {
                outAllPass = a.process(outAllPass)
            }

            // Vi combInput da bi nhan COMB_INPUT_GAIN o dau vao, can nhan
            // nguoc lai (1/COMB_INPUT_GAIN) o day de outAllPass tro ve dung
            // thang bien do tuong duong voi truoc day (giu nguyen "do vang"
            // nghe duoc, chi khac o cho KHONG con bi cong huong vuot thang
            // truoc khi mix).
            val wetSignal = outAllPass * (1f / COMB_INPUT_GAIN)

            // ✅ HA he so nhan wet tu 2.2 xuong 1.3 (xem canh bao ve vong lap
            // am hoc o KDoc dau class) - giam bot gain cong them tu module
            // nay, danh doi lay duoi vang mong hon 1 chut de doi lay it rui
            // ro hu hon khi mic/loa o gan nhau.
            val mixed = (input * (1f - wet * 0.18f)) + (wetSignal * wet * 1.3f)

            // ✅ Soft clip (tanh) thay cho hard clamp cu: neu tin hieu van
            // gan/vuot thang PCM (truong hop hiem, con sot lai sau khi da
            // them COMB_INPUT_GAIN), tanh() bo tron dinh thay vi cat cut dot
            // ngot -> khong con sinh hai bac cao gay tieng "re/vo".
            // Voi tin hieu trong thang binh thuong (|mixed| << 32767),
            // tanh(x) ~ x nen KHONG lam doi am luong/mau am cua truong hop
            // binh thuong, chi "phanh em" khi thuc su vuot nguong.
            val normalized = mixed / Short.MAX_VALUE
            val softClipped = tanh(normalized) * Short.MAX_VALUE

            buffer[i] = softClipped.toInt().toShort()
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