package com.karaokeapp.audio.mixer

import android.util.Log
import com.karaokeapp.audio.music.CaptureLogBus
import com.karaokeapp.audio.output.OutputRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
class LowLatencyMixer(private val outputRouter: OutputRouter) {

    companion object {
        private const val TAG = "LowLatencyMixer"
        private const val SAMPLE_RATE = 44100

        private const val CHUNK_MS = 40L
        private const val CHUNK_SIZE = (SAMPLE_RATE * CHUNK_MS / 1000L).toInt()

        private const val POLL_INTERVAL_MS = 3L
        private const val MAX_WAIT_MS = 200L

        // ✅ Giam tu SAMPLE_RATE (1000ms) xuong ~200ms - chan do tre buffer
        // phinh to qua muc chap nhan duoc neu co tut lai tam thoi.
        private const val RING_BUFFER_CAPACITY = SAMPLE_RATE / 5

        fun mix(music: ShortArray, musicLen: Int, vocal: ShortArray, vocalLen: Int, outLength: Int): ShortArray {
            val out = ShortArray(outLength)
            for (i in 0 until outLength) {
                val m = if (i < musicLen) music[i].toInt() else 0
                val v = if (i < vocalLen) vocal[i].toInt() else 0
                var sum = m + v
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
    private val scope = CoroutineScope(Dispatchers.Default)

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

            while (running) {
                var waitedMs = 0L
                while (running && musicBuffer.size() < CHUNK_SIZE && vocalBuffer.size() < CHUNK_SIZE && waitedMs < MAX_WAIT_MS) {
                    delay(POLL_INTERVAL_MS)
                    waitedMs += POLL_INTERVAL_MS
                }
                if (!running) break

                val musicLen = musicBuffer.drain(musicChunk, CHUNK_SIZE)
                val vocalLen = vocalBuffer.drain(vocalChunk, CHUNK_SIZE)
                val mixed = mix(musicChunk, musicLen, vocalChunk, vocalLen, CHUNK_SIZE)
                outputRouter.write(mixed, CHUNK_SIZE)
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
        logBoth("🛑 Da dung mixer")
    }
}