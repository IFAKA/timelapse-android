package com.faka.timelapse;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Log;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

/** Builds an mp4 from the timelapse photos with MediaCodec + MediaMuxer. */
public class VideoService extends Service {
    static final String TAG = "Timelapse";
    static final int FPS = 24;
    static volatile boolean cancel = false;
    static volatile boolean working = false;
    PowerManager.WakeLock wl;
    volatile String session = "";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        if (in != null && "CANCEL".equals(in.getAction())) { cancel = true; return START_NOT_STICKY; }
        if (working || in == null || in.getStringExtra("session") == null) return START_NOT_STICKY;
        session = in.getStringExtra("session");
        working = true;
        cancel = false;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("tlv", "Timelapse video", NotificationManager.IMPORTANCE_LOW));
        startForeground(2, note("Making video..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timelapse:video");
        wl.acquire(60 * 60 * 1000L);
        new Thread(() -> {
            try { encode(); } catch (Throwable t) { Log.w(TAG, "video failed", t); status("Video failed: " + t); }
            working = false;
            if (wl.isHeld()) wl.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, "tl-video").start();
        return START_NOT_STICKY;
    }

    Notification note(String t) {
        return new Notification.Builder(this, "tlv").setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("Timelapse video").setContentText(t).setOngoing(true).build();
    }

    void status(String t) {
        Log.i(TAG, "video: " + t);
        getSharedPreferences("tl_state", MODE_PRIVATE).edit().putString("video", t).putString("video_session", session).apply();
        sendBroadcast(new Intent(TimelapseService.ACTION).setPackage(getPackageName()));
    }

    void encode() throws Exception {
        List<Uri> photos = Media.photos(this, session, false);
        if (photos.isEmpty()) { status("No photos to make a video from."); return; }
        status("Starting...");
        Bitmap first = null;
        for (Uri u : photos) { first = Media.load(this, u, 1080); if (first != null) break; }
        if (first == null) { status("Could not read any photo."); return; }
        float sc = Math.min(1f, 1080f / Math.max(first.getWidth(), first.getHeight()));
        final int w = (Math.round(first.getWidth() * sc) / 2) * 2, h = (Math.round(first.getHeight() * sc) / 2) * 2;
        first.recycle();

        String name = session + ".mp4";
        Media.deleteVideo(this, session);
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Timelapse");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri out = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(out, "rw");
        MediaCodec enc = null;
        MediaMuxer mux = null;
        boolean ok = false;
        try {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int[] track = {-1};
            int n = photos.size(), done = 0;
            for (int i = 0; i < n; i++) {
                if (cancel) break;
                status("frame " + (i + 1) + " of " + n);
                Bitmap b = Media.load(this, photos.get(i), 1080);
                if (b == null) { Log.w(TAG, "skip unreadable " + photos.get(i)); continue; }
                Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
                if (s != b) b.recycle();
                int idx;
                while ((idx = enc.dequeueInputBuffer(10000)) < 0) drain(enc, mux, info, track, false);
                fill(enc.getInputImage(idx), s, w, h);
                s.recycle();
                enc.queueInputBuffer(idx, 0, w * h * 3 / 2, (long) done * 1_000_000L / FPS, 0);
                done++;
                drain(enc, mux, info, track, false);
            }
            if (done == 0 || cancel) {
                status(cancel ? "Cancelled." : "Could not read any photo.");
            } else {
                int idx;
                while ((idx = enc.dequeueInputBuffer(10000)) < 0) drain(enc, mux, info, track, false);
                enc.queueInputBuffer(idx, 0, 0, (long) done * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drain(enc, mux, info, track, true);
                ok = true;
                status("Saved " + name + " (" + done + " frames)");
            }
        } finally {
            try { if (enc != null) { enc.stop(); enc.release(); } } catch (Exception e) {}
            try { if (mux != null) { mux.stop(); mux.release(); } } catch (Exception e) { ok = false; }
            try { pfd.close(); } catch (Exception e) {}
            if (ok) {
                ContentValues d = new ContentValues();
                d.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(out, d, null, null);
            } else {
                getContentResolver().delete(out, null, null);
            }
        }
    }

    void drain(MediaCodec enc, MediaMuxer mux, MediaCodec.BufferInfo info, int[] track, boolean eos) {
        while (true) {
            int o = enc.dequeueOutputBuffer(info, eos ? 10000 : 0);
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!eos) return; else continue; }
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track[0] = mux.addTrack(enc.getOutputFormat());
                mux.start();
            } else if (o >= 0) {
                ByteBuffer bb = enc.getOutputBuffer(o);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && track[0] >= 0)
                    mux.writeSampleData(track[0], bb, info);
                enc.releaseOutputBuffer(o, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    static void fill(android.media.Image img, Bitmap bm, int w, int h) {
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        android.media.Image.Plane[] p = img.getPlanes();
        ByteBuffer y = p[0].getBuffer(), u = p[1].getBuffer(), vv = p[2].getBuffer();
        int ys = p[0].getRowStride(), us = p[1].getRowStride(), vs = p[2].getRowStride();
        int up = p[1].getPixelStride(), vp = p[2].getPixelStride();
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int c = px[j * w + i];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                y.put(j * ys + i, (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
                if ((j & 1) == 0 && (i & 1) == 0) {
                    u.put((j / 2) * us + (i / 2) * up, (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
                    vv.put((j / 2) * vs + (i / 2) * vp, (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
                }
            }
        }
    }

    @Override public void onDestroy() {
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }
}
