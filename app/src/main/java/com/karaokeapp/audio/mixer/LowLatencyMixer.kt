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
 * Phase 3 - tron 2 nguon PCM (Music + Vocal) thanh 1 output stream.
 *
 * Thiet ke: MusicInput va MicInput chay tren 2 coroutine doc lap, moi ben
 * KHONG goi truc tiep vao nhau - thay vao do day PCM vao 1 hang doi rieng
 * (pushMusic/pushVocal, thread-safe qua synchronized). 1 coroutine mixer
 * rieng chay theo NHIP CO DINH (~20ms/lan), rut ra dung so luong sample can
 * thiet tu MOI hang doi (dem 0 - "silence" - neu hang doi chua co du du
 * lieu, tranh mixer bi khoa cho 1 nguon cham hon nguon kia), cong lai qua
 * mix(), roi ghi ra OutputRouter.
 *
 * Day la thiet ke DON GIAN cho Phase 3 (dung nguyen tac "khong toi uu som"
 * cua PLAN.md) - dung delay() co dinh thay vi dong bo chinh xac theo
 * AudioTrack, co the co jitter nho. Neu Phase 4/5 phat hien desync ro ret,
 * day la noi dau tien can quay lai cai thien.
 */
class LowLatencyMixer(private val outputRouter: OutputRouter) {

    companion object {
        private const val TAG = "LowLatencyMixer"
        private const val SAMPLE_RATE = 44100
        private const val CHUNK_MS = 20L

        // 20ms tai 44100Hz = 882 sample - kich thuoc 1 lan rut/mix/ghi.
        private const val CHUNK_SIZE = (SAMPLE_RATE * CHUNK_MS / 1000L).toInt()

        // Gioi han do dai toi da moi hang doi (1 giay) - tranh phinh to vo han
        // neu 1 nguon dung cung cap du lieu lau (vd YouTube bi pause) trong
        // khi nguon kia van chay binh thuong.
        private const val MAX_QUEUE_SIZE = SAMPLE_RATE

        /**
         * Cong 2 mang PCM theo tung sample, clamp ve khoang Short hop le de
         * tranh clipping/tran so khi cong 2 gia tri lon.
         */
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

    private val musicQueue = ArrayDeque<Short>()
    private val vocalQueue = ArrayDeque<Short>()
    private val musicLock = Any()
    private val vocalLock = Any()

    private var mixerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var running = false

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[LowLatencyMixer] $msg")
    }

    /** Goi tu callback PCM cua MusicInput. */
    fun pushMusic(buffer: ShortArray, size: Int) {
        synchronized(musicLock) {
            for (i in 0 until size) musicQueue.addLast(buffer[i])
            while (musicQueue.size > MAX_QUEUE_SIZE) musicQueue.removeFirst()
        }
    }

    /** Goi tu callback PCM cua MicInput. */
    fun pushVocal(buffer: ShortArray, size: Int) {
        synchronized(vocalLock) {
            for (i in 0 until size) vocalQueue.addLast(buffer[i])
            while (vocalQueue.size > MAX_QUEUE_SIZE) vocalQueue.removeFirst()
        }
    }

    /** Rut toi da "count" sample tu 1 hang doi, dem 0 cho phan con thieu. Tra ve (mang, soLuongThatSuLayDuoc). */
    private fun drain(queue: ArrayDeque<Short>, lock: Any, count: Int): Pair<ShortArray, Int> {
        synchronized(lock) {
            val available = min(count, queue.size)
            val out = ShortArray(count)
            for (i in 0 until available) {
                out[i] = queue.removeFirst()
            }
            return out to available
        }
    }

    fun start() {
        if (running) {
            logBoth("⚠️ Mixer da chay roi, bo qua start() thua.")
            return
        }
        running = true
        synchronized(musicLock) { musicQueue.clear() }
        synchronized(vocalLock) { vocalQueue.clear() }

        mixerJob = scope.launch {
            logBoth("✅ Bat dau mixer loop, chunk=$CHUNK_SIZE sample (~${CHUNK_MS}ms/lan)")
            while (running) {
                val (musicChunk, musicLen) = drain(musicQueue, musicLock, CHUNK_SIZE)
                val (vocalChunk, vocalLen) = drain(vocalQueue, vocalLock, CHUNK_SIZE)
                val mixed = mix(musicChunk, musicLen, vocalChunk, vocalLen, CHUNK_SIZE)
                outputRouter.write(mixed, CHUNK_SIZE)
                delay(CHUNK_MS)
            }
            logBoth("Mixer loop da dung.")
        }
    }

    fun stop() {
        running = false
        mixerJob?.cancel()
        mixerJob = null
        synchronized(musicLock) { musicQueue.clear() }
        synchronized(vocalLock) { vocalQueue.clear() }
        logBoth("🛑 Da dung mixer")
    }
}