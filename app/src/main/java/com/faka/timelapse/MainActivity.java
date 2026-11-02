package com.faka.timelapse;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.util.List;
import java.util.Set;

/** Library: every timelapse you have made, newest first. */
public class MainActivity extends Activity {
    final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    LinearLayout list, bar;
    TextView live, remote, daily, count;
    Button add;
    final Set<String> sel = new java.util.LinkedHashSet<>();
    List<Media.Session> lastS = new java.util.ArrayList<>();
    Set<String> lastV = new java.util.HashSet<>();
    final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateLive(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        int pad = Ui.dp(this, 24);
        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(Ui.BG); fl.setFitsSystemWindows(true);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, Ui.dp(this, 120));
        sv.addView(col);

        TextView title = Ui.thin(this, "Timelapses", 34);
        title.setPadding(pad, Ui.dp(this, 36), pad, Ui.dp(this, 16));
        col.addView(title);
        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 12));
        count = Ui.text(this, "", 17, Ui.TXT);
        bar.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        TextView all = Ui.caps(this, "SELECT ALL", Ui.AMBER);
        all.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        all.setOnClickListener(v -> selectAll());
        TextView del = Ui.caps(this, "DELETE", Ui.RED);
        del.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        del.setOnClickListener(v -> confirmDelete());
        TextView cancel = Ui.caps(this, "CANCEL", Ui.DIM);
        cancel.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), 0, Ui.dp(this, 12));
        cancel.setOnClickListener(v -> clearSelection());
        bar.addView(all); bar.addView(del); bar.addView(cancel);
        bar.setVisibility(View.GONE);
        col.addView(bar);
        live = Ui.caps(this, "", Ui.RED);
        live.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 14));
        live.setOnClickListener(v -> startActivity(new Intent(this, RecordActivity.class)));
        live.setVisibility(View.GONE);
        col.addView(live);
        daily = Ui.caps(this, "", Ui.AMBER);
        daily.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 14));
        daily.setOnClickListener(v -> startActivity(new Intent(this, RecordActivity.class)));
        col.addView(daily);
        remote = Ui.text(this, "", 13, Ui.DIM);
        remote.setPadding(pad, 0, pad, Ui.dp(this, 16));
        remote.setOnClickListener(v -> {
            String u = WebService.url(this);
            if (u == null) return;
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("link", u));
            Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show();
        });
        col.addView(remote);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);
        fl.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        add = new Button(this);
        add.setText("New timelapse"); add.setAllCaps(false); add.setTextSize(17);
        add.setTypeface(null, android.graphics.Typeface.BOLD);
        add.setTextColor(Ui.BG); add.setBackground(Ui.pill(this, Ui.AMBER, 30));
        add.setStateListAnimator(null); add.setPadding(pad * 2, 0, pad * 2, 0);
        Ui.press(add).setOnClickListener(v -> startActivity(new Intent(this, RecordActivity.class)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, Ui.dp(this, 60), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Ui.dp(this, 24);
        fl.addView(add, lp);
        setContentView(fl);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent in) { super.onNewIntent(in); setIntent(in); handle(in); }

    /** adb autostart extras are passed on to the record screen. */
    void handle(Intent in) {
        if (in.getBooleanExtra("autostart", false)) {
            startActivity(new Intent(this, RecordActivity.class).putExtras(in));
            in.removeExtra("autostart");
        }
    }

    void updateLive() {
        SharedPreferences s = getSharedPreferences("tl_state", MODE_PRIVATE);
        String st = s.getString("status", "STOPPED");
        boolean on = !st.equals("STOPPED") && Ui.recordingAlive(this);
        live.setVisibility(on ? View.VISIBLE : View.GONE);
        boolean d = Daily.on(this);
        daily.setVisibility(d ? View.VISIBLE : View.GONE);
        if (d) daily.setText(String.format("DAILY  ·  %02d:%02d → %02d:%02d   ›", Daily.sh(this), Daily.sm(this), Daily.eh(this), Daily.em(this)));
        if (on) live.setText((st.equals("RECORDING") ? "●  RECORDING" : "●  WAITING FOR LIGHT") + "  ·  " + s.getInt("count", 0) + " PHOTOS   ›");
    }

    void reload() {
        Ui.IO.execute(() -> {
            List<Media.Session> ss = Media.sessions(this);
            Set<String> videos = Media.videoIds(this);
            runOnUiThread(() -> show(ss, videos));
        });
    }

    void show(List<Media.Session> ss, Set<String> videos) {
        if (isDestroyed()) return;
        lastS = ss; lastV = videos;
        Set<String> ids = new java.util.HashSet<>();
        for (Media.Session x : ss) ids.add(x.id);
        sel.retainAll(ids);
        list.removeAllViews();
        int pad = Ui.dp(this, 24);
        if (ss.isEmpty()) {
            LinearLayout e = new LinearLayout(this);
            e.setOrientation(LinearLayout.VERTICAL);
            e.setPadding(pad, Ui.dp(this, 60), pad, 0);
            e.addView(Ui.text(this, "Nothing here yet", 20, Ui.TXT));
            TextView h = Ui.text(this, "Choose how long to record. The phone takes the photos and builds the video.", 15, Ui.DIM);
            h.setPadding(0, Ui.dp(this, 8), 0, 0);
            e.addView(h);
            list.addView(e);
            return;
        }
        list.addView(Ui.hairline(this));
        for (Media.Session s : ss) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 14));
            ImageView t = new ImageView(this);
            t.setScaleType(ImageView.ScaleType.CENTER_CROP); t.setBackgroundColor(Ui.LINE);
            FrameLayout tf = new FrameLayout(this);
            tf.addView(t, new FrameLayout.LayoutParams(-1, -1));
            boolean on = sel.contains(s.id);
            if (!sel.isEmpty()) {                                  // selecting: a ring on every photo, filled and ticked when chosen
                TextView ck = Ui.text(this, on ? "✓" : "", 15, Ui.BG);
                ck.setGravity(Gravity.CENTER);
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                g.setColor(on ? Ui.AMBER : 0x66000000); g.setStroke(Ui.dp(this, 2), on ? Ui.AMBER : Ui.TXT);
                ck.setBackground(g);
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.TOP | Gravity.START);
                cl.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), 0, 0);
                tf.addView(ck, cl);
                t.setAlpha(on ? 0.6f : 1f);
            }
            if (on) r.setBackgroundColor(0xFF1A1914);
            r.addView(tf, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(Ui.dp(this, 16), 0, 0, 0);
            c.addView(Ui.text(this, Media.name(this, s.id), 17, Ui.TXT));
            TextView m = Ui.text(this, s.count + " photos  ·  " + Ui.duration(s.endMs - s.startMs), 13, Ui.DIM);
            m.setPadding(0, Ui.dp(this, 4), 0, 0);
            c.addView(m);
            if (videos.contains(s.id)) {
                TextView v = Ui.caps(this, "VIDEO READY", Ui.AMBER);
                v.setPadding(0, Ui.dp(this, 6), 0, 0);
                c.addView(v);
            }
            r.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(Ui.text(this, "›", 22, Ui.DIM));
            Ui.press(r).setOnClickListener(v -> {
                if (sel.isEmpty()) startActivity(new Intent(this, DetailActivity.class).putExtra("session", s.id));
                else toggle(s.id);
            });
            r.setOnLongClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); toggle(s.id); return true; });
            list.addView(r);
            list.addView(Ui.hairline(this));
            Ui.IO.execute(() -> {
                Bitmap bm = Media.load(this, s.cover, 240);
                runOnUiThread(() -> { if (bm != null) t.setImageBitmap(bm); });
            });
        }
    }

    void toggle(String id) {
        if (!sel.remove(id)) sel.add(id);
        redraw();
    }

    void selectAll() {
        if (sel.size() == lastS.size()) sel.clear();
        else for (Media.Session x : lastS) sel.add(x.id);
        redraw();
    }

    void clearSelection() { sel.clear(); redraw(); }

    void redraw() {
        boolean on = !sel.isEmpty();
        bar.setVisibility(on ? View.VISIBLE : View.GONE);
        add.setVisibility(on ? View.GONE : View.VISIBLE);
        count.setText(sel.size() + " selected");
        show(lastS, lastV);
    }

    void confirmDelete() {
        int n = sel.size();
        if (n == 0) return;
        new android.app.AlertDialog.Builder(this).setTitle(n == 1 ? "Delete this timelapse?" : "Delete " + n + " timelapses?")
                .setMessage("Their photos and videos are removed from the phone.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Delete", (d, w) -> {
                    final java.util.List<String> ids = new java.util.ArrayList<>(sel);
                    String busy = getSharedPreferences("tl_state", MODE_PRIVATE).getString("session", "");
                    boolean rec = Ui.recordingAlive(this);
                    Ui.IO.execute(() -> {
                        for (String id : ids) if (!(rec && id.equals(busy))) Media.delete(this, id);   // never delete the run in progress
                        runOnUiThread(() -> { sel.clear(); redraw(); reload(); });
                    });
                }).show();
    }

    @Override public void onBackPressed() {
        if (!sel.isEmpty()) clearSelection(); else super.onBackPressed();
    }

    void updateRemote() {
        String u = WebService.url(this);
        remote.setText(u == null ? (WebService.ip() == null ? "Remote control: connect to Wi-Fi" : "Remote control: starting...")
                : "Control from your computer (tap to copy)\n" + u);
    }

    @Override protected void onResume() {
        super.onResume();
        startForegroundService(new Intent(this, WebService.class));
        updateRemote();
        ui.postDelayed(this::updateRemote, 1500);
        registerReceiver(rx, new IntentFilter(TimelapseService.ACTION), RECEIVER_NOT_EXPORTED);
        updateLive();
        reload();
    }

    @Override protected void onPause() {
        super.onPause();
        unregisterReceiver(rx);
    }
}
