package com.karaokeapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * Entry point tam thoi. Chua co UI that.
 * Phase 1: chi dung de trigger test AudioPlaybackCapture.
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Karaoke App - Phase 1: AudioPlaybackCapture test");
        setContentView(tv);
    }
}
