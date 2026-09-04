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
 * Phase 3 - tron 2 nguon PCM (Music + Vocal) thanh 1 output stream.
 *
 * ✅ CAP NHAT (sua loi "echo/lech 1 giay" phat hien qua test thuc te): 2
 * hang doi truoc day dung ArrayDeque<Short> (boxing object, cham) va gioi
 * han toi da 1 GIAY moi hang doi - khi xu ly cham hon du lieu den (rat de
 * xay ra tren may bi gioi han hieu nang nen nhu Honor), du lieu don ung dan
 * toi tran 1 giay, nghe ra dung nhu "2 nguon lech nhau ~1 giay". Doi sang
 * ShortRingBuffer (mang nguyen thuy, khong boxing) VA giam tran xuong con
 * ~200ms - neu co tut lai, do tre toi da cung chi ~200ms thay vi ca giay.
 */
class LowLatencyMixer(
    private val outputRouter: OutputRouter,
    /**
     * ✅ MOI (Phase 4 - phan con lai): Limiter TUY CHON, ap dung cho MIX
     * TONG (nhac + vocal) NGAY SAU khi cong 2 nguon, TRUOC khi ghi ra
     * OutputRouter. Doc lap voi Limiter dang chay tren rieng vocal (o
     * PlaybackCaptureService.kt, TRUOC khi vao day) - vi tri nay chan
     * truong hop nhac + vocal (da qua EQ/Compressor/Echo, co the co gain
     * cao hon tin hieu goc) cong lai vuot nguong DU tung nguon rieng le van
     * on, dung y PLAN.md goc ("Limiter cuoi chuoi"). null = tat (mac dinh,
     * giu tuong thich nguoc cho code cu/test cu khong truyen tham so nay).
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

        // ✅ MOI (giam log du thua): tang tu 1000ms -> 3000ms, dong bo voi
        // MusicInput/MicInput - van du day do phan giai theo doi do sau
        // queue, giam 3 lan so dong log.
        private const val QUEUE_LOG_INTERVAL_MS = 3000L

        // ✅ Giam tu SAMPLE_RATE (1000ms) xuong ~200ms - chan do tre buffer
        // phinh to qua muc chap nhan duoc neu co tut lai tam thoi.
        private const val RING_BUFFER_CAPACITY = SAMPLE_RATE / 5

        // ⚠️ CAP NHAT QUAN TRONG (AN TOAN - fix "mic hu" phat hien qua test
        // thuc te): VOCAL_GAIN=4.0 truoc day da vo tinh day do loi cua VONG
        // LAP PHAN HOI AM THANH (feedback loop: loa phat -> mic mo bat lai
        // -> nhan gain -> mix lai -> loa phat to hon -> mic bat to hon...)
        // VUOT QUA nguong tu kich hoat hu - da xac nhan qua log thuc te
        // (amplitude mic tang von: 2500 -> 6000 -> 7000 -> 8000 roi giu o
        // muc cao, dung dac trung vong lap phan hoi). Day la GIOI HAN VAT LY
        // cua viec dung loa ngoai + mic mo CUNG LUC ma chua co AEC (Acoustic
        // Echo Cancellation) hay Limiter (du kien Phase 4) - GIAM gain o day
        // chi giam RUI RO (giam do loi vong lap), KHONG loai bo hoan toan
        // nguy co hu neu am luong loa dat qua cao hoac mic qua gan loa.
        //
        // ✅ KHUYEN NGHI BAT BUOC trong luc test Phase 3 (cho toi khi co
        // AEC/Limiter that): DUNG TAI NGHE (co day, cam vao may) thay vi loa
        // ngoai - tai nghe cach ly hoan toan duong phat khoi mic, loai bo
        // hoan toan nguy co hu, khong phu thuoc gain bao nhieu.
        //
        // Giam tu 4.0 xuong 1.8 - van con boost vocal (khong ve lai 1:1 qua
        // nho), nhung giam dang ke do loi vong lap so voi 4.0. Van CAN nguoi
        // dung tu tinh chinh tiep tuy thiet bi/khoang cach mic-loa thuc te.
        private const val VOCAL_GAIN = 1.8f

        fun mix(music: ShortArray, musicLen: Int, vocal: ShortArray, vocalLen: Int, outLength: Int): ShortArray {
            val out = ShortArray(outLength)
            for (i in 0 until outLength) {
                val m = if (i < musicLen) music[i].toInt() else 0
                // ✅ Ap VOCAL_GAIN truoc khi cong - clamp RIENG truoc, tranh
                // truong hop gain lam vocal tu no da vuot Short.MAX_VALUE
                // truoc ca khi cong voi music (se lam sai lech phep clamp
                // tong sau do).
                val vRaw = if (i < vocalLen) vocal[i].toInt() else 0
                var vBoosted = (vRaw * VOCAL_GAIN).toInt()
                if (vBoosted > Short.MAX_VALUE) vBoosted = Short.MAX_VALUE.toInt()
                if (vBoosted < Short.MIN_VALUE) vBoosted = Short.MIN_VALUE.toInt()

                var sum = m + vBoosted
                if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE.toInt()
                if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE.toInt()
                out[i] = sum.toShort()
            }
            return out
        }
    }

    private val musicBuffer = ShortRingBuffer(RING_BUFFER_CAPACITY)
    private val vocalBuffer = ShortRingBuffer(RING_BUFFER_CAPACITY)

    private var mixerJob: Job? = null

    // ✅ MOI (fix chung goc voi MusicInput/MicInput - xem giai thich chi
    // tiet trong MusicInput.kt): vong lap mixer (drain 2 ring buffer + ghi
    // ra OutputRouter moi ~40ms) cung la duong real-time, cung can thread
    // rieng uu tien THREAD_PRIORITY_URGENT_AUDIO thay vi Dispatchers.Default
    // dung chung, de khong bi tre khi app khac (YouTube) dang chiem CPU o
    // foreground.
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

    fun pushVocal(buffer: ShortArray, size: Int) {
        vocalBuffer.push(buffer, size)
    }

    fun start() {
        if (running) {
            logBoth("⚠️ Mixer da chay roi, bo qua start() thua.")
            return
        }
        running = true
        musicBuffer.clear()
        vocalBuffer.clear()

        mixerJob = scope.launch {
            logBoth("✅ Bat dau mixer loop (ring buffer, khong boxing), chunk=$CHUNK_SIZE sample (~${CHUNK_MS}ms), tran buffer=~${RING_BUFFER_CAPACITY}sample")
            val musicChunk = ShortArray(CHUNK_SIZE)
            val vocalChunk = ShortArray(CHUNK_SIZE)

            var lastQueueLogTime = System.currentTimeMillis()

            // ✅ MOI (chan doan chuoi dieu tra MusicInput -> ring buffer ->
            // Mixer -> OutputRouter): chi log khi CHUYEN TRANG THAI im lang
            // <-> co am thanh CUA RIENG PHAN MUSIC tai Mixer, doc lap voi
            // log CAPTURE SILENCE/RECOVERED cua MusicInput.kt. Neu MusicInput
            // KHONG bao silence nhung o day van thay MIXER MUSIC SILENCE,
            // nghia la PCM bi roi giua duong (ring buffer/scheduling), khong
            // phai loi capture/AudioPlaybackCapture.
            var wasMusicSilentAtMixer = false

            while (running) {
                var waitedMs = 0L
                // ✅ SUA LOI (dieu kien cho sai): truoc day dung "&&" giua 2
                // buffer, nghia la vong lap CHI tiep tuc cho khi CA HAI deu
                // chua du 1 chunk - hau qua la no THOAT NGAY khi CHI 1 ben du,
                // roi drain ben con lai du thieu (bi zero-fill), gay hut/lech
                // tieng. Doi sang "||": tiep tuc cho khi CON IT NHAT 1 ben
                // CHUA du, tuc la chi thoat khi CA HAI da du (hoac het
                // MAX_WAIT_MS de tranh treo mixer vinh vien neu 1 nguon chet).
                while (running &&
                    (musicBuffer.size() < CHUNK_SIZE || vocalBuffer.size() < CHUNK_SIZE) &&
                    waitedMs < MAX_WAIT_MS
                ) {
                    delay(POLL_INTERVAL_MS)
                    waitedMs += POLL_INTERVAL_MS
                }
                if (!running) break

                val musicLen = musicBuffer.drain(musicChunk, CHUNK_SIZE)
                val vocalLen = vocalBuffer.drain(vocalChunk, CHUNK_SIZE)

                // ✅ MOI: do bien do trung binh cua rieng phan Music vua drain
                // duoc, TRUOC khi mix - day la bang chung quyet dinh de biet
                // PCM co toi duoc Mixer hay khong (xem giai thich o khai bao
                // wasMusicSilentAtMixer o tren).
                var musicAbs = 0L
                for (i in 0 until musicLen) {
                    musicAbs += kotlin.math.abs(musicChunk[i].toInt())
                }
                val musicAvg = if (musicLen > 0) musicAbs / musicLen else 0L
                val musicSilentNow = musicLen == 0 || musicAvg == 0L
                if (musicSilentNow && !wasMusicSilentAtMixer) {
                    logBoth(
                        "⚠️ MIXER MUSIC SILENCE: musicLen=$musicLen/$CHUNK_SIZE musicAvg=$musicAvg " +
                            "vocalLen=$vocalLen queueM=${musicBuffer.size()} queueV=${vocalBuffer.size()} " +
                            "waited=${waitedMs}ms"
                    )
                } else if (!musicSilentNow && wasMusicSilentAtMixer) {
                    logBoth(
                        "🔄 MIXER MUSIC RECOVERED: musicLen=$musicLen/$CHUNK_SIZE musicAvg=$musicAvg " +
                            "vocalLen=$vocalLen queueM=${musicBuffer.size()} queueV=${vocalBuffer.size()} " +
                            "waited=${waitedMs}ms"
                    )
                }
                wasMusicSilentAtMixer = musicSilentNow

                val mixed = mix(musicChunk, musicLen, vocalChunk, vocalLen, CHUNK_SIZE)
                // ✅ MOI (Phase 4 - phan con lai): chan clipping tren tin
                // hieu DA MIX, sau khi vocal da qua EQ/Compressor/Echo (co
                // the co gain cao hon dau vao goc) - xem giai thich day du o
                // khai bao finalLimiter phia tren.
                finalLimiter?.process(mixed, CHUNK_SIZE)
                outputRouter.write(mixed, CHUNK_SIZE)

                // ✅ MOI: log dinh ky (~1 lan/giay) do sau (tinh theo ms) cua
                // ca 2 hang doi, giup phan biet "lech dong bo nhat thoi" (queue
                // dao dong quanh 20-60ms) voi "producer/consumer lech toc do
                // lien tuc" (1 ben tang dan toi gan RING_BUFFER_CAPACITY).
                val now = System.currentTimeMillis()
                if (now - lastQueueLogTime >= QUEUE_LOG_INTERVAL_MS) {
                    val musicMs = musicBuffer.size() * 1000L / SAMPLE_RATE
                    val vocalMs = vocalBuffer.size() * 1000L / SAMPLE_RATE
                    logBoth("queue M=${musicMs}ms V=${vocalMs}ms (waited=${waitedMs}ms lan cuoi)")
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
        vocalBuffer.clear()
        // ✅ MOI: dong thread rieng, tranh ro ri (xem giai thich trong
        // MusicInput.stopCapture()). mixerJob dang o trong delay()
        // (cancellable) nen cancel() se ngat vong lap gan nhu ngay lap tuc,
        // an toan de dong dispatcher tiep theo.
        mixerDispatcher.close()
        logBoth("🛑 Da dung mixer")
    }
}