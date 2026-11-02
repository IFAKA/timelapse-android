package com.faka.timelapse;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.*;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

public class TimelapseService extends Service implements SensorEventListener {
    static final String ACTION = "com.faka.timelapse.STATE";
    static final String TAG = "Timelapse";
    static final String CH = "tl";

    HandlerThread ht;
    Handler h;
    PowerManager.WakeLock wl;
    SensorManager sm;
    NotificationManager nm;

    float threshold = 50, lux = -1;
    long intervalMs = 30000, darkMs = 600000, endAt = 0;
    int minLuma = 0;            // frames darker than this are not saved (0 = keep all)
    float expMax = 0;
    String state = "WAITING";
    String session = "";
    int count = 0;
    long brightSince = -1, darkSince = -1, lastShot = 0;
    volatile boolean busy = false, running = false;
    int brightReadings = 0;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        if (in != null && "STOP".equals(in.getAction())) {
            if (!running) startForeground(1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        running = true;
        if (in != null) {
            threshold = in.getFloatExtra("threshold", 50);
            intervalMs = Math.max(1, (long) (in.getFloatExtra("interval", 30) * 1000));
            darkMs = (long) (in.getFloatExtra("dark", 10) * 60000);
            endAt = in.getLongExtra("endAt", 0);
            minLuma = in.getIntExtra("minLuma", 0);
        }
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Timelapse", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timelapse:wl");
        wl.acquire();
        ht = new HandlerThread("tl");
        ht.start();
        h = new Handler(ht.getLooper());
        count = 0;
        session = Media.newId();
        setState("WAITING");
        sm = getSystemService(SensorManager.class);
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, h);
        else Log.w(TAG, "no light sensor");
        Log.i(TAG, "started threshold=" + threshold + " interval=" + intervalMs + "ms dark=" + darkMs + "ms");
        h.postDelayed(tick, 1000);
        return START_NOT_STICKY;
    }

    Notification notif() {
        return new Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Timelapse " + state)
                .setContentText("photos: " + count + "  lux: " + lux)
                .addAction(new Notification.Action.Builder(null, "Stop", PendingIntent.getService(this, 0,
                        new Intent(this, TimelapseService.class).setAction("STOP"),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .setOngoing(true).build();
    }

    void setState(String s) {
        if (!s.equals(state)) Log.i(TAG, "state " + state + " -> " + s + " lux=" + lux);
        state = s;
        publish();
    }

    void publish() {
        getSharedPreferences("tl_state", MODE_PRIVATE).edit().putString("status", state)
                .putFloat("lux", lux).putInt("count", count).putString("session", session).putLong("endAt", endAt).apply();
        if (nm != null) nm.notify(1, notif());
        sendBroadcast(new Intent(ACTION).setPackage(getPackageName()));
    }

    @Override public void onSensorChanged(SensorEvent e) { lux = e.values[0]; }
    @Override public void onAccuracyChanged(Sensor s, int a) {}

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (endAt > 0 && System.currentTimeMillis() >= endAt) { Log.i(TAG, "end time reached"); stopSelf(); return; }
            long now = SystemClock.elapsedRealtime();
            if (lux >= 0) {
                if (lux >= threshold) {
                    brightReadings++;
                    darkSince = -1;
                    if (brightSince < 0) brightSince = now;
                    if (state.equals("WAITING") && brightReadings >= 3 && now - brightSince >= 10000) {
                        setState("RECORDING");
                        lastShot = 0;
                    }
                } else {
                    brightReadings = 0;
                    brightSince = -1;
                    if (state.equals("RECORDING")) {
                        if (lux < threshold / 2) {
                            if (darkSince < 0) darkSince = now;
                            if (now - darkSince >= darkMs) { setState("WAITING"); darkSince = -1; }
                        } else darkSince = -1;
                    }
                }
            }
            if (state.equals("RECORDING") && !busy && now - lastShot >= intervalMs) {
                lastShot = now;
                shoot();
            }
            if (now % 5 == 0) publish();
            h.postDelayed(this, 1000);
        }
    };

    // ---- one shot: open camera, short preview for auto-exposure, capture, close ----
    CameraDevice cam;
    ImageReader reader;
    SurfaceTexture tex;
    Surface previewSurface;
    boolean shotDone;

    void shoot() {
        busy = true;
        shotDone = false;
        try {
            CameraManager cm = getSystemService(CameraManager.class);
            String id = null;
            CameraCharacteristics cc = null;
            for (String c : cm.getCameraIdList()) {
                CameraCharacteristics x = cm.getCameraCharacteristics(c);
                Integer f = x.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) { id = c; cc = x; break; }
            }
            if (id == null) { Log.w(TAG, "no rear camera"); busy = false; return; }
            Size best = null;
            for (Size s : cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG)) {
                if (s.getWidth() <= 1920 && (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight())) best = s;
            }
            final int orient = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            aeRange = null;
            for (Range<Integer> r : cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
                if (aeRange == null || r.getUpper() < aeRange.getUpper()
                        || (r.getUpper().equals(aeRange.getUpper()) && r.getLower() < aeRange.getLower())) aeRange = r;
            aeMax = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE).getUpper();
            reader = ImageReader.newInstance(best.getWidth(), best.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireLatestImage();
                if (img == null) return;
                try {
                    ByteBuffer b = img.getPlanes()[0].getBuffer();
                    byte[] d = new byte[b.remaining()];
                    b.get(d);
                    save(d);
                } finally { img.close(); }
                finishShot();
            }, h);
            h.postDelayed(watchdog, 20000);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    cam = d;
                    if (shotDone) { d.close(); return; }
                    startSession(d, orient);
                }
                @Override public void onDisconnected(CameraDevice d) { Log.w(TAG, "camera disconnected"); finishShot(); }
                @Override public void onError(CameraDevice d, int e) { Log.w(TAG, "camera error " + e); finishShot(); }
            }, h);
        } catch (Exception e) {
            Log.w(TAG, "shoot failed: " + e);
            finishShot();
        }
    }

    Range<Integer> aeRange; int aeMax;

    /** In dim light: allow long exposures and brighten the picture so a dim room is still visible. */
    void boostLowLight(CaptureRequest.Builder b) {
        if (lux < 0 || lux >= 20) return;
        if (aeRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeRange);
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeMax);
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
    }

    void startSession(CameraDevice d, int orient) {
        try {
            tex = new SurfaceTexture(0);
            tex.setDefaultBufferSize(640, 480);
            previewSurface = new Surface(tex);
            d.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    try {
                        CaptureRequest.Builder pb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        pb.addTarget(previewSurface);
                        boostLowLight(pb);
                        s.setRepeatingRequest(pb.build(), null, h);
                        h.postDelayed(() -> {
                            if (shotDone) return;
                            try {
                                CaptureRequest.Builder cb = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                cb.addTarget(reader.getSurface());
                                cb.set(CaptureRequest.JPEG_ORIENTATION, orient);
                                cb.set(CaptureRequest.JPEG_QUALITY, (byte) 88);
                                boostLowLight(cb);
                                s.capture(cb.build(), null, h);
                            } catch (Exception e) { Log.w(TAG, "capture failed: " + e); finishShot(); }
                        }, lux >= 0 && lux < 20 ? 3000 : 1500);
                    } catch (Exception e) { Log.w(TAG, "preview failed: " + e); finishShot(); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { Log.w(TAG, "configure failed"); finishShot(); }
            }, h);
        } catch (Exception e) { Log.w(TAG, "session failed: " + e); finishShot(); }
    }

    final Runnable watchdog = () -> { Log.w(TAG, "shot timeout"); finishShot(); };

    void finishShot() {
        if (shotDone) return;
        shotDone = true;
        h.removeCallbacks(watchdog);
        try { if (cam != null) cam.close(); } catch (Exception e) {}
        cam = null;
        try { if (reader != null) reader.close(); } catch (Exception e) {}
        reader = null;
        try { if (previewSurface != null) previewSurface.release(); } catch (Exception e) {}
        try { if (tex != null) tex.release(); } catch (Exception e) {}
        previewSurface = null; tex = null;
        busy = false;
    }

    /** True if a person looking at this frame could see something (not a black picture). */
    boolean visible(byte[] d) {
        if (minLuma <= 0) return true;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 16;
        Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length, o);
        if (b == null) return true;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        int[] hist = new int[256];
        long sum = 0;
        for (int p : px) {
            int l = ((p >> 16 & 255) * 299 + (p >> 8 & 255) * 587 + (p & 255) * 114) / 1000;
            hist[l]++; sum += l;
        }
        double mean = sum / (double) px.length;
        int target = (int) (px.length * 0.95), acc = 0, p95 = 0;
        for (int i = 0; i < 256; i++) { acc += hist[i]; if (acc >= target) { p95 = i; break; } }
        boolean ok = mean >= minLuma || p95 >= minLuma * 4;   // dim overall is fine if part of it is lit (a screen, a lamp)
        Log.i(TAG, "frame mean=" + (int) mean + " p95=" + p95 + " lux=" + lux + (ok ? " keep" : " too dark, skipped"));
        return ok;
    }

    void save(byte[] data) {
        if (!visible(data)) return;
        try {
            String name = "tl_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Media.ROOT + session);
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            int n = data.length;                         // the camera pads the JPEG up to a fixed size: cut after the last end marker
            for (int i = n - 2; i > 0; i--) if (data[i] == (byte) 0xFF && data[i + 1] == (byte) 0xD9) { n = i + 2; break; }
            try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(data, 0, n); }
            count++;
            Log.i(TAG, "saved " + name + " bytes=" + n + " count=" + count);
            publish();
        } catch (Exception e) { Log.w(TAG, "save failed: " + e); }
    }

    @Override public void onDestroy() {
        running = false;
        if (sm != null) sm.unregisterListener(this);
        if (h != null) {
            h.removeCallbacksAndMessages(null);
            h.post(this::finishShot);
        }
        if (ht != null) ht.quitSafely();
        state = "STOPPED";
        publish();
        if (nm != null) nm.cancel(1);
        if (wl != null && wl.isHeld()) wl.release();
        Log.i(TAG, "stopped, photos=" + count);
        super.onDestroy();
    }
}
