package com.faka.timelapse;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/** The rule that turns "how long" and "how long a video" into a photo interval, and starts a run. */
final class Plan {
    static final int FPS = 24;
    static final int AUTO_LUX = 1;      // below this the sensor says pitch black: do not even try
    static final int AUTO_LUMA = 18;    // frames darker than this are not saved

    /** Seconds between photos so that the run fills the wanted video length at 24 frames per second. */
    static int intervalSec(long durMs, int videoSec) {
        long s = Math.round(durMs / 1000.0 / (videoSec * (double) FPS));
        return (int) Math.max(3, Math.min(300, s));
    }

    /** Next time the clock shows h:m (today, or tomorrow if that is almost now). */
    static long nextClock(int h, int m) {
        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now + 60000) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** Auto = only photos a person can see. Always = every photo, even black ones. */
    static void start(Context c, long endAt, int interval, boolean auto) {
        start(c, endAt, interval, auto ? (float) AUTO_LUX : 0f, auto ? AUTO_LUMA : 0);
    }

    static void start(Context c, long endAt, int interval, float threshold, int minLuma) {
        Intent i = new Intent(c, TimelapseService.class);
        i.putExtra("threshold", threshold).putExtra("minLuma", minLuma)
                .putExtra("interval", (float) interval).putExtra("dark", 10f).putExtra("endAt", endAt);
        c.startForegroundService(i);
        c.getSharedPreferences("tl_state", Context.MODE_PRIVATE).edit().putString("status", "WAITING").apply();
        if (endAt > 0) alarm(c, "FINISH", endAt + 20000);   // build the video when the run is over
    }

    /**
     * Start a run when no screen of the app is open (web page, daily timer). Android only gives the camera to a
     * background service if it is started by an alarm, so go through one.
     */
    static void startBg(Context c, long endAt, int interval, float threshold, int minLuma) {
        c.getSharedPreferences("tl_run", Context.MODE_PRIVATE).edit().putLong("endAt", endAt).putInt("interval", interval)
                .putFloat("thr", threshold).putInt("luma", minLuma).apply();
        c.getSharedPreferences("tl_state", Context.MODE_PRIVATE).edit().putString("status", "WAITING").apply();
        alarm(c, "RUN", System.currentTimeMillis() + 300);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, TimelapseService.class));
        c.getSharedPreferences("tl_state", Context.MODE_PRIVATE).edit().putString("status", "STOPPED").apply();
    }

    static void makeVideo(Context c, String id) {
        c.getSharedPreferences("tl_state", Context.MODE_PRIVATE).edit()
                .putString("video", "Starting...").putString("video_session", id).apply();
        c.startForegroundService(new Intent(c, VideoService.class).putExtra("session", id));
    }

    static PendingIntent pi(Context c, String action) {
        if (action.equals("START") || action.equals("RUN"))      // these open a see-through screen: Android only gives the camera to an app that has a screen
            return PendingIntent.getActivity(c, action.hashCode(),
                    new Intent(c, RunActivity.class).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getBroadcast(c, action.hashCode(),
                new Intent(c, AlarmReceiver.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Wake the phone at a time, even when it is locked or idle. Alarms that start the camera (START, RUN) are alarm-clock
     * alarms that open RunActivity. They show a small alarm icon in the status bar.
     */
    static void alarm(Context c, String action, long at) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (action.equals("START") || action.equals("RUN")) {
            PendingIntent show = PendingIntent.getActivity(c, 1, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi(c, action));
            return;
        }
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, action));
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, action));
    }
}
