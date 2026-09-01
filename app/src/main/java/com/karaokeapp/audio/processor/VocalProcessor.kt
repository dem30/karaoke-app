package com.karaokeapp.audio.processor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phase 4 - chain xu ly: EQ -> Compressor -> Reverb -> Echo -> Limiter.
 * Chi lam SAU KHI mixer da on dinh (Phase 3 xong).
 *
 * Xem chi tiet task cu the trong PLAN.md, muc "Phase 4".
 *
 * ✅ Buoc 1/5 (theo dung thu tu PLAN.md - test rieng truoc khi ghep chuoi):
 * EQ 3-band (bass/mid/treble) bang biquad filter chuan RBJ Audio EQ Cookbook
 * (cong thuc pho bien, on dinh, khong can vien thu vien ngoai).
 *
 * Kien truc: 3 tang noi tiep (serial) tren CUNG 1 luong PCM mono:
 *   input -> [Low Shelf: bass] -> [Peaking: mid] -> [High Shelf: treble] -> output
 *
 * Moi tang la 1 bien the cua bo loc "biquad" (IIR bac 2), xu ly TUNG SAMPLE
 * mot (khong phai xu ly theo khoi FFT) - phu hop pipeline real-time hien tai
 * (nhan tung ShortArray chunk ~40ms tu MicInput/mixer, khong the cho gom du
 * lieu de lam FFT ma khong tang latency).
 *
 * Gain don vi: dB, dai khuyen nghi thuc te cho karaoke la -12..+12 dB moi
 * band (qua muc nay de dan de bi rit/on hoac mat tieng qua muc).
 */
class VocalProcessor(
    sampleRate: Int = 44100,
    bassGainDb: Float = 0f,
    midGainDb: Float = 0f,
    trebleGainDb: Float = 0f
) {

    /**
     * 1 tang bien che biquad IIR bac 2, cong thuc chuan tu RBJ Audio EQ
     * Cookbook (Robert Bristow-Johnson) - tai lieu tham khao kinh dien, dung
     * rong rai trong DSP audio (VST, plugin EQ...).
     *
     * Cong thuc chung: H(z) = (b0 + b1*z^-1 + b2*z^-2) / (a0 + a1*z^-1 + a2*z^-2)
     * Trien khai Direct Form I - don gian, on dinh so hoc du voi he so nho.
     */
    private class Biquad {
        var b0 = 1f; var b1 = 0f; var b2 = 0f
        var a1 = 0f; var a2 = 0f // a0 da duoc chuan hoa ve 1 truoc khi luu

        // Cac gia tri mau truoc do (state cua bo loc) - can giu qua nhieu
        // lan goi process() lien tiep de loc hoat dong dung (IIR phu thuoc
        // qua khu).
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

    private val bassFilter = Biquad()
    private val midFilter = Biquad()
    private val trebleFilter = Biquad()

    companion object {
        // Tan so cat/trung tam cho tung band - lua chon pho bien cho giong
        // hat karaoke (khong phai nhac cu dai rong, tap trung vao dai giong
        // nguoi 80Hz-8kHz):
        // - Bass shelf tai 200Hz: dieu chinh "day/mong" cua giong.
        // - Mid peak tai 1000Hz (1kHz): vung "ro/duc" chinh cua giong noi/hat.
        // - Treble shelf tai 6000Hz (6kHz): dieu chinh "sang/toi", "airy".
        private const val BASS_FREQ = 200f
        private const val MID_FREQ = 1000f
        private const val TREBLE_FREQ = 6000f

        // Q cho bo loc peak (mid band) - 1.0 la muc "vua phai", khong qua
        // hep (de gay cong huong nghe chua) cung khong qua rong (de mat tinh
        // chinh xac cua dai tan muon chinh).
        private const val MID_Q = 1.0f

        /**
         * Tinh he so cho Low Shelf filter (RBJ Cookbook).
         * Dung de boost/cut CAC tan so THAP HON freq (bass).
         */
        private fun lowShelf(target: Biquad, freq: Float, sampleRate: Int, gainDb: Float) {
            val a = sqrt(dbToLinear(gainDb)) // "A" trong cong thuc RBJ (khong phai a1/a2)
            val w0 = 2f * PI.toFloat() * freq / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / 2f * sqrt((a + 1f / a) * (1f / 0.9f - 1f) + 2f) // S=0.9 (shelf slope on dinh)

            val twoSqrtAAlpha = 2f * sqrt(a) * alpha

            val b0 = a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha)
            val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
            val b2 = a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha)
            val a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
            val a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
            val a2 = (a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha

            normalizeAndAssign(target, b0, b1, b2, a0, a1, a2)
        }

        /**
         * Tinh he so cho High Shelf filter (RBJ Cookbook).
         * Dung de boost/cut CAC tan so CAO HON freq (treble).
         */
        private fun highShelf(target: Biquad, freq: Float, sampleRate: Int, gainDb: Float) {
            val a = sqrt(dbToLinear(gainDb))
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

        /**
         * Tinh he so cho Peaking EQ filter (RBJ Cookbook).
         * Dung de boost/cut 1 dai tan HEP quanh freq, giu nguyen cac dai khac
         * (khong giong shelf - shelf anh huong toan bo 1 phia).
         */
        private fun peaking(target: Biquad, freq: Float, sampleRate: Int, q: Float, gainDb: Float) {
            val a = sqrt(dbToLinear(gainDb))
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

        private fun normalizeAndAssign(
            target: Biquad,
            b0: Float, b1: Float, b2: Float,
            a0: Float, a1: Float, a2: Float
        ) {
            // Chuan hoa ve a0=1 (RBJ Cookbook luon dinh nghia he so tho voi
            // a0 != 1) de cong thuc process() o tren (da gia dinh a0=1) dung.
            target.b0 = b0 / a0
            target.b1 = b1 / a0
            target.b2 = b2 / a0
            target.a1 = a1 / a0
            target.a2 = a2 / a0
        }

        private fun dbToLinear(db: Float): Float = Math.pow(10.0, db / 40.0).toFloat()
    }

    // Luu gain hien tai (dB) de co the doc lai qua getter neu UI can hien thi
    // slider - tranh phai luu rieng o noi khac roi de lech voi filter that.
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
        lowShelf(bassFilter, BASS_FREQ, currentSampleRate, bassGainDb)
        peaking(midFilter, MID_FREQ, currentSampleRate, MID_Q, midGainDb)
        highShelf(trebleFilter, TREBLE_FREQ, currentSampleRate, trebleGainDb)
    }

    /**
     * Cap nhat gain 1 hoac nhieu band - goi khi nguoi dung keo slider EQ.
     * Clamp ve [-12, +12] dB de tranh gia tri cuc doan gay method boost/cut
     * qua muc, de dan toi rit/mat tieng.
     *
     * ⚠️ Goi ham nay SE lam gian doan nho tin hieu dang xu ly (thay doi he
     * so bo loc giua chung khi dang chay) - co the nghe 1 "click" rat nho
     * neu doi gain dot ngot trong luc dang phat am to. Chap nhan duoc cho
     * Phase 4 (chua toi uu smoothing) - ghi chu de xem xet neu nghe ro click
     * trong test thuc te.
     */
    fun setGains(bassDb: Float = bassGainDb, midDb: Float = midGainDb, trebleDb: Float = trebleGainDb) {
        bassGainDb = clampGain(bassDb)
        midGainDb = clampGain(midDb)
        trebleGainDb = clampGain(trebleDb)
        recomputeAllFilters()
    }

    /**
     * Xu ly 1 buffer PCM mono TAI CHO (in-place) - sua truc tiep noi dung
     * buffer, khong cap phat mang moi (tranh GC pressure trong vong lap
     * real-time, dung tinh than voi ShortRingBuffer o LowLatencyMixer).
     *
     * @param buffer mang PCM 16-bit mono can xu ly.
     * @param size so luong sample THUC can xu ly trong buffer (co the nho
     * hon buffer.size, giong quy uoc onPcmChunk cua MicInput/MusicInput).
     */
    fun process(buffer: ShortArray, size: Int) {
        for (i in 0 until size) {
            // Chuyen ve Float de tinh toan chinh xac hon (tranh mat mat lam
            // tron lien tuc qua nhieu tang Int), roi clamp lai ve Short o
            // cuoi chuoi 3 tang.
            var sample = buffer[i].toFloat()
            sample = bassFilter.process(sample)
            sample = midFilter.process(sample)
            sample = trebleFilter.process(sample)

            val clamped = when {
                sample > Short.MAX_VALUE -> Short.MAX_VALUE.toFloat()
                sample < Short.MIN_VALUE -> Short.MIN_VALUE.toFloat()
                else -> sample
            }
            buffer[i] = clamped.toInt().toShort()
        }
    }

    /** Reset toan bo state noi bo cua ca 3 tang - goi khi bat dau 1 session moi de tranh "tan du" tu session truoc. */
    fun reset() {
        bassFilter.reset()
        midFilter.reset()
        trebleFilter.reset()
    }
}
