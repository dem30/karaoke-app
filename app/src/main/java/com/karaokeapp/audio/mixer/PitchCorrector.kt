package com.karaokeapp.audio.mixer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PitchCorrector - "Auto-tune nhe" cho 1 kenh vocal, CHI chinh SAI TONE (cao do) - KHONG chinh duoc
 * lech nhip/phach (sua nhip that su can biet truoc tuong lai nguoi hat se hat the nao, khong lam
 * duoc trong xu ly realtime tung khoi PCM nhu the nay).
 *
 * Nguyen ly: PSOLA (Pitch Synchronous Overlap-Add) rut gon:
 *  1. Dinh ky do lai cao do hien tai cua giong hat bang autocorrelation tren 1 cua so phan tich.
 *  2. "Ep" (snap) cao do do ve not nhac gan nhat trong 1 thang am duoc phep (mac dinh: Cromatic du
 *     12 not - co the gioi han lai qua [allowedPitchClasses], vi du chi Truong/Thu cua 1 tone cu the).
 *  3. Tai tao tin hieu bang cach trich cac "hat" (grain) dai ~2 chu ky cao do tu vung lich su vua thu,
 *     roi chong lap (overlap-add, co cua so Hann) chung o 1 nhip do MOI (ung voi cao do dich) - vi moi
 *     grain la nguyen 1-2 chu ky song THAT cua giong hat (khong keo gian noi dung ben trong grain) nen
 *     giu duoc mau sac giong (formant) tuong doi tot hon so voi cach "resample tho" don gian.
 *
 * ⚠️ GIOI HAN THAT SU (doc truoc khi dua vao san xuat):
 *  - Day la thuat toan RUT GON de tham khao/demo, KHONG phai thu vien pitch-shift chuyen dung da duoc
 *    kiem chung rong rai (vd SoundTouch, Rubber Band Library qua JNI) - neu can do on dinh/chat luong
 *    cao cho san pham that, nen can nhac dung 1 thu vien C/C++ da test ky thay vi tu viet tu dau.
 *  - Do tre thuat toan KHONG THE = 0: phai "thay" duoc it nhat ~1 chu ky cao do de doan tan so, cong
 *    them ~1 chu ky nua de tao grain - tong do tre khoang 2/F0 giay. Giong nam tram F0~100Hz -> ~20ms;
 *    giong nu cao F0~300Hz -> ~6-7ms. Bien processingDelaySamples ben duoi phan anh dung con so nay.
 *  - Autocorrelation co the doan sai quang 8 (octave error) neu harmonic manh hon tan so co ban (rung
 *    giong, echo/reverb dang bat cung kenh...) - MIN/MAX_SHIFT_RATIO la 1 lop chan an toan (khong cho
 *    pitch-shift qua muc) nhung khong xoa het duoc loi nay - can test thuc te va tinh lai MIN/MAX_FREQUENCY_HZ
 *    theo dai giong nguoi dung thuc te cua ban.
 *  - Vong lap autocorrelation la O(cua_so x so_chu_ky_can_do) - da them buoc "do tho truoc (buoc nhay
 *    COARSE_STEP), tinh chinh sau" de giam tai CPU, nhung VAN NEN do dac tren thiet bi that (dac biet
 *    may cu/gia re) truoc khi bat mac dinh cho tat ca nguoi dung - neu qua nang, giam ANALYSIS_WINDOW_SAMPLES
 *    hoac tang PITCH_DETECT_HOP_SAMPLES (do thua hon, doi lay it CPU hon).
 *  - Ty le ghi/doc cua vong Overlap-Add (olaWritePos/olaReadPos) co the "troi" dan qua thoi gian dai
 *    vi synthesisHop thay doi lien tuc theo ty le pitch-shift - da them 1 lop ep an toan (xem trong
 *    triggerGrain()) de tranh doc/ghi de len nhau gay ra tieng "rac", nhung day la 1 don gian hoa,
 *    khong phai giai phap toi uu tuyet doi.
 */
class PitchCorrector(private val sampleRate: Int = 44100) {

    companion object {
        // Vung tan so giong hat se xet khi do cao do - chinh lai neu can bat giong rat tram/rat cao.
        private const val MIN_FREQUENCY_HZ = 70.0    // ~D2, thap hon giong nam tram binh thuong 1 chut
        private const val MAX_FREQUENCY_HZ = 900.0   // bao het giong nu cao/falsetto thong thuong

        // Bien do RMS toi thieu de coi la "co tieng hat that su" - tranh do pitch tren im lang/noise nen.
        private const val SILENCE_RMS_THRESHOLD = 60.0

        // Nguong tuong quan (0..1) toi thieu de tin ket qua do cao do - duoi muc nay coi nhu "khong
        // ro cao do" (noise, phu am, hat khong ro not...) va bo qua, giu nguyen ty le shift cu.
        private const val CORRELATION_THRESHOLD = 0.35

        // Do dai cua so phan tich autocorrelation (mau).
        private const val ANALYSIS_WINDOW_SAMPLES = 1024

        // Do lai cao do moi 256 mau (~5.8ms @ 44100Hz) thay vi moi mau - giam tai CPU dang ke.
        private const val PITCH_DETECT_HOP_SAMPLES = 256

        // Buoc nhay khi do THO truoc khi tinh chinh +-COARSE_STEP quanh ung vien tot nhat.
        private const val COARSE_STEP = 2

        // Gioi han ty le pitch-shift cho phep - tranh meo giong qua muc khi do sai/octave error.
        private const val MAX_SHIFT_RATIO = 1.5
        private const val MIN_SHIFT_RATIO = 1.0 / 1.5
    }

    /** Bat/tat module - mac dinh TAT, phai duoc UI/nguoi dung chu dong bat. */
    var enabled: Boolean = false

    /**
     * Muc do "ep" ve dung tone: 0f = giu nguyen giong that (khong chinh gi), 1f = ep het muc ve dung
     * not gan nhat. Nen de o muc trung binh (0.4f-0.6f) de nghe tu nhien - 1f de test/nghe ro hieu ung.
     */
    var correctionStrength: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /** Cac not (pitch class 0-11, ung voi C, C#, D, D#... B) duoc PHEP snap toi - mac dinh: Cromatic (du 12 not). */
    var allowedPitchClasses: Set<Int> = (0..11).toSet()

    private val minPeriodSamples = (sampleRate / MAX_FREQUENCY_HZ).toInt().coerceAtLeast(8)
    private val maxPeriodSamples = (sampleRate / MIN_FREQUENCY_HZ).toInt()

    // ================= Vung dem lich su (tin hieu tho, chua qua xu ly) =================
    private val historyCapacity = ANALYSIS_WINDOW_SAMPLES + maxPeriodSamples * 3
    private val history = FloatArray(historyCapacity)
    private var historyWritePos = 0
    private var totalSamplesWritten = 0L

    // ================= Vung dem Overlap-Add cho tin hieu DA duoc chinh =================
    private val olaCapacity = maxPeriodSamples * 6
    private val olaBuffer = FloatArray(olaCapacity)
    private val olaWeight = FloatArray(olaCapacity) // tong trong so cua so da cong don - de chuan hoa bien do khi 2 grain de len nhau
    private var olaWritePos: Int
    private var olaReadPos = 0

    // ================= Trang thai dong bo hoa PSOLA =================
    private var currentPeriodSamples = (sampleRate / 180.0).toInt() // gia tri khoi tao tam (~180Hz) truoc khi co du du lieu
    private var currentShiftRatio = 1.0
    private var synthesisPhase = 0.0 // "so mau" con lai truoc khi trigger grain tiep theo

    // Do lech CO DINH giua vi tri ghi va vi tri doc cua olaBuffer luc khoi tao - chinh la "do tre
    // thuat toan" cua ca module (xem canh bao o dau file).
    private val processingDelaySamples = maxPeriodSamples + ANALYSIS_WINDOW_SAMPLES / 2

    init {
        olaWritePos = processingDelaySamples % olaCapacity
    }

    /**
     * Xu ly TAI CHO (in-place) 1 khoi PCM 16-bit mono. Goi hang dau tien trong chuoi xu ly cua
     * VocalChannel (TRUOC AutoGain/EQ/Compressor/Echo) - vi pitch-shift can tin hieu giong hat cang
     * THO cang tot de do cao do chinh xac, chua bi EQ/nen lam lech pho tan.
     */
    fun process(buffer: ShortArray, size: Int) {
        if (!enabled) return

        for (i in 0 until size) {
            pushHistory(buffer[i].toFloat())

            if (totalSamplesWritten % PITCH_DETECT_HOP_SAMPLES == 0L) {
                updatePitchAndRatio()
            }

            advanceSynthesisClockAndMaybeTriggerGrain()

            val outSample = popOlaOutput()
            buffer[i] = outSample.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    /**
     * Dua TOAN BO state noi bo (lich su tin hieu, vung dem Overlap-Add, dong
     * ho dong bo PSOLA) ve dung nhu luc vua khoi tao - KHONG doi cac tham so
     * nguoi dung da chinh (enabled/correctionStrength/allowedPitchClasses).
     * Goi khi bat dau lai 1 session (giong het tinh than reset() cua cac
     * module khac trong VocalChannel) de tranh doc lai history/olaBuffer
     * con sot tu lan hat/session TRUOC (co the gay tieng "rac"/pitch sai o
     * vai chuc ms dau cua session moi).
     */
    fun reset() {
        history.fill(0f)
        historyWritePos = 0
        totalSamplesWritten = 0L

        olaBuffer.fill(0f)
        olaWeight.fill(0f)
        olaWritePos = processingDelaySamples % olaCapacity
        olaReadPos = 0

        currentPeriodSamples = (sampleRate / 180.0).toInt()
        currentShiftRatio = 1.0
        synthesisPhase = 0.0
    }

    // ------------------------- Lich su tin hieu tho -------------------------

    private fun pushHistory(sample: Float) {
        history[historyWritePos] = sample
        historyWritePos = (historyWritePos + 1) % historyCapacity
        totalSamplesWritten++
    }

    /** Lay mau tai vi tri "cach day [samplesAgo] mau" so voi mau MOI NHAT vua ghi (samplesAgo=0 la mau moi nhat). */
    private fun historySamplesAgo(samplesAgo: Int): Float {
        val safeAgo = samplesAgo.coerceIn(0, historyCapacity - 1)
        val idx = ((historyWritePos - 1 - safeAgo) % historyCapacity + historyCapacity) % historyCapacity
        return history[idx]
    }

    // ------------------------- Do cao do (autocorrelation) -------------------------

    private fun updatePitchAndRatio() {
        if (totalSamplesWritten < ANALYSIS_WINDOW_SAMPLES + maxPeriodSamples) return // chua du du lieu

        // Bo qua neu dang im lang - tranh do pitch tren noise nen, giu nguyen currentPeriodSamples/
        // currentShiftRatio (gia tri cu) cho toi khi lai co tieng hat that su.
        var sumSquares = 0.0
        for (n in 0 until ANALYSIS_WINDOW_SAMPLES) {
            val s = historySamplesAgo(n)
            sumSquares += (s * s).toDouble()
        }
        val rms = sqrt(sumSquares / ANALYSIS_WINDOW_SAMPLES)
        if (rms < SILENCE_RMS_THRESHOLD) return

        val period = detectPeriodCoarseThenRefine() ?: return
        currentPeriodSamples = period

        val detectedFreq = sampleRate.toDouble() / period
        val targetFreq = snapToNearestAllowedNote(detectedFreq)

        val rawRatio = targetFreq / detectedFreq
        // Chi ap dung [correctionStrength] phan cua do lech - 1f la ep het muc, 0f la giu nguyen.
        val blended = 1.0 + (rawRatio - 1.0) * correctionStrength
        currentShiftRatio = blended.coerceIn(MIN_SHIFT_RATIO, MAX_SHIFT_RATIO)
    }

    /**
     * Do THO truoc voi buoc nhay COARSE_STEP (giam ~COARSE_STEP lan so lan tinh autocorrelation),
     * roi tinh chinh +-COARSE_STEP quanh ung vien tot nhat - can bang giua do chinh xac va tai CPU.
     */
    private fun detectPeriodCoarseThenRefine(): Int? {
        var bestPeriod = -1
        var bestCorr = 0.0

        var period = minPeriodSamples
        while (period <= maxPeriodSamples) {
            val corr = normalizedAutocorrelationAt(period)
            if (corr > bestCorr) {
                bestCorr = corr
                bestPeriod = period
            }
            period += COARSE_STEP
        }
        if (bestPeriod < 0) return null

        val refineFrom = max(minPeriodSamples, bestPeriod - COARSE_STEP)
        val refineTo = min(maxPeriodSamples, bestPeriod + COARSE_STEP)
        for (p in refineFrom..refineTo) {
            val corr = normalizedAutocorrelationAt(p)
            if (corr > bestCorr) {
                bestCorr = corr
                bestPeriod = p
            }
        }

        return if (bestCorr >= CORRELATION_THRESHOLD) bestPeriod else null
    }

    private fun normalizedAutocorrelationAt(period: Int): Double {
        var corr = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        for (n in 0 until ANALYSIS_WINDOW_SAMPLES) {
            val a = historySamplesAgo(n + period).toDouble()
            val b = historySamplesAgo(n).toDouble()
            corr += a * b
            norm1 += a * a
            norm2 += b * b
        }
        val denom = sqrt(norm1 * norm2)
        return if (denom > 1e-6) corr / denom else 0.0
    }

    // ------------------------- Snap ve thang am -------------------------

    private fun snapToNearestAllowedNote(freqHz: Double): Double {
        val exactMidi = 69.0 + 12.0 * log2(freqHz / 440.0)
        val roundedMidi = exactMidi.roundToInt()
        var bestMidi = roundedMidi
        var bestDist = Double.MAX_VALUE

        // Xet vai not lan can (+-3 nua cung) de tim not GAN NHAT ma pitch class nam trong allowedPitchClasses.
        for (candidate in (roundedMidi - 3)..(roundedMidi + 3)) {
            val pitchClass = ((candidate % 12) + 12) % 12
            if (pitchClass !in allowedPitchClasses) continue
            val dist = abs(candidate - exactMidi)
            if (dist < bestDist) {
                bestDist = dist
                bestMidi = candidate
            }
        }
        return 440.0 * 2.0.pow((bestMidi - 69) / 12.0)
    }

    // ------------------------- Tong hop PSOLA (dong ho tong hop + overlap-add) -------------------------

    private fun advanceSynthesisClockAndMaybeTriggerGrain() {
        synthesisPhase -= 1.0
        if (synthesisPhase <= 0.0) {
            val synthesisHop = currentPeriodSamples / currentShiftRatio
            synthesisPhase += synthesisHop
            triggerGrain(synthesisHop.roundToInt().coerceAtLeast(1))
        }
    }

    private fun triggerGrain(synthesisHopSamples: Int) {
        val grainHalfLength = currentPeriodSamples.coerceIn(minPeriodSamples, maxPeriodSamples)
        val grainLength = grainHalfLength * 2

        // Tam grain lay tu lich su: k=0 la phan CU nhat cua grain (samplesAgo=grainLength, con nam
        // trong qua khu), k=grainLength-1 la phan MOI nhat (samplesAgo=1, van la qua khu gan "hien
        // tai") - KHONG bao gio can mau "tuong lai" (day chinh la ly do phai chap nhan do tre thuat
        // toan da giai thich o dau file).
        for (k in 0 until grainLength) {
            val samplesAgo = grainLength - k
            val sample = historySamplesAgo(samplesAgo)
            val window = hannWindow(k, grainLength)
            val outIdx = (olaWritePos + k) % olaCapacity
            olaBuffer[outIdx] += sample * window
            olaWeight[outIdx] += window
        }

        olaWritePos = (olaWritePos + synthesisHopSamples) % olaCapacity

        // ✅ An toan chong "troi" khoang cach ghi/doc (xem canh bao dau file): vi synthesisHop thay
        // doi lien tuc theo currentShiftRatio, khoang cach thuc te giua olaWritePos va olaReadPos co
        // the troi dan xa khoi processingDelaySamples ly tuong qua thoi gian dai. Neu troi qua xa
        // (vi du ganh doc sap "duoi kip" ghi), ep lai ve gia tri an toan - chap nhan 1 buoc nhay nho
        // trong tin hieu (hiem khi xay ra neu correctionStrength/MAX_SHIFT_RATIO o muc hop ly).
        val gap = ((olaWritePos - olaReadPos) % olaCapacity + olaCapacity) % olaCapacity
        val safeMin = grainLength
        val safeMax = olaCapacity - grainLength
        if (gap < safeMin) {
            olaWritePos = (olaReadPos + safeMin) % olaCapacity
        } else if (gap > safeMax) {
            olaWritePos = (olaReadPos + safeMax) % olaCapacity
        }
    }

    private fun hannWindow(k: Int, length: Int): Float {
        if (length <= 1) return 1f
        return (0.5 - 0.5 * cos(2.0 * PI * k / (length - 1))).toFloat()
    }

    private fun popOlaOutput(): Float {
        val idx = olaReadPos
        val weight = olaWeight[idx]
        val sample = if (weight > 1e-3f) olaBuffer[idx] / weight else olaBuffer[idx]
        // Don sach o vua doc de tai su dung vong sau (day la ring buffer).
        olaBuffer[idx] = 0f
        olaWeight[idx] = 0f
        olaReadPos = (olaReadPos + 1) % olaCapacity
        return sample
    }
}
