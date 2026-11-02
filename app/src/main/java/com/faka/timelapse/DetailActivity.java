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
    Button make, cancel, play, share;
    VideoView player;
    final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (!VideoService.working) checkVideo(); else refresh(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        id = getIntent().getStringExtra("session");
        if (id == null) { finish(); return; }
        int pad = Ui.dp(this, 24);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG); sv.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root);
        root.addView(Ui.back(this, "Timelapses"));

        title = Ui.thin(this, Media.name(this, id), 30);
        title.setPadding(pad, Ui.dp(this, 8), pad, 0);
        title.setOnClickListener(v -> rename());
        meta = Ui.caps(this, "", Ui.DIM);
        meta.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 20));
        root.addView(title); root.addView(meta);

        // Frame viewer
        frame = new ImageView(this);
        frame.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.setBackgroundColor(Ui.LINE);
        root.addView(frame, new LinearLayout.LayoutParams(-1, Ui.dp(this, 360)));
        seek = new SeekBar(this);
        seek.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 6));
        seek.getProgressDrawable().setTint(Ui.AMBER); seek.getThumb().setTint(Ui.AMBER);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) show(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        frameLabel = Ui.caps(this, "", Ui.DIM);
        frameLabel.setPadding(pad, 0, pad, 0);
        root.addView(seek); root.addView(frameLabel);

        // Video
        LinearLayout vb = new LinearLayout(this);
        vb.setOrientation(LinearLayout.VERTICAL);
        vb.setPadding(pad, Ui.dp(this, 32), pad, 0);
        vb.addView(Ui.caps(this, "VIDEO", Ui.DIM));
        videoText = Ui.text(this, "", 14, Ui.DIM);
        videoText.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        vb.addView(videoText);
        vb.addView(Ui.hairline(this));
        make = Ui.row(this, "Make video", Ui.TXT);
        cancel = Ui.row(this, "Cancel", Ui.TXT);
        play = Ui.row(this, "Play", Ui.TXT);
        share = Ui.row(this, "Share", Ui.TXT);
        Button del = Ui.row(this, "Delete timelapse", Ui.RED);
        for (Button x : new Button[]{make, cancel, play, share}) { vb.addView(x); vb.addView(Ui.hairline(this)); }
        player = new VideoView(this);
        player.setVisibility(View.GONE);
        vb.addView(player, new LinearLayout.LayoutParams(-1, Ui.dp(this, 260)));
        TextView gap = new TextView(this); vb.addView(gap, new LinearLayout.LayoutParams(-1, Ui.dp(this, 24)));
        vb.addView(Ui.hairline(this)); vb.addView(del); vb.addView(Ui.hairline(this));
        vb.setPadding(pad, Ui.dp(this, 32), pad, Ui.dp(this, 48));
        root.addView(vb);

        make.setOnClickListener(v -> {
            Plan.makeVideo(this, id);
            refresh();
        });
        cancel.setOnClickListener(v -> startService(new Intent(this, VideoService.class).setAction("CANCEL")));
        play.setOnClickListener(v -> play());
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
        setContentView(sv);
        load();
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
                seek.setMax(p.size() - 1); seek.setProgress(p.size() - 1);
                show(p.size() - 1);
                refresh();
            });
        });
    }

    void checkVideo() {
        Ui.IO.execute(() -> {
            boolean v = Media.video(this, id) != null;
            runOnUiThread(() -> { hasVideo = v; refresh(); });
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
        make.setText(hasVideo ? "Make video again" : "Make video");
        make.setEnabled(!VideoService.working); make.setAlpha(make.isEnabled() ? 1f : 0.35f);
        cancel.setVisibility(busyMine ? View.VISIBLE : View.GONE);
        play.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
        share.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
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

    void play() {
        Uri u = Media.video(this, id);
        if (u == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException ex) {
            player.setVisibility(View.VISIBLE);
            player.setMediaController(new MediaController(this));
            player.setVideoURI(u);
            player.setOnPreparedListener(m -> { m.setLooping(true); player.start(); });
        }
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(rx, new IntentFilter(TimelapseService.ACTION), RECEIVER_NOT_EXPORTED);
        if (info != null) checkVideo();
    }

    @Override protected void onPause() {
        super.onPause();
        unregisterReceiver(rx);
        player.stopPlayback();
    }
}
