package com.karaokeapp.audio.music;

/**
 * Phase 1 - trong tam chinh.
 *
 * Muc tieu: chung minh co the lay PCM tu app phat nhac (vd YouTube) qua
 * AudioPlaybackCaptureConfiguration (API 29+), thay vi gia dinh no hoat dong.
 *
 * Pipeline theo thiet ke:
 *   App phat nhac -> Android AudioPlaybackCapture -> AudioRecord -> PCM
 *
 * TODO:
 *  1. Xin MediaProjection consent (thong qua Activity.startActivityForResult
 *     voi MediaProjectionManager.createScreenCaptureIntent(), Android goi chung
 *     API nay cho audio playback capture).
 *  2. Dung AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
 *     .addMatchingUsage(AudioAttributes.USAGE_MEDIA) de loc dung stream nhac.
 *  3. Tao AudioRecord voi config o tren, doc PCM lien tuc trong 1 thread rieng.
 *  4. Neu capture that bai / tra ve toan silence -> KHONG tiep tuc build mixer,
 *     quay lai kiem tra permission / app nguon co cho capture khong.
 */
public class MusicInput {
    // TODO: implement sau khi xac nhan duoc buoc test dau tien hoat dong.
}
