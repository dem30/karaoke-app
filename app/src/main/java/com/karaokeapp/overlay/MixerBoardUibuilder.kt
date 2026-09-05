package com.karaokeapp.overlay

import android.content.Context
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.karaokeapp.audio.mixer.LowLatencyMixer
import com.karaokeapp.audio.music.PlaybackCaptureService

/**
 * ✅ MOI (Phase 6 - dung chung giua dialog trong app va overlay "on the
 * fly"): truoc day toan bo logic dung "Ban mixer" (section Tong the, section
 * tung kenh voi Volume/EQ/AutoGain/Compressor/Echo/Mute) nam RIENG trong
 * MainActivity.showMixerBoardDialog() - khi can THEM 1 noi hien THU HAI (ban
 * mixer noi de dung duoc ngay tren YouTube, xem MixerBoardOverlay.kt), copy
 * y nguyen logic do sang se tao 2 ban giong het nhau, moi lan sua 1 tham so
 * (vd doi khoang EQ tu [-12,12] sang [-15,15]) phai nho sua CA HAI noi -
 * de sot 1 cho la 2 giao dien lech nhau.
 *
 * MixerBoardUiBuilder tach logic do ra thanh 1 object DUNG CHUNG, chi can
 * Context (khong can Activity) - ca MainActivity (dialog, Context = chinh
 * Activity) lan MixerBoardOverlay (chay trong Service, Context =
 * applicationContext) deu goi duoc CUNG 1 ham de dung dung 1 giao dien,
 * dung 1 nguon du lieu (PlaybackCaptureService companion object).
 */
object MixerBoardUiBuilder {

    /** Nhan mo ta ngan gon cho 1 sourceId - "Mic tai cho (May nay)" cho local_mic, con lai hien nguyen clientId. */
    fun channelDisplayLabel(sourceId: String): String {
        return if (sourceId == LowLatencyMixer.SOURCE_LOCAL_MIC) {
            "🎤 Mic tai cho (may nay)"
        } else {
            "🎤 May: $sourceId"
        }
    }

    /** Slider [-12f, 12f] dB dung chung cho ca 3 dai EQ (bass/mid/treble) - progress 0..240, 120 = 0dB. */
    fun addDbSeekBar(
        context: Context,
        container: LinearLayout,
        label: String,
        initialDb: Float,
        onChange: (Float) -> Unit
    ) {
        val valueLabel = TextView(context).apply {
            text = "$label: ${"%.1f".format(initialDb)} dB"
        }
        val seekBar = SeekBar(context).apply {
            max = 240
            progress = ((initialDb + 12f) * 10f).toInt().coerceIn(0, 240)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val db = (progress / 10f) - 12f
                    valueLabel.text = "$label: ${"%.1f".format(db)} dB"
                    if (fromUser) onChange(db)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(valueLabel)
        container.addView(seekBar)
    }

    /** Slider [0f, 2f] dung chung cho volume kenh/nhac nen/tong the - progress 0..200, 100 = 1.0x. */
    fun addVolumeSeekBar(
        context: Context,
        container: LinearLayout,
        label: String,
        initialVolume: Float,
        onChange: (Float) -> Unit
    ) {
        val valueLabel = TextView(context).apply {
            text = "$label: ${"%.0f".format(initialVolume * 100)}%"
        }
        val seekBar = SeekBar(context).apply {
            max = 200
            progress = (initialVolume * 100f).toInt().coerceIn(0, 200)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val volume = progress / 100f
                    valueLabel.text = "$label: ${"%.0f".format(volume * 100)}%"
                    if (fromUser) onChange(volume)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(valueLabel)
        container.addView(seekBar)
    }

    /** Section "Tong the" - 2 slider Nhac nen + Tong the (master), doc/ghi truc tiep qua PlaybackCaptureService companion object. */
    fun buildMasterVolumeSection(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24)
            addView(TextView(context).apply {
                text = "— Tong the —"
                textSize = 16f
            })
            addVolumeSeekBar(context, this, "Nhac nen", PlaybackCaptureService.getMusicVolume()) { v ->
                PlaybackCaptureService.setMusicVolume(v)
            }
            addVolumeSeekBar(context, this, "Tong the (master)", PlaybackCaptureService.getMasterVolume()) { v ->
                PlaybackCaptureService.setMasterVolume(v)
            }
        }
    }

    /** Section 1 kenh vocal (mic tai cho hoac 1 clientId remote) - Mute, Volume, 3 EQ, 6 toggle (AutoGain/EQ/Compressor/Echo/Anti-Feedback/Reverb - 2 cai cuoi MAC DINH TAT) - doc gia tri HIEN TAI tu Service de khoi tao dung. */
    fun buildChannelSection(context: Context, sourceId: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 24)

            addView(TextView(context).apply {
                text = "— ${channelDisplayLabel(sourceId)} —"
                textSize = 16f
            })

            val mutedCheckBox = CheckBox(context).apply {
                text = "Cau kenh nay"
                isChecked = PlaybackCaptureService.isChannelMuted(sourceId)
                setOnCheckedChangeListener { _, checked ->
                    PlaybackCaptureService.setChannelMuted(sourceId, checked)
                }
            }
            addView(mutedCheckBox)

            addVolumeSeekBar(context, this, "Volume", PlaybackCaptureService.getChannelVolume(sourceId)) { v ->
                PlaybackCaptureService.setChannelVolume(sourceId, v)
            }

            // ✅ Doc dung 3 gia tri EQ HIEN TAI tu Service (khong dung hang so
            // mac dinh) - giao dien hien DUNG nhung gi nguoi dung da chinh o
            // lan mo truoc, khong bi "quen" ve mac dinh moi lan mo lai.
            var bassDb = PlaybackCaptureService.getChannelEQBass(sourceId)
            var midDb = PlaybackCaptureService.getChannelEQMid(sourceId)
            var trebleDb = PlaybackCaptureService.getChannelEQTreble(sourceId)
            addDbSeekBar(context, this, "Bass", bassDb) { db ->
                bassDb = db
                PlaybackCaptureService.setChannelEQ(sourceId, bassDb, midDb, trebleDb)
            }
            addDbSeekBar(context, this, "Mid", midDb) { db ->
                midDb = db
                PlaybackCaptureService.setChannelEQ(sourceId, bassDb, midDb, trebleDb)
            }
            addDbSeekBar(context, this, "Treble", trebleDb) { db ->
                trebleDb = db
                PlaybackCaptureService.setChannelEQ(sourceId, bassDb, midDb, trebleDb)
            }

            val toggleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(CheckBox(context).apply {
                    text = "AutoGain"
                    isChecked = PlaybackCaptureService.isChannelAutoGainEnabled(sourceId)
                    setOnCheckedChangeListener { _, checked -> PlaybackCaptureService.setChannelAutoGainEnabled(sourceId, checked) }
                })
                addView(CheckBox(context).apply {
                    text = "EQ"
                    isChecked = PlaybackCaptureService.isChannelEQEnabled(sourceId)
                    setOnCheckedChangeListener { _, checked -> PlaybackCaptureService.setChannelEQEnabled(sourceId, checked) }
                })
            }
            addView(toggleRow)

            val toggleRow2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(CheckBox(context).apply {
                    text = "Compressor"
                    isChecked = PlaybackCaptureService.isChannelCompressorEnabled(sourceId)
                    setOnCheckedChangeListener { _, checked -> PlaybackCaptureService.setChannelCompressorEnabled(sourceId, checked) }
                })
                addView(CheckBox(context).apply {
                    text = "Echo"
                    isChecked = PlaybackCaptureService.isChannelEchoEnabled(sourceId)
                    setOnCheckedChangeListener { _, checked -> PlaybackCaptureService.setChannelEchoEnabled(sourceId, checked) }
                })
            }
            addView(toggleRow2)

            // ✅ MOI: 2 module dot cuoi, MAC DINH TAT (xem canh bao trong
            // FeedbackSuppressor.kt/PlateReverb.kt) - nguoi dung tu bat de
            // nghe thu, khong bat san cho nguoi dung that.
            val toggleRow3 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(CheckBox(context).apply {
                    text = "Anti-Feedback"
                    isChecked = PlaybackCaptureService.isChannelFeedbackSuppressorEnabled(sourceId)
                    setOnCheckedChangeListener { _, checked -> PlaybackCaptureService.setChannelFeedbackSuppressorEnabled(sourceId, checked) }
                })
                addView(CheckBox(context).apply {
                    text = "Reverb"
                    isChecked = PlaybackCaptureService.isChannelReverbEnabled(sourceId)
                    setOnCheckedChangeListener { _, checked -> PlaybackCaptureService.setChannelReverbEnabled(sourceId, checked) }
                })
            }
            addView(toggleRow3)

            // ✅ MOI: Pitch Correction ("auto-tune nhe") - MAC DINH TAT (xem
            // canh bao ve do tre/octave-error trong PitchCorrector.kt). Rieng
            // 1 checkbox + 1 slider "muc do ep" (0-100%, chi co tac dung khi
            // checkbox tren dang bat).
            val toggleRow4 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(CheckBox(context).apply {
                    text = "Pitch Correction (auto-tune)"
                    isChecked = PlaybackCaptureService.isChannelPitchCorrectorEnabled(sourceId)
                    setOnCheckedChangeListener { _, checked ->
                        PlaybackCaptureService.setChannelPitchCorrectorEnabled(sourceId, checked)
                    }
                })
            }
            addView(toggleRow4)

            addVolumeSeekBar(
                context,
                this,
                "Muc do ep tone",
                PlaybackCaptureService.getChannelPitchCorrectorStrength(sourceId)
            ) { v ->
                // addVolumeSeekBar cho pham vi 0f..2f; correctionStrength chi
                // hop le trong 0f..1f nen ep lai truoc khi ghi (setter cua
                // PitchCorrector.correctionStrength cung tu coerceIn 0..1,
                // day chi la lop an toan them o phia UI).
                PlaybackCaptureService.setChannelPitchCorrectorStrength(sourceId, v.coerceIn(0f, 1f))
            }
        }
    }
}