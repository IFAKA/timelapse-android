package com.faka.timelapse;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/** Automatic daily timelapse: starts at a time every day, runs until another time, builds the video, cleans up old ones. */
final class Daily {
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("tl_daily", Context.MODE_PRIVATE); }

    static boolean on(Context c) { return sp(c).getBoolean("on", false); }
    static int sh(Context c) { return sp(c).getInt("sh", 7); }
    static int sm(Context c) { return sp(c).getInt("sm", 0); }
    static int eh(Context c) { return sp(c).getInt("eh", 19); }
    static int em(Context c) { return sp(c).getInt("em", 0); }
    static int video(Context c) { return sp(c).getInt("video", 30); }
    static boolean auto(Context c) { return sp(c).getBoolean("auto", true); }
    static int keep(Context c) { return sp(c).getInt("keep", 14); }

    static void save(Context c, int sh, int sm, int eh, int em, int video, boolean auto, int keep) {
        sp(c).edit().putInt("sh", sh).putInt("sm", sm).putInt("eh", eh).putInt("em", em)
                .putInt("video", video).putBoolean("auto", auto).putInt("keep", keep).apply();
        if (on(c)) scheduleNext(c);
    }

    static long nextStart(Context c) { return Plan.nextClock(sh(c), sm(c)); }

    /** Length of one daily run. Start = end means a full day. */
    static long windowMs(Context c) {
        long m = ((eh(c) * 60 + em(c)) - (sh(c) * 60 + sm(c)) + 1440) % 1440;
        return (m == 0 ? 1440 : m) * 60000L;
    }

    /** Are we inside today's window right now? (The next end comes before the next start.) */
    static boolean windowActive(Context c) { return Plan.nextClock(eh(c), em(c)) < nextStart(c); }

    static void scheduleNext(Context c) {
        c.getSystemService(android.app.AlarmManager.class).cancel(Plan.pi(c, "START"));
        if (on(c)) Plan.alarm(c, "START", nextStart(c));
    }

    static void enable(Context c) {
        sp(c).edit().putBoolean("on", true).apply();
        scheduleNext(c);
        if (windowActive(c) && !Ui.recordingAlive(c)) startRun(c);
    }

    static void disable(Context c) {
        sp(c).edit().putBoolean("on", false).apply();
        c.getSystemService(android.app.AlarmManager.class).cancel(Plan.pi(c, "START"));
    }

    static void startRun(Context c) {
        long end = Plan.nextClock(eh(c), em(c));
        int iv = Plan.intervalSec(end - System.currentTimeMillis(), video(c));
        Plan.start(c, end, iv, auto(c) ? Plan.AUTO_LUX : 0, auto(c) ? Plan.AUTO_LUMA : 0);
    }

    /** Delete timelapses older than the keep setting. */
    static void prune(Context c) {
        long cutoff = System.currentTimeMillis() - keep(c) * 86400000L;
        String live = c.getSharedPreferences("tl_state", Context.MODE_PRIVATE).getString("session", "");
        List<Media.Session> all = Media.sessions(c);
        for (Media.Session s : all) if (s.startMs < cutoff && !s.id.equals(live)) Media.delete(c, s.id);
    }
}
