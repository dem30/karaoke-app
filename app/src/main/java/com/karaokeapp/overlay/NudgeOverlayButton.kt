package com.karaokeapp.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.karaokeapp.audio.music.CaptureLogBus
import kotlin.math.abs

/**
 * ✅ MOI (fix "khong the tin tuong hoan toan vao trigger tu dong, YouTube
 * chi song duoc ~5 giay roi im" - xem giai thich chi tiet trong
 * PlaybackCaptureService.kt/OutputRouter.kt): 1 nut TRON, NHO, NOI TREN moi
 * ung dung khac (ke ca YouTube dang o foreground) - dung WindowManager +
 * TYPE_APPLICATION_OVERLAY, KHONG phai UI thuong cua Activity nen van hien
 * dien du nguoi dung dang xem video toan man hinh o app khac.
 *
 * Ly do can nut NOI (khac voi action button tren notification da lam o ban
 * truoc): quan sat thuc te cho thay am thanh co the im chi sau ~5 giay dau
 * moi lan mo YouTube, dung luc nguoi dung dang xem/seek video - viec phai
 * thoat khoi video, keo thanh thong bao xuong, bam nut, roi quay lai video
 * moi lan la qua bat tien va gian doan trai nghiem. Nut noi cho phep bam
 * NGAY TRONG luc dang xem, khong roi man hinh hien tai.
 *
 * Nut co the KEO DI CHUYEN duoc (drag) de nguoi dung tu dat vao goc khong
 * vuong tam nhin video - phan biet "keo" voi "bam" bang khoang cach di
 * chuyen: neu tong quang duong di chuyen duoi CLICK_SLOP_PX thi tinh la 1
 * cu bam (goi onTap), nguoc lai coi la keo (chi cap nhat vi tri, KHONG goi
 * onTap) - tranh vo tinh kich hoat nudge moi lan nguoi dung chi muon doi
 * cho nut.
 *
 * Yeu cau quyen "Hien thi tren ung dung khac" (SYSTEM_ALERT_WINDOW) - PHAI
 * duoc nguoi dung cap thu cong qua man hinh Settings rieng (Android khong
 * cho xin runtime permission binh thuong nhu RECORD_AUDIO), xem
 * MainActivity.ensureOverlayPermission(). Neu chua co quyen, show() se tu
 * bo qua (khong crash) va chi log canh bao.
 */
class NudgeOverlayButton(
    private val appContext: Context,
    private val onTap: () -> Unit
) {

    private var windowManager: WindowManager? = null
    private var buttonView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false

    companion object {
        private const val TAG = "NudgeOverlayButton"

        // ✅ Nguong (px) de phan biet "bam" voi "keo" - duoi muc nay tinh la
        // bam du ngon tay co xe nhe trong luc nhan (rat kho giu tuyet doi
        // dung yen tren man hinh cam ung thuc te).
        private const val CLICK_SLOP_PX = 12
        private const val BUTTON_SIZE_DP = 56
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[NudgeOverlay] $msg")
    }

    private fun dpToPx(dp: Int): Int {
        val density = appContext.resources.displayMetrics.density
        return (dp * density).toInt()
    }

    /**
     * Them nut noi len man hinh. An toan de goi nhieu lan (bo qua neu da
     * them roi, hoac neu chua co quyen overlay).
     */
    fun show() {
        if (isAdded) {
            logBoth("⚠️ Nut noi da hien thi roi, bo qua show() thua.")
            return
        }

        if (!Settings.canDrawOverlays(appContext)) {
            logBoth(
                "❌ Chua co quyen 'Hien thi tren ung dung khac' (SYSTEM_ALERT_WINDOW) - " +
                    "khong the hien nut noi. Vao MainActivity de cap quyen truoc.",
                isError = true
            )
            return
        }

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val size = dpToPx(BUTTON_SIZE_DP)
        val button = TextView(appContext).apply {
            text = "🔊"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            // ✅ Bo tron nhe qua GradientDrawable de trong giong 1 nut FAB nho,
            // khong can file drawable XML rieng.
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.argb(200, 30, 30, 30))
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            type,
            // ✅ FLAG_NOT_FOCUSABLE: nut noi KHONG duoc cuop input focus cua
            // app dang o foreground (vi du YouTube) - neu thieu co nay, cham
            // vao vung khac ngoai nut van co the lam gian doan tuong tac voi
            // video dang xem.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // ✅ Vi tri khoi tao: goc phai man hinh, hoi thap xuong duoi status
            // bar - vi tri hop ly de khong che thanh dieu khien video YouTube,
            // nguoi dung co the tu keo di neu muon.
            x = appContext.resources.displayMetrics.widthPixels - size - dpToPx(16)
            y = dpToPx(160)
        }
        layoutParams = params

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
                        logBoth("⚠️ Loi khi keo nut noi (bo qua): ${e.message}")
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (totalMoved < CLICK_SLOP_PX) {
                        logBoth("👆 [ManualNudge] Nguoi dung bam nut noi tren man hinh.")
                        flashFeedback(view as TextView)
                        onTap()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(button, params)
            buttonView = button
            isAdded = true
            logBoth("✅ Da hien nut noi 'Kich hoat lai' tren man hinh.")
        } catch (e: Exception) {
            logBoth("❌ Khong the them nut noi vao WindowManager: ${e.message}", isError = true)
        }
    }

    /**
     * ✅ Phan hoi thi giac ngan (nhap nhay mau) khi bam - de nguoi dung biet
     * chac chan da dang ky duoc cu bam, ke ca khi nudge thuc te bi
     * OutputRouter bo qua vi dang trong cooldown (xem OutputRouter.kt).
     */
    private fun flashFeedback(view: TextView) {
        val original = (view.background as? android.graphics.drawable.GradientDrawable)
        view.animate()
            .scaleX(1.25f).scaleY(1.25f)
            .setDuration(80)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
        original?.setColor(Color.argb(220, 0, 140, 60))
        view.postDelayed({
            original?.setColor(Color.argb(200, 30, 30, 30))
        }, 250)
    }

    /** Go nut noi khoi man hinh. An toan de goi nhieu lan / khi chua show(). */
    fun hide() {
        if (!isAdded) return
        try {
            buttonView?.let { windowManager?.removeView(it) }
            logBoth("🛑 Da an nut noi.")
        } catch (e: Exception) {
            logBoth("⚠️ Loi khi go nut noi (khong nghiem trong): ${e.message}")
        } finally {
            buttonView = null
            layoutParams = null
            windowManager = null
            isAdded = false
        }
    }
}