package com.karaokeapp.audio.mixer

import com.karaokeapp.audio.processor.AutoGainControl
import com.karaokeapp.audio.processor.Compressor
import com.karaokeapp.audio.processor.EchoReverb
import com.karaokeapp.audio.processor.Limiter
import com.karaokeapp.audio.processor.VocalProcessor
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 6 - "Kenh mixer" cho 1 nguon vocal (mic vat ly cua May A, hoac 1
 * clientId cua May B/C qua WebRTC).
 *
 * ✅ THAY THE co che cu: truoc day mic vat ly (May A) co day du chuoi
 * Limiter -> EQ -> Compressor -> Echo, con tung nguon remote (May B/C) CHI
 * co Limiter - giong hat qua WebRTC nghe "mộc" hon han giong hat truc tiep.
 * VocalChannel gio la 1 CLASS DUY NHAT dung cho CA HAI loai nguon - moi
 * nguon (dinh danh boi sourceId) co 1 instance rieng voi state DSP rieng,
 * nhung cung 1 thu tu xu ly va cung bo tham so mac dinh - dam bao chat am
 * dong nhat giua may hat tai cho va may hat tu xa.
 *
 * ✅ THAY THE HowlGuard cu (co che "leo thang" tu dong cam/ha am luong khi
 * nghi ngo hu) - da bi go bo hoan toan vi lam giong hat luc to luc nho
 * khong theo y muon nguoi dung ("nghe nhu con rit" ngay ca khi dang chi la
 * dang giam am tam thoi). Gio kenh nay CHI con:
 * - AutoGainControl: san bang am luong GIUA CAC nguon hat (khong phai
 *   chan hu) - dieu chinh CHAM va co gioi han, khong bao gio cam mic.
 * - EQ/Compressor/Echo: tao chat am, nguoi dung dieu chinh duoc.
 * - volume: he so am luong THU CONG, do CHINH NGUOI DUNG dat (vd tu 1
 *   thanh truot tren UI) - day la thu duy nhat "ha" am luong theo y muon
 *   ro rang cua nguoi dung, khong phai phan mem tu quyet dinh.
 * - Limiter an toan cuoi chuoi: chi chan CLIP (khong cho vuot bien do PCM),
 *   khong lien quan gi den chong hu vat ly.
 *
 * Chong hu vat ly (loa phat lai vao mic) gio la trach nhiem VAT LY cua
 * nguoi dung (giam am luong loa / dua mic ra xa loa / dung tai nghe) - dung
 * tinh than ghi chu san co truoc day trong LowLatencyMixer (VOCAL_GAIN cu).
 *
 * Thu tu xu ly: AutoGain -> EQ -> Compressor -> Echo -> Volume (thu cong) ->
 * Limiter (an toan, luon BAT, khong the tat qua UI - khac voi 4 buoc tren).
 */
class VocalChannel(
    sampleRate: Int = 44100,
    /** Nhan dung de log/debug (thuong la sourceId - vd "local_mic" hoac clientId cua May B/C). */
    val label: String = "channel"
) {

    // ✅ Tham so mac dinh giong het bo tham so truoc day CHI ap dung cho mic
    // vat ly cua May A - gio la mac dinh CHUNG cho MOI nguon (local + remote)
    // nguoi dung co the dieu chinh tiep tu day qua setEQGains()/v.v.
    val autoGain = AutoGainControl(sampleRate = sampleRate)
    val eq = VocalProcessor(sampleRate = sampleRate, bassGainDb = -2.0f, midGainDb = 1.0f, trebleGainDb = 3.0f)
    val compressor = Compressor(sampleRate = sampleRate, thresholdDb = -18.0f, ratio = 3.0f, attackMs = 12f, releaseMs = 100f, makeupGainDb = 2.0f)
    val echo = EchoReverb(sampleRate = sampleRate, delayMs = 200f, feedback = 0.38f, wetLevel = 0.32f, damping = 0.35f)

    // Limiter an toan CUOI chuoi - luon bat, chi chan clip PCM (KHONG phai
    // chong hu). Rieng cho tung kenh, khac voi finalMixLimiter (chay tren
    // TOAN BO ban mix, o LowLatencyMixer).
    private val safetyLimiter = Limiter(sampleRate = sampleRate, thresholdRatio = 0.9f, releaseMs = 50f)

    // ✅ MOI: cac cong tac BAT/TAT tung buoc rieng le - nguoi dung co the tu
    // tat AutoGain/EQ/Compressor/Echo neu muon nghe "giong that 100%" ma
    // khong can code lai gi ca.
    @Volatile var autoGainEnabled: Boolean = true
    @Volatile var eqEnabled: Boolean = true
    @Volatile var compressorEnabled: Boolean = true
    @Volatile var echoEnabled: Boolean = true

    /** He so am luong THU CONG (0f = cau hoan toan, 1f = binh thuong, 2f = to gap doi). Nguoi dung dieu chinh qua UI (slider). */
    @Volatile
    var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 2f)
        }

    /** Cau kenh nay (vd nguoi dung bam nut "Tat mic" cho 1 May B cu the) - khac voi volume=0 o cho KHONG chay ca chuoi DSP (tiet kiem CPU). */
    @Volatile var muted: Boolean = false

    /**
     * Xu ly 1 buffer PCM mono TAI CHO (in-place) - ap dung toan bo chuoi
     * theo dung thu tu AutoGain -> EQ -> Compressor -> Echo -> Volume ->
     * Limiter an toan.
     */
    fun process(buffer: ShortArray, size: Int) {
        if (size <= 0) return

        if (muted) {
            for (i in 0 until size) buffer[i] = 0
            return
        }

        if (autoGainEnabled) autoGain.process(buffer, size)
        if (eqEnabled) eq.process(buffer, size)
        if (compressorEnabled) compressor.process(buffer, size)
        if (echoEnabled) echo.process(buffer, size)

        if (volume != 1.0f) {
            for (i in 0 until size) {
                var v = buffer[i] * volume
                v = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), v))
                buffer[i] = v.toInt().toShort()
            }
        }

        // Luoi an toan cuoi cung - luon chay, khong phu thuoc cong tac nao o
        // tren (kho co the tat nham va gay clip cung khi tang volume/EQ/echo
        // qua cao).
        safetyLimiter.process(buffer, size)
    }

    /** Doi gain EQ 3 dai - moi tham so [-12f, 12f] dB, xem VocalProcessor.setGains(). */
    fun setEQGains(bassDb: Float = eq.bassGainDb, midDb: Float = eq.midGainDb, trebleDb: Float = eq.trebleGainDb) {
        eq.setGains(bassDb, midDb, trebleDb)
    }

    /** Reset TOAN BO state DSP (filter/compressor/echo/auto-gain/limiter) - KHONG doi volume/EQ/cong tac nguoi dung da chinh (giu nguyen y muon nguoi dung qua cac lan Bat/Tat Mixer Test). Goi khi bat dau lai 1 session de tranh tan du (click/pop) tu session truoc. */
    fun reset() {
        autoGain.reset()
        eq.reset()
        compressor.reset()
        echo.reset()
        safetyLimiter.reset()
    }
}