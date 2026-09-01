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
 * ✅ CAP NHAT (sua loi re/giat/rot rot phat hien qua test thuc te): thiet ke
 * BAN DAU dung dong ho co dinh (delay(20ms) roi rut DUNG CHUNK_SIZE tu ca 2
 * hang doi, doi 0 cho phan thieu) - moi lan 1 trong 2 nguon chua kip co du
 * du lieu (rat binh thuong khi 3 coroutine - MusicInput/MicInput/Mixer -
 * tranh CPU nhau tren Dispatchers.Default), phan doi 0 tao ra 1 "khoang
 * lang dot ngot" giua dong am thanh lien tuc - nghe ra dung tieng click/re.
 *
 * THIET KE MOI: vong lap KHONG con chay theo dong ho co dinh nua. Moi lan,
 * no CHO (poll voi delay ngan) den khi IT NHAT MOT trong 2 hang doi da co
 * du CHUNK_SIZE sample that su (khong gioi han ca 2 cung luc - vi 1 nguon
 * co the thuc su im lang that, khong phai lag), toi da MAX_WAIT_MS de
 * tranh treo vo han neu ca 2 nguon dung han. Nho vay gan nhu khong con phai
 * doi 0 "gia" do lech nhip CPU nua - chi doi 0 that (nguon do thuc su chua
 * co gi de gui, vi du im lang that hoac chua noi vao mic).
 */
class LowLatencyMixer(private val outputRouter: OutputRouter) {

    companion object {
        private const val TAG = "LowLatencyMixer"
        private const val SAMPLE_RATE = 44100

        // ✅ Tang tu 20ms len 40ms - cho them bien do chiu lech nhip CPU giua
        // cac coroutine, giam tan suat phai xu ly khi du lieu chua kip toi.
        private const val CHUNK_MS = 40L
        private const val CHUNK_SIZE = (SAMPLE_RATE * CHUNK_MS / 1000L).toInt()

        // Thoi gian poll ngan khi cho du lieu - can du nho de khong lam tang
        // latency dang ke, nhung du lon de khong ton CPU vo ich.
        private const val POLL_INTERVAL_MS = 3L

        // Cho toi da bao lau truoc khi danh chap nhan rut du lieu du chua du
        // CHUNK_SIZE (vd 1 nguon thuc su dung han lau, khong phai lag ngan
        // han) - tranh mixer treo vo han neu ca Music lan Vocal deu ngung.
        private const val MAX_WAIT_MS = 200L

        private const val MAX_QUEUE_SIZE = SAMPLE_RATE

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

    fun pushMusic(buffer: ShortArray, size: Int) {
        synchronized(musicLock) {
            for (i in 0 until size) musicQueue.addLast(buffer[i])
            while (musicQueue.size > MAX_QUEUE_SIZE) musicQueue.removeFirst()
        }
    }

    fun pushVocal(buffer: ShortArray, size: Int) {
        synchronized(vocalLock) {
            for (i in 0 until size) vocalQueue.addLast(buffer[i])
            while (vocalQueue.size > MAX_QUEUE_SIZE) vocalQueue.removeFirst()
        }
    }

    private fun queueSize(queue: ArrayDeque<Short>, lock: Any): Int {
        synchronized(lock) { return queue.size }
    }

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
            logBoth("✅ Bat dau mixer loop (che do cho du lieu that), chunk=$CHUNK_SIZE sample (~${CHUNK_MS}ms)")
            while (running) {
                // ✅ Cho den khi IT NHAT MOT nguon co du CHUNK_SIZE sample that
                // su, toi da MAX_WAIT_MS - xem giai thich chi tiet o dau file.
                var waitedMs = 0L
                while (running &&
                    queueSize(musicQueue, musicLock) < CHUNK_SIZE &&
                    queueSize(vocalQueue, vocalLock) < CHUNK_SIZE &&
                    waitedMs < MAX_WAIT_MS
                ) {
                    delay(POLL_INTERVAL_MS)
                    waitedMs += POLL_INTERVAL_MS
                }
                if (!running) break

                val (musicChunk, musicLen) = drain(musicQueue, musicLock, CHUNK_SIZE)
                val (vocalChunk, vocalLen) = drain(vocalQueue, vocalLock, CHUNK_SIZE)
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
        synchronized(musicLock) { musicQueue.clear() }
        synchronized(vocalLock) { vocalQueue.clear() }
        logBoth("🛑 Da dung mixer")
    }
}