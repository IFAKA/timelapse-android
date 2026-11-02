package com.faka.timelapse;

import android.Manifest;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.*;
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
    static final int[] KEEP = {7, 14, 30};              // days to keep daily timelapses

    SharedPreferences sp;
    TextView title, moreBtn, stateLabel, luxNum, summary, summary2, shots, fromBtn, toBtn, autoHint;
    Ui.LightBar bar;
    Button main;
    LinearLayout options, onceBox, dailyBox, keepBox, moreBox;
    boolean moreOpen;
    Chips lenChips, whenChips, modeChips, keepChips;
    TextView untilChip;
    EditText vidEdit, hoursEdit;
    Chips dayChips;
    LinearLayout dayTimes;
    boolean allDay() { return sh == 0 && sm == 0 && eh == 0 && em == 0; }
    LinearLayout hoursRow;
    float hoursVal = 4;      // hours to film, any value; the last one used is kept
    boolean useUntil;        // film until a clock time instead of for some hours
    int videoSec = 60;   // seconds of finished video; any length, the last one used is kept
    int modeIdx, keepIdx, untilH, untilM, sh, sm, eh, em;
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
                Ui.press(t).setOnClickListener(x -> { select(k); on.accept(k); });
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

    /** A pill-shaped number field. decimal = allow a fraction (hours). */
    EditText numberBox(boolean decimal) {
        EditText e = new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | (decimal ? android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        e.setSingleLine(true);
        e.setTextSize(18); e.setTextColor(Ui.AMBER); e.setGravity(Gravity.CENTER);
        e.setBackground(Ui.pill(this, Ui.LINE, 10));
        return e;
    }

    static String fmtHours(float h) { return h == Math.round(h) ? String.valueOf(Math.round(h)) : String.valueOf(h); }

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
        Ui.press(t).setOnClickListener(v -> pick.run());
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        sp = getSharedPreferences("tl", MODE_PRIVATE);
        modeIdx = Daily.on(this) ? 1 : sp.getInt("modeIdx", 0);
        hoursVal = sp.getFloat("hoursVal", 4); useUntil = sp.getBoolean("useUntil", false);
        untilH = sp.getInt("untilH", 18); untilM = sp.getInt("untilM", 0);
        sh = Daily.sh(this); sm = Daily.sm(this); eh = Daily.eh(this); em = Daily.em(this);
        videoSec = Daily.video(this);
        keepIdx = Math.max(0, Arrays.binarySearch(KEEP, Daily.keep(this)));
        auto = Daily.auto(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG); sv.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root);
        int pad = Ui.dp(this, 24);
        root.addView(Ui.back(this, "Timelapses"));

        // Title and one plain line about the light, so the user knows the camera will see something
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(pad, Ui.dp(this, 8), pad, 0);
        stateLabel = Ui.caps(this, "", Ui.AMBER);
        title = Ui.thin(this, "New timelapse", 34);
        luxNum = Ui.text(this, "", 14, Ui.DIM);
        luxNum.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
        bar = new Ui.LightBar(this);
        top.addView(stateLabel); top.addView(title); top.addView(luxNum);
        top.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 20)));
        root.addView(top);

        // The two questions that matter. Everything has a ready-made answer, so Start works at once.
        options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(pad, 0, pad, 0);

        // Repeat tabs sit on top, right under the light line
        modeChips = new Chips(new String[]{"Just once", "Every day"}, modeIdx, i -> { modeIdx = i; save(); refresh(); });
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.VERTICAL);
        tabs.setPadding(0, Ui.dp(this, 22), 0, 0);
        tabs.addView(modeChips.row);
        options.addView(tabs);

        // Film for: a number of hours OR until a clock time
        lenChips = new Chips(new String[]{"For hours", "Until a time"}, useUntil ? 1 : 0, i -> { useUntil = i == 1; save(); refresh(); });
        hoursEdit = numberBox(true);
        hoursEdit.setText(fmtHours(hoursVal));
        hoursEdit.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                try { hoursVal = Math.max(0.1f, Math.min(72f, Float.parseFloat(s.toString().replace(',', '.')))); } catch (NumberFormatException e) { return; }
                save(); refresh();
            }
        });
        hoursRow = new LinearLayout(this);
        hoursRow.addView(hoursEdit, new LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 48)));
        TextView hu = Ui.text(this, "hours", 15, Ui.DIM);
        hu.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
        hl.setMarginStart(Ui.dp(this, 12));
        hoursRow.addView(hu, hl);
        untilChip = timeBtn(() -> pickTime(untilH, untilM, (h, m) -> { untilH = h; untilM = m; }));
        onceBox = new LinearLayout(this);
        ((LinearLayout) onceBox).setOrientation(LinearLayout.VERTICAL);
        LinearLayout lenBox = new LinearLayout(this);
        lenBox.setOrientation(LinearLayout.VERTICAL);
        lenBox.addView(lenChips.row);
        LinearLayout.LayoutParams sp1 = new LinearLayout.LayoutParams(-1, -2);
        sp1.topMargin = Ui.dp(this, 10);
        lenBox.addView(hoursRow, sp1);
        lenBox.addView(untilChip, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        ((LinearLayout.LayoutParams) untilChip.getLayoutParams()).topMargin = Ui.dp(this, 10);
        onceBox.addView(group("FILM FOR", lenBox));
        options.addView(onceBox);

        LinearLayout tr = new LinearLayout(this);
        fromBtn = timeBtn(() -> pickTime(sh, sm, (h, m) -> { sh = h; sm = m; }));
        toBtn = timeBtn(() -> pickTime(eh, em, (h, m) -> { eh = h; em = m; }));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        l1.setMarginEnd(Ui.dp(this, 6));
        tr.addView(fromBtn, l1); tr.addView(toBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        dailyBox = new LinearLayout(this);
        ((LinearLayout) dailyBox).setOrientation(LinearLayout.VERTICAL);
        // Start = end (00:00 to 00:00) means the whole day. That is the default; "Set hours" shows the two times.
        dayChips = new Chips(new String[]{"All day", "Set hours"}, allDay() ? 0 : 1, i -> {
            if (Daily.on(this)) { dayChips.select(allDay() ? 0 : 1); return; }
            if (i == 0) { sh = sm = eh = em = 0; }
            else if (allDay()) { sh = 8; sm = 0; eh = 20; em = 0; }
            save(); refresh();
        });
        LinearLayout dbox = new LinearLayout(this);
        dbox.setOrientation(LinearLayout.VERTICAL);
        dbox.addView(dayChips.row);
        dayTimes = tr;
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.topMargin = Ui.dp(this, 10);
        dbox.addView(tr, dl);
        dailyBox.addView(group("FILM EVERY DAY", dbox));
        options.addView(dailyBox);

        vidEdit = numberBox(false);
        vidEdit.setText(String.valueOf(videoSec));
        vidEdit.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                try { videoSec = Math.max(5, Math.min(3600, Integer.parseInt(s.toString()))); } catch (NumberFormatException e) { return; }
                save(); refresh();
            }
        });
        TextView unit = Ui.text(this, "seconds", 15, Ui.DIM);
        unit.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout vrow = new LinearLayout(this);
        vrow.addView(vidEdit, new LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 48)));
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
        ul.setMarginStart(Ui.dp(this, 12));
        vrow.addView(unit, ul);
        options.addView(group("MAKE A VIDEO OF", vrow));

        // Rarely needed settings stay folded away
        moreBtn = Ui.caps(this, "", Ui.DIM);
        moreBtn.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 6));
        Ui.press(moreBtn).setOnClickListener(v -> { moreOpen = !moreOpen; refresh(); });
        options.addView(moreBtn);
        moreBox = new LinearLayout(this);
        moreBox.setOrientation(LinearLayout.VERTICAL);
        whenChips = new Chips(new String[]{"Only when bright", "Always"}, auto ? 0 : 1, i -> { auto = i == 0; save(); refresh(); });
        LinearLayout wg = group("TAKE PHOTOS", whenChips.row);
        autoHint = Ui.text(this, "", 13, Ui.DIM);
        autoHint.setPadding(0, Ui.dp(this, 8), 0, 0);
        wg.addView(autoHint);
        moreBox.addView(wg);
        keepChips = new Chips(new String[]{"7 days", "14 days", "30 days"}, keepIdx, i -> { keepIdx = i; save(); refresh(); });
        keepBox = group("DELETE DAILY TIMELAPSES AFTER", keepChips.row);
        moreBox.addView(keepBox);
        options.addView(moreBox);
        root.addView(options);

        // What will happen, in one sentence, right above the button
        LinearLayout sm_ = new LinearLayout(this);
        sm_.setOrientation(LinearLayout.VERTICAL);
        sm_.setPadding(pad, Ui.dp(this, 28), pad, 0);
        summary = Ui.text(this, "", 17, Ui.TXT);
        summary2 = Ui.text(this, "", 13, Ui.DIM); summary2.setPadding(0, Ui.dp(this, 4), 0, 0);
        sm_.addView(summary); sm_.addView(summary2);
        root.addView(sm_);

        // One button, always labelled with what it does
        main = new Button(this);
        main.setAllCaps(false); main.setTextSize(18); main.setStateListAnimator(null);
        main.setTypeface(null, android.graphics.Typeface.BOLD);
        shots = Ui.text(this, "", 14, Ui.DIM); shots.setGravity(Gravity.CENTER);
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 40));
        mid.addView(main, new LinearLayout.LayoutParams(-1, Ui.dp(this, 60)));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = Ui.dp(this, 14);
        mid.addView(shots, sl);
        root.addView(mid);

        Ui.press(main).setOnClickListener(v -> {
            if (running) Ui.sheet(this, "Stop this run?", "The photos so far are kept, but this run cannot be continued. Daily runs still start on time.", "Stop", true, this::stopAndOpen);
            else if (modeIdx == 1) toggleDaily();
            else start(endAtMs(), intervalSec(endAtMs() - System.currentTimeMillis()));
        });
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
        sp.edit().putInt("modeIdx", modeIdx).putFloat("hoursVal", hoursVal).putBoolean("useUntil", useUntil).putInt("untilH", untilH).putInt("untilM", untilM).apply();
        Daily.save(this, sh, sm, eh, em, videoSec, auto, KEEP[keepIdx]);
    }

    interface TimeSet { void set(int h, int m); }

    void pickTime(int h, int m, TimeSet set) {
        new TimePickerDialog(this, (v, hh, mm) -> { set.set(hh, mm); save(); refresh(); }, h, m, true).show();
    }

    long endAtMs() {
        if (!useUntil) return System.currentTimeMillis() + (long) (hoursVal * 3600000L);
        return Plan.nextClock(untilH, untilM);
    }

    int intervalSec(long durMs) { return Plan.intervalSec(durMs, videoSec); }

    void start(long endAt, int interval) {
        save();
        Plan.start(this, endAt, interval, auto);
        refresh();
    }

    void toggleDaily() {
        save();
        if (Daily.on(this)) { Daily.disable(this); refresh(); return; }
        if (!getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms()) {
            ask("Allow alarms", "So the phone can wake up at your start time, even when it is locked.",
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            ask("Allow display over other apps", "Android only lets an app use the camera in the background if it can show a screen. "
                    + "Nothing is shown. It only starts the recording while you sleep.", Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            return;
        }
        Daily.enable(this);
        Toast.makeText(this, "Daily is on. Next run: " + clock(Daily.nextStart(this)), Toast.LENGTH_LONG).show();
        refresh();
    }

    /** Say why a permission is needed before sending the user to the Android settings page for it. */
    void ask(String title, String why, String action) {
        Ui.sheet(this, title, why + " Then come back and press the button again.", "Open settings", false,
                () -> startActivity(new Intent(action, Uri.parse("package:" + getPackageName()))));
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
                : daily ? "DAILY ON  ·  NEXT " + clock(Daily.nextStart(this)) : "");
        stateLabel.setVisibility(stateLabel.length() == 0 ? View.GONE : View.VISIBLE);
        stateLabel.setTextColor(rec ? Ui.RED : Ui.AMBER);
        title.setText(running ? "Filming" : "New timelapse");
        boolean dark = auto && l >= 0 && l < Plan.AUTO_LUX;
        luxNum.setText(l < 0 ? "Measuring the light…" : dark
                ? "Too dark to film right now. It waits for light."
                : "Enough light to film (" + Math.round(l) + " lux)");
        luxNum.setTextColor(dark ? Ui.AMBER : Ui.DIM);
        bar.lux = Math.max(0, l); bar.thr = auto ? Plan.AUTO_LUX : 0; bar.invalidate();
        options.setVisibility(running ? View.GONE : View.VISIBLE);
        onceBox.setVisibility(dmode ? View.GONE : View.VISIBLE);
        dailyBox.setVisibility(dmode ? View.VISIBLE : View.GONE);
        keepBox.setVisibility(dmode ? View.VISIBLE : View.GONE);
        moreBtn.setText(moreOpen ? "FEWER OPTIONS  ˄" : "MORE OPTIONS  ˅");
        moreBox.setVisibility(moreOpen ? View.VISIBLE : View.GONE);
        autoHint.setText(auto ? "Skips photos when it is too dark to see anything. A dim room lit by a screen counts as bright enough."
                : "Takes a photo every time, even in the dark.");
        fromBtn.setText("From " + hm(sh, sm)); toBtn.setText("To " + hm(eh, em));
        dayChips.select(allDay() ? 0 : 1);
        dayTimes.setVisibility(allDay() ? View.GONE : View.VISIBLE);
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
            summary.setText((allDay() ? "Films all day" : "Films " + hm(sh, sm) + " to " + hm(eh, em)) + ", then makes a " + videoSec + " s video.");
            summary2.setText("Runs by itself every day. A photo every " + iv + " s, about " + (dur / 1000 / iv) + " photos.");
            shots.setText(daily ? "Nothing to do. Videos appear in your list." : "");
        } else {
            long end = endAtMs(), dur = end - System.currentTimeMillis();
            int iv = intervalSec(dur);
            long photos = dur / 1000 / iv;
            summary.setText("Films until " + clock(end) + ", then makes a " + (iv > 3 && iv < 300 ? videoSec : Math.max(1, photos / Plan.FPS)) + " s video.");
            summary2.setText("A photo every " + iv + " s, about " + photos + " photos.");
            shots.setText("");
        }
        untilChip.setText("Until " + hm(untilH, untilM));
        hoursRow.setVisibility(useUntil ? View.GONE : View.VISIBLE);
        untilChip.setVisibility(useUntil ? View.VISIBLE : View.GONE);

        main.setText(running ? "Stop" : dmode ? (daily ? "Turn off daily" : "Turn on daily") : "Start filming");
        boolean quiet = !running && dmode && daily;
        main.setTextColor(running ? Ui.TXT : quiet ? Ui.TXT : Ui.BG);
        main.setBackground(Ui.pill(this, running ? Ui.RED : quiet ? Ui.LINE : Ui.AMBER, 30));
        shots.setVisibility(shots.length() == 0 ? View.GONE : View.VISIBLE);
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
