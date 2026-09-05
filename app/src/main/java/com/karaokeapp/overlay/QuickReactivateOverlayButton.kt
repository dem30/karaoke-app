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
import android.widget.TextView
import com.karaokeapp.audio.music.CaptureLogBus
import kotlin.math.abs

/**
 * ✅ MOI (fix "thong bao THAT den lam YouTube im, phai bam Stop roi Play lai
 * (2 lan bam vao nut ▶/⏸) moi co nhac lai"): nut noi THU BA, doc lap voi nut
 * ▶/⏸ (Bat/Tat Mixer Test, co trang thai) va nut 🎚️ (Ban mixer) - CHI lam
 * DUNG 1 viec khi bam: goi thang beginOverlayReactivationSequence() (chuyen
 * foreground THAT toi MainActivity -> tat/bat lai Mixer Test -> quay lai
 * YouTube -> nudge OutputRouter) trong DUNG 1 LAN BAM, BAT KE Mixer Test
 * dang o trang thai BAT hay TAT theo phan mem.
 *
 * Ly do can nut RIENG thay vi dung lai nut ▶/⏸ co san: nut ▶/⏸ la TOGGLE CO
 * TRANG THAI - khi am thanh bi "duck" HAL/OEM (vi du do 1 thong bao THAT vua
 * den - xem giai thich chi tiet trong PlaybackCaptureService: "Duck CHI duoc
 * xoa khi co 1 su kien chuyen foreground THAT xay ra"), Mixer Test VAN dang
 * "BAT" theo phan mem (mixer != null, chi la am thanh vat ly bi cam), nen
 * bam nut ▶/⏸ LAN DAU chi TAT Mixer Test (dung theo trang thai no doc duoc),
 * phai bam THEM 1 LAN NUA moi thuc su chay chuoi Reactivation. Nut nay bo
 * qua hoan toan buoc doc trang thai do, luon lam DUNG 1 hanh dong "sua ngay"
 * - dung tinh than 1 cham thay vi 2.
 *
 * Icon CO DINH (khac voi nut ▶/⏸ - khong doi theo trang thai) vi hanh dong
 * nay luon giong nhau moi lan bam, khong phan anh trang thai Mixer Test.
 */
class QuickReactivateOverlayButton(
    private val appContext: Context,
    private val onTap: () -> Unit
) {
    private var windowManager: WindowManager? = null
    private var buttonView: TextView? = null
    private var isAdded = false

    companion object {
        private const val TAG = "QuickReactivateOverlay"
        private const val CLICK_SLOP_PX = 12
        private const val BUTTON_SIZE_DP = 48
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[QuickReactivateOverlay] $msg")
    }

    private fun dpToPx(dp: Int): Int {
        val density = appContext.resources.displayMetrics.density
        return (dp * density).toInt()
    }

    /** An toan goi nhieu lan (khong tao trung nut neu da co). */
    fun show() {
        if (isAdded) return

        if (!Settings.canDrawOverlays(appContext)) {
            logBoth(
                "❌ Chua co quyen 'Hien thi tren ung dung khac' - khong the hien nut Kich hoat lai nhanh.",
                isError = true
            )
            return
        }

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val size = dpToPx(BUTTON_SIZE_DP)
        val button = TextView(appContext).apply {
            text = "🔔"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                // Mau cam - khac nut ▶/⏸ (xanh/do) va nut 🎚️ (den) de khong
                // nham lan 3 nut khi nhin luot qua.
                setColor(Color.argb(220, 150, 90, 0))
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Dat NGAY DUOI nut ▶/⏸ (cung goc phai, y = 160dp + 56dp (kich
            // thuoc nut ▶/⏸) + 8dp khoang cach) - khong chong len nut do
            // hay nut 🎚️ (goc trai, y=160dp, xem MixerBoardOverlay).
            x = appContext.resources.displayMetrics.widthPixels - size - dpToPx(16)
            y = dpToPx(160 + 56 + 8)
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
                        logBoth("⚠️ Loi khi keo nut Kich hoat lai nhanh (bo qua): ${e.message}")
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (totalMoved < CLICK_SLOP_PX) {
                        logBoth("👆 Nguoi dung bam nut Kich hoat lai nhanh - goi onTap() ngay.")
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
            logBoth("✅ Da hien nut noi 'Kich hoat lai nhanh'.")
        } catch (e: Exception) {
            logBoth("❌ Khong the them nut Kich hoat lai nhanh vao WindowManager: ${e.message}", isError = true)
        }
    }

    /** Phan hoi thi giac ngan (phong to nhe) khi bam - xac nhan da dang ky cu bam. */
    private fun flashFeedback(view: TextView) {
        view.animate()
            .scaleX(1.25f).scaleY(1.25f)
            .setDuration(80)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }

    /** Go nut khoi man hinh HOAN TOAN - goi khi ca phien capture (Phase 1) ket thuc. An toan goi nhieu lan / khi chua show(). */
    fun hide() {
        if (!isAdded) return
        try {
            buttonView?.let { windowManager?.removeView(it) }
            logBoth("🛑 Da an nut Kich hoat lai nhanh.")
        } catch (e: Exception) {
            logBoth("⚠️ Loi khi go nut Kich hoat lai nhanh (khong nghiem trong): ${e.message}")
        } finally {
            buttonView = null
            windowManager = null
            isAdded = false
        }
    }
}