package com.karaokeapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.karaokeapp.audio.music.CaptureLogBus
import com.karaokeapp.audio.music.PlaybackCaptureService

/**
 * Activity trong suot, khong UI - tu chiem foreground trong choc lat de ep
 * he thong tao ra 1 su kien chuyen foreground THAT (roi khoi YouTube), day
 * la dieu kien can de xoa "duck" con sot ma nudge/recreate() goi tu Service
 * dang chay nen KHONG lam duoc (da xac nhan qua test thuc te).
 *
 * Flow: tu mo len -> cho TRANSITION_DELAY_MS -> goi Service recreate()
 * OutputRouter -> tu mo lai YouTube -> tu finish().
 */
class NudgeTransitionActivity : Activity() {

    companion object {
        private const val TAG = "NudgeTransitionActivity"
        private const val TRANSITION_DELAY_MS = 400L
        private const val TARGET_PACKAGE_YOUTUBE = "com.google.android.youtube"

        fun buildLaunchIntent(context: android.content.Context): Intent {
            return Intent(context, NudgeTransitionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
        }
    }

    private fun logBoth(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        CaptureLogBus.log("[NudgeTransition] $msg")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logBoth("✅ Da len foreground - dem ${TRANSITION_DELAY_MS}ms truoc khi recreate() + mo lai YouTube.")
    }

    override fun onResume() {
        super.onResume()
        Handler(Looper.getMainLooper()).postDelayed({
            performRecreateAndReturnToSource()
        }, TRANSITION_DELAY_MS)
    }

    private fun performRecreateAndReturnToSource() {
        logBoth("⏰ Het gio cho - goi Service recreate() OutputRouter.")
        try {
            startService(
                Intent(this, PlaybackCaptureService::class.java).apply {
                    action = PlaybackCaptureService.ACTION_MANUAL_NUDGE_RECREATE
                }
            )
        } catch (e: Exception) {
            logBoth("❌ Loi khi goi Service recreate(): ${e.message}", isError = true)
        }
        launchSourceAppAndFinish()
    }

    private fun launchSourceAppAndFinish() {
        val launchIntent = try {
            packageManager.getLaunchIntentForPackage(TARGET_PACKAGE_YOUTUBE)
        } catch (e: Exception) {
            logBoth("❌ Loi khi tim launch intent cho $TARGET_PACKAGE_YOUTUBE: ${e.message}", isError = true)
            null
        }

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try {
                startActivity(launchIntent)
                logBoth("✅ Da mo lai $TARGET_PACKAGE_YOUTUBE.")
            } catch (e: Exception) {
                logBoth("❌ Loi khi mo lai $TARGET_PACKAGE_YOUTUBE: ${e.message}", isError = true)
            }
        } else {
            logBoth("⚠️ Khong tim thay $TARGET_PACKAGE_YOUTUBE da cai dat.", isError = true)
        }

        finish()
    }
}
