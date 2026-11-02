package com.faka.timelapse;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.FileDescriptor;
import java.text.SimpleDateFormat;
import java.util.*;

/** MediaStore queries and bitmap loading helpers. */
final class Media {
    static final String ROOT = "Pictures/Timelapse/";
    static final SimpleDateFormat ID = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    /** One run of the camera: a folder of photos named by its start time. */
    static final class Session {
        String id;
        int count;
        Uri cover;          // newest photo
        long startMs, endMs;
    }

    static long parse(String ts) {
        try { synchronized (ID) { return ID.parse(ts).getTime(); } } catch (Exception e) { return 0; }
    }

    static String newId() { synchronized (ID) { return ID.format(new Date()); } }

    /** All timelapses, newest first. */
    static List<Session> sessions(Context c) {
        Map<String, Session> m = new LinkedHashMap<>();
        try (Cursor q = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME},
                MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?",
                new String[]{ROOT + "%", "tl_%.jpg"}, MediaStore.Images.Media.DISPLAY_NAME + " ASC")) {
            while (q != null && q.moveToNext()) {
                String id = q.getString(1).substring(ROOT.length()).replace("/", "");
                String name = q.getString(2);
                if (id.isEmpty() || name.length() < 18) continue;
                Session s = m.get(id);
                if (s == null) { s = new Session(); s.id = id; s.startMs = parse(id); m.put(id, s); }
                s.count++;
                s.cover = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(q.getLong(0)));
                s.endMs = parse(name.substring(3, 18));
            }
        }
        List<Session> out = new ArrayList<>(m.values());
        Collections.sort(out, (a, b) -> b.id.compareTo(a.id));
        return out;
    }

    static List<Uri> photos(Context c, String id, boolean newestFirst) {
        List<Uri> out = new ArrayList<>();
        try (Cursor q = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},
                MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?",
                new String[]{ROOT + id + "%", "tl_%.jpg"},
                MediaStore.Images.Media.DISPLAY_NAME + (newestFirst ? " DESC" : " ASC"))) {
            while (q != null && q.moveToNext())
                out.add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(q.getLong(0))));
        }
        return out;
    }

    static Uri video(Context c, String id) {
        try (Cursor q = c.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID},
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Video.Media.DISPLAY_NAME + " = ?",
                new String[]{"Movies/Timelapse%", id + ".mp4"}, null)) {
            if (q != null && q.moveToFirst())
                return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(q.getLong(0)));
        }
        return null;
    }

    /** Ids of the timelapses that already have a video. */
    static Set<String> videoIds(Context c) {
        Set<String> out = new HashSet<>();
        try (Cursor q = c.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media.DISPLAY_NAME},
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?", new String[]{"Movies/Timelapse%"}, null)) {
            while (q != null && q.moveToNext()) {
                String n = q.getString(0);
                if (n.endsWith(".mp4")) out.add(n.substring(0, n.length() - 4));
            }
        }
        return out;
    }

    static void deleteVideo(Context c, String id) {
        c.getContentResolver().delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Video.Media.DISPLAY_NAME + " = ?",
                new String[]{"Movies/Timelapse%", id + ".mp4"});
    }

    static void delete(Context c, String id) {
        c.getContentResolver().delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?", new String[]{ROOT + id + "%"});
        deleteVideo(c, id);
        c.getSharedPreferences("tl_names", Context.MODE_PRIVATE).edit().remove(id).apply();
    }

    static String name(Context c, String id) {
        String n = c.getSharedPreferences("tl_names", Context.MODE_PRIVATE).getString(id, null);
        if (n != null && !n.isEmpty()) return n;
        return new SimpleDateFormat("MMM d \u00B7 HH:mm", Locale.getDefault()).format(new Date(parse(id)));
    }

    static void setName(Context c, String id, String n) {
        c.getSharedPreferences("tl_names", Context.MODE_PRIVATE).edit().putString(id, n.trim()).apply();
    }

    /** Decode with sampling so the long side is about maxLong or less, apply EXIF rotation. Null on failure. */
    static Bitmap load(Context c, Uri u, int maxLong) {
        ContentResolver cr = c.getContentResolver();
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(u, "r")) {
            FileDescriptor fd = pfd.getFileDescriptor();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(fd, null, o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxLong) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeFileDescriptor(fd, null, d);
            if (b == null) return null;
            int rot = 0;
            try {
                int ori = new ExifInterface(fd).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                if (ori == 6) rot = 90; else if (ori == 3) rot = 180; else if (ori == 8) rot = 270;
            } catch (Exception e) {}
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
