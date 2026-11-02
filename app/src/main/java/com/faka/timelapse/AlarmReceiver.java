package com.faka.timelapse;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Alarms: START (daily run begins) and FINISH (run is over: build the video, clean up). Also re-arms after reboot. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent in) {
        String a = in.getAction();
        Log.i("Timelapse", "alarm " + a);
        final PendingResult pr = goAsync();
        Ui.IO.execute(() -> {
            try {
                if ("FINISH".equals(a)) {
                    String sid = c.getSharedPreferences("tl_state", Context.MODE_PRIVATE).getString("session", "");
                    if (!sid.isEmpty() && !VideoService.working && Media.video(c, sid) == null
                            && !Media.photos(c, sid, false).isEmpty() && !Ui.recordingAlive(c)) Plan.makeVideo(c, sid);
                    if (Daily.on(c)) Daily.prune(c);
                } else {                                         // boot or app update
                    Daily.scheduleNext(c);
                    if (Daily.on(c) && Daily.windowActive(c) && !Ui.recordingAlive(c)) Daily.startRun(c);
                }
            } catch (Exception e) {
                Log.w("Timelapse", "alarm failed: " + e);
            } finally { pr.finish(); }
        });
    }
}
