package com.faka.timelapse;

import android.Manifest;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.IntConsumer;

/** Live screen: choose how long (once, or every day), press the shutter. The photo interval is worked out from that. */
public class RecordActivity extends Activity {
    static final int[] HOURS = {1, 2, 4, 8, 12, 0};      // 0 = until a clock time
    static final int[] VIDEO = {15, 30, 60};            // seconds of finished video
    static final int[] KEEP = {7, 14, 30};              // days to keep daily timelapses

    SharedPreferences sp;
    TextView stateLabel, luxNum, summary, summary2, shots, fromBtn, toBtn, autoHint;
    Ui.LightBar bar;
    View shutter;
    Button main, dailyBtn;
    LinearLayout options, onceBox, dailyBox, keepBox;
    Chips lenChips, vidChips, whenChips, modeChips, keepChips;
    TextView untilChip;
    int modeIdx, hoursIdx, videoIdx, keepIdx, untilH, untilM, sh, sm, eh, em;
    boolean auto, running, wasRunning;
    final Handler ui = new Handler(Looper.getMainLooper());
    final Runnable tick = new Runnable() { @Override public void run() { refresh(); ui.postDelayed(this, 1000); } };
    final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    /** A row of equal-width choices; one is selected. */
    final class Chips {
        final LinearLayout row = new LinearLayout(RecordActivity.this);
        final TextView[] v;
        Chips(String[] labels, int sel, IntConsumer on) {
            v = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int k = i;
                TextView t = Ui.text(RecordActivity.this, labels[i], 15, Ui.TXT);
                t.setGravity(Gravity.CENTER);
                t.setOnClickListener(x -> { select(k); on.accept(k); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(RecordActivity.this, 44), 1);
                lp.setMarginEnd(i < labels.length - 1 ? Ui.dp(RecordActivity.this, 6) : 0);
                row.addView(t, lp);
                v[i] = t;
            }
            select(sel);
        }
        void select(int i) {
            for (int k = 0; k < v.length; k++) {
                v[k].setBackground(Ui.pill(RecordActivity.this, k == i ? Ui.AMBER : Ui.LINE, 10));
                v[k].setTextColor(k == i ? Ui.BG : Ui.TXT);
                v[k].setTypeface(null, k == i ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }
    }

    LinearLayout group(String title, View content) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setPadding(0, Ui.dp(this, 22), 0, 0);
        TextView c = Ui.caps(this, title, Ui.DIM);
        c.setPadding(0, 0, 0, Ui.dp(this, 10));
        g.addView(c); g.addView(content);
        return g;
    }

    TextView timeBtn(Runnable pick) {
        TextView t = Ui.text(this, "", 17, Ui.AMBER);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.pill(this, Ui.LINE, 10));
        t.setOnClickListener(v -> pick.run());
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        sp = getSharedPreferences("tl", MODE_PRIVATE);
        modeIdx = Daily.on(this) ? 1 : sp.getInt("modeIdx", 0);
        hoursIdx = sp.getInt("hoursIdx", 2);
        untilH = sp.getInt("untilH", 18); untilM = sp.getInt("untilM", 0);
        sh = Daily.sh(this); sm = Daily.sm(this); eh = Daily.eh(this); em = Daily.em(this);
        videoIdx = Math.max(0, Arrays.binarySearch(VIDEO, Daily.video(this)));
        keepIdx = Math.max(0, Arrays.binarySearch(KEEP, Daily.keep(this)));
        auto = Daily.auto(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG); sv.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root);
        int pad = Ui.dp(this, 24);
        root.addView(Ui.back(this, "Timelapses"));

        // Light reading
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(pad, Ui.dp(this, 8), pad, 0);
        stateLabel = Ui.caps(this, "", Ui.AMBER);
        LinearLayout numRow = new LinearLayout(this);
        numRow.setGravity(Gravity.BOTTOM);
        luxNum = Ui.thin(this, "0", 72);
        TextView unit = Ui.text(this, " lux", 18, Ui.DIM); unit.setPadding(0, 0, 0, Ui.dp(this, 12));
        numRow.addView(luxNum); numRow.addView(unit);
        bar = new Ui.LightBar(this);
        top.addView(stateLabel); top.addView(numRow);
        top.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 28)));
        root.addView(top);

        // Options (hidden while recording)
        options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(pad, 0, pad, 0);
        modeChips = new Chips(new String[]{"Once", "Every day"}, modeIdx, i -> { modeIdx = i; save(); refresh(); });
        options.addView(group("SCHEDULE", modeChips.row));

        lenChips = new Chips(new String[]{"1 h", "2 h", "4 h", "8 h", "12 h", "Until"}, hoursIdx, i -> {
            hoursIdx = i;
            if (HOURS[i] == 0) pickTime(untilH, untilM, (h, m) -> { untilH = h; untilM = m; });
            save(); refresh();
        });
        untilChip = lenChips.v[5];
        onceBox = new LinearLayout(this);
        ((LinearLayout) onceBox).setOrientation(LinearLayout.VERTICAL);
        onceBox.addView(group("HOW LONG", lenChips.row));
        options.addView(onceBox);

        LinearLayout tr = new LinearLayout(this);
        fromBtn = timeBtn(() -> pickTime(sh, sm, (h, m) -> { sh = h; sm = m; }));
        toBtn = timeBtn(() -> pickTime(eh, em, (h, m) -> { eh = h; em = m; }));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        l1.setMarginEnd(Ui.dp(this, 6));
        tr.addView(fromBtn, l1); tr.addView(toBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        dailyBox = new LinearLayout(this);
        ((LinearLayout) dailyBox).setOrientation(LinearLayout.VERTICAL);
        dailyBox.addView(group("EVERY DAY, FROM AND TO", tr));
        options.addView(dailyBox);

        vidChips = new Chips(new String[]{"15 s", "30 s", "1 min"}, videoIdx, i -> { videoIdx = i; save(); refresh(); });
        options.addView(group("VIDEO LENGTH", vidChips.row));
        whenChips = new Chips(new String[]{"Auto", "Always"}, auto ? 0 : 1, i -> { auto = i == 0; save(); refresh(); });
        LinearLayout wg = group("PHOTOS", whenChips.row);
        autoHint = Ui.text(this, "", 13, Ui.DIM);
        autoHint.setPadding(0, Ui.dp(this, 8), 0, 0);
        wg.addView(autoHint);
        options.addView(wg);

        keepChips = new Chips(new String[]{"7 days", "14 days", "30 days"}, keepIdx, i -> { keepIdx = i; save(); refresh(); });
        keepBox = group("KEEP OLD TIMELAPSES", keepChips.row);
        options.addView(keepBox);
        root.addView(options);

        // Plain-words summary of what will happen
        LinearLayout sm_ = new LinearLayout(this);
        sm_.setOrientation(LinearLayout.VERTICAL);
        sm_.setPadding(pad, Ui.dp(this, 28), pad, 0);
        summary = Ui.text(this, "", 17, Ui.AMBER);
        summary2 = Ui.text(this, "", 13, Ui.DIM); summary2.setPadding(0, Ui.dp(this, 4), 0, 0);
        sm_.addView(summary); sm_.addView(summary2);
        root.addView(sm_);

        // Shutter (one time) and daily switch
        FrameLayout sh_ = new FrameLayout(this);
        shutter = new View(this);
        sh_.addView(shutter, new FrameLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 88), Gravity.CENTER));
        main = new Button(this); main.setBackgroundColor(0); main.setStateListAnimator(null);
        sh_.addView(main, new FrameLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 88), Gravity.CENTER));
        dailyBtn = new Button(this);
        dailyBtn.setAllCaps(false); dailyBtn.setTextSize(17); dailyBtn.setStateListAnimator(null);
        dailyBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        sh_.addView(dailyBtn, new FrameLayout.LayoutParams(-1, Ui.dp(this, 60), Gravity.CENTER));
        shots = Ui.text(this, "", 14, Ui.DIM); shots.setGravity(Gravity.CENTER);
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(pad, Ui.dp(this, 28), pad, Ui.dp(this, 40));
        mid.addView(sh_, new LinearLayout.LayoutParams(-1, Ui.dp(this, 88)));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = Ui.dp(this, 14);
        mid.addView(shots, sl);
        root.addView(mid);

        main.setOnClickListener(v -> {
            if (running) stopAndOpen(); else start(endAtMs(), intervalSec(endAtMs() - System.currentTimeMillis()));
        });
        dailyBtn.setOnClickListener(v -> toggleDaily());
        setContentView(sv);
        requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS}, 1);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent in) { super.onNewIntent(in); setIntent(in); handle(in); }

    /** adb: --ez autostart true [--ei minutes N] [--ei interval S] [--ez always true] */
    void handle(Intent in) {
        if (!in.getBooleanExtra("autostart", false)) return;
        Plan.stop(this);
        long end = in.hasExtra("minutes") ? System.currentTimeMillis() + in.getIntExtra("minutes", 60) * 60000L : endAtMs();
        int iv = in.hasExtra("interval") ? in.getIntExtra("interval", 30) : intervalSec(end - System.currentTimeMillis());
        if (in.hasExtra("always")) auto = !in.getBooleanExtra("always", false);
        start(end, iv);
        in.removeExtra("autostart");
    }

    void save() {
        sp.edit().putInt("modeIdx", modeIdx).putInt("hoursIdx", hoursIdx).putInt("untilH", untilH).putInt("untilM", untilM).apply();
        Daily.save(this, sh, sm, eh, em, VIDEO[videoIdx], auto, KEEP[keepIdx]);
    }

    interface TimeSet { void set(int h, int m); }

    void pickTime(int h, int m, TimeSet set) {
        new TimePickerDialog(this, (v, hh, mm) -> { set.set(hh, mm); save(); refresh(); }, h, m, true).show();
    }

    long endAtMs() {
        if (HOURS[hoursIdx] > 0) return System.currentTimeMillis() + HOURS[hoursIdx] * 3600000L;
        return Plan.nextClock(untilH, untilM);
    }

    int intervalSec(long durMs) { return Plan.intervalSec(durMs, VIDEO[videoIdx]); }

    void start(long endAt, int interval) {
        save();
        Plan.start(this, endAt, interval, auto);
        refresh();
    }

    void toggleDaily() {
        save();
        if (Daily.on(this)) { Daily.disable(this); refresh(); return; }
        if (!getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms()) {
            Toast.makeText(this, "Allow alarms for Timelapse, then press again", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" so it can start the camera while you sleep, then press again", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        Daily.enable(this);
        refresh();
    }

    void stopAndOpen() {
        SharedPreferences s = getSharedPreferences("tl_state", MODE_PRIVATE);
        String sid = s.getString("session", ""); int n = s.getInt("count", 0);
        Plan.stop(this);
        wasRunning = false; running = false;
        if (n > 0 && !sid.isEmpty()) startActivity(new Intent(this, DetailActivity.class).putExtra("session", sid));
        finish();
    }

    String clock(long ms) { return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms)); }
    static String hm(int h, int m) { return String.format(Locale.US, "%02d:%02d", h, m); }

    void refresh() {
        SharedPreferences s = getSharedPreferences("tl_state", MODE_PRIVATE);
        String st = s.getString("status", "STOPPED");
        float l = s.getFloat("lux", -1);
        int n = s.getInt("count", 0);
        running = !st.equals("STOPPED") && Ui.recordingAlive(this);
        if (!running && !st.equals("STOPPED")) s.edit().putString("status", "STOPPED").apply();
        if (wasRunning && !running) {   // finished on its own (end time reached)
            String sid = s.getString("session", "");
            wasRunning = false;
            if (n > 0 && !sid.isEmpty()) startActivity(new Intent(this, DetailActivity.class).putExtra("session", sid));
            finish();
            return;
        }
        wasRunning = running;
        boolean rec = running && st.equals("RECORDING"), daily = Daily.on(this), dmode = modeIdx == 1;
        stateLabel.setText(rec ? "●  RECORDING" : running ? "WAITING FOR LIGHT"
                : daily ? "DAILY ON  ·  NEXT " + clock(Daily.nextStart(this)) : "READY");
        stateLabel.setTextColor(rec ? Ui.RED : running || daily ? Ui.AMBER : Ui.DIM);
        luxNum.setText(l < 0 ? "–" : String.valueOf(Math.round(l)));
        bar.lux = Math.max(0, l); bar.thr = auto ? Plan.AUTO_LUX : 0; bar.invalidate();
        options.setVisibility(running ? View.GONE : View.VISIBLE);
        onceBox.setVisibility(dmode ? View.GONE : View.VISIBLE);
        dailyBox.setVisibility(dmode ? View.VISIBLE : View.GONE);
        keepBox.setVisibility(dmode ? View.VISIBLE : View.GONE);
        autoHint.setText(auto ? "Takes a photo only when there is enough light to see something. A dim room lit by a screen counts."
                : "Takes a photo every time, even when it is dark.");
        fromBtn.setText("From " + hm(sh, sm)); toBtn.setText("To " + hm(eh, em));
        fromBtn.setEnabled(!daily); toBtn.setEnabled(!daily);

        if (running) {
            long end = s.getLong("endAt", 0), left = end - System.currentTimeMillis();
            summary.setText(end > 0 ? "Stops at " + clock(end) : "Running");
            summary2.setText(end > 0 ? Ui.duration(Math.max(0, left)) + " left" : "");
            shots.setText(n + " photos so far");
        } else if (dmode) {
            long dur = ((eh * 60 + em) - (sh * 60 + sm) + 1440) % 1440 * 60000L;
            if (dur == 0) dur = 86400000L;
            int iv = intervalSec(dur);
            summary.setText("A photo every " + iv + " s");
            summary2.setText("Every day " + hm(sh, sm) + " → " + hm(eh, em) + " · about " + (dur / 1000 / iv) + " photos · "
                    + VIDEO[videoIdx] + " s video · keeps " + KEEP[keepIdx] + " days");
            shots.setText(daily ? "The video is built after each run. Nothing to do." : "Turn on once. It runs by itself every day.");
        } else {
            long end = endAtMs(), dur = end - System.currentTimeMillis();
            int iv = intervalSec(dur);
            long photos = dur / 1000 / iv;
            summary.setText("A photo every " + iv + " s");
            summary2.setText("About " + photos + " photos · " + Math.max(1, photos / Plan.FPS) + " s video · stops at " + clock(end));
            shots.setText("Press to start");
        }
        untilChip.setText(HOURS[hoursIdx] == 0 ? hm(untilH, untilM) : "Until");

        boolean showShutter = running || !dmode;
        shutter.setVisibility(showShutter ? View.VISIBLE : View.GONE);
        main.setVisibility(showShutter ? View.VISIBLE : View.GONE);
        dailyBtn.setVisibility(showShutter ? View.GONE : View.VISIBLE);
        dailyBtn.setText(daily ? "Turn off daily" : "Turn on daily");
        dailyBtn.setTextColor(daily ? Ui.TXT : Ui.BG);
        dailyBtn.setBackground(daily ? Ui.pill(this, Ui.LINE, 30) : Ui.pill(this, Ui.AMBER, 30));

        GradientDrawable g = Ui.pill(this, running ? Ui.RED : Ui.AMBER, running ? 10 : 36);
        int inset = Ui.dp(this, running ? 26 : 12);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL); ring.setColor(0); ring.setStroke(Ui.dp(this, 3), Ui.TXT);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{ring, g});
        ld.setLayerInset(1, inset, inset, inset, inset);
        shutter.setBackground(ld);
        main.setContentDescription(running ? "Stop" : "Start");
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(rx, new IntentFilter(TimelapseService.ACTION), RECEIVER_NOT_EXPORTED);
        ui.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        save();
        unregisterReceiver(rx);
        ui.removeCallbacks(tick);
    }
}
