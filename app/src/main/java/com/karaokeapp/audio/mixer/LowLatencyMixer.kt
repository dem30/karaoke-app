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

private class ShortRingBuffer(private val capacity: Int) {
    private val buffer = ShortArray(capacity)
    private var head = 0
    private var count = 0

    private val overflowCount = AtomicLong(0)
    private val trimmedForLatencyCount = AtomicLong(0)

    @Synchronized
    fun push(src: ShortArray, size: Int) {
        for (i in 0 until size) {
            val writeIndex = (head + count) % capacity
            buffer[writeIndex] = src[i]
            if (count < capacity) {
                count++
            } else {
                head = (head + 1) % capacity
                overflowCount.incrementAndGet()
            }
        }
    }

    @Synchronized
    fun size(): Int = count

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
     * Cat giam do tre ve targetSize. Voi buffer Stereo, fadeSamples luon la so chan
     * de kenh Trai blend voi Trai, kenh Phai blend voi Phai.
     */
    @Synchronized
    fun trimToTarget(targetSize: Int, fadeSamples: Int = 128) {
        if (count <= targetSize) return
        var excess = count - targetSize
        // Neu buffer chua so chan sample (Stereo), dam bao excess chan de khong lech kenh L/R
        if (targetSize % 2 == 0 && excess % 2 != 0) {
            excess++
            if (count < targetSize + excess) return
        }

        val actualFade = minOf(fadeSamples, excess, targetSize)

        if (actualFade > 0) {
            for (i in 0 until actualFade) {
                val discardedIdx = (head + excess - actualFade + i) % capacity
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

    fun drainOverflowCount(): Long = overflowCount.getAndSet(0)
    fun drainTrimmedForLatencyCount(): Long = trimmedForLatencyCount.getAndSet(0)
}

/**
 * Phase 3 - Tron Nhac Stereo + N nguon Vocal Mono thanh 1 output Stereo.
 *
 * ✅ NANG CAP CHAT LUONG AM THANH:
 * 1. Nhac nen giu nguyen Stereo 2 kenh L/R doc lap.
 * 2. Vocal Mono duoc cong deu vao ca 2 kenh L va R (tao center phantom image vung chac).
 * 3. Nang nguong soft-knee len 31500f (sat tran 32767), khong con nen ep som tieng nhac,
 *    giu nguyen do nay cua tieng trong (kick/snare) va bass, am thanh trong veo.
 */
class LowLatencyMixer(
    private val outputRouter: OutputRouter,
    private val finalLimiter: Limiter? = null
) {

    companion object {
        private const val TAG = "LowLatencyMixer"
        private const val SAMPLE_RATE = 44100
        private const val CHUNK_MS = 40L

        // So frame trong 40ms: 1764 frame
        private const val FRAMES_PER_CHUNK = (SAMPLE_RATE * CHUNK_MS / 1000L).toInt()
        // Nhac Stereo: 1764 frame * 2 = 3528 sample
        private const val STEREO_CHUNK_SIZE = FRAMES_PER_CHUNK * 2
        // Vocal Mono: 1764 sample
        private const val MONO_CHUNK_SIZE = FRAMES_PER_CHUNK

        private const val POLL_INTERVAL_MS = 3L
        private const val MAX_WAIT_MS = 200L
        private const val QUEUE_LOG_INTERVAL_MS = 3000L

        // Dung luong buffer rong rai (~330ms) de khong bi hard-overflow
        private const val MUSIC_RING_BUFFER_CAPACITY = (SAMPLE_RATE / 3) * 2 // Stereo
        private const val VOCAL_RING_BUFFER_CAPACITY = SAMPLE_RATE / 3       // Mono

        // Nguong buffer toi uu
        private const val LOCAL_TARGET_QUEUE_FRAMES = FRAMES_PER_CHUNK * 2  // ~80ms cho nguon local
        private const val REMOTE_TARGET_QUEUE_FRAMES = FRAMES_PER_CHUNK * 4 // ~160ms cho mic qua Wi-Fi

        const val SOURCE_LOCAL_MIC = "local_mic"

        // Nang nguong soft-knee len 31500f de giai phong headroom dong luc hoc (Punchy & Clear)
        private const val SOFT_KNEE_THRESHOLD_ABS = 31500f
        private const val SOFT_KNEE_CEILING_ABS = 32767f
        private const val MIXER_LOOP_DELAY_WARN_THRESHOLD_MS = 60L

        @JvmStatic
        @Volatile
        var musicVolume: Float = 0.7f // Tang mac dinh len 0.7 cho tieng nhac day dan hon
            set(value) { field = value.coerceIn(0f, 2f) }

        @JvmStatic
        @Volatile
        var masterVolume: Float = 1.0f
            set(value) { field = value.coerceIn(0f, 2f) }
    }

    private fun softKnee(x: Float): Float {
        val absX = kotlin.math.abs(x)
        if (absX <= SOFT_KNEE_THRESHOLD_ABS) return x
        val over = absX - SOFT_KNEE_THRESHOLD_ABS
        val range = SOFT_KNEE_CEILING_ABS - SOFT_KNEE_THRESHOLD_ABS
        val compressed = SOFT_KNEE_THRESHOLD_ABS + range * kotlin.math.tanh(over / range)
        return if (x < 0f) -compressed else compressed
    }

    // Buffer Stereo dau ra tai su dung (3528 samples = 1764 frames L/R)
    private val mixedOutBuffer = ShortArray(STEREO_CHUNK_SIZE)

    /**
     * Hoa tron: Nhac Stereo (L/R) + Cac nguon Vocal Mono (cong vao ca 2 tai).
     */
    private fun mixMultiSource(
        music: ShortArray, musicLen: Int, musicVolumeSnapshot: Float,
        vocalChunks: List<ShortArray>, vocalLens: List<Int>,
        masterVolumeSnapshot: Float,
        framesCount: Int
    ): ShortArray {
        for (f in 0 until framesCount) {
            val stereoIdx = f * 2
            val mLeft = if (stereoIdx < musicLen) music[stereoIdx] * musicVolumeSnapshot else 0f
            val mRight = if (stereoIdx + 1 < musicLen) music[stereoIdx + 1] * musicVolumeSnapshot else 0f

            // Cong don tat ca cac giong hat Mono vao vocalSum
            var vocalSum = 0f
            for (srcIdx in vocalChunks.indices) {
                val len = vocalLens[srcIdx]
                if (f < len) {
                    vocalSum += vocalChunks[srcIdx][f].toFloat()
                }
            }

            // Giong hat duoc phan bo deu ca 2 kenh Trai va Phai
            var leftSum = (mLeft + vocalSum) * masterVolumeSnapshot
            var rightSum = (mRight + vocalSum) * masterVolumeSnapshot

            leftSum = softKnee(leftSum).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            rightSum = softKnee(rightSum).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())

            mixedOutBuffer[stereoIdx] = leftSum.toInt().toShort()
            mixedOutBuffer[stereoIdx + 1] = rightSum.toInt().toShort()
        }
        return mixedOutBuffer
    }

    private val musicBuffer = ShortRingBuffer(MUSIC_RING_BUFFER_CAPACITY)
    private val vocalBuffers = ConcurrentHashMap<String, ShortRingBuffer>()
    private val vocalScratchBuffers = ConcurrentHashMap<String, ShortArray>()

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
        return vocalBuffers.getOrPut(sourceId) { ShortRingBuffer(VOCAL_RING_BUFFER_CAPACITY) }
    }

    private fun getVocalScratch(sourceId: String): ShortArray {
        return vocalScratchBuffers.getOrPut(sourceId) { ShortArray(MONO_CHUNK_SIZE) }
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
            logBoth("✅ Bat dau Mixer Loop (STEREO MUSIC + MONO VOCAL), chunk=$STEREO_CHUNK_SIZE samples ($FRAMES_PER_CHUNK frames L/R)")
            val musicChunk = ShortArray(STEREO_CHUNK_SIZE)

            var lastQueueLogTime = System.currentTimeMillis()
            var wasMusicSilentAtMixer = false
            var lastIterationStartNanoTime = System.nanoTime()
            var maxIterationGapMsInWindow = 0L
            var delayedIterationCountInWindow = 0
            var sumIterationGapMsInWindow = 0L
            var iterationCountInWindow = 0

            while (running) {
                var waitedMs = 0L

                // Cho den khi du 1 chunk Stereo
                while (running && musicBuffer.size() < STEREO_CHUNK_SIZE && waitedMs < MAX_WAIT_MS) {
                    delay(POLL_INTERVAL_MS)
                    waitedMs += POLL_INTERVAL_MS
                }
                if (!running) break

                val now = System.nanoTime()
                val iterationGapMs = (now - lastIterationStartNanoTime) / 1_000_000L
                lastIterationStartNanoTime = now
                val gapExcludingWait = iterationGapMs - waitedMs
                if (gapExcludingWait >= MIXER_LOOP_DELAY_WARN_THRESHOLD_MS) {
                    delayedIterationCountInWindow++
                }
                if (gapExcludingWait > maxIterationGapMsInWindow) maxIterationGapMsInWindow = gapExcludingWait
                sumIterationGapMsInWindow += gapExcludingWait
                iterationCountInWindow++

                // Trim nhac Stereo ve target (80ms = LOCAL_TARGET_QUEUE_FRAMES * 2 samples)
                musicBuffer.trimToTarget(LOCAL_TARGET_QUEUE_FRAMES * 2, fadeSamples = 256)
                val musicLen = musicBuffer.drain(musicChunk, STEREO_CHUNK_SIZE)

                var musicAbs = 0L
                for (i in 0 until musicLen) {
                    musicAbs += kotlin.math.abs(musicChunk[i].toInt())
                }
                val musicAvg = if (musicLen > 0) musicAbs / musicLen else 0L
                val musicSilentNow = musicLen == 0 || musicAvg == 0L
                if (musicSilentNow && !wasMusicSilentAtMixer) {
                    logBoth("⚠️ MIXER MUSIC SILENCE: musicLen=$musicLen/$STEREO_CHUNK_SIZE waited=${waitedMs}ms")
                } else if (!musicSilentNow && wasMusicSilentAtMixer) {
                    logBoth("🔄 MIXER MUSIC RECOVERED: musicLen=$musicLen/$STEREO_CHUNK_SIZE waited=${waitedMs}ms")
                }
                wasMusicSilentAtMixer = musicSilentNow

                // Drain tung kenh vocal Mono
                vocalChunksReuse.clear()
                vocalLensReuse.clear()
                for ((sourceId, ringBuffer) in vocalBuffers) {
                    val targetFrames = if (sourceId == SOURCE_LOCAL_MIC) {
                        LOCAL_TARGET_QUEUE_FRAMES
                    } else {
                        REMOTE_TARGET_QUEUE_FRAMES
                    }
                    ringBuffer.trimToTarget(targetFrames, fadeSamples = 128)
                    val scratch = getVocalScratch(sourceId)
                    val len = ringBuffer.drain(scratch, MONO_CHUNK_SIZE)
                    vocalChunksReuse.add(scratch)
                    vocalLensReuse.add(len)
                }

                val musicVolumeSnapshot = musicVolume
                val masterVolumeSnapshot = masterVolume

                // Hoa tron thanh Stereo
                val mixed = mixMultiSource(
                    musicChunk, musicLen, musicVolumeSnapshot,
                    vocalChunksReuse, vocalLensReuse,
                    masterVolumeSnapshot,
                    FRAMES_PER_CHUNK
                )

                finalLimiter?.process(mixed, STEREO_CHUNK_SIZE)
                // Ghi truc tiep mang Stereo ra AudioTrack
                outputRouter.write(mixed, STEREO_CHUNK_SIZE)

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastQueueLogTime >= QUEUE_LOG_INTERVAL_MS) {
                    val sourceIds = vocalBuffers.keys.toList()
                    // Nhac Stereo: chia 2 de ra so frame tuong ung
                    val musicMs = (musicBuffer.size() / 2) * 1000L / SAMPLE_RATE
                    val vocalSummary = sourceIds.joinToString(", ") { id ->
                        val ms = (vocalBuffers[id]?.size() ?: 0) * 1000L / SAMPLE_RATE
                        "$id=${ms}ms"
                    }
                    val musicOverflow = musicBuffer.drainOverflowCount()
                    val vocalOverflowSummary = sourceIds.joinToString(", ") { id ->
                        val dropped = vocalBuffers[id]?.drainOverflowCount() ?: 0L
                        "$id=$dropped"
                    }
                    val musicTrimmed = musicBuffer.drainTrimmedForLatencyCount()
                    val vocalTrimmedSummary = sourceIds.joinToString(", ") { id ->
                        val trimmed = vocalBuffers[id]?.drainTrimmedForLatencyCount() ?: 0L
                        "$id=$trimmed"
                    }
                    val avgLoopGapMs = if (iterationCountInWindow > 0) sumIterationGapMsInWindow / iterationCountInWindow else 0L

                    logBoth(
                        "queue Stereo M=${musicMs}ms | Vocal[$vocalSummary] (waited=${waitedMs}ms) " +
                            "| [ChanDoan] avgGap=${avgLoopGapMs}ms maxGap=${maxIterationGapMsInWindow}ms " +
                            "| overflow: M=$musicOverflow V[$vocalOverflowSummary] | trim: M=$musicTrimmed V[$vocalTrimmedSummary]"
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