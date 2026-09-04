package com.karaokeapp.audio.processor

import kotlin.math.max
import kotlin.math.min

/**
 * Phase 4, buoc 3/5 - Hieu ung Vang/Nhai (Karaoke Echo & Room Reverb) cho
 * rieng giong hat.
 *
 * ⚠️ LY DO KHONG DUNG android.media.audiofx.PresetReverb (PLAN.md goc co
 * goi y dung cai nay de do cong viet DSP): PresetReverb la 1 AudioEffect
 * gan vao AudioSession cua 1 AudioTrack dang phat - trong kien truc app
 * nay, AudioTrack duy nhat dang phat la cua OutputRouter, mang tin hieu DA
 * MIX (nhac + vocal). Gan PresetReverb vao do se lam vang CA nhac nen lan
 * giong hat - sai muc dich (karaoke that chi vang giong hat, khong vang
 * nhac). Ngoai ra AudioTrack cua OutputRouter bi huy/tao lai nhieu lan
 * trong app nay (moi lan Bat/Tat Mixer Test, moi lan chuoi "Kich hoat lai"
 * chay) - gan lai AudioEffect moi lan AudioTrack doi la them 1 lop dong bo
 * de vo, rui ro cao trong 1 he thong da nhieu bug tinh thoi diem (timing)
 * kho debug tu truoc. Giai phap: tu viet 1 delay line don gian, xu ly
 * THANG tren PCM cua RIENG vocal (truoc khi mix) - nam gon trong 1 class,
 * khong dung API AudioEffect cua he thong, khong co state ngoai tam kiem
 * soat cua chinh app.
 *
 * Nguyen ly: delay line (tape delay) co vong phan hoi (feedback), cong them
 * 1 bo loc low-pass 1-cuc (one-pole) tren nhanh phan hoi de cac lan nhai
 * sau AM DAN, tieu bot tieng tep/choi tai - mo phong dac tinh vang phong
 * that (tuong tu Schroeder reverb don gian, khong phai reverb thuat toan
 * day du). Xu ly in-place, dung 1 mang tinh lam ring buffer, khong cap phat
 * bo nho trong process().
 */
class EchoReverb(
    sampleRate: Int = 44100,
    /**
     * Thoi gian tre (ms). 180-220ms la khoang tao nhip echo dac trung cua
     * karaoke gia dinh/phong hat pho thong (ngan hon nghe nhu "chorus/flange",
     * dai hon nghe nhu "hi vong" tach roi cau hat).
     */
    delayMs: Float = 200f,
    /**
     * Muc phan hoi (0f..0.85f) - quyet dinh SO LAN tieng nhai con nghe duoc
     * truoc khi tat han. 0.38f cho khoang 3-4 lan nhai giam dan.
     *
     * ⚠️ KHONG duoc dat gan hoac vuot 1.0f - delay line co feedback se tu
     * khuyech dai vo han (vong lap ho -> hu/tieng ret tang dan khong ngung),
     * khac ban chat voi feedback loop AM HOC (mic bat lai loa) nhung HAU QUA
     * giong het nhau. Gioi han cung trong init{} de chan nham cau hinh.
     */
    private val feedback: Float = 0.38f,
    /**
     * Ty le tieng vang tron vao (0f..1f) - am luong cua tieng nhai (wet) so
     * voi tieng hat moc (dry). 0.32f giu loi hat ro chu ma van co do vang.
     */
    private val wetLevel: Float = 0.32f,
    /**
     * He so loc bot tep o nhanh phan hoi (0f..0.9f) - cang cao, cac lan nhai
     * sau cang "am/mo" nhanh, giong vang phong thuc te hon la vang kim loai
     * (metallic) cua delay thuan khong loc.
     */
    private val damping: Float = 0.35f
) {

    private val delaySamples = (sampleRate * (delayMs / 1000f)).toInt()
    private val delayBuffer = ShortArray(max(1, delaySamples + 1))
    private var bufferIndex = 0

    // State cua bo loc low-pass tren nhanh feedback (giu qua nhieu sample,
    // giong tinh than Biquad.x1/y1 trong VocalProcessor.kt).
    private var lastFeedbackSample = 0f

    init {
        require(feedback in 0f..0.85f) {
            "feedback phai trong [0, 0.85] - vuot muc nay delay line tu khuyech dai vo han (howling ky thuat so)"
        }
    }

    fun process(buffer: ShortArray, size: Int) {
        // Giu ty le dry gan 1.0 (chi giam rat nhe theo wetLevel) - uu tien
        // giong that luon ro rang, tieng vang la "them vao" chu khong "thay
        // the" mot phan giong goc (khac kieu crossfade constant-power thuong
        // dung cho hieu ung nhac cu, karaoke can loi hat luon la trong tam).
        val dryLevel = 1.0f - (wetLevel * 0.15f)

        for (i in 0 until size) {
            val dry = buffer[i].toInt()

            val delayedSample = delayBuffer[bufferIndex].toFloat()

            lastFeedbackSample = (delayedSample * (1f - damping)) + (lastFeedbackSample * damping)

            var newDelayValue = dry + (lastFeedbackSample * feedback)
            newDelayValue = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), newDelayValue))
            delayBuffer[bufferIndex] = newDelayValue.toInt().toShort()

            bufferIndex = (bufferIndex + 1) % delayBuffer.size

            var mixed = (dry * dryLevel) + (delayedSample * wetLevel)
            mixed = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), mixed))
            buffer[i] = mixed.toInt().toShort()
        }
    }

    /** Xoa sach delay line - goi khi bat dau 1 luot hat moi, tranh tieng vang tan du tu session truoc lan sang session sau. */
    fun reset() {
        delayBuffer.fill(0)
        bufferIndex = 0
        lastFeedbackSample = 0f
    }
}