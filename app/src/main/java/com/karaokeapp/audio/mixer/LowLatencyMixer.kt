package com.karaokeapp.audio.mixer

import android.os.Process
import android.util.Log
import com.karaokeapp.audio.music.CaptureLogBus
import com.karaokeapp.audio.output.OutputRouter
import com.karaokeapp.audio.processor.Limiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Ring buffer PCM don gian, dung ShortArray nguyen thuy (KHONG dung
 * ArrayDeque<Short> - moi phan tu se bi "boxing" thanh 1 object rieng tren
 * heap, cuc ky ton CPU/GC voi ~44100 sample/giay). Khi day (overflow), tu
 * dong bo mau CU NHAT de nhuong cho mau moi - uu tien phat am thanh moi
 * nhat thay vi de do tre phinh to vo han khi ben san xuat nhanh hon ben
 * tieu thu.
 */
private class ShortRingBuffer(private val capacity: Int) {
    private val buffer = ShortArray(capacity)
    private var head = 0
    private var count = 0

    @Synchronized
    fun push(src: ShortArray, size: Int) {
        for (i in 0 until size) {
            val writeIndex = (head + count) % capacity
            buffer[writeIndex] = src[i]
            if (count < capacity) {
                count++
            } else {
                // Da day - bo mau cu nhat (tien head len 1) de nhuong cho mau moi.
                head = (head + 1) % capacity
            }
        }
    }

    @Synchronized
    fun size(): Int = count

    /** Rut toi da "requestCount" sample vao dest (tu index 0). Tra ve so luong THAT SU lay duoc. */
    @Synchronized
    fun drain(dest: ShortArray, requestCount: Int): Int {
        val available = min(requestCount, count)
        for (i in 0 until available) {
            dest[i] = buffer[(head + i) % capacity]
        }
        head = (head + available) % capacity
        count -= available
        return available
    }

    @Synchronized
    fun clear() {
        head = 0
        count = 0
    }
}

/**
 * Phase 3 (+ Phase 5 - ho tro song ca nhieu may) - tron nhieu nguon PCM
 * (Music + N nguon Vocal) thanh 1 output stream.
 *
 * ✅ CAP NHAT LON (fix "tieng ret/giat khi 2+ may cung hat qua Phong Karaoke
 * LAN"): PHIEN BAN TRUOC dung 1 vocalBuffer DUY NHAT dung CHUNG cho moi
 * nguon vocal (mic vat ly tai cho + tung May B/C qua WebRTC). Khi co tu 2
 * nguon tro len cung push() vao 1 hang doi FIFO chung, cac chunk PCM ~40ms
 * cua tung nguon bi XEP NOI DUOI NHAU (interleave) thay vi duoc CONG DON -
 * nghia la mixer phat luan phien tung mieng vun cua nguon A roi B roi C...
 * chu KHONG hoa quyen thanh 1 ban song ca thuc su. Day chinh la nguyen nhan
 * goc cua tieng "ret/giat" khi test voi 2+ may.
 *
 * Sua: MOI nguon vocal (dinh danh boi sourceId - xem SOURCE_LOCAL_MIC cho
 * mic vat ly, hoac clientId cua tung May B/C) co 1 ShortRingBuffer RIENG.
 * Moi chu ky mixer (~40ms), TUNG nguon duoc drain() rieng, roi CONG DON lai
 * (co clamp) - giong 1 ban mixer vat ly co nhieu kenh fader, khong phai 1
 * hang doi chung.
 *
 * ✅ CAP NHAT MOI (fix "vocal bi CAT VUONG (hard-clip) khi nhieu nguon cung
 * to, nghe gat/be trong khi da co finalLimiter"): PHIEN BAN TRUOC dung
 * coerceIn() CUNG 2 lan trong mixMultiSource() - 1 lan ngay sau khi cong don
 * TAT CA nguon vocal, 1 lan nua sau khi cong voi nhac va nhan masterVolume.
 * coerceIn() la CAT VUONG (hard clip): bat ky gia tri nao vuot bien do PCM
 * bi CHAT THANG ve dung +-32767, mat hoan toan hinh dang waveform o phan
 * vuot do - xay ra TRUOC CA khi finalLimiter (chay SAU mixMultiSource(), xem
 * start()) kip xu ly, nen finalLimiter khong the "cuu" lai duoc gi (du lieu
 * da mat that su, khong phai chi bi giam gain). Trieu chung thuc te: khi 2-3
 * nguon vocal cung to, hoac vocal+nhac cung dinh dinh, am thanh nghe gat/be/
 * "loa qua tai" ngay ca khi tung nguon rieng le nghe binh thuong.
 *
 * Sua: thay 2 lan coerceIn() do bang 1 buoc "soft-knee" (xem ham softKnee()
 * o duoi) NGAY TRUOC buoc chuyen Float -> Short cuoi cung. Gia tri duoi
 * nguong SOFT_KNEE_THRESHOLD_ABS giu NGUYEN 100% (khong dong toi, khac voi
 * han che cua tanh() ap dung tren toan bo tin hieu - se lam "min" ca doan
 * nho khong can thiet). Chi phan VUOT nguong moi bi nen mem dan ve gan
 * SOFT_KNEE_CEILING_ABS bang tanh(), thay vi bi CAT THANG. coerceIn() cuoi
 * cung (sau softKnee()) CHI con vai tro luoi an toan chan wraparound so hoc
 * khi Float -> Short (vd 40000f.toInt().toShort() se KHONG tu clamp ve
 * 32767 nhu nhieu nguoi lam, ma wrap thanh 1 gia tri AM/rac - day la ly do
 * TUYET DOI khong duoc bo qua buoc clamp cuoi nay du softKnee() da xu ly
 * gan het truong hop thuc te).
 */
class LowLatencyMixer(
    private val outputRouter: OutputRouter,
    /**
     * Limiter TUY CHON, ap dung cho MIX TONG (nhac + TAT CA vocal da cong
     * dong) NGAY SAU khi mix, TRUOC khi ghi ra OutputRouter. Doc lap voi
     * Limiter rieng cua tung nguon vocal (chay o PlaybackCaptureService.kt,
     * hoac Limiter rieng cho tung nguon remote - xem
     * PlaybackCaptureService.vocalChannels - moi kenh co Limiter an toan
     * rieng cua no, xem VocalChannel.kt). null = tat (mac dinh).
     *
     * ✅ Van giu nguyen vai tro sau khi them softKnee() - finalLimiter la
     * bo loc CO TRANG THAI (attack/release, tha dan theo thoi gian, xem
     * Limiter.kt) chay SAU softKnee(), con softKnee() KHONG co trang thai
     * (memoryless, xu ly tung sample doc lap). 2 lop nay bo tro nhau: softKnee
     * dam bao KHONG mat waveform ngay tai buoc cong don (truoc day la diem
     * yeu nhat), finalLimiter tiep tuc lam muot dong bien do qua thoi gian
     * (tranh "bom/pumping" neu vuot nguong lien tuc nhieu buffer).
     */
    private val finalLimiter: Limiter? = null
) {

    companion object {
        private const val TAG = "LowLatencyMixer"
        private const val SAMPLE_RATE = 44100

        private const val CHUNK_MS = 40L
        private const val CHUNK_SIZE = (SAMPLE_RATE * CHUNK_MS / 1000L).toInt()

        private const val POLL_INTERVAL_MS = 3L
        private const val MAX_WAIT_MS = 200L

        private const val QUEUE_LOG_INTERVAL_MS = 3000L

        // ✅ Giam tu SAMPLE_RATE (1000ms) xuong ~200ms - chan do tre buffer
        // phinh to qua muc chap nhan duoc neu co tut lai tam thoi.
        private const val RING_BUFFER_CAPACITY = SAMPLE_RATE / 5

        // ✅ SUA (Phase 6 - bo VOCAL_GAIN co dinh): truoc day moi nguon vocal
        // bi nhan CUNG 1 he so 1.8x co dinh o day, KHONG the tuy chinh -
        // day chinh la 1 phan nguyen nhan HowlGuard (da bi go bo) phai phan
        // ung qua nhay: gain vao mixer luon bi day cao san. Gio MOI nguon
        // tu quyet dinh gain cua rieng no qua VocalChannel.volume (nguoi
        // dung dieu chinh duoc qua UI) TRUOC KHI push() vao day - Mixer o
        // day chi CONG DON cac nguon DA duoc gain san, khong nhan them gi
        // nua (xem mixMultiSource()).
        //
        // ✅ MOI: id co dinh cho nguon vocal cua MIC VAT LY tren chinh May A
        // (Mixer) - phan biet voi id cua tung May B/C (dung chinh clientId
        // cua chung, xem WebRtcManager/SignalingServer). Dat 1 hang so o day
        // de PlaybackCaptureService khong phai tu bia 1 chuoi rieng, tranh
        // go nham/lech chinh ta giua 2 noi goi.
        const val SOURCE_LOCAL_MIC = "local_mic"

        // ✅ MOI (fix hard-clip - xem giai thich day du o KDoc dau class):
        // nguong bat dau nen mem, tinh tuyet doi tren thang Short (0..32767).
        // ~28000 tuong duong -1.4dBFS (20*log10(28000/32767) ~= -1.37dB) -
        // dat mot chut duoi Short.MAX_VALUE de con "khoang dem" cho soft-knee
        // lam viec (neu dat sat 32767 qua, phan nen se qua gap/gan nhu van
        // la hard-clip). Dung tinh than voi Limiter.kt (thresholdRatio mac
        // dinh 0.85f = ~27852) va OutputRouter (khong doi gi o day, chi de
        // nhat quan y tuong "de lai margin an toan").
        private const val SOFT_KNEE_THRESHOLD_ABS = 28000f
        private const val SOFT_KNEE_CEILING_ABS = 32767f

        /**
         * ✅ MOI: nen mem 1 sample (dang Float, CHUA chuyen ve Short) - gia
         * tri trong khoang [-SOFT_KNEE_THRESHOLD_ABS, +SOFT_KNEE_THRESHOLD_ABS]
         * duoc GIU NGUYEN 100% (khong dung toi limiter/nen gi ca - khac voi
         * cach dung tanh() tren TOAN BO tin hieu, se lam "min" ca doan nho
         * khong can thiet). Chi PHAN VUOT nguong (|x| > threshold) moi bi
         * nen dan theo tanh() sao cho tien dan ve SOFT_KNEE_CEILING_ABS thay
         * vi bi CAT THANG - giup dinh song van "tron", khong bi vuong goc
         * cung nhu coerceIn() truoc day.
         *
         * Ham nay KHONG co trang thai (memoryless) - khac voi Limiter.kt (co
         * attack/release qua thoi gian) - dung o day chi de xu ly TUC THOI
         * ngay tai buoc cong don, con finalLimiter (Limiter.kt, co trang
         * thai) van chay SAU o start() nhu cu de xu ly muot hon qua nhieu
         * sample/buffer lien tiep.
         */
        private fun softKnee(x: Float): Float {
            val absX = kotlin.math.abs(x)
            if (absX <= SOFT_KNEE_THRESHOLD_ABS) return x
            val over = absX - SOFT_KNEE_THRESHOLD_ABS
            val range = SOFT_KNEE_CEILING_ABS - SOFT_KNEE_THRESHOLD_ABS
            val compressed = SOFT_KNEE_THRESHOLD_ABS + range * kotlin.math.tanh(over / range)
            return if (x < 0f) -compressed else compressed
        }

        /**
         * ✅ SUA (fix hard-clip, xem KDoc dau class de biet day du boi canh):
         * khong con dung coerceIn() CUNG ngay sau khi cong don vocal, cung
         * khong con coerceIn() CUNG ngay sau khi cong voi music - CA HAI gia
         * tri trung gian nay gio duoc GIU NGUYEN dang Float (co the vuot
         * +-32767 tam thoi, hoan toan binh thuong va co chu dich), CHI nen
         * mem 1 LAN DUY NHAT (qua softKnee()) ngay truoc khi chuyen ve Short
         * o cuoi ham - dam bao waveform khong bi "cat cut" som truoc khi co
         * co hoi qua buoc nen mem.
         *
         * coerceIn() cuoi cung (sau softKnee()) la BAT BUOC PHAI CO, KHONG
         * duoc bo qua: day KHONG phai buoc "lam viec" chinh (softKnee() da
         * dam bao gia tri gan nhu luon nam trong [-32767, 32767] roi), ma la
         * luoi an toan chan wraparound so hoc thuan tuy khi ep kieu Float ->
         * Short trong Kotlin/JVM (toShort() KHONG tu dong saturate/clamp -
         * mot gia tri nhu 40000f.toInt().toShort() se cho ra 1 con so AM/rac
         * do tran bit, KHONG phai 32767 nhu nhieu nguoi lam tuong).
         */
        private fun mixMultiSource(
            music: ShortArray, musicLen: Int, musicVolume: Float,
            vocalChunks: List<ShortArray>, vocalLens: List<Int>,
            masterVolume: Float,
            outLength: Int
        ): ShortArray {
            val out = ShortArray(outLength)
            for (i in 0 until outLength) {
                val m = if (i < musicLen) music[i] * musicVolume else 0f

                var vocalSum = 0f
                for (srcIdx in vocalChunks.indices) {
                    val len = vocalLens[srcIdx]
                    if (i < len) {
                        vocalSum += vocalChunks[srcIdx][i].toFloat()
                    }
                }
                // ✅ SUA: KHONG con coerceIn() cung o day nua - de vocalSum (co
                // the tam thoi vuot bien do neu 2-3 nguon cung to) di tiep
                // xuong buoc softKnee() ben duoi cung voi music, thay vi bi
                // CAT VUONG rieng le som (mat thong tin waveform truoc ca khi
                // biet tong the co thuc su vuot nguong hay khong).

                var sum = (m + vocalSum) * masterVolume
                // ✅ SUA: thay coerceIn() cung bang softKnee() - xem giai
                // thich day du o KDoc ham softKnee() va dau class.
                sum = softKnee(sum)
                // Luoi an toan CUOI CUNG (chan wraparound Float->Short thuan
                // so hoc) - BAT BUOC giu lai, xem giai thich o KDoc ham nay.
                sum = sum.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                out[i] = sum.toInt().toShort()
            }
            return out
        }
    }

    // ✅ MOI (Phase 6 - "ban mixer" dieu chinh duoc): he so am luong rieng
    // cho nhac nen va cho TOAN BO ban mix cuoi (master) - nguoi dung dieu
    // chinh qua UI (vd 2 thanh truot "Nhac" va "Tong"), doc lap voi volume
    // rieng cua tung nguon vocal (xem VocalChannel.volume).
    @Volatile
    var musicVolume: Float = 0.4f
        set(value) { field = value.coerceIn(0f, 2f) }

    @Volatile
    var masterVolume: Float = 1.0f
        set(value) { field = value.coerceIn(0f, 2f) }

    private val musicBuffer = ShortRingBuffer(RING_BUFFER_CAPACITY)

    // ✅ SUA (fix goc "tieng ret/giat khi 2+ may cung hat"): truoc day CHI 1
    // vocalBuffer DUY NHAT dung chung cho moi nguon. Gio moi nguon co 1
    // ShortRingBuffer RIENG, dinh danh boi sourceId. ConcurrentHashMap vi
    // nguon co the duoc them/bo BAT KY LUC NAO tu thread khac (WebRTC
    // DataChannel callback add/remove client) trong khi mixer loop dang doc
    // o thread rieng cua no (mixerDispatcher).
    private val vocalBuffers = ConcurrentHashMap<String, ShortRingBuffer>()

    // Scratch buffer TAI SU DUNG cho tung nguon (tranh cap phat ShortArray
    // moi moi vong lap ~40ms/lan) - chi tao MOI khi gap sourceId lan dau,
    // don khi nguon bi go (removeVocalSource()).
    private val vocalScratchBuffers = ConcurrentHashMap<String, ShortArray>()

    private var mixerJob: Job? = null

    // ✅ Vong lap mixer (drain nhieu ring buffer + ghi ra OutputRouter moi
    // ~40ms) la duong real-time, can thread rieng uu tien
    // THREAD_PRIORITY_URGENT_AUDIO thay vi Dispatchers.Default dung chung,
    // de khong bi tre khi app khac (YouTube) dang chiem CPU o foreground.
    private val mixerDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        object : Thread(runnable, "LowLatencyMixerLoop") {
            override fun run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                super.run()
            }
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(mixerDispatcher)

    @Volatile
    private var running = false

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[LowLatencyMixer] $msg")
    }

    fun pushMusic(buffer: ShortArray, size: Int) {
        musicBuffer.push(buffer, size)
    }

    /**
     * ✅ SUA: gio BAT BUOC truyen sourceId - dung SOURCE_LOCAL_MIC cho mic
     * vat ly cua chinh May A, hoac clientId cua tung May B/C (Phase 5) cho
     * mic khong day. Nguon MOI (sourceId chua tung thay) se tu dong duoc
     * tao ring buffer rieng qua getOrCreateVocalBuffer() - khong can dang
     * ky truoc, chi can goi pushVocal() lan dau la du.
     */
    fun pushVocal(sourceId: String, buffer: ShortArray, size: Int) {
        getOrCreateVocalBuffer(sourceId).push(buffer, size)
    }

    private fun getOrCreateVocalBuffer(sourceId: String): ShortRingBuffer {
        return vocalBuffers.getOrPut(sourceId) { ShortRingBuffer(RING_BUFFER_CAPACITY) }
    }

    private fun getVocalScratch(sourceId: String): ShortArray {
        return vocalScratchBuffers.getOrPut(sourceId) { ShortArray(CHUNK_SIZE) }
    }

    /**
     * ✅ MOI: go 1 nguon vocal khoi mixer - goi khi May B/C ngat ket noi
     * (SignalingServer.Listener.onMicDisconnected, xem MainActivity.kt) hoac
     * khi mic tai cho bi khoa hoan toan. An toan goi voi sourceId khong ton
     * tai (ConcurrentHashMap.remove tra null, khong crash).
     */
    fun removeVocalSource(sourceId: String) {
        vocalBuffers.remove(sourceId)
        vocalScratchBuffers.remove(sourceId)
        logBoth("Da go nguon vocal '$sourceId' khoi mixer.")
    }

    fun start() {
        if (running) {
            logBoth("⚠️ Mixer da chay roi, bo qua start() thua.")
            return
        }
        running = true
        musicBuffer.clear()
        vocalBuffers.clear()
        vocalScratchBuffers.clear()

        mixerJob = scope.launch {
            logBoth("✅ Bat dau mixer loop (nhieu nguon vocal cong dong, soft-knee limiter), chunk=$CHUNK_SIZE sample (~${CHUNK_MS}ms), tran buffer=~${RING_BUFFER_CAPACITY}sample/nguon")
            val musicChunk = ShortArray(CHUNK_SIZE)

            var lastQueueLogTime = System.currentTimeMillis()
            var wasMusicSilentAtMixer = false

            while (running) {
                var waitedMs = 0L

                // ✅ SUA (ho tro nhieu nguon vocal dong thoi): CHI cho theo
                // musicBuffer (nguon LUON on dinh, tu MusicInput chay lien
                // tuc) - KHONG con cho theo 1 vocalBuffer DUY NHAT nhu ban
                // truoc. Ly do: gio co THE co nhieu nguon vocal cung luc
                // (mic tai cho + May B + May C), moi nguon push doc lap voi
                // toc do rieng. Neu van cho TAT CA nguon vocal deu du 1
                // chunk moi chay tiep, 1 nguon bi cham/dut mang tam thoi (vi
                // du May C mat song WiFi 1 nhip) se lam TREO CA mixer, cat
                // oan tieng luon ca May B dang hat binh thuong - khong chap
                // nhan duoc cho kich ban song ca. Nguon vocal thieu du lieu
                // se duoc drain() tra ve it hon CHUNK_SIZE va tu dong duoc
                // coi la "0" (zero-fill) trong mixMultiSource().
                while (running && musicBuffer.size() < CHUNK_SIZE && waitedMs < MAX_WAIT_MS) {
                    delay(POLL_INTERVAL_MS)
                    waitedMs += POLL_INTERVAL_MS
                }
                if (!running) break

                val musicLen = musicBuffer.drain(musicChunk, CHUNK_SIZE)

                var musicAbs = 0L
                for (i in 0 until musicLen) {
                    musicAbs += kotlin.math.abs(musicChunk[i].toInt())
                }
                val musicAvg = if (musicLen > 0) musicAbs / musicLen else 0L
                val musicSilentNow = musicLen == 0 || musicAvg == 0L
                if (musicSilentNow && !wasMusicSilentAtMixer) {
                    logBoth(
                        "⚠️ MIXER MUSIC SILENCE: musicLen=$musicLen/$CHUNK_SIZE musicAvg=$musicAvg " +
                            "waited=${waitedMs}ms"
                    )
                } else if (!musicSilentNow && wasMusicSilentAtMixer) {
                    logBoth(
                        "🔄 MIXER MUSIC RECOVERED: musicLen=$musicLen/$CHUNK_SIZE musicAvg=$musicAvg " +
                            "waited=${waitedMs}ms"
                    )
                }
                wasMusicSilentAtMixer = musicSilentNow

                // ✅ MOI: chup lai (snapshot) danh sach sourceId HIEN TAI -
                // co the doi giua cac vong lap do May B/C connect/disconnect
                // song song voi vong lap mixer nay. Drain TUNG nguon vao
                // scratch RIENG cua no truoc khi cong dong trong mixMultiSource().
                val sourceIds = vocalBuffers.keys.toList()
                val vocalChunks = ArrayList<ShortArray>(sourceIds.size)
                val vocalLens = ArrayList<Int>(sourceIds.size)
                for (sourceId in sourceIds) {
                    val ringBuffer = vocalBuffers[sourceId] ?: continue
                    val scratch = getVocalScratch(sourceId)
                    val len = ringBuffer.drain(scratch, CHUNK_SIZE)
                    vocalChunks.add(scratch)
                    vocalLens.add(len)
                }

                val mixed = mixMultiSource(
                    musicChunk, musicLen, musicVolume,
                    vocalChunks, vocalLens,
                    masterVolume,
                    CHUNK_SIZE
                )
                // Chan clipping tren tin hieu DA MIX (nhac + TAT CA vocal da
                // cong dong) - vi tri cuoi chuoi, dung y PLAN.md goc. Van giu
                // nguyen sau khi them softKnee() ben trong mixMultiSource() -
                // xem giai thich vai tro bo tro cua 2 lop nay o KDoc truong
                // finalLimiter phia tren.
                finalLimiter?.process(mixed, CHUNK_SIZE)
                outputRouter.write(mixed, CHUNK_SIZE)

                val now = System.currentTimeMillis()
                if (now - lastQueueLogTime >= QUEUE_LOG_INTERVAL_MS) {
                    val musicMs = musicBuffer.size() * 1000L / SAMPLE_RATE
                    val vocalSummary = sourceIds.joinToString(", ") { id ->
                        val ms = (vocalBuffers[id]?.size() ?: 0) * 1000L / SAMPLE_RATE
                        "$id=${ms}ms"
                    }
                    logBoth("queue M=${musicMs}ms | Vocal[$vocalSummary] (waited=${waitedMs}ms lan cuoi)")
                    lastQueueLogTime = now
                }
            }
            logBoth("Mixer loop da dung.")
        }
    }

    fun stop() {
        running = false
        mixerJob?.cancel()
        mixerJob = null
        musicBuffer.clear()
        vocalBuffers.clear()
        vocalScratchBuffers.clear()
        // ✅ mixerJob dang o trong delay() (cancellable) nen cancel() se ngat
        // vong lap gan nhu ngay lap tuc, an toan de dong dispatcher tiep theo.
        mixerDispatcher.close()
        logBoth("🛑 Da dung mixer")
    }
}