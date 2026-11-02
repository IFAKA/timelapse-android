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
    LinearLayout list;
    TextView live, remote, daily;
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

        Button add = new Button(this);
        add.setText("New timelapse"); add.setAllCaps(false); add.setTextSize(17);
        add.setTypeface(null, android.graphics.Typeface.BOLD);
        add.setTextColor(Ui.BG); add.setBackground(Ui.pill(this, Ui.AMBER, 30));
        add.setStateListAnimator(null); add.setPadding(pad * 2, 0, pad * 2, 0);
        add.setOnClickListener(v -> startActivity(new Intent(this, RecordActivity.class)));
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
            r.addView(t, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
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
            r.setOnClickListener(v -> startActivity(new Intent(this, DetailActivity.class).putExtra("session", s.id)));
            list.addView(r);
            list.addView(Ui.hairline(this));
            Ui.IO.execute(() -> {
                Bitmap bm = Media.load(this, s.cover, 240);
                runOnUiThread(() -> { if (bm != null) t.setImageBitmap(bm); });
            });
        }
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
