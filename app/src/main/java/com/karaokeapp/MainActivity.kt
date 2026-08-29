package com.karaokeapp

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Entry point tam thoi, chua co UI that.
 * Phase 1: chi dung de trigger va quan sat ket qua test AudioPlaybackCapture.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "Karaoke App - Phase 1: AudioPlaybackCapture test"
        setContentView(tv)
    }
}
