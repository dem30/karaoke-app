package com.karaokeapp.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.karaokeapp.audio.music.CaptureLogBus
import kotlin.math.abs

/**
 * ✅ CAP NHAT LON (thay the hoan toan co che "beep" cu): sau khi xac nhan
 * qua thuc te rang beep tu dong (chay tu coroutine cua Service) VAN phat ra
 * duoc am thanh du dang o YouTube - nghia la KHONG phai do he thong (Honor)
 * chan hoan toan audio nen - nhung ban than hanh dong "phat 1 tieng beep" tu
 * no KHONG dang tin cay de thuc su chua duoc trieu chung goc (co luc hieu
 * qua, co luc khong ro ly do). Trong khi do, TAT/BAT lai hoan toan Mixer
 * Test (dung lai tu dau ca MicInput + LowLatencyMixer + OutputRouter) da
 * duoc xac nhan qua thao tac tay THUC SU hieu qua moi lan.
 *
 * Vi vay nut noi nay GIO KHONG con phat beep nao ca - no la 1 CONG TAC
 * BAT/TAT Mixer Test tu xa, hoat dong y het 2 nut "Bat/Tat Mixer Test" +
 * "Kich hoat lai" gop lam 1, nhung bam duoc NGAY TRONG luc dang xem YouTube
 * toan man hinh, khong can roi khoi video.
 *
 * Khac biet quan trong so voi ban truoc (NudgeOverlayButton): nut nay GIO
 * TON TAI DOC LAP voi trang thai Mixer Test dang bat hay tat - truoc day nut
 * bi go bo (hide()) NGAY khi Mixer Test tat, nen neu nguoi dung bam de TAT
 * thi chinh cai nut ho vua bam se bien mat, khong con cach nao bam lai de
 * BAT tro lai ma khong quay ve app. Nut nay CHI bi go bo khi ca phien
 * capture (Phase 1) ket thuc hoan toan (xem
 * PlaybackCaptureService.stopCurrentSessionIfAny()), con trong luc Phase 1
 * van chay, nut se o LAI man hinh xuyen suot, chi doi ICON/MAU sac de phan
 * anh dung trang thai hien tai (▶ xam = dang TAT, sap bam se BAT; ⏸ xanh =
 * dang BAT, sap bam se TAT).
 *
 * Nut co the KEO DI CHUYEN duoc (drag) de nguoi dung tu dat vao goc khong
 * vuong tam nhin video - phan biet "keo" voi "bam" bang khoang cach di
 * chuyen: neu tong quang duong di chuyen duoi CLICK_SLOP_PX thi tinh la 1
 * cu bam (goi onToggle), nguoc lai coi la keo (chi cap nhat vi tri, KHONG
 * goi onToggle).
 *
 * Yeu cau quyen "Hien thi tren ung dung khac" (SYSTEM_ALERT_WINDOW) - PHAI
 * duoc nguoi dung cap thu cong qua man hinh Settings rieng (Android khong
 * cho xin runtime permission binh thuong nhu RECORD_AUDIO), xem
 * MainActivity.ensureOverlayPermission(). Neu chua co quyen, show() se tu
 * bo qua (khong crash) va chi log canh bao.
 */
class MixerToggleOverlayButton(
    private val appContext: Context,
    private val onToggle: () -> Unit
) {

    private var windowManager: WindowManager? = null
    private var buttonView: TextView? = null
    private var isAdded = false

    companion object {
        private const val TAG = "MixerToggleOverlay"

        // ✅ Nguong (px) de phan biet "bam" voi "keo" - duoi muc nay tinh la
        // bam du ngon tay co xe nhe trong luc nhan (rat kho giu tuyet doi
        // dung yen tren man hinh cam ung thuc te).
        private const val CLICK_SLOP_PX = 12
        private const val BUTTON_SIZE_DP = 56

        // Mau/icon cho 2 trang thai - dat rieng de applyState() gon.
        private const val ICON_RUNNING = "⏸"
        private const val ICON_STOPPED = "▶"
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[MixerToggleOverlay] $msg")
    }

    private fun dpToPx(dp: Int): Int {
        val density = appContext.resources.displayMetrics.density
        return (dp * density).toInt()
    }

    /**
     * Them nut noi len man hinh voi trang thai ban dau. An toan de goi nhieu
     * lan (neu da them roi, chi cap nhat lai trang thai qua updateState()).
     */
    fun show(initiallyRunning: Boolean) {
        if (isAdded) {
            updateState(initiallyRunning)
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
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            // ✅ Bo tron nhe qua GradientDrawable de trong giong 1 nut FAB nho,
            // khong can file drawable XML rieng.
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 30, 30, 30))
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
                        logBoth("👆 [Toggle] Nguoi dung bam nut noi de bat/tat Mixer Test.")
                        // ✅ Rung phan hoi ngan, doc lap voi bat ky am thanh nao -
                        // xac nhan cu bam da toi noi ngay ca khi nguoi dung dang
                        // xem video toan man hinh khong nghe ro tieng.
                        vibrate()
                        flashFeedback(view as TextView)
                        onToggle()
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
            applyState(button, initiallyRunning)
            logBoth("✅ Da hien nut noi 'Bat/Tat Mixer Test', trang thai ban dau=" + if (initiallyRunning) "BAT" else "TAT")
        } catch (e: Exception) {
            logBoth("❌ Khong the them nut noi vao WindowManager: ${e.message}", isError = true)
        }
    }

    /**
     * ✅ MOI: cap nhat lai icon/mau cua nut theo trang thai Mixer Test HIEN
     * TAI - goi tu PlaybackCaptureService NGAY SAU khi startMixerTestInternal()/
     * stopMixerTestInternal() hoan tat (bat ke duoc kich hoat tu chinh nut nay
     * hay tu nut trong app/notification), de nut LUON phan anh dung trang
     * thai thuc te, khong bi "lech" so voi Mixer Test dang thuc su chay hay
     * khong.
     */
    fun updateState(isRunning: Boolean) {
        val view = buttonView ?: return
        applyState(view, isRunning)
    }

    private fun applyState(view: TextView, isRunning: Boolean) {
        view.text = if (isRunning) ICON_RUNNING else ICON_STOPPED
        val bgColor = if (isRunning) {
            Color.argb(220, 0, 130, 60) // xanh la - dang chay, bam se TAT
        } else {
            Color.argb(220, 110, 30, 30) // do sam - dang tat, bam se BAT
        }
        (view.background as? GradientDrawable)?.setColor(bgColor)
    }

    /**
     * ✅ Rung ngan (~60ms) doc lap voi audio - phan hoi xac nhan cu bam da
     * toi noi. An toan bo qua neu thiet bi khong co Vibrator hoac bi loi
     * (khong lam gian doan viec bat/tat Mixer Test).
     */
    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(60L)
            }
        } catch (e: Exception) {
            logBoth("⚠️ Loi khi rung phan hoi (khong nghiem trong): ${e.message}")
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

    /**
     * Go nut noi khoi man hinh HOAN TOAN - CHI goi khi ca phien capture
     * (Phase 1) ket thuc, KHONG goi khi chi Mixer Test bi tat (luc do dung
     * updateState(false) de giu nut o lai, doi sang trang thai TAT). An toan
     * de goi nhieu lan / khi chua show().
     */
    fun hide() {
        if (!isAdded) return
        try {
            buttonView?.let { windowManager?.removeView(it) }
            logBoth("🛑 Da an nut noi (Phase 1 ket thuc hoan toan).")
        } catch (e: Exception) {
            logBoth("⚠️ Loi khi go nut noi (khong nghiem trong): ${e.message}")
        } finally {
            buttonView = null
            windowManager = null
            isAdded = false
        }
    }
}