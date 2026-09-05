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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Ring buffer PCM don gian, dung ShortArray nguyen thuy (KHONG dung
 * ArrayDeque<Short> - moi phan tu se bi "boxing" thanh 1 object rieng tren
 * heap, cuc ky ton CPU/GC voi ~44100 sample/giay). Khi day (overflow), tu
 * dong bo mau CU NHAT de nhuong cho mau moi - uu tien phat am thanh moi
 * nhat thay vi de do tre phinh to vo han khi ben san xuat nhanh hon ben
 * tieu thu.
 *
 * ✅ MOI (chan doan tieng "ret/giat" - xem giai thich day du trong
 * LowLatencyMixer.start()): them dem overflowCount - moi lan buffer DAY va
 * phai bo 1 sample cu de nhuong cho sample moi (nhanh "else" trong push()),
 * dem tang 1. Neu dem nay tang LIEN TUC voi toc do cao trong luc dang
 * capture/mixer chay binh thuong (khong phai luc moi bat dau/dang cho du
 * du lieu), day la BANG CHUNG CU THE rang ben SAN XUAT (MicInput/MusicInput)
 * dang nhanh hon ben TIEU THU (mixer loop) mot cach CO HE THONG - khac voi
 * truong hop mixer loop bi CPU gianh mat (xem mixerLoopDelay ben duoi).
 */
private class ShortRingBuffer(private val capacity: Int) {
    private val buffer = ShortArray(capacity)
    private var head = 0
    private var count = 0

    // ✅ MOI: AtomicLong (khong can @Synchronized rieng, doc/ghi doc lap voi
    // lock cua push()/drain() - chi dung de CHAN DOAN, khong anh huong logic
    // audio chinh).
    private val overflowCount = AtomicLong(0)

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
                overflowCount.incrementAndGet()
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
        overflowCount.set(0)
    }

    /** Lay va RESET ve 0 dem overflow tich luy tu lan doc truoc - dung cho log dinh ky (giong tinh than cac bo dem windowed khac trong PlaybackCaptureService). */
    fun drainOverflowCount(): Long = overflowCount.getAndSet(0)
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
 * ✅ CAP NHAT (fix hard-clip - soft-knee limiter thay coerceIn() cung trong
 * mixMultiSource(), xem KDoc ham softKnee() o duoi).
 *
 * ✅ CAP NHAT MOI (fix "ban mixer fly mat gia tri musicVolume/masterVolume
 * moi lan bam play/pause de kich hoat lai am thanh"): PHIEN BAN TRUOC
 * musicVolume/masterVolume la 2 property CUA INSTANCE (var musicVolume: ...
 * ngay trong class body) - moi lan startMixerTestInternal() trong
 * PlaybackCaptureService.kt chay (bao gom CA LUC TU DONG trong chuoi
 * "Kich hoat lai" khi FocusObserver/nut noi kich hoat, KHONG chi khi nguoi
 * dung tu tay bam "Bat/Tat Mixer Test"), 1 object LowLatencyMixer HOAN TOAN
 * MOI duoc tao (val mix = LowLatencyMixer(router, ...)), khien 2 gia tri
 * nay bi RESET VE DEFAULT - khac han vocalChannels (Volume/EQ rieng cua
 * tung mic), von duoc luu trong 1 ConcurrentHashMap o COMPANION OBJECT cua
 * PlaybackCaptureService nen KHONG bi mat khi LowLatencyMixer bi tao lai.
 * Day la ly do nguoi dung thay Volume/EQ tung kenh mic thi con, nhung
 * "Nhac nen"/"Tong the" (master) thi bi ve lai mac dinh moi lan bam play/
 * pause tren YouTube (kich hoat chuoi Reactivation tu dong tat/bat lai
 * Mixer Test).
 *
 * Sua: chuyen musicVolume/masterVolume tu INSTANCE property sang COMPANION
 * (static) property - giong dung tinh than vocalChannels: gia tri song o
 * cap "toan cuc" (mot lan set la giu mai, khong phu thuoc instance nao dang
 * chay), khong con bi reset khi 1 LowLatencyMixer moi duoc tao ra. Noi goi
 * (PlaybackCaptureService.setMusicVolume/getMusicVolume/setMasterVolume/
 * getMasterVolume) cung duoc doi tu "activeMixerInstance?.musicVolume = ..."
 * (chi co tac dung khi mixer DANG chay) sang "LowLatencyMixer.musicVolume =
 * ..." (luon co tac dung, ke ca khi mixer dang TAT - gia tri se duoc ap
 * dung ngay khi mixer bat lai, khong can cho "dang chay" moi set duoc).
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

        const val SOURCE_LOCAL_MIC = "local_mic"

        // ✅ MOI (fix hard-clip): nguong bat dau nen mem, tinh tuyet doi tren
        // thang Short (0..32767). Xem giai thich day du trong softKnee().
        private const val SOFT_KNEE_THRESHOLD_ABS = 28000f
        private const val SOFT_KNEE_CEILING_ABS = 32767f

        // ✅ MOI (chan doan tieng "ret/giat" - CPU hay khong): neu 1 vong
        // lap mixer (ly thuyet moi ~CHUNK_MS=40ms/lan) thuc te mat LAU HON
        // nguong nay de hoan tat 1 chu ky, nghia la thread mixer (du da
        // THREAD_PRIORITY_URGENT_AUDIO) van bi he thong/CPU tre lai - day la
        // BANG CHUNG TRUC TIEP cua tranh chap CPU (vd YouTube dang decode
        // video nang), khac voi hien tuong "buffer overflow" (ben san xuat
        // nhanh hon tieu thu). 80ms = gap doi CHUNK_MS, du bat thuong de
        // dang tin, du "long tay" de khong bao dong gia do jitter nho binh
        // thuong cua scheduler.
        private const val MIXER_LOOP_DELAY_WARN_THRESHOLD_MS = 80L

        // ✅ SUA (fix mat gia tri khi Mixer Test bi tat/bat lai tu dong - xem
        // giai thich day du o KDoc dau class): chuyen tu INSTANCE property
        // sang COMPANION (static) property - "song" doc lap voi tung
        // instance LowLatencyMixer, giong dung tinh than vocalChannels o
        // PlaybackCaptureService. 2 gia tri nay gio la CUA CHUNG TOAN APP
        // (chi co 1 Mixer Test chay tai 1 thoi diem), khong phai cua rieng
        // 1 instance. QUAN TRONG: phai khai bao THUC SU BEN TRONG companion
        // object nay (khong phai ngay sau dau "}" dong companion) thi
        // @JvmStatic moi hop le va gia tri moi thuc su la static.
        @JvmStatic
        @Volatile
        var musicVolume: Float = 0.4f
            set(value) { field = value.coerceIn(0f, 2f) }

        @JvmStatic
        @Volatile
        var masterVolume: Float = 1.0f
            set(value) { field = value.coerceIn(0f, 2f) }
    }

    /**
     * ✅ MOI: nen mem 1 sample (dang Float, CHUA chuyen ve Short) - gia
     * tri trong khoang [-SOFT_KNEE_THRESHOLD_ABS, +SOFT_KNEE_THRESHOLD_ABS]
     * duoc GIU NGUYEN 100%. Chi PHAN VUOT nguong moi bi nen dan theo tanh()
     * sao cho tien dan ve SOFT_KNEE_CEILING_ABS thay vi bi CAT THANG.
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
     * ✅ SUA (fix hard-clip): khong con coerceIn() cung ngay sau khi cong
     * don vocal, cung khong con coerceIn() cung ngay sau khi cong voi
     * music - CA HAI gia tri trung gian duoc giu Float, CHI nen mem 1 LAN
     * DUY NHAT (qua softKnee()) ngay truoc khi chuyen ve Short.
     */
    private fun mixMultiSource(
        music: ShortArray, musicLen: Int, musicVolumeSnapshot: Float,
        vocalChunks: List<ShortArray>, vocalLens: List<Int>,
        masterVolumeSnapshot: Float,
        outLength: Int
    ): ShortArray {
        val out = ShortArray(outLength)
        for (i in 0 until outLength) {
            val m = if (i < musicLen) music[i] * musicVolumeSnapshot else 0f

            var vocalSum = 0f
            for (srcIdx in vocalChunks.indices) {
                val len = vocalLens[srcIdx]
                if (i < len) {
                    vocalSum += vocalChunks[srcIdx][i].toFloat()
                }
            }

            var sum = (m + vocalSum) * masterVolumeSnapshot
            sum = softKnee(sum)
            // Luoi an toan CUOI CUNG (chan wraparound Float->Short thuan so
            // hoc) - BAT BUOC giu lai, xem giai thich o KDoc softKnee().
            sum = sum.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            out[i] = sum.toInt().toShort()
        }
        return out
    }

    private val musicBuffer = ShortRingBuffer(RING_BUFFER_CAPACITY)
    private val vocalBuffers = ConcurrentHashMap<String, ShortRingBuffer>()
    private val vocalScratchBuffers = ConcurrentHashMap<String, ShortArray>()

    private var mixerJob: Job? = null

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

    fun pushVocal(sourceId: String, buffer: ShortArray, size: Int) {
        getOrCreateVocalBuffer(sourceId).push(buffer, size)
    }

    private fun getOrCreateVocalBuffer(sourceId: String): ShortRingBuffer {
        return vocalBuffers.getOrPut(sourceId) { ShortRingBuffer(RING_BUFFER_CAPACITY) }
    }

    private fun getVocalScratch(sourceId: String): ShortArray {
        return vocalScratchBuffers.getOrPut(sourceId) { ShortArray(CHUNK_SIZE) }
    }

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
            logBoth(
                "✅ Bat dau mixer loop (soft-knee limiter, musicVolume/masterVolume " +
                    "GIU NGUYEN qua cac lan bat/tat), chunk=$CHUNK_SIZE sample (~${CHUNK_MS}ms), " +
                    "tran buffer=~${RING_BUFFER_CAPACITY}sample/nguon"
            )
            val musicChunk = ShortArray(CHUNK_SIZE)

            var lastQueueLogTime = System.currentTimeMillis()
            var wasMusicSilentAtMixer = false

            // ✅ MOI (chan doan CPU vs buffer overflow - xem giai thich day
            // du o KDoc hang so MIXER_LOOP_DELAY_WARN_THRESHOLD_MS): moc
            // thoi gian BAT DAU cua chu ky truoc, dung de tinh khoang cach
            // THUC TE giua 2 lan ghi ra OutputRouter lien tiep.
            var lastIterationStartNanoTime = System.nanoTime()
            var maxIterationGapMsInWindow = 0L
            var delayedIterationCountInWindow = 0

            while (running) {
                var waitedMs = 0L

                while (running && musicBuffer.size() < CHUNK_SIZE && waitedMs < MAX_WAIT_MS) {
                    delay(POLL_INTERVAL_MS)
                    waitedMs += POLL_INTERVAL_MS
                }
                if (!running) break

                // ✅ MOI: do khoang cach THUC TE ke tu lan bat dau chu ky
                // truoc - neu con so nay vuot han CHUNK_MS (~40ms) MA
                // KHONG PHAI do waitedMs (cho musicBuffer du du lieu, da
                // tru rieng), nghia la ban than mixer THREAD bi he thong
                // tre lai (CPU scheduling) - khac voi truong hop buffer
                // overflow (ben san xuat nhanh hon, xem overflowCount).
                val now = System.nanoTime()
                val iterationGapMs = (now - lastIterationStartNanoTime) / 1_000_000L
                lastIterationStartNanoTime = now
                val gapExcludingWait = iterationGapMs - waitedMs
                if (gapExcludingWait >= MIXER_LOOP_DELAY_WARN_THRESHOLD_MS) {
                    delayedIterationCountInWindow++
                    logBoth(
                        "⚠️ [CpuJitterProbe] Chu ky mixer bi TRE ${gapExcludingWait}ms so voi ky vong " +
                            "(~${CHUNK_MS}ms, DA TRU thoi gian cho musicBuffer=${waitedMs}ms) - " +
                            "nghi van CPU/scheduler dang gianh thread mixer (vd YouTube dang decode nang)."
                    )
                }
                if (gapExcludingWait > maxIterationGapMsInWindow) maxIterationGapMsInWindow = gapExcludingWait

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

                // Chup gia tri volume tai THOI DIEM mix (co the doi giua
                // chung do nguoi dung keo slider) - dung 1 gia tri nhat
                // quan cho toan bo chunk nay, tranh doc lai companion field
                // nhieu lan trong 1 vong for ben trong mixMultiSource().
                val musicVolumeSnapshot = musicVolume
                val masterVolumeSnapshot = masterVolume

                val mixed = mixMultiSource(
                    musicChunk, musicLen, musicVolumeSnapshot,
                    vocalChunks, vocalLens,
                    masterVolumeSnapshot,
                    CHUNK_SIZE
                )
                finalLimiter?.process(mixed, CHUNK_SIZE)
                outputRouter.write(mixed, CHUNK_SIZE)

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastQueueLogTime >= QUEUE_LOG_INTERVAL_MS) {
                    val musicMs = musicBuffer.size() * 1000L / SAMPLE_RATE
                    val vocalSummary = sourceIds.joinToString(", ") { id ->
                        val ms = (vocalBuffers[id]?.size() ?: 0) * 1000L / SAMPLE_RATE
                        "$id=${ms}ms"
                    }
                    // ✅ MOI: gop them so lieu chan doan CPU/overflow vao
                    // dung 1 dong log dinh ky co san (khong them dong log
                    // rieng, tranh spam/tran CaptureLogBus.MAX_LINES).
                    val musicOverflow = musicBuffer.drainOverflowCount()
                    val vocalOverflowSummary = sourceIds.joinToString(", ") { id ->
                        val dropped = vocalBuffers[id]?.drainOverflowCount() ?: 0L
                        "$id=$dropped"
                    }
                    logBoth(
                        "queue M=${musicMs}ms | Vocal[$vocalSummary] (waited=${waitedMs}ms lan cuoi) " +
                            "| [ChanDoan] maxLoopGap=${maxIterationGapMsInWindow}ms " +
                            "soLanTre(>=${MIXER_LOOP_DELAY_WARN_THRESHOLD_MS}ms)=$delayedIterationCountInWindow " +
                            "| overflowDrop: music=$musicOverflow vocal[$vocalOverflowSummary]"
                    )
                    lastQueueLogTime = nowMs
                    maxIterationGapMsInWindow = 0L
                    delayedIterationCountInWindow = 0
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
        mixerDispatcher.close()
        logBoth("🛑 Da dung mixer")
    }
}