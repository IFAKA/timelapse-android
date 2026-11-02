package com.faka.timelapse;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * Opened by an alarm (START: the daily run begins, RUN: a run asked for from the web page). It has no picture.
 * Android gives the camera to a service only when the app has a screen, so the recording starts from here.
 */
public class RunActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String a = getIntent().getAction();
        Log.i("Timelapse", "alarm " + a);
        try {
            if ("START".equals(a)) {
                if (Daily.on(this)) {
                    Daily.scheduleNext(this);                   // tomorrow first, so a failure today does not stop the series
                    if (!Ui.recordingAlive(this)) Daily.startRun(this);
                }
            } else if ("RUN".equals(a)) {
                android.content.SharedPreferences r = getSharedPreferences("tl_run", MODE_PRIVATE);
                Plan.start(this, r.getLong("endAt", 0), r.getInt("interval", 30), r.getFloat("thr", 0), r.getInt("luma", 0));
            }
        } catch (Exception e) {
            Log.w("Timelapse", "run failed: " + e);
        }
        getWindow().getDecorView().postDelayed(this::finish, 2000);
    }
}
