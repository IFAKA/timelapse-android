package com.faka.timelapse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Small web server so a computer on the same Wi-Fi can run everything the app does.
 * Every request needs the secret key (query ?k=KEY once, then a cookie).
 */
public class WebService extends Service {
    static final String TAG = "TimelapseWeb";
    static volatile int port = 0;
    ServerSocket server;
    PowerManager.WakeLock wl;
    WifiManager.WifiLock wifi;
    String key;

    @Override public IBinder onBind(Intent i) { return null; }

    static String key(Context c) {
        SharedPreferences sp = c.getSharedPreferences("tl", MODE_PRIVATE);
        String k = sp.getString("webkey", null);
        if (k == null || k.length() < 12) {        // 12 letters and digits: not guessable from another device on the Wi-Fi
            String abc = "abcdefghjkmnpqrstuvwxyz23456789";
            java.security.SecureRandom r = new java.security.SecureRandom();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 12; i++) b.append(abc.charAt(r.nextInt(abc.length())));
            k = b.toString();
            sp.edit().putString("webkey", k).apply();
        }
        return k;
    }

    /** Wi-Fi address of the phone, or null. */
    static String ip() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces()))
                if (n.isUp() && !n.isLoopback() && n.getName().startsWith("wlan"))
                    for (InetAddress a : Collections.list(n.getInetAddresses()))
                        if (a instanceof Inet4Address) return a.getHostAddress();
        } catch (Exception e) {}
        return null;
    }

    static String url(Context c) {
        String ip = ip();
        return ip == null || port == 0 ? null : "https://" + ip + ":" + port + "/?k=" + key(c);
    }

    /** An unusual port, so it does not clash with other apps. If it is busy, the next 9 are tried. */
    static final int PORT = 47831;
    static WebService inst;
    boolean fg;

    void promote() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("tlw", "Remote control", NotificationManager.IMPORTANCE_LOW));
        startForeground(3, new Notification.Builder(this, "tlw").setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Timelapse remote").setContentText("Open the link shown in the app from your computer")
                .setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        fg = true;
    }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        inst = this;
        if (server != null) { if (!fg) promote(); return START_STICKY; }
        key = key(this);
        promote();
        wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timelapse:web");
        wl.acquire();
        wifi = getSystemService(WifiManager.class).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "timelapse:web");
        wifi.acquire();
        new Thread(this::serve, "tl-web").start();
        new Thread(this::keepAwake, "tl-wifi").start();
        return START_STICKY;
    }

    // Self-signed certificate, made once and kept in the app's private storage.
    static javax.net.ssl.SSLServerSocketFactory tls(Context c) throws Exception {
        char[] pw = "tl".toCharArray();
        File f = new File(c.getFilesDir(), "web.p12");
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        File ipf = new File(c.getFilesDir(), "web.ip");
        String ip = String.valueOf(ip());
        boolean same = false;
        try (BufferedReader br = new BufferedReader(new FileReader(ipf))) { same = ip.equals(br.readLine()); } catch (IOException e) {}
        // A new certificate is made when the phone's address changed or the old one is nearly a year old,
        // because browsers check that the address is named in it and refuse long-lived ones.
        if (f.exists() && same && System.currentTimeMillis() - f.lastModified() < 300L * 86400000L) {
            try (InputStream in = new FileInputStream(f)) { ks.load(in, pw); }
        } else {
            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            java.security.KeyPair kp = g.generateKeyPair();
            byte[] alg = der(0x30, der(0x06, new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B}), new byte[]{5, 0});
            byte[] name = der(0x30, der(0x31, der(0x30, der(0x06, new byte[]{0x55, 0x04, 0x03}), der(0x0C, "Timelapse".getBytes("UTF-8")))));
            java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
            df.setTimeZone(TimeZone.getTimeZone("UTC"));
            long now = System.currentTimeMillis();
            byte[] valid = der(0x30, der(0x17, df.format(new Date(now - 86400000L)).getBytes("US-ASCII")),
                    der(0x17, df.format(new Date(now + 365L * 86400000L)).getBytes("US-ASCII")));
            byte[] tbs = der(0x30, der(0xA0, der(0x02, new byte[]{2})), der(0x02, new byte[]{1, (byte) (now & 0x7F)}),
                    alg, name, valid, name, kp.getPublic().getEncoded(), der(0xA3, der(0x30, san(ip), eku())));
            java.security.Signature sg = java.security.Signature.getInstance("SHA256withRSA");
            sg.initSign(kp.getPrivate());
            sg.update(tbs);
            byte[] sig = sg.sign();
            byte[] bits = new byte[sig.length + 1];
            System.arraycopy(sig, 0, bits, 1, sig.length);
            byte[] cert = der(0x30, tbs, alg, der(0x03, bits));
            java.security.cert.Certificate cc = java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(cert));
            ks.load(null, null);
            ks.setKeyEntry("tl", kp.getPrivate(), pw, new java.security.cert.Certificate[]{cc});
            try (OutputStream out = new FileOutputStream(f)) { ks.store(out, pw); }
            try (FileWriter w = new FileWriter(ipf)) { w.write(ip); }
        }
        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx.getServerSocketFactory();
    }

    // Extension "subject alt name": this phone's address and localhost. Browsers need the address named.
    static byte[] san(String ip) {
        ByteArrayOutputStream names = new ByteArrayOutputStream();
        byte[] dns = der(0x82, "localhost".getBytes());
        names.write(dns, 0, dns.length);
        try {
            byte[] a = InetAddress.getByName(ip).getAddress();
            if (a.length == 4) { byte[] n = der(0x87, a); names.write(n, 0, n.length); }
        } catch (Exception e) {}
        return der(0x30, der(0x06, new byte[]{0x55, 0x1D, 0x11}), der(0x04, der(0x30, names.toByteArray())));
    }

    // Extension "extended key usage": this certificate is for a web server.
    static byte[] eku() {
        return der(0x30, der(0x06, new byte[]{0x55, 0x1D, 0x25}),
                der(0x04, der(0x30, der(0x06, new byte[]{0x2B, 6, 1, 5, 5, 7, 3, 1}))));
    }

    // One DER element: tag, length, then the parts joined.
    static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        int n = body.size();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        if (n < 128) o.write(n);
        else if (n < 256) { o.write(0x81); o.write(n); }
        else { o.write(0x82); o.write(n >> 8); o.write(n & 255); }
        o.write(body.toByteArray(), 0, n);
        return o.toByteArray();
    }

    /**
     * An idle phone Wi-Fi chip sleeps and stops answering the router's "who has this address?" question,
     * so the computer sees "no route to host". Sending one tiny packet to the router every few seconds keeps it awake.
     */
    @SuppressWarnings("deprecation")
    void keepAwake() {
        try (DatagramSocket u = new DatagramSocket()) {
            while (inst == this) {
                int gw = getSystemService(WifiManager.class).getDhcpInfo().gateway;
                if (gw != 0) {
                    byte[] a = {(byte) gw, (byte) (gw >> 8), (byte) (gw >> 16), (byte) (gw >> 24)};
                    u.send(new DatagramPacket(new byte[1], 1, InetAddress.getByAddress(a), 9));
                }
                Thread.sleep(5000);
            }
        } catch (Exception e) { Log.w(TAG, "keepAwake: " + e); }
    }

    void serve() {
        for (int p = PORT; p < PORT + 10 && server == null; p++) {
            try { server = tls(this).createServerSocket(p); port = p; } catch (Exception e) { Log.w(TAG, "bind " + p + ": " + e); }
        }
        if (server == null) { Log.w(TAG, "no free port"); return; }
        Log.i(TAG, "listening on " + port);
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Ui.IO.execute(() -> { try { handle(s); } catch (Exception e) { Log.w(TAG, "request: " + e); } finally { try { s.close(); } catch (Exception x) {} } });
            } catch (IOException e) { break; }
        }
    }

    @Override public void onDestroy() {
        try { if (server != null) server.close(); } catch (Exception e) {}
        port = 0; inst = null;
        if (wl != null && wl.isHeld()) wl.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    // ---------------- HTTP ----------------
    void handle(Socket s) throws Exception {
        s.setSoTimeout(15000);
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
        String line = r.readLine();
        if (line == null) return;
        String[] parts = line.split(" ");
        if (parts.length < 2) return;
        String method = parts[0], target = parts[1];
        Map<String, String> h = new HashMap<>();
        String l;
        while ((l = r.readLine()) != null && !l.isEmpty()) {
            int i = l.indexOf(':');
            if (i > 0) h.put(l.substring(0, i).trim().toLowerCase(Locale.US), l.substring(i + 1).trim());
        }
        String path = target, qs = "";
        int qi = target.indexOf('?');
        if (qi >= 0) { path = target.substring(0, qi); qs = target.substring(qi + 1); }
        Map<String, String> q = new HashMap<>();
        for (String kv : qs.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            String k = e < 0 ? kv : kv.substring(0, e), v = e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8");
            q.put(k, v);
        }
        OutputStream out = new BufferedOutputStream(s.getOutputStream());

        String cookie = h.getOrDefault("cookie", "");
        boolean cookieOk = Arrays.asList(cookie.split(";\\s*")).contains("k=" + key);
        boolean queryOk = key.equals(q.get("k"));
        if (!cookieOk && !queryOk) {
            send(out, 401, "text/html; charset=utf-8", null,
                    "<body style='background:#0c0c0b;color:#f2efe8;font:16px sans-serif;padding:40px'>Open the full link shown in the Timelapse app on the phone.</body>");
            out.flush();
            return;
        }
        if (queryOk && !cookieOk && path.equals("/")) {   // set the cookie and drop the key from the address bar
            out.write(("HTTP/1.1 302 Found\r\nLocation: /\r\nSet-Cookie: k=" + key
                    + "; Path=/; Max-Age=31536000; SameSite=Strict\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes());
            out.flush();
            return;
        }
        route(method, path, q, h, out);
        out.flush();
    }

    void send(OutputStream out, int code, String type, String extra, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        sendBytes(out, code, type, extra, b);
    }

    void sendBytes(OutputStream out, int code, String type, String extra, byte[] b) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : "Error")
                + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n"
                + (extra == null ? "" : extra) + "\r\n").getBytes());
        out.write(b);
    }

    void json(OutputStream out, Object o) throws IOException {
        send(out, 200, "application/json", "Cache-Control: no-store\r\n", o.toString());
    }

    void route(String method, String path, Map<String, String> q, Map<String, String> h, OutputStream out) throws Exception {
        boolean post = method.equals("POST");
        String sid = q.get("s");
        switch (path) {
            case "/": {
                try (InputStream in = getAssets().open("index.html")) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    sendBytes(out, 200, "text/html; charset=utf-8", "Cache-Control: no-store\r\n", bo.toByteArray());
                }
                return;
            }
            case "/api/state": json(out, state()); return;
            case "/api/thumb": case "/api/frame": {
                List<Uri> ps = null;
                int i = 0, w = path.equals("/api/thumb") ? 320 : 1280;
                if (sid == null) break;
                if (path.equals("/api/thumb")) {
                    for (Media.Session s : Media.sessions(this)) if (s.id.equals(sid)) {
                        ps = new ArrayList<>(); ps.add(s.cover);
                    }
                } else {
                    ps = Media.photos(this, sid, false);
                    i = Integer.parseInt(q.getOrDefault("i", "0"));
                }
                if (ps == null || ps.isEmpty()) break;
                Bitmap b = Media.load(this, ps.get(Math.max(0, Math.min(i, ps.size() - 1))), w);
                if (b == null) break;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 82, bo);
                b.recycle();
                sendBytes(out, 200, "image/jpeg", "Cache-Control: max-age=3600\r\n", bo.toByteArray());
                return;
            }
            case "/api/start": {
                if (!post) break;
                int videoSec = Math.max(5, Math.min(3600, Integer.parseInt(q.getOrDefault("video", "60"))));
                Daily.setVideo(this, videoSec);
                float hours = Float.parseFloat(q.getOrDefault("hours", "4"));
                long end = hours > 0 ? System.currentTimeMillis() + (long) (hours * 3600000L)
                        : Plan.nextClock(Integer.parseInt(q.getOrDefault("h", "18")), Integer.parseInt(q.getOrDefault("m", "0")));
                int iv = q.containsKey("interval") ? Integer.parseInt(q.get("interval")) : Plan.intervalSec(end - System.currentTimeMillis(), videoSec);
                stopService(new Intent(this, TimelapseService.class));
                boolean auto = q.getOrDefault("bright", "1").equals("1");
                Plan.startBg(this, end, iv, auto ? Plan.AUTO_LUX : 0,
                        q.containsKey("minluma") ? Integer.parseInt(q.get("minluma")) : auto ? Plan.AUTO_LUMA : 0);
                json(out, new JSONObject().put("ok", true).put("interval", iv).put("endAt", end));
                return;
            }
            case "/api/daily": {
                if (!post) break;
                int sh = Integer.parseInt(q.getOrDefault("sh", "7")), sm = Integer.parseInt(q.getOrDefault("sm", "0"));
                int eh = Integer.parseInt(q.getOrDefault("eh", "19")), em = Integer.parseInt(q.getOrDefault("em", "0"));
                Daily.save(this, sh, sm, eh, em, Math.max(5, Math.min(3600, Integer.parseInt(q.getOrDefault("video", "60")))),
                        q.getOrDefault("bright", "1").equals("1"), Integer.parseInt(q.getOrDefault("keep", "14")));
                if (q.getOrDefault("on", "0").equals("1")) Daily.enable(this); else Daily.disable(this);
                json(out, new JSONObject().put("ok", true));
                return;
            }
            case "/api/stop": if (!post) break; Plan.stop(this); json(out, new JSONObject().put("ok", true)); return;
            case "/api/makevideo": if (!post || sid == null) break; Plan.makeVideo(this, sid); json(out, new JSONObject().put("ok", true)); return;
            case "/api/cancel": if (!post) break; startService(new Intent(this, VideoService.class).setAction("CANCEL")); json(out, new JSONObject().put("ok", true)); return;
            case "/api/rename": if (!post || sid == null) break; Media.setName(this, sid, q.getOrDefault("name", "")); json(out, new JSONObject().put("ok", true)); return;
            case "/api/delete": if (!post || sid == null) break; Media.delete(this, sid); json(out, new JSONObject().put("ok", true)); return;
            default:
                if (path.startsWith("/video/") && path.endsWith(".mp4")) {
                    video(path.substring(7, path.length() - 4), q.containsKey("dl"), h.get("range"), out);
                    return;
                }
        }
        send(out, 404, "text/plain", null, "not found");
    }

    JSONObject state() throws Exception {
        SharedPreferences s = getSharedPreferences("tl_state", MODE_PRIVATE);
        String st = s.getString("status", "STOPPED");
        boolean alive = Ui.recordingAlive(this);
        JSONObject o = new JSONObject();
        o.put("status", alive ? st : "STOPPED").put("lux", s.getFloat("lux", -1)).put("count", s.getInt("count", 0))
                .put("session", s.getString("session", "")).put("endAt", s.getLong("endAt", 0))
                .put("now", System.currentTimeMillis())
                .put("videoBusy", VideoService.working).put("videoText", s.getString("video", ""))
                .put("videoSession", s.getString("video_session", ""));
        Set<String> vids = Media.videoIds(this);
        JSONArray a = new JSONArray();
        for (Media.Session m : Media.sessions(this))
            a.put(new JSONObject().put("id", m.id).put("name", Media.name(this, m.id)).put("count", m.count)
                    .put("start", m.startMs).put("end", m.endMs).put("video", vids.contains(m.id)));
        o.put("sessions", a);
        o.put("daily", new JSONObject().put("on", Daily.on(this)).put("sh", Daily.sh(this)).put("sm", Daily.sm(this))
                .put("eh", Daily.eh(this)).put("em", Daily.em(this)).put("video", Daily.video(this))
                .put("bright", Daily.auto(this)).put("keep", Daily.keep(this)).put("next", Daily.nextStart(this)));
        return o;
    }

    /** Stream the mp4, with Range support so the browser can seek. */
    void video(String id, boolean download, String range, OutputStream out) throws Exception {
        Uri u = Media.video(this, id);
        if (u == null) { send(out, 404, "text/plain", null, "no video"); return; }
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(u, "r");
             FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
            long size = pfd.getStatSize(), start = 0, end = size - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String[] r = range.substring(6).split("-", 2);
                try {
                    if (!r[0].isEmpty()) start = Long.parseLong(r[0]);
                    if (r.length > 1 && !r[1].isEmpty()) end = Long.parseLong(r[1]);
                    partial = true;
                } catch (NumberFormatException e) { start = 0; end = size - 1; }
            }
            end = Math.min(end, size - 1);
            long len = end - start + 1;
            String hd = "HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK") + "\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n"
                    + "Content-Length: " + len + "\r\n"
                    + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n" : "")
                    + (download ? "Content-Disposition: attachment; filename=\"timelapse_" + id + ".mp4\"\r\n" : "")
                    + "Connection: close\r\n\r\n";
            out.write(hd.getBytes());
            FileChannel ch = fis.getChannel();
            ch.position(start);
            byte[] buf = new byte[64 * 1024];
            long left = len;
            while (left > 0) {
                int n = fis.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }
}
