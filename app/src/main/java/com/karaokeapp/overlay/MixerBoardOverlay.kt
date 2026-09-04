package com.karaokeapp.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.karaokeapp.audio.music.CaptureLogBus
import com.karaokeapp.audio.music.PlaybackCaptureService
import kotlin.math.abs

/**
 * ✅ MOI (Phase 6 - "ban mixer on fly"): nut noi THU HAI, dat CANH nut ▶/⏸
 * co san (MixerToggleOverlayButton) - bam vao se mo/dong BAN MIXER DAY DU
 * (giong het dialog "Ban mixer" trong MainActivity: section Tong the +
 * MOI kenh vocal dang hoat dong voi Volume/EQ 3 dai/AutoGain/Compressor/
 * Echo/Mute) ngay tren man hinh, NGAY CA KHI dang xem YouTube toan man
 * hinh - khong can roi khoi video de quay lai app.
 *
 * ✅ SUA (theo yeu cau nguoi dung): truoc day ban nay CHI co 3 slider don
 * gian (Nhac nen/Tong the/Mic tai cho). Gio dung CHUNG logic dung UI voi
 * dialog trong MainActivity qua MixerBoardUiBuilder - nen liet ke DUNG
 * moi kenh dang hoat dong (mic tai cho + TUNG May B/C/D dang ket noi, lay
 * tu PlaybackCaptureService.listActiveChannelIds()) voi DAY DU cac dieu
 * khien, khong con thieu EQ/Compressor/Echo/tung may remote nhu ban cu.
 *
 * Ly do can nut nay (nhac lai): khi Mixer Test dang BAT, STREAM_MUSIC
 * (nhac goc YouTube) bi CHU DONG MUTE (tranh nghe DUP 2 lan qua ca YouTube
 * goc lan qua Mixer) - section "Tong the" trong ban nay cho phep chinh lai
 * musicVolume (nhac di qua duong Mixer) ngay tren video dang xem.
 *
 * Dung WindowManager overlay (TYPE_APPLICATION_OVERLAY) giong
 * MixerToggleOverlayButton. Nut bam de MO/DONG keo di chuyen duoc; BAN THAN
 * bang mixer (khi dang mo) la 1 view WindowManager RIENG, CO the nhan
 * touch/scroll binh thuong (khong dung FLAG_NOT_TOUCHABLE) de cac thanh
 * truot/checkbox ben trong hoat dong, nhung van FLAG_NOT_FOCUSABLE de
 * khong cuop phim cung/back cua video dang xem.
 */
class MixerBoardOverlay(
    private val appContext: Context
) {
    private var windowManager: WindowManager? = null
    private var toggleButtonView: TextView? = null
    private var addedBoardWindowView: android.view.View? = null
    private var isToggleAdded = false
    private var isBoardVisible = false

    companion object {
        private const val TAG = "MixerBoardOverlay"
        private const val CLICK_SLOP_PX = 12
        private const val BUTTON_SIZE_DP = 56

        // ✅ Kich thuoc ban mixer day du: rong hon ban 3-slider truoc day
        // (can cho nhieu section EQ/toggle), va GIOI HAN CHIEU CAO toi da
        // (thay vi WRAP_CONTENT vo han) - vi co the co RAT NHIEU kenh (may
        // B/C/D...), ScrollView ben trong se cuon thay vi day tran man hinh.
        private const val BOARD_WIDTH_DP = 300
        private const val BOARD_MAX_HEIGHT_DP = 480
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[MixerBoardOverlay] $msg")
    }

    private fun dpToPx(dp: Int): Int {
        val density = appContext.resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    /**
     * Them nut "🎚️" len man hinh (goc trai, doi dien voi nut ▶/⏸ o goc
     * phai de khong chong len nhau) - bam vao se mo/dong ban mixer day du.
     * An toan goi nhieu lan (khong tao trung nut neu da co).
     */
    fun show() {
        if (isToggleAdded) return

        if (!Settings.canDrawOverlays(appContext)) {
            logBoth(
                "❌ Chua co quyen 'Hien thi tren ung dung khac' - khong the hien nut Ban mixer noi.",
                isError = true
            )
            return
        }

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val size = dpToPx(BUTTON_SIZE_DP)
        val button = TextView(appContext).apply {
            text = "🎚️"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 30, 30, 30))
            }
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = dpToPx(160)
        }

        var downRawX = 0f
        var downRawY = 0f
        var downParamX = 0
        var downParamY = 0
        var totalMoved = 0f

        button.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = params.x
                    downParamY = params.y
                    totalMoved = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    totalMoved += abs(dx) + abs(dy)
                    params.x = downParamX + dx.toInt()
                    params.y = downParamY + dy.toInt()
                    try {
                        wm.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        logBoth("⚠️ Loi khi keo nut Ban mixer (bo qua): ${e.message}")
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (totalMoved < CLICK_SLOP_PX) {
                        logBoth("👆 Nguoi dung bam nut Ban mixer noi.")
                        toggleBoard(anchorX = params.x, anchorY = params.y, buttonSize = size)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(button, params)
            toggleButtonView = button
            isToggleAdded = true
            logBoth("✅ Da hien nut noi 'Ban mixer'.")
        } catch (e: Exception) {
            logBoth("❌ Khong the them nut Ban mixer vao WindowManager: ${e.message}", isError = true)
        }
    }

    private fun toggleBoard(anchorX: Int, anchorY: Int, buttonSize: Int) {
        if (isBoardVisible) {
            closeBoard()
        } else {
            openBoard(anchorX, anchorY, buttonSize)
        }
    }

    /**
     * Dung MixerBoardUiBuilder (dung chung voi dialog trong MainActivity)
     * de dung DAY DU section Tong the + tung kenh dang hoat dong, roi hien
     * qua 1 view WindowManager RIENG (khac voi nut bam) - them/xoa doc lap
     * voi nut bam de bam lai nut vao/ra khong lam mat vi tri nut.
     */
    private fun openBoard(anchorX: Int, anchorY: Int, buttonSize: Int) {
        val wm = windowManager ?: return
        if (isBoardVisible) return

        val screenWidth = appContext.resources.displayMetrics.widthPixels
        val boardWidth = dpToPx(BOARD_WIDTH_DP)

        val scrollContent = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
        }

        scrollContent.addView(TextView(appContext).apply {
            text = "🎚️ Ban mixer"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 16f
        })

        scrollContent.addView(MixerBoardUiBuilder.buildMasterVolumeSection(appContext).apply {
            colorAllTextWhite(this)
        })

        val channelIds = PlaybackCaptureService.listActiveChannelIds()
        if (channelIds.isEmpty()) {
            scrollContent.addView(TextView(appContext).apply {
                text = "Chua co kenh vocal nao dang hoat dong (chua bat Mixer Test / chua co ai hat)."
                setTextColor(Color.argb(200, 255, 255, 255))
                textSize = 12f
                setPadding(0, dpToPx(8), 0, 0)
            })
        } else {
            channelIds.forEach { sourceId ->
                scrollContent.addView(MixerBoardUiBuilder.buildChannelSection(appContext, sourceId).apply {
                    colorAllTextWhite(this)
                })
            }
        }

        val refreshButton = Button(appContext).apply {
            text = "🔄 Lam moi danh sach kenh"
        }
        val closeButton = Button(appContext).apply {
            text = "✖ Dong"
        }
        val actionRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(refreshButton)
            addView(closeButton)
        }

        val scrollView = ScrollView(appContext).apply {
            addView(scrollContent)
        }

        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.argb(240, 20, 20, 20))
            }
            addView(actionRow)
            addView(scrollView)
        }

        val boardMaxHeight = dpToPx(BOARD_MAX_HEIGHT_DP)
        val boardParams = WindowManager.LayoutParams(
            boardWidth,
            boardMaxHeight,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val spaceOnRight = screenWidth - (anchorX + buttonSize)
            x = if (spaceOnRight >= boardWidth) {
                anchorX + buttonSize + dpToPx(8)
            } else {
                (anchorX - boardWidth - dpToPx(8)).coerceAtLeast(dpToPx(8))
            }
            y = anchorY
        }

        refreshButton.setOnClickListener {
            closeBoard()
            openBoard(anchorX, anchorY, buttonSize)
        }
        closeButton.setOnClickListener { closeBoard() }

        try {
            wm.addView(root, boardParams)
            addedBoardWindowView = root
            isBoardVisible = true
            logBoth("📖 Da mo ban mixer day du (${channelIds.size} kenh dang hoat dong).")
        } catch (e: Exception) {
            logBoth("❌ Khong the mo ban mixer: ${e.message}", isError = true)
        }
    }

    /**
     * ✅ MixerBoardUiBuilder dung chung voi dialog trong app (nen mac dinh
     * mau chu la mau text mac dinh cua theme, thuong la DEN tren dialog nen
     * sang) - tren overlay nen TOI (Color.argb(240, 20, 20, 20)) can doi
     * MOI TextView/CheckBox con lai sang mau TRANG de doc duoc. Duyet de
     * quy toan bo cay view vua duoc MixerBoardUiBuilder tao ra.
     */
    private fun colorAllTextWhite(view: android.view.View) {
        when (view) {
            is TextView -> view.setTextColor(Color.WHITE)
            is LinearLayout -> for (i in 0 until view.childCount) colorAllTextWhite(view.getChildAt(i))
        }
    }

    private fun closeBoard() {
        val wm = windowManager ?: return
        val view = addedBoardWindowView ?: return
        try {
            wm.removeView(view)
            logBoth("📕 Da dong ban mixer.")
        } catch (e: Exception) {
            logBoth("⚠️ Loi khi dong ban mixer (bo qua): ${e.message}")
        } finally {
            addedBoardWindowView = null
            isBoardVisible = false
        }
    }

    /** Dong ban mixer NEU dang mo - KHONG go nut "🎚️" (giu nguyen tren man hinh). Goi tu ben ngoai khi Mixer Test bi TAT (xem PlaybackCaptureService.stopMixerTestInternal()). An toan goi nhieu lan / khi bang dang dong san. */
    fun closeBoardIfOpen() {
        if (isBoardVisible) closeBoard()
    }

    /**
     * Go het nut + ban mixer khoi man hinh HOAN TOAN - goi khi ca phien
     * capture (Phase 1) ket thuc, giong tinh than hide() cua
     * MixerToggleOverlayButton. An toan goi nhieu lan / khi chua show().
     */
    fun hide() {
        closeBoardIfOpen()
        if (!isToggleAdded) return
        try {
            toggleButtonView?.let { windowManager?.removeView(it) }
            logBoth("🛑 Da an nut Ban mixer noi.")
        } catch (e: Exception) {
            logBoth("⚠️ Loi khi go nut Ban mixer (khong nghiem trong): ${e.message}")
        } finally {
            toggleButtonView = null
            windowManager = null
            isToggleAdded = false
        }
    }
}
