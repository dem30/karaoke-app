package com.karaokeapp.audio.music

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
 */
object CaptureLogBus {
    private const val MAX_LINES = 500
    private val lines = mutableListOf<String>()
    private var listener: ((String) -> Unit)? = null

    @Synchronized
    fun log(line: String) {
        lines.add(line)
        if (lines.size > MAX_LINES) lines.removeAt(0)
        listener?.invoke(line)
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
