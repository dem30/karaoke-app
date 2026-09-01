package com.karaokeapp.audio.music

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kenh truyen log tu PlaybackCaptureService (chay ngam, co vong doi khac
 * Activity) ve MainActivity de hien thi ngay tren man hinh.
 *
 * Ly do can file nay: nguoi dung chi lam viec tren dien thoai, build qua
 * GitHub Actions (khong co adb/Logcat de xem log ngoai app) - nen moi dong
 * log quan trong trong Phase 1 phai vua ghi Log.d (de sau nay neu co adb
 * van xem duoc) VUA day qua day de hien thi + copy duoc ngay trong app.
 *
 * Dung 1 object don gian (khong LiveData/Flow) vi Service va Activity chay
 * chung 1 process - khong can co che cross-process phuc tap.
 *
 * ✅ MOI (chan doan mat tieng khi seek): them TIMESTAMP (HH:mm:ss.SSS) vao
 * DAU MOI dong log, o DUNG 1 CHO DUY NHAT (ham log() nay) - ap dung tu dong
 * cho TOAN BO log cua moi class goi qua day (MusicInput, MicInput,
 * LowLatencyMixer, OutputRouter, Service...), khong can sua tung noi goi
 * logBoth() rieng le. Ly do can gap: cac lan debug truoc do KHONG co
 * timestamp trong text log hien thi trong app (Log.d cua Android co san
 * timestamp qua Logcat, nhung nguoi dung khong co adb de xem), nen KHONG
 * the doi chieu chinh xac dong log nao xay ra DUNG LUC nguoi dung thao tac
 * (seek/doi bai/lam nho/tat app) - lam moi ket luan chi mang tinh tuong
 * doi ("xay ra gan do"), khong the khang dinh chac chan quan he nhan-qua.
 * Do chinh xac ~10ms (do goi lien tiep tu nhieu thread khac nhau - MICRO
 * sai lech giua cac dong lien tiep KHONG dang ke so voi muc dich doi chieu
 * "thao tac cua nguoi dung xay ra khoang thoi diem nao").
 */
object CaptureLogBus {
    private const val MAX_LINES = 500
    private val lines = mutableListOf<String>()
    private var listener: ((String) -> Unit)? = null

    // ✅ MOI: dung rieng 1 instance SimpleDateFormat cho object nay (KHONG
    // dung chung voi timeFormat cua PlaybackCaptureService/MainActivity -
    // SimpleDateFormat KHONG thread-safe, nen moi noi can instance rieng du
    // cung dinh dang, tranh loi ngau nhien khi nhieu thread format cung luc).
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @Synchronized
    fun log(line: String) {
        val timestamped = "[${timeFormat.format(Date())}] $line"
        lines.add(timestamped)
        if (lines.size > MAX_LINES) lines.removeAt(0)
        listener?.invoke(timestamped)
    }

    /** MainActivity goi ham nay o onResume() de nhan log moi realtime. */
    @Synchronized
    fun setListener(l: ((String) -> Unit)?) {
        listener = l
    }

    /** Lay toan bo log da tich luy - dung khi MainActivity moi mo lai man
     * hinh trong luc service van dang chay ngam tu truoc. */
    @Synchronized
    fun getAllLogsText(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }
}