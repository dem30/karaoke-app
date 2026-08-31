package com.karaokeapp.audio.output

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.karaokeapp.audio.music.CaptureLogBus

/**
 * Phase 2 - ban toi gian, CHUA co mixer (xem PLAN.md muc "Phase 2"): nhan
 * thang PCM tu MicInput va ghi ra AudioTrack o che do streaming, chi de nghe
 * duoc tieng minh noi qua loa/tai nghe NGAY LAP TUC phuc vu do latency.
 *
 * Phase 3 se doi OutputRouter de nhan input DA MIX (Music + Vocal) thay vi
 * thang tu MicInput - interface write(buffer, size) giu nguyen, chi doi noi
 * goi no tu MicInput truc tiep sang tu LowLatencyMixer.
 */
class OutputRouter {

    private var audioTrack: AudioTrack? = null

    companion object {
        private const val TAG = "OutputRouter"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[OutputRouter] $msg")
    }

    fun start() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            logBoth("❌ AudioTrack.getMinBufferSize khong hop le: $minBufferSize", isError = true)
            return
        }

        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_MEDIA + CONTENT_TYPE_MUSIC thay vi VOICE_COMMUNICATION -
                    // tranh he thong tu dong bat AEC/NS xu ly stream nay o mot so may,
                    // dung nguyen tac "khong AEC/NS/AGC" xuyen suot pipeline karaoke.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG_OUT)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)

        // PERFORMANCE_MODE_LOW_LATENCY chi co tu API 26 - giam do tre duong
        // ra dang ke tren thiet bi ho tro (khong phai may nao cung co).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        val track = builder.build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            logBoth("❌ AudioTrack khoi tao that bai, state=${track.state}", isError = true)
            track.release()
            return
        }

        audioTrack = track
        track.play()
        logBoth("✅ Da bat dau output, sampleRate=$SAMPLE_RATE, minBufferSize=$minBufferSize")
    }

    /** Ghi 1 buffer PCM ra output. Goi tu thread doc cua MicInput/Mixer - KHONG tu tao thread rieng o day de tranh them do tre. */
    fun write(buffer: ShortArray, size: Int) {
        val track = audioTrack ?: return
        val written = track.write(buffer, 0, size)
        if (written < 0) {
            logBoth("❌ AudioTrack.write() loi, code=$written", isError = true)
        }
    }

    fun stop() {
        audioTrack?.apply {
            stop()
            release()
        }
        audioTrack = null
        logBoth("🛑 Da dung output")
    }
}