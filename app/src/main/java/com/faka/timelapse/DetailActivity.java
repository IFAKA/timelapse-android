package com.faka.timelapse;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;

import java.util.ArrayList;
import java.util.List;

/** One timelapse: scrub the frames, make / play / share the video, rename, delete. */
public class DetailActivity extends Activity {
    String id;
    List<Uri> photos = new ArrayList<>();
    Media.Session info;
    boolean hasVideo, loadingFrame;
    volatile int want = -1;
    ImageView frame;
    SeekBar seek;
    TextView title, meta, frameLabel, videoText;
    TextView make, cancel, share;
    VideoView player;
    TextView tabVideo, tabPhotos, playMark;
    View tabsRow;
    boolean videoMode, userChoseMode;
    final android.os.Handler tick = new android.os.Handler(android.os.Looper.getMainLooper());
    final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (!VideoService.working) checkVideo(); else refresh(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        id = getIntent().getStringExtra("session");
        if (id == null) { finish(); return; }
        int pad = Ui.dp(this, 24);
        // One screen, no scrolling: the viewer takes whatever height is left between the title and the buttons.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG); root.setFitsSystemWindows(true);
        root.addView(Ui.back(this, "Timelapses"));

        title = Ui.thin(this, Media.name(this, id), 26);
        title.setPadding(pad, Ui.dp(this, 2), pad, 0);
        title.setOnClickListener(v -> rename());
        meta = Ui.caps(this, "", Ui.DIM);
        meta.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 8));
        root.addView(title); root.addView(meta);

        // Viewer: the video plays here (no other app), or the photos are scrubbed here
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(pad, 0, pad, Ui.dp(this, 4));
        tabVideo = Ui.caps(this, "VIDEO", Ui.AMBER);
        tabPhotos = Ui.caps(this, "PHOTOS", Ui.DIM);
        tabVideo.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 8));
        tabPhotos.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        tabVideo.setOnClickListener(v -> { userChoseMode = true; setMode(true); });
        tabPhotos.setOnClickListener(v -> { userChoseMode = true; setMode(false); });
        tabs.addView(tabVideo); tabs.addView(tabPhotos);
        tabs.setVisibility(View.GONE);
        root.addView(tabs);
        tabsRow = tabs;
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(0xFF000000);
        frame = new ImageView(this);
        frame.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.setBackgroundColor(Ui.LINE);
        stage.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        player = new VideoView(this);
        player.setVisibility(View.GONE);
        stage.addView(player, new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));
        playMark = Ui.text(this, "▶", 56, Ui.TXT);
        playMark.setGravity(android.view.Gravity.CENTER);
        playMark.setVisibility(View.GONE);
        stage.addView(playMark, new FrameLayout.LayoutParams(-1, -1));
        stage.setOnClickListener(v -> { if (videoMode) togglePlay(); });
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        seek = new SeekBar(this);
        seek.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 4));
        seek.getProgressDrawable().setTint(Ui.AMBER); seek.getThumb().setTint(Ui.AMBER);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                if (videoMode) { player.seekTo(p); frameLabel.setText(clock(p) + " / " + clock(player.getDuration())); } else show(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        frameLabel = Ui.caps(this, "", Ui.DIM);
        frameLabel.setPadding(pad, 0, pad, 0);
        root.addView(seek); root.addView(frameLabel);

        // Status line and one row of actions
        videoText = Ui.text(this, "", 13, Ui.DIM);
        videoText.setSingleLine(true); videoText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        videoText.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 2));
        root.addView(videoText);
        root.addView(Ui.hairline(this));
        LinearLayout acts = new LinearLayout(this);
        acts.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 4));
        make = action("MAKE VIDEO", Ui.AMBER);
        cancel = action("CANCEL", Ui.TXT);
        share = action("SHARE", Ui.TXT);
        TextView del = action("DELETE", Ui.RED);
        for (TextView x : new TextView[]{make, cancel, share, del}) acts.addView(x, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        root.addView(acts);

        make.setOnClickListener(v -> {
            Plan.makeVideo(this, id);
            refresh();
        });
        cancel.setOnClickListener(v -> startService(new Intent(this, VideoService.class).setAction("CANCEL")));
        share.setOnClickListener(v -> {
            Uri u = Media.video(this, id);
            if (u == null) return;
            Intent i = new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share video"));
        });
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete this timelapse?")
                .setMessage(photos.size() + " photos and the video will be removed from the phone.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Delete", (d, w) -> Ui.IO.execute(() -> {
                    Media.delete(this, id);
                    runOnUiThread(this::finish);
                })).show());
        setContentView(root);
        load();
    }

    TextView action(String label, int color) {
        TextView t = Ui.caps(this, label, color);
        t.setGravity(android.view.Gravity.CENTER);
        return Ui.press(t);
    }

    void load() {
        Ui.IO.execute(() -> {
            Media.Session found = null;
            for (Media.Session s : Media.sessions(this)) if (s.id.equals(id)) found = s;
            List<Uri> p = Media.photos(this, id, false);
            boolean v = Media.video(this, id) != null;
            final Media.Session f = found;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                info = f; photos = p; hasVideo = v;
                if (p.isEmpty()) { finish(); return; }
                meta.setText(p.size() + " PHOTOS  ·  " + Ui.duration(f == null ? 0 : f.endMs - f.startMs).toUpperCase());
                setMode(hasVideo && !userChoseMode ? true : videoMode && hasVideo);
                refresh();
            });
        });
    }

    void checkVideo() {
        Ui.IO.execute(() -> {
            boolean v = Media.video(this, id) != null;
            runOnUiThread(() -> {
                boolean fresh = v && !hasVideo;
                hasVideo = v; refresh();
                if (fresh || (!v && videoMode)) setMode(v);          // a video just finished: show it
            });
        });
    }

    /** Show frame i; if the finger moves faster than loading, only the latest frame is loaded. */
    void show(int i) {
        want = i;
        if (loadingFrame || photos.isEmpty()) return;
        loadingFrame = true;
        Ui.IO.execute(() -> {
            int k; Bitmap bm;
            do { k = want; bm = Media.load(this, photos.get(k), 1100); } while (k != want);
            final Bitmap fb = bm; final int fk = k;
            runOnUiThread(() -> {
                loadingFrame = false;
                if (fb != null) frame.setImageBitmap(fb);
                frameLabel.setText("FRAME " + (fk + 1) + " OF " + photos.size());
                if (want != fk) show(want);
            });
        });
    }

    void refresh() {
        SharedPreferences s = getSharedPreferences("tl_state", MODE_PRIVATE);
        boolean mine = id.equals(s.getString("video_session", ""));
        boolean busyMine = VideoService.working && mine;
        String v = s.getString("video", "");
        videoText.setText(mine && !v.isEmpty() && !v.equals("-") && (busyMine || !hasVideo || v.startsWith("Saved")) ? v
                : hasVideo ? "Video ready." : "No video yet. It is built from these photos, 24 per second.");
        make.setText(hasVideo ? "MAKE AGAIN" : "MAKE VIDEO");
        make.setEnabled(!VideoService.working); make.setAlpha(make.isEnabled() ? 1f : 0.35f);
        cancel.setVisibility(busyMine ? View.VISIBLE : View.GONE);
        share.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
        tabsRow.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
    }

    String clock(int ms) { int t = Math.max(0, ms) / 1000; return (t / 60) + ":" + String.format(java.util.Locale.US, "%02d", t % 60); }

    /** Video tab: plays the video in the app, looping. Photos tab: scrub the frames. */
    void setMode(boolean video) {
        videoMode = video && hasVideo;
        tabVideo.setTextColor(videoMode ? Ui.AMBER : Ui.DIM);
        tabPhotos.setTextColor(videoMode ? Ui.DIM : Ui.AMBER);
        tick.removeCallbacksAndMessages(null);
        if (videoMode) {
            frame.setVisibility(View.GONE); player.setVisibility(View.VISIBLE);
            Uri u = Media.video(this, id);
            if (u == null) { videoMode = false; setMode(false); return; }
            player.setOnPreparedListener(m -> {
                m.setLooping(true);
                seek.setMax(Math.max(1, player.getDuration())); seek.setProgress(0);
                player.start(); playMark.setVisibility(View.GONE);
                tick.post(this::follow);
            });
            player.setVideoURI(u);
        } else {
            player.stopPlayback(); player.setVisibility(View.GONE); playMark.setVisibility(View.GONE);
            frame.setVisibility(View.VISIBLE);
            if (!photos.isEmpty()) {
                seek.setMax(photos.size() - 1); seek.setProgress(photos.size() - 1);
                show(photos.size() - 1);
            }
        }
    }

    void togglePlay() {
        if (player.isPlaying()) { player.pause(); playMark.setVisibility(View.VISIBLE); }
        else { player.start(); playMark.setVisibility(View.GONE); }
    }

    /** Move the seek bar along with the video. */
    void follow() {
        if (!videoMode) return;
        if (player.isPlaying()) {
            seek.setProgress(player.getCurrentPosition());
            frameLabel.setText(clock(player.getCurrentPosition()) + " / " + clock(player.getDuration()));
        }
        tick.postDelayed(this::follow, 100);
    }

    void rename() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT); e.setText(Media.name(this, id)); e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Name").setView(e)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    Media.setName(this, id, e.getText().toString());
                    title.setText(Media.name(this, id));
                }).show();
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(rx, new IntentFilter(TimelapseService.ACTION), RECEIVER_NOT_EXPORTED);
        if (info != null) { checkVideo(); if (videoMode) setMode(true); }
    }

    @Override protected void onPause() {
        super.onPause();
        unregisterReceiver(rx);
        tick.removeCallbacksAndMessages(null);
        player.stopPlayback();
    }
}
