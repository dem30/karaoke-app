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

    // ✅ MOI (fix "hang doi kep cung o tran 200ms vinh vien" - xem giai
    // thich day du trong KDoc TARGET_QUEUE_SAMPLES o LowLatencyMixer): dem
    // rieng so sample bi CHU DONG bo (trimToTarget()) de dua hang doi ve
    // muc muc tieu - KHAC voi overflowCount (chi tang khi buffer da DAY
    // CUNG, tuc da qua muon). Dem nay tang som hon, ngay khi hang doi vuot
    // muc tieu (con truoc khi cham tran), giup thay ro mixer co dang phai
    // "duoi kip" backlog thuong xuyen hay khong.
    private val trimmedForLatencyCount = AtomicLong(0)

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

    /**
     * ✅ MOI: neu hang doi dang GIU NHIEU HON targetSize sample, CHU DONG
     * bo phan du thua NHAT (tien head len, khong copy ra ngoai - khac
     * drain()) de dua hang doi VE DUNG targetSize NGAY LAP TUC, thay vi
     * doi den khi buffer DAY HAN (capacity) roi moi bat dau bo tung sample
     * 1 qua push() (qua muon - luc do do tre da o muc TOI DA cua buffer).
     *
     * Goi TRUOC drain() moi vong lap mixer - bien hang doi tu "leaky
     * bucket chi tran o muc capacity" thanh "hoi tu ve 1 muc do tre muc
     * tieu on dinh", giai quyet trieu chung "hang doi leo thang roi kep
     * cung vinh vien o tran" da quan sat duoc qua log thuc te (backlog
     * phat sinh 1 lan luc khoi dong/gian doan CPU ngan, nhung KHONG BAO
     * GIO tu rut ngan lai vi drain() chi rut dung 1 CHUNK_SIZE/vong lap du
     * dang ton dong bao nhieu).
     *
     * ⚠️ SUA LOI MOI (fix "tieng ret/rẹt moi ~vai giay" - phat hien qua
     * test thuc te NGAY SAU khi bat trimToTarget: ban dau chi "nhay coc"
     * thang - noi truc tiep sample NGAY SAU diem cat vao sample NGAY TRUOC
     * diem cat, tao 1 buoc nhay bien do DOT NGOT trong dang song. Ve mat
     * am thanh, 1 buoc nhay dot ngot nhu vay nghe DUNG Y HET 1 tieng
     * click/tach - va vi trimToTarget() bi kich hoat gan nhu MOI vai giay
     * (xac nhan qua log thuc te: trimToTarget bao co so > 0 o hau het cac
     * cua so log 3s), nguoi dung nghe thanh tieng "ret ret" dinh ky.
     *
     * Sua: crossfade tuyen tinh NGAN (fadeSamples, mac dinh 128 sample =
     * ~2.9ms tai 44100Hz - du ngan de khong lam tang do tre dang ke, du
     * dai de an het buoc nhay bien do doi voi da so noi dung nhac/giong
     * hat) NGAY TAI diem cat: pha tron dan giua "duoi" cua doan SAP BI BO
     * va "dau" cua doan SE GIU LAI, ghi de KET QUA vao dung vi tri dau cua
     * doan giu lai (TRUOC khi doi head) - thay vi noi cung 2 doan lai voi
     * nhau. Ket qua nghe duoc: 1 chuyen tiep muot thay vi 1 buoc nhay dot
     * ngot.
     */
    @Synchronized
    fun trimToTarget(targetSize: Int, fadeSamples: Int = 128) {
        if (count <= targetSize) return
        val excess = count - targetSize

        // Khong crossfade nhieu hon so sample THAT SU co o ca 2 phia (doan
        // sap bo va doan giu lai) - tranh doc ra ngoai vung du lieu hop le
        // khi excess hoac targetSize qua nho (vd ngay sau start()/clear()).
        val actualFade = minOf(fadeSamples, excess, targetSize)

        if (actualFade > 0) {
            for (i in 0 until actualFade) {
                // "Duoi" cua doan SAP BI BO - tinh nguoc tu diem cat.
                val discardedIdx = (head + excess - actualFade + i) % capacity
                // "Dau" cua doan SE GIU LAI - se tro thanh sample dau tien
                // sau khi head duoc doi ben duoi.
                val retainedIdx = (head + excess + i) % capacity
                val t = (i + 1).toFloat() / (actualFade + 1).toFloat()
                val blended = buffer[discardedIdx] * (1f - t) + buffer[retainedIdx] * t
                buffer[retainedIdx] = blended.toInt().toShort()
            }
        }

        head = (head + excess) % capacity
        count -= excess
        trimmedForLatencyCount.addAndGet(excess.toLong())
    }

    @Synchronized
    fun clear() {
        head = 0
        count = 0
        overflowCount.set(0)
        trimmedForLatencyCount.set(0)
    }

    /** Lay va RESET ve 0 dem overflow tich luy tu lan doc truoc - dung cho log dinh ky (giong tinh than cac bo dem windowed khac trong PlaybackCaptureService). */
    fun drainOverflowCount(): Long = overflowCount.getAndSet(0)

    /** Lay va RESET ve 0 dem sample bi trimToTarget() chu dong bo tu lan doc truoc. */
    fun drainTrimmedForLatencyCount(): Long = trimmedForLatencyCount.getAndSet(0)
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

        // ✅ MOI (fix "hang doi kep cung o tran 200ms vinh vien" - phat hien
        // qua log thuc te SAU KHI da sua avgLoopGap ve dung ~40ms): avgLoopGap
        // dung khong con nghia la hang doi se TU rut ngan lai - drain() van
        // CHI rut dung CHUNK_SIZE/vong lap DU dang ton dong bao nhieu, nen
        // 1 lan dong dat backlog (vd luc khoi dong 3 AudioRecord/AudioTrack
        // gan nhu cung luc, hoac 1 lan thread MicInput/MusicInput bi delay
        // ngan khien driver dong lai roi tra ve dồn dap) se lam hang doi leo
        // thang va KET LUON o RING_BUFFER_CAPACITY (200ms), tu do chi con co
        // che "bo mau cu khi day" (overflowCount) giu no o muc TRAN - khong
        // bao gio tu co lai muc thap. Da xac nhan qua log: overflowDrop xay
        // ra DEU DAN moi cua so 3s tu giay thu ~9 tro di, khong phai hien
        // tuong hiem gap.
        //
        // Sua: moi vong lap, TRUOC khi drain() de mix, chu dong
        // trimToTarget() ve muc nay (2 chunk = ~80ms, dung bang muc quan
        // sat duoc luc he thong dang chay "khoe" o log dau tien) - bat ky
        // luc nao hang doi vuot muc nay, phan du duoc bo NGAY, thay vi doi
        // den khi cham RING_BUFFER_CAPACITY. RING_BUFFER_CAPACITY (200ms)
        // van giu nguyen lam luoi an toan cho cac cu giat CPU that su lon,
        // TARGET_QUEUE_SAMPLES moi la muc do tre "binh thuong" ma he thong
        // se hoi tu ve.
        private const val TARGET_QUEUE_SAMPLES = CHUNK_SIZE * 2

        const val SOURCE_LOCAL_MIC = "local_mic"

        // ✅ MOI (fix hard-clip): nguong bat dau nen mem, tinh tuyet doi tren
        // thang Short (0..32767). Xem giai thich day du trong softKnee().
        private const val SOFT_KNEE_THRESHOLD_ABS = 28000f
        private const val SOFT_KNEE_CEILING_ABS = 32767f

        // ✅ SUA (giam nguong tu 80ms xuong 60ms - xem giai thich day du o
        // KDoc mixedOutBuffer/vocalChunksReuse ben duoi): 80ms (=2x CHUNK_MS)
        // qua long, CHI bat duoc cac cu giat CPU lon (YouTube decode nang -
        // vd 94ms/121ms da thay trong log thuc te), nhung BO LOT hoan toan
        // hien tuong chu ky mixer chay THUONG TRUC ~50ms (tren muc tieu
        // 40ms nhung duoi 80ms) do cap phat heap lien tuc trong vong lap
        // (ShortArray moi + List moi moi ~40ms) gay ap luc GC nhe nhung DEU
        // DAN - chinh day la nguyen nhan khien vocal ring buffer (tran
        // 200ms) LUON nam sat/cham tran thay vi o muc thap nhu ky vong. Sau
        // khi bo cap phat trong vong lap (xem duoi), 60ms (=1.5x CHUNK_MS)
        // du "rong tay" cho jitter nho binh thuong, nhung du "chat" de vua
        // bat lai neu hien tuong nay tai xuat hien.
        //
        // ✅ MOI (chan doan tieng "ret/giat" - CPU hay khong): neu 1 vong
        // lap mixer (ly thuyet moi ~CHUNK_MS=40ms/lan) thuc te mat LAU HON
        // nguong nay de hoan tat 1 chu ky, nghia la thread mixer (du da
        // THREAD_PRIORITY_URGENT_AUDIO) van bi he thong/CPU tre lai - day la
        // BANG CHUNG TRUC TIEP cua tranh chap CPU (vd YouTube dang decode
        // video nang), khac voi hien tuong "buffer overflow" (ben san xuat
        // nhanh hon tieu thu). 80ms = gap doi CHUNK_MS, du bat thuong de
        // dang tin, du "long tay" de khong bao dong gia do jitter nho binh
        // thuong cua scheduler.
        private const val MIXER_LOOP_DELAY_WARN_THRESHOLD_MS = 60L

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
     *
     * ✅ SUA (giam do tre ~10-15ms/chu ky - xem KDoc day du o
     * MIXER_LOOP_DELAY_WARN_THRESHOLD_MS o tren): TRUOC DAY ham nay cap
     * phat 1 "ShortArray(outLength)" MOI moi lan goi (~25 lan/giay). Rieng
     * le thi khong dang ke, nhung day la 1 trong 3 diem cap phat heap lap
     * lai trong CHINH vong lap mixer (cung voi vocalChunks/vocalLens truoc
     * day - xem sua doi o start()) - cong don lai tao ap luc GC DEU DAN
     * tren thread URGENT_AUDIO, la nguyen nhan chinh khien chu ky mixer
     * thuc te ~50ms thay vi 40ms nhu thiet ke (da xac nhan qua log
     * maxLoopGap=50-55ms xay ra LIEN TUC, khong chi luc CpuJitterProbe bao
     * dong), khien vocal ring buffer (tran 200ms) LUON o gan/cham tran.
     *
     * Sua: ghi thang vao "mixedOutBuffer" (field cua instance, cap phat 1
     * LAN DUY NHAT luc khoi tao class) thay vi tao moi moi lan goi. AN
     * TOAN vi mixMultiSource() CHI duoc goi tu 1 thread duy nhat
     * (mixerDispatcher, single-thread executor) va gia tri tra ve duoc
     * outputRouter.write() tieu thu NGAY LAP TUC (dong bo, cung 1 vong
     * lap) truoc khi lan goi mixMultiSource() tiep theo co the ghi de.
     */
    private val mixedOutBuffer = ShortArray(CHUNK_SIZE)

    private fun mixMultiSource(
        music: ShortArray, musicLen: Int, musicVolumeSnapshot: Float,
        vocalChunks: List<ShortArray>, vocalLens: List<Int>,
        masterVolumeSnapshot: Float,
        outLength: Int
    ): ShortArray {
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
            mixedOutBuffer[i] = sum.toInt().toShort()
        }
        return mixedOutBuffer
    }

    private val musicBuffer = ShortRingBuffer(RING_BUFFER_CAPACITY)
    private val vocalBuffers = ConcurrentHashMap<String, ShortRingBuffer>()
    private val vocalScratchBuffers = ConcurrentHashMap<String, ShortArray>()

    // ✅ MOI (giam do tre - xem KDoc mixedOutBuffer o tren): 2 danh sach
    // TAI SU DUNG cho vocal chunks/lens moi vong lap mixer, thay vi
    // "vocalBuffers.keys.toList()" + 2 ArrayList MOI moi ~40ms nhu truoc.
    // clear() khong cap phat lai vung nho, chi dat size ve 0.
    private val vocalChunksReuse = ArrayList<ShortArray>()
    private val vocalLensReuse = ArrayList<Int>()

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

            // ✅ MOI (thay MIXER_LOOP_DELAY_WARN_THRESHOLD_MS chi bat "dinh",
            // 2 bien nay tinh CHU KY TRUNG BINH thuc te trong window 3s -
            // gia tri nay moi la thu quyet dinh vocal ring buffer day hay
            // khong, vi backlog tich luy theo TRUNG BINH chu ky, khong phai
            // theo dinh cao nhat thinh thoang xay ra).
            var sumIterationGapMsInWindow = 0L
            var iterationCountInWindow = 0

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
                sumIterationGapMsInWindow += gapExcludingWait
                iterationCountInWindow++

                // ✅ MOI (xem KDoc TARGET_QUEUE_SAMPLES o tren): chu dong bo
                // phan ton dong VUOT muc tieu TRUOC khi rut CHUNK_SIZE de mix
                // - neu khong lam buoc nay, hang doi se KHONG BAO GIO tu rut
                // ngan lai du avgLoopGap dung 40ms, vi drain() ben duoi luon
                // chi lay dung 1 CHUNK_SIZE bat ke dang ton dong bao nhieu.
                musicBuffer.trimToTarget(TARGET_QUEUE_SAMPLES)
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

                // ✅ SUA (giam do tre - xem KDoc mixedOutBuffer/vocalChunksReuse
                // o tren): KHONG con "vocalBuffers.keys.toList()" (cap phat 1
                // List moi) + 2 ArrayList moi moi vong lap - duyet TRUC TIEP
                // vocalBuffers.entries (ConcurrentHashMap duyet an toan dong
                // thoi, khong can copy snapshot ra List rieng) va ghi vao 2
                // list TAI SU DUNG, chi clear() truoc khi dung.
                vocalChunksReuse.clear()
                vocalLensReuse.clear()
                for ((sourceId, ringBuffer) in vocalBuffers) {
                    ringBuffer.trimToTarget(TARGET_QUEUE_SAMPLES)
                    val scratch = getVocalScratch(sourceId)
                    val len = ringBuffer.drain(scratch, CHUNK_SIZE)
                    vocalChunksReuse.add(scratch)
                    vocalLensReuse.add(len)
                }

                // Chup gia tri volume tai THOI DIEM mix (co the doi giua
                // chung do nguoi dung keo slider) - dung 1 gia tri nhat
                // quan cho toan bo chunk nay, tranh doc lai companion field
                // nhieu lan trong 1 vong for ben trong mixMultiSource().
                val musicVolumeSnapshot = musicVolume
                val masterVolumeSnapshot = masterVolume

                val mixed = mixMultiSource(
                    musicChunk, musicLen, musicVolumeSnapshot,
                    vocalChunksReuse, vocalLensReuse,
                    masterVolumeSnapshot,
                    CHUNK_SIZE
                )
                finalLimiter?.process(mixed, CHUNK_SIZE)
                outputRouter.write(mixed, CHUNK_SIZE)

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastQueueLogTime >= QUEUE_LOG_INTERVAL_MS) {
                    // ✅ SUA: sourceIds gio CHI duoc tinh O DAY (moi 3 giay),
                    // khong con o hot path moi ~40ms - 1 lan copy List moi
                    // 3 giay khong dang ke, khac han 1 lan moi 40ms.
                    val sourceIds = vocalBuffers.keys.toList()
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
                    // ✅ MOI: dem sample bi trimToTarget() chu dong bo (xem
                    // KDoc TARGET_QUEUE_SAMPLES) - neu con so nay > 0 thuong
                    // xuyen, nghia la backlog VAN dang phat sinh lien tuc o
                    // phia san xuat (MicInput/MusicInput), chi la gio duoc
                    // don dep SOM (o muc ~80ms) thay vi de leo tan RING_BUFFER_
                    // CAPACITY (200ms) nhu truoc - can tim tiep nguyen nhan
                    // phia san xuat neu van thay so nay cao lien tuc.
                    val musicTrimmed = musicBuffer.drainTrimmedForLatencyCount()
                    val vocalTrimmedSummary = sourceIds.joinToString(", ") { id ->
                        val trimmed = vocalBuffers[id]?.drainTrimmedForLatencyCount() ?: 0L
                        "$id=$trimmed"
                    }
                    // ✅ MOI: avgLoopGap - xem giai thich o KDoc khai bao
                    // sumIterationGapMsInWindow/iterationCountInWindow o
                    // tren. Day la so lieu QUAN TRONG NHAT de xac nhan sua
                    // co hieu qua hay khong: truoc khi sua, gia tri nay se
                    // ~50ms; neu sua dung, phai ve gan 40ms.
                    val avgLoopGapMs = if (iterationCountInWindow > 0) {
                        sumIterationGapMsInWindow / iterationCountInWindow
                    } else 0L
                    logBoth(
                        "queue M=${musicMs}ms | Vocal[$vocalSummary] (waited=${waitedMs}ms lan cuoi) " +
                            "| [ChanDoan] avgLoopGap=${avgLoopGapMs}ms maxLoopGap=${maxIterationGapMsInWindow}ms " +
                            "soLanTre(>=${MIXER_LOOP_DELAY_WARN_THRESHOLD_MS}ms)=$delayedIterationCountInWindow " +
                            "| overflowDrop: music=$musicOverflow vocal[$vocalOverflowSummary] " +
                            "| trimToTarget: music=$musicTrimmed vocal[$vocalTrimmedSummary]"
                    )
                    lastQueueLogTime = nowMs
                    maxIterationGapMsInWindow = 0L
                    delayedIterationCountInWindow = 0
                    sumIterationGapMsInWindow = 0L
                    iterationCountInWindow = 0
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