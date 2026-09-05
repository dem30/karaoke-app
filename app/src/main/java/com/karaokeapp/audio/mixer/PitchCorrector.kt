package com.karaokeapp.audio.mixer

import com.karaokeapp.audio.music.CaptureLogBus
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
 *  4. ✅ MOI (PSOLA dong bo pha THAT SU - xac nhan qua chan doan thuc te: strength=0 sach tieng,
 *     nhung strength cang tang (ratio cang xa 1) thi rè cang tang, du chi lech vai %): buoc (3) o tren
 *     dung 2 DONG HO DOC LAP thay vi 1 - dong ho PHAN TICH (advanceAnalysisClockAndMaybeExtractGrain())
 *     trich grain moi CHINH XAC moi currentPeriodSamples mau (dung nhip THAT cua tin hieu, khong bi
 *     currentShiftRatio lam meo) nen cac grain lien tiep LUON dong bo pha voi nhau; dong ho TONG HOP
 *     (advanceSynthesisClockAndMaybeTriggerGrain()) rieng biet quyet dinh KHI NAO va CACH NHAU BAO XA
 *     cac grain do (lay tu hang doi [pendingAnalysisGrains]) duoc DAT vao OLA, theo synthesisHop =
 *     currentPeriodSamples/currentShiftRatio - chinh su LECH nhip giua 2 dong ho nay moi tao ra hieu
 *     ung dich cao do, dung tinh than PSOLA "kinh dien" (analysis epoch != synthesis epoch).
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
 *    COARSE_STEP), tinh chinh sau" de giam tai CPU, VA da nang PITCH_DETECT_HOP_SAMPLES len 2048 (thay
 *    vi 256 ban dau) sau khi xac nhan thuc te tren thiet bi CPU yeu: o 256 mau, vong autocorrelation
 *    chay ~172 lan/giay TREN CHINH audio thread lam audio khong kip deadline (mat tieng/"ret ret" ngay
 *    khi bat). VAN NEN do dac tren thiet bi that truoc khi bat mac dinh cho tat ca nguoi dung - neu con
 *    yeu, giam ANALYSIS_WINDOW_SAMPLES hoac tang further PITCH_DETECT_HOP_SAMPLES (vd 4096).
 *  - Ty le ghi/doc cua vong Overlap-Add (olaWritePos/olaReadPos) co the "troi" dan qua thoi gian dai
 *    vi synthesisHop thay doi lien tuc theo ty le pitch-shift - da them 1 lop ep an toan (xem trong
 *    placeGrainInOla()) de tranh doc/ghi de len nhau gay ra tieng "rac", nhung day la 1 don gian hoa,
 *    khong phai giai phap toi uu tuyet doi.
 *
 * ✅ FAIL-SAFE V2 (crossfade - sua sau khi xac nhan qua 2 vong test thuc te): popOlaOutput() BAN DAU
 *   tra ve 0f (im lang) khi olaWeight tai vi tri doc con qua thap - gay MAT TIENG hoan toan luc vua
 *   bat auto-tune. Ban V1 sua bang cach HARD-SWITCH ve mau mic goc khi weight thap - het mat tieng,
 *   NHUNG lai sinh tieng "ret/click" MOI tai moi ranh gioi chuyen doi giua PSOLA/mic goc (2 nguon
 *   bien do/pha khac nhau, nhay dot ngot). GIO dung CROSSFADE lien tuc (tron tuyen tinh PSOLA voi mic
 *   goc theo chinh do tin cay cua olaWeight, xem CROSSFADE_FULL_CONFIDENCE_WEIGHT va popOlaOutput()) -
 *   vua khong bao gio im lang/rac, vua khong con ranh gioi cung nao gay tieng ret.
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

        // ✅ SUA (giam tai CPU tren audio thread - xac nhan qua chan doan
        // thuc te tren thiet bi cua nguoi dung): truoc la 256 mau (~5.8ms @
        // 44100Hz, ~172 lan/giay) - QUA DAY cho 1 vong autocorrelation O(cua
        // so x so chu ky) chay TRUC TIEP tren audio thread, de lam audio
        // thread khong kip deadline tren may CPU yeu (bieu hien: mat tieng/
        // "ret ret" ngay khi bat enabled=true, xem log chan doan). Nang len
        // 2048 mau (~46ms, ~21 lan/giay) - van du nhanh cho auto-tune "nhe"
        // (giong hat khong doi cao do nhanh hon vai chuc ms), giam tai CPU
        // manh. Neu may van yeu/con nghe "ret ret", thu nang tiep len 4096
        // (~93ms, ~11 lan/giay) - danh doi: pitch-shift phan ung cham hon
        // mot chut voi thay doi cao do dot ngot cua giong hat.
        private const val PITCH_DETECT_HOP_SAMPLES = 2048

        // Buoc nhay khi do THO truoc khi tinh chinh +-COARSE_STEP quanh ung vien tot nhat.
        private const val COARSE_STEP = 2

        // Gioi han ty le pitch-shift cho phep - tranh meo giong qua muc khi do sai/octave error.
        private const val MAX_SHIFT_RATIO = 1.5
        private const val MIN_SHIFT_RATIO = 1.0 / 1.5

        // ✅ MOI (fail-safe V2 - crossfade, xem KDoc popOlaOutput()): nguong
        // olaWeight coi nhu "PSOLA da du tin cay, khong can tron voi mic goc
        // nua". 1 grain Hann full-overlap (2 grain ke nhau chong dung 50%)
        // dong gop weight quanh 1.0 tai vung giua - de nguong nay = 0.5f
        // (thap hon 1.0) de crossfade hoan tat SOM, khong keo dai qua muc
        // can thiet (tranh nghe "mo"/pha tron qua lau o vung ranh gioi).
        private const val CROSSFADE_FULL_CONFIDENCE_WEIGHT = 0.5f

        // ✅ MOI (chan doan): in log RMS/period/freq/ratio moi bao nhieu lan
        // goi updatePitchAndRatio() - 1 lan goi ~46ms @ 44100Hz (sau khi nang
        // PITCH_DETECT_HOP_SAMPLES len 2048), nen 50 lan ~= 1 lan log moi
        // ~2.3s - du day de doc, khong spam logcat.
        private const val DIAGNOSTIC_LOG_EVERY_N_UPDATES = 50

        // ✅ MOI (fail-safe V3 - bypass PSOLA khi ratio~1, xem KDoc process()
        // va popBypassSample()): sai so cho phep de coi currentShiftRatio la
        // "dung 1.0" (khong can dich cao do). Dat = 0.0005 (0.05%) - du nho
        // de khong bao gio bypass nham 1 gia tri ratio thuc su can dich (vi
        // du 1.005 - lech ~5 cent, van nghe ro), nhung du "co gian" de khong
        // bi rung/nhap nhang qua lai bypass<->PSOLA lien tuc do sai so lam
        // tron floating-point khi blended = 1.0 + (rawRatio - 1.0) * strength
        // ra dung 1.0 hoac rat gan 1.0.
        private const val RATIO_BYPASS_EPSILON = 0.0005

        // ✅ MOI (PSOLA dong bo pha THAT SU - xem KDoc updatePitchAndRatio()/
        // advanceAnalysisClockAndMaybeExtractGrain()): so grain PHAN TICH toi
        // da duoc giu trong hang doi [pendingAnalysisGrains] cho vong tong hop
        // tieu thu. Xay ra khi correctionStrength dich xuong (currentShiftRatio
        // < 1 => nhip tong hop THUA nhip phan tich => grain san xuat nhanh hon
        // tieu thu) - can chan tren de KHONG cho hang doi phinh to vo han (se
        // lam do tre "troi" dan qua thoi gian dai). Dat = 4 (~4 chu ky cao do,
        // vai chuc ms) - du de "dem" qua 1 vai lan dao dong nhip nho, nhung
        // van xa ngay khi lech nhieu (chap nhan bo bot vai chu ky cu - dung
        // ban chat cua viec ha cao do bang PSOLA: phai "bo bot" chu ky).
        private const val MAX_PENDING_ANALYSIS_GRAINS = 4
    }

    // ✅ MOI (chan doan): dem so lan updatePitchAndRatio() da chay (ke ca
    // cac lan bi return som vi im lang/khong ro cao do) - dung rieng bien
    // nay (khong dung totalSamplesWritten) de dieu khien tan suat log doc
    // lap, khong lien quan gi den logic PSOLA.
    private var diagnosticTickCount = 0L

    /** Bat/tat module - mac dinh TAT, phai duoc UI/nguoi dung chu dong bat. */
    var enabled: Boolean = false
        set(value) {
            // ✅ MOI (chan doan): log MOI LAN doi (khong throttle, vi day la
            // su kien hiem - bam checkbox) - de xac nhan UI co thuc su goi
            // toi DUNG instance PitchCorrector dang chay trong luong audio
            // hay khong (loai tru kha nang tham chieu sai/instance khac).
            CaptureLogBus.log("[PitchCorrector] ${if (value) "✅ BAT" else "⬜ TAT"} (instance=${System.identityHashCode(this)}).")
            field = value
        }

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

    // ✅ MOI (PSOLA dong bo pha THAT SU - tach doi dong ho PHAN TICH (analysis)
    // ra khoi dong ho TONG HOP (synthesis), xem KDoc phia duoi 2 ham
    // advanceAnalysisClockAndMaybeExtractGrain()/advanceSynthesisClockAndMaybeTriggerGrain()):
    // truoc day CA HAI dung chung 1 dong ho duy nhat (nhip = synthesisHop =
    // currentPeriodSamples/currentShiftRatio), nghia la grain duoc TRICH tu
    // history VOI CUNG nhip ma no duoc DAT vao OLA - khi ratio != 1, nhip do
    // KHONG con khop voi chu ky THAT cua tin hieu goc nua => moi grain lien
    // tiep vo tinh lay nhau o pha khac nhau cua song => OLA noi lai bi lech
    // pha => rè/kim loai (rõ dan khi ratio cang xa 1, dung nhu log thuc te da
    // xac nhan: strength=0 sach, strength tang dan thi rè tang dan). Bay gio
    // co 2 dong ho doc lap: [analysisPhase] luon dem theo DUNG currentPeriodSamples
    // (nhip THAT cua tin hieu do duoc, KHONG bi ratio lam meo) de trich grain
    // luon dong bo pha voi nhau, con [synthesisPhase] (o duoi) van dem theo
    // synthesisHop de quyet dinh KHI NAO va CACH NHAU BAO XA cac grain do
    // duoc dat vao OLA - chinh khoang cach dat lai nay (khac voi khoang cach
    // trich ra) moi la thu tao ra hieu ung dich cao do, dung tinh than PSOLA
    // "kinh dien" (analysis epoch va synthesis epoch la 2 thu khac nhau).
    private var analysisPhase = 0.0 // "so mau" con lai truoc khi trich 1 grain PHAN TICH moi tu history

    // Hang doi FIFO cac grain da trich (chua windowed) dang cho duoc dong ho
    // TONG HOP tieu thu - xem MAX_PENDING_ANALYSIS_GRAINS o tren (chan phinh
    // to khi ratio<1) va lastAnalysisGrain o duoi (chan can khi ratio>1).
    private val pendingAnalysisGrains = ArrayDeque<FloatArray>()

    // Grain PHAN TICH gan nhat da duoc tieu thu - dung de "dung lai" (tai su
    // dung) khi dong ho TONG HOP chay nhanh hon dong ho PHAN TICH (ratio>1,
    // dich cao do LEN: synthesisHop < currentPeriodSamples => can nhieu grain
    // hon so voi so grain moi duoc trich trong cung 1 khoang thoi gian) -
    // dung ban chat cua viec nang cao do bang PSOLA: phai "lap lai" 1 chu ky.
    private var lastAnalysisGrain: FloatArray? = null

    private var synthesisPhase = 0.0 // "so mau" con lai truoc khi dat grain tiep theo vao OLA

    // Do lech CO DINH giua vi tri ghi va vi tri doc cua olaBuffer luc khoi tao - chinh la "do tre
    // thuat toan" cua ca module (xem canh bao o dau file).
    private val processingDelaySamples = maxPeriodSamples + ANALYSIS_WINDOW_SAMPLES / 2

    // ✅ MOI (fail-safe popOlaOutput): dem TUYET DOI so mau da duoc "xuat ra"
    // qua popOlaOutput() tu luc khoi tao/reset - KHONG dung truc tiep
    // olaReadPos cho viec nay vi olaReadPos la CHI SO VONG (% olaCapacity,
    // se lap lai gia tri sau moi olaCapacity mau) nen khong the dung de suy
    // ra "mau nay tuong ung voi mau mic nao trong qua khu" mot khi da vong
    // qua it nhat 1 lan. Bo dem rieng, tuyen tinh nay moi la thu dung de
    // tinh dung offset vao [history].
    private var totalSamplesPopped = 0L

    init {
        olaWritePos = processingDelaySamples % olaCapacity
    }

    /**
     * Xu ly TAI CHO (in-place) 1 khoi PCM 16-bit mono. Goi hang dau tien trong chuoi xu ly cua
     * VocalChannel (TRUOC AutoGain/EQ/Compressor/Echo) - vi pitch-shift can tin hieu giong hat cang
     * THO cang tot de do cao do chinh xac, chua bi EQ/nen lam lech pho tan.
     *
     * ✅ MOI (fail-safe V3 - bypass PSOLA khi ratio~1): da xac nhan qua chan
     * doan thuc te la ratio=1.000 (bao gom ca correctionStrength=0) KHONG co
     * nghia PSOLA tai tao ra dung tin hieu goc - code truoc day van chay
     * advanceSynthesisClockAndMaybeTriggerGrain()/popOlaOutput() LIEN TUC bat
     * ke ratio, tuc la van cat grain 2 chu ky tu [history] roi Hann+OLA lai
     * moi lan, du ratio=1. Vi grain duoc cat theo "so mau" (samplesAgo=
     * grainLength-k) chu KHONG dong bo theo pha/pitch-mark that su cua song
     * (xem giai thich chi tiet o cho goi PitchCorrector nay), cac grain lien
     * tiep co the bat dau o pha khac nhau => waveform sau OLA khong noi dung
     * pha => nghe "rè/nhoe/rung/metallic" NGAY CA KHI khong can dich cao do
     * chut nao. Bay gio: khi correctionStrength=0 HOAC currentShiftRatio da
     * ~1.0 (xem RATIO_BYPASS_EPSILON), BO QUA HOAN TOAN duong PSOLA (khong
     * goi advanceAnalysisClockAndMaybeExtractGrain/advanceSynthesisClockAndMaybeTriggerGrain,
     * khong cong don OLA) - xuat thang mau mic goc tu
     * [history] (qua popBypassSample(), giu dung processingDelaySamples nhu
     * duong PSOLA de khop timing khi chuyen qua lai 2 mode). Vua het rè o
     * ratio=1, vua nhe CPU hon (bo hang loat phep Hann/OLA khi khong can).
     */
    fun process(buffer: ShortArray, size: Int) {
        if (!enabled) return

        for (i in 0 until size) {
            pushHistory(buffer[i].toFloat())

            if (totalSamplesWritten % PITCH_DETECT_HOP_SAMPLES == 0L) {
                updatePitchAndRatio()
            }

            val bypassPsola = correctionStrength <= 0f ||
                abs(currentShiftRatio - 1.0) < RATIO_BYPASS_EPSILON

            val outSample = if (bypassPsola) {
                popBypassSample()
            } else {
                advanceAnalysisClockAndMaybeExtractGrain()
                advanceSynthesisClockAndMaybeTriggerGrain()
                popOlaOutput()
            }

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
        totalSamplesPopped = 0L

        currentPeriodSamples = (sampleRate / 180.0).toInt()
        currentShiftRatio = 1.0
        analysisPhase = 0.0
        pendingAnalysisGrains.clear()
        lastAnalysisGrain = null
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
        diagnosticTickCount++
        val shouldLog = diagnosticTickCount % DIAGNOSTIC_LOG_EVERY_N_UPDATES == 0L

        if (totalSamplesWritten < ANALYSIS_WINDOW_SAMPLES + maxPeriodSamples) {
            if (shouldLog) {
                CaptureLogBus.log("[PitchCorrector] ⏳ Chua du du lieu (totalSamplesWritten=$totalSamplesWritten).")
            }
            return // chua du du lieu
        }

        // Bo qua neu dang im lang - tranh do pitch tren noise nen, giu nguyen currentPeriodSamples/
        // currentShiftRatio (gia tri cu) cho toi khi lai co tieng hat that su.
        var sumSquares = 0.0
        for (n in 0 until ANALYSIS_WINDOW_SAMPLES) {
            val s = historySamplesAgo(n)
            sumSquares += (s * s).toDouble()
        }
        val rms = sqrt(sumSquares / ANALYSIS_WINDOW_SAMPLES)
        if (rms < SILENCE_RMS_THRESHOLD) {
            if (shouldLog) {
                CaptureLogBus.log("[PitchCorrector] 🔇 RMS=${"%.1f".format(rms)} < nguong $SILENCE_RMS_THRESHOLD - coi la im lang, bo qua do pitch.")
            }
            return
        }

        val period = detectPeriodCoarseThenRefine()
        if (period == null) {
            if (shouldLog) {
                CaptureLogBus.log("[PitchCorrector] ❓ RMS=${"%.1f".format(rms)} (co tieng) nhung KHONG do duoc cao do ro rang (tuong quan < $CORRELATION_THRESHOLD) - giu nguyen ratio cu=$currentShiftRatio.")
            }
            return
        }
        currentPeriodSamples = period

        val detectedFreq = sampleRate.toDouble() / period
        val targetFreq = snapToNearestAllowedNote(detectedFreq)

        val rawRatio = targetFreq / detectedFreq
        // Chi ap dung [correctionStrength] phan cua do lech - 1f la ep het muc, 0f la giu nguyen.
        val blended = 1.0 + (rawRatio - 1.0) * correctionStrength
        currentShiftRatio = blended.coerceIn(MIN_SHIFT_RATIO, MAX_SHIFT_RATIO)

        if (shouldLog) {
            CaptureLogBus.log(
                "[PitchCorrector] 🎤 RMS=${"%.1f".format(rms)} period=$period detectedFreq=${"%.1f".format(detectedFreq)}Hz " +
                    "targetFreq=${"%.1f".format(targetFreq)}Hz rawRatio=${"%.3f".format(rawRatio)} " +
                    "strength=$correctionStrength -> currentShiftRatio=${"%.3f".format(currentShiftRatio)}"
            )
        }
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

    // ------------------------- Tong hop PSOLA (2 dong ho doc lap: PHAN TICH + TONG HOP) -------------------------

    /**
     * ✅ MOI (PSOLA dong bo pha THAT SU): trich 1 grain THO (chua windowed) tu
     * [history] moi khi da troi qua DUNG currentPeriodSamples mau ke tu lan
     * trich truoc - nghia la nhip trich hoan toan theo CHU KY THAT cua tin
     * hieu dang thu, KHONG bi anh huong boi currentShiftRatio. Vi grain luon
     * lay "2 chu ky gan nhat tinh den hien tai" (samplesAgo=grainLength..1,
     * xem extractAnalysisGrain()) VA khoang cach giua 2 lan trich lien tiep
     * LUON DUNG BANG 1 chu ky that (khong hon khong kem, tru sai so do dac
     * pitch nho), 2 grain lien tiep se gan nhu la ban sao cua nhau dich di
     * dung 1 chu ky - tuc DONG BO PHA voi nhau. Day chinh la phan con thieu
     * so voi PSOLA "chuan": truoc day grain duoc trich theo nhip TONG HOP
     * (synthesisHop, bi ratio lam meo) nen 2 grain lien tiep KHONG dong bo
     * pha voi nhau ngay ca khi noi dung tin hieu hoan toan on dinh.
     */
    private fun advanceAnalysisClockAndMaybeExtractGrain() {
        analysisPhase -= 1.0
        if (analysisPhase <= 0.0) {
            analysisPhase += currentPeriodSamples
            pendingAnalysisGrains.addLast(extractAnalysisGrain())
            // Chan phinh to hang doi khi ratio<1 (xem MAX_PENDING_ANALYSIS_GRAINS) -
            // bo cac grain CU nhat truoc (giu grain moi nhat, sat voi "hien tai" nhat).
            while (pendingAnalysisGrains.size > MAX_PENDING_ANALYSIS_GRAINS) {
                pendingAnalysisGrains.removeFirst()
            }
        }
    }

    private fun extractAnalysisGrain(): FloatArray {
        val grainHalfLength = currentPeriodSamples.coerceIn(minPeriodSamples, maxPeriodSamples)
        val grainLength = grainHalfLength * 2
        val grain = FloatArray(grainLength)

        // Tam grain lay tu lich su: k=0 la phan CU nhat cua grain (samplesAgo=grainLength, con nam
        // trong qua khu), k=grainLength-1 la phan MOI nhat (samplesAgo=1, van la qua khu gan "hien
        // tai") - KHONG bao gio can mau "tuong lai" (day chinh la ly do phai chap nhan do tre thuat
        // toan da giai thich o dau file).
        for (k in 0 until grainLength) {
            val samplesAgo = grainLength - k
            grain[k] = historySamplesAgo(samplesAgo)
        }
        return grain
    }

    /**
     * ✅ MOI (PSOLA dong bo pha THAT SU): dong ho nay CHI con nhiem vu quyet
     * dinh KHI NAO va CACH NHAU BAO XA cac grain (da duoc trich san boi dong
     * ho PHAN TICH o tren) duoc DAT vao OLA - chinh khoang cach dat lai nay
     * (synthesisHop = currentPeriodSamples/currentShiftRatio, KHAC voi
     * khoang cach trich ra la currentPeriodSamples) moi la thu thuc su tao
     * ra hieu ung dich cao do. Lay grain tu hang doi FIFO (grain CU nhat
     * truoc); neu hang doi rong (ratio>1 - dich LEN, dong ho tong hop chay
     * nhanh hon dong ho phan tich san xuat) thi TAI SU DUNG grain gan nhat
     * (lastAnalysisGrain) - dung ban chat PSOLA khi dich cao do len la phai
     * "lap lai" bot 1 vai chu ky.
     */
    private fun advanceSynthesisClockAndMaybeTriggerGrain() {
        synthesisPhase -= 1.0
        if (synthesisPhase <= 0.0) {
            val synthesisHop = currentPeriodSamples / currentShiftRatio
            synthesisPhase += synthesisHop

            val grain = if (pendingAnalysisGrains.isNotEmpty()) {
                pendingAnalysisGrains.removeFirst()
            } else {
                lastAnalysisGrain
            }
            if (grain != null) {
                lastAnalysisGrain = grain
                placeGrainInOla(grain, synthesisHop.roundToInt().coerceAtLeast(1))
            }
        }
    }

    private fun placeGrainInOla(grain: FloatArray, synthesisHopSamples: Int) {
        val grainLength = grain.size
        for (k in 0 until grainLength) {
            val window = hannWindow(k, grainLength)
            val outIdx = (olaWritePos + k) % olaCapacity
            olaBuffer[outIdx] += grain[k] * window
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

    /**
     * ✅ MOI (fail-safe V3, xem KDoc process()): duong "tat PSOLA" - dung khi
     * correctionStrength=0 hoac currentShiftRatio~1.0. Van "tien" olaReadPos
     * va don sach olaBuffer/olaWeight tai vi tri do - Y HET nhung gi
     * popOlaOutput() lam - de dam bao neu sau nay PSOLA duoc kich hoat tro
     * lai (nguoi hat lech tone/ratio!=1), vi tri do trong OLA buffer khong
     * con "rac" cua lan chay truoc (tranh tieng rac luc chuyen mode). Diem
     * khac biet duy nhat: KHONG doc/chuan hoa olaBuffer (khong co PSOLA sample
     * nao ca vi khong grain nao duoc dat vao trong mode nay), ma tra thang
     * mau mic goc tu [history], dung [processingDelaySamples] y het duong
     * PSOLA dung (qua totalSamplesWritten - 1 - totalSamplesPopped) de 2 luong
     * ra co CUNG do tre - tranh buoc nhay/click khi bat/tat correctionStrength
     * hoac khi ratio dao dong qua lai quanh 1.0 giua cac lan updatePitchAndRatio().
     */
    private fun popBypassSample(): Float {
        val idx = olaReadPos
        olaBuffer[idx] = 0f
        olaWeight[idx] = 0f
        olaReadPos = (olaReadPos + 1) % olaCapacity

        val samplesAgo = (totalSamplesWritten - 1 - totalSamplesPopped).coerceAtLeast(0L).toInt()
        val sample = historySamplesAgo(samplesAgo)
        totalSamplesPopped++
        return sample
    }

    /**
     * ✅ SUA (fail-safe, thay the hanh vi cu tra ve 0f khi olaWeight qua thap):
     * TUYET DOI KHONG duoc phep lam mic bien thanh im lang/rac chi vi PSOLA
     * chua kip tao grain hop le tai vi tri dang doc (vd ngay sau khi bat
     * enabled=true, con nam trong processingDelaySamples dau tien, hoac neu
     * co lo hong tam thoi giua 2 lan dat grain). Khi weight qua thap,
     * TRON DAN (crossfade) ve MAU MIC GOC (khong qua PSOLA) - lay tu chinh
     * [history] tai dung vi tri tuong ung voi mau dang duoc "xuat ra" o buoc
     * nay, dua theo do lech co dinh processingDelaySamples giua luc ghi vao
     * history va luc doc ra o day.
     *
     * ⚠️ SUA TIEP (fail-safe V2 - xac nhan qua test thuc te tren thiet bi):
     * ban V1 CHUYEN CUNG (hard switch) giua "100% PSOLA" va "100% mic goc"
     * ngay tai nguong weight > 1e-3f - dieu nay GIAI QUYET duoc mat tieng,
     * NHUNG lai TAO RA tieng "ret/click" MOI tai moi ranh gioi chuyen doi, vi
     * 2 nguon tin hieu (da qua PSOLA vs mic goc chua xu ly) co bien do/pha
     * khac nhau, nhay dot ngot giua chung nghe nhu noi bang bi dut quang.
     * Tren may CPU yeu (weight de roi vao vung thap thuong xuyen hon do cac
     * lan dat grain co the bi tre/nhay coc), so lan chuyen doi cang
     * nhieu, tieng ret cang ro.
     *
     * GIAI PHAP: TRON THEO TY LE (crossfade) dua tren chinh [weight] lam he
     * so tin cay, KHONG hard-switch. weight=0 -> 100% mic goc; weight >=
     * CROSSFADE_FULL_CONFIDENCE_WEIGHT -> 100% PSOLA; o giua -> pha tron
     * tuyen tinh ca 2 nguon. Ket qua: van dam bao KHONG BAO GIO im lang/rac
     * (dung tinh than fail-safe ban dau), nhung KHONG con ranh gioi cung nao
     * de gay tieng "ret/click" nua - chuyen tiep giua PSOLA va mic goc muot
     * dan, tai kho phan biet duoc diem chuyen doi.
     */
    private fun popOlaOutput(): Float {
        val idx = olaReadPos
        val weight = olaWeight[idx]

        // Mau PSOLA "thuan" (chuan hoa theo weight) - chi co y nghia khi
        // weight > 0, nhung van tinh duoc an toan (0f/0f se khong xay ra vi
        // co kiem tra o duoi).
        val psolaSample = if (weight > 1e-6f) olaBuffer[idx] / weight else 0f

        // Mau mic goc bypass - luon tinh duoc, dung lam "nen" cho crossfade
        // (xem giai thich offset o ban V1 phia tren, khong doi).
        val samplesAgo = (totalSamplesWritten - 1 - totalSamplesPopped).coerceAtLeast(0L).toInt()
        val bypassSample = historySamplesAgo(samplesAgo)

        // He so tin cay [0f, 1f]: weight cang cao (nhieu grain PSOLA chong
        // len nhau tai diem nay) cang tin PSOLA, weight thap/0 thi tin
        // hoan toan mic goc. CROSSFADE_FULL_CONFIDENCE_WEIGHT la nguong
        // weight coi nhu "PSOLA da chin" - thuong 1 grain Hann full-overlap
        // dong gop weight quanh 1.0 tai tam, nen de nguong nay hoi thap hon
        // 1.0 mot chut de crossfade khong keo dai qua muc can thiet.
        val confidence = (weight / CROSSFADE_FULL_CONFIDENCE_WEIGHT).coerceIn(0f, 1f)
        val sample = bypassSample * (1f - confidence) + psolaSample * confidence

        // Don sach o vua doc de tai su dung vong sau (day la ring buffer).
        olaBuffer[idx] = 0f
        olaWeight[idx] = 0f
        olaReadPos = (olaReadPos + 1) % olaCapacity
        totalSamplesPopped++
        return sample
    }
}