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
     * ✅ CO CHE HYSTERESIS (VUNG DEM THO):
     * Chi cat bo khi dung luong thuc su vuot nguong tran (threshold).
     * Khi khong vuot nguong, KHONG CAT BAT KY MAU NAO, giup am nhac chay muot ma 100%,
     * khong bi nhay coc hay giat nhip dinh ky.
     */
    @Synchronized
    fun trimIfExceeds(threshold: Int, targetSize: Int, fadeSamples: Int = 128) {
        if (count <= threshold) return
        var excess = count - targetSize
        if (excess <= 0) return

        // Voi du lieu Stereo, dam bao excess luon chan de khong bi dao lon kenh L va R
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
 */
class LowLatencyMixer(
    private val outputRouter: OutputRouter,
    private val finalLimiter: Limiter? = null
) {

    companion object {
        private const val TAG = "LowLatencyMixer"
        private const val SAMPLE_RATE = 44100
        private const val CHUNK_MS = 40L

        // 40ms o 44.1kHz = 1764 frames
        private const val FRAMES_PER_CHUNK = (SAMPLE_RATE * CHUNK_MS / 1000L).toInt()
        // Nhac Stereo: 1764 frames * 2 = 3528 samples
        private const val STEREO_CHUNK_SIZE = FRAMES_PER_CHUNK * 2
        // Vocal Mono: 1764 samples
        private const val MONO_CHUNK_SIZE = FRAMES_PER_CHUNK

        private const val POLL_INTERVAL_MS = 3L
        private const val MAX_WAIT_MS = 200L
        private const val QUEUE_LOG_INTERVAL_MS = 3000L

        // Buffer rong rai (~330ms) de khong bi overflow cung
        private const val MUSIC_RING_BUFFER_CAPACITY = (SAMPLE_RATE / 3) * 2 // Stereo
        private const val VOCAL_RING_BUFFER_CAPACITY = SAMPLE_RATE / 3       // Mono

        const val SOURCE_LOCAL_MIC = "local_mic"

        // Nang nguong soft-knee len 31500f de giu nguyen tieng bass/trong trong treo, khong bi nen dep
        private const val SOFT_KNEE_THRESHOLD_ABS = 31500f
        private const val SOFT_KNEE_CEILING_ABS = 32767f
        private const val MIXER_LOOP_DELAY_WARN_THRESHOLD_MS = 60L

        @JvmStatic
        @Volatile
        var musicVolume: Float = 0.7f
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

    private val mixedOutBuffer = ShortArray(STEREO_CHUNK_SIZE)

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

            var vocalSum = 0f
            for (srcIdx in vocalChunks.indices) {
                val len = vocalLens[srcIdx]
                if (f < len) {
                    vocalSum += vocalChunks[srcIdx][f].toFloat()
                }
            }

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
            logBoth("✅ Bat dau Mixer Loop (HYSTERESIS BUFFER - STEREO MUSIC), chunk=$STEREO_CHUNK_SIZE samples")
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

                // ✅ AP DUNG HYSTERESIS CHO NHAC:
                // Nguong tran = 160ms (4 chunks = 14112 samples). Neu khong vuot 160ms, KHONG CAT BO MAU NAO!
                // Neu vuot qua 160ms (do lag/freeze that su), dua em ve 120ms (10584 samples).
                musicBuffer.trimIfExceeds(
                    threshold = STEREO_CHUNK_SIZE * 4,
                    targetSize = STEREO_CHUNK_SIZE * 3,
                    fadeSamples = 256
                )
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

                // ✅ AP DUNG HYSTERESIS CHO VOCAL:
                vocalChunksReuse.clear()
                vocalLensReuse.clear()
                for ((sourceId, ringBuffer) in vocalBuffers) {
                    if (sourceId == SOURCE_LOCAL_MIC) {
                        // Mic local: nguong tran 160ms (4 chunks = 7056 samples), target 120ms
                        ringBuffer.trimIfExceeds(
                            threshold = MONO_CHUNK_SIZE * 4,
                            targetSize = MONO_CHUNK_SIZE * 3,
                            fadeSamples = 128
                        )
                    } else {
                        // Mic remote (qua Wi-Fi): nguong tran 240ms (6 chunks = 10584 samples), target 200ms
                        ringBuffer.trimIfExceeds(
                            threshold = MONO_CHUNK_SIZE * 6,
                            targetSize = MONO_CHUNK_SIZE * 5,
                            fadeSamples = 128
                        )
                    }
                    val scratch = getVocalScratch(sourceId)
                    val len = ringBuffer.drain(scratch, MONO_CHUNK_SIZE)
                    vocalChunksReuse.add(scratch)
                    vocalLensReuse.add(len)
                }

                val musicVolumeSnapshot = musicVolume
                val masterVolumeSnapshot = masterVolume

                val mixed = mixMultiSource(
                    musicChunk, musicLen, musicVolumeSnapshot,
                    vocalChunksReuse, vocalLensReuse,
                    masterVolumeSnapshot,
                    FRAMES_PER_CHUNK
                )

                finalLimiter?.process(mixed, STEREO_CHUNK_SIZE)
                outputRouter.write(mixed, STEREO_CHUNK_SIZE)

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastQueueLogTime >= QUEUE_LOG_INTERVAL_MS) {
                    val sourceIds = vocalBuffers.keys.toList()
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