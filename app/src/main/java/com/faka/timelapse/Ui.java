package com.faka.timelapse;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared look: colors, flat text styles, list rows. */
final class Ui {
    static final int BG = 0xFF0C0C0B, TXT = 0xFFF2EFE8, DIM = 0xFF77736B, LINE = 0xFF24231F,
            AMBER = 0xFFFFB020, RED = 0xFFFF5A47;
    static final ExecutorService IO = Executors.newFixedThreadPool(2);

    /** Press feedback: the view shrinks a little while a finger is on it, and springs back fast. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    static <T extends View> T press(T v) {
        v.setOnTouchListener((x, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) x.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2)).start();
            else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) x.animate().scaleX(1).scaleY(1).setDuration(160)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2)).start();
            return false;
        });
        return v;
    }

    static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static void window(Activity a) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
    }

    static TextView text(Context c, String t, int size, int color) {
        TextView v = new TextView(c);
        v.setText(t); v.setTextSize(size); v.setTextColor(color); v.setIncludeFontPadding(false);
        return v;
    }

    static TextView caps(Context c, String t, int color) {
        TextView v = text(c, t, 11, color);
        v.setLetterSpacing(0.18f); v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    static TextView thin(Context c, String t, int size) {
        TextView v = text(c, t, size, TXT);
        v.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        return v;
    }

    static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(c, 1)));
        return v;
    }

    /** Flat list row: just a label, full width, tall enough to tap. */
    static Button row(Context c, String label, int color) {
        Button b = new Button(c);
        b.setText(label); b.setAllCaps(false); b.setTextColor(color); b.setTextSize(16);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setBackgroundColor(0); b.setPadding(0, 0, 0, 0); b.setStateListAnimator(null);
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(c, 54)));
        return b;
    }

    static GradientDrawable pill(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static TextView back(Activity a, String label) {
        TextView t = caps(a, "‹  " + label.toUpperCase(), DIM);
        t.setPadding(dp(a, 24), dp(a, 24), dp(a, 24), dp(a, 8));
        t.setOnClickListener(v -> a.finish());
        return t;
    }

    @SuppressWarnings("deprecation")
    static boolean recordingAlive(Context c) {
        for (ActivityManager.RunningServiceInfo i : c.getSystemService(ActivityManager.class).getRunningServices(50))
            if (TimelapseService.class.getName().equals(i.service.getClassName())) return true;
        return false;
    }

    static float parse(android.widget.EditText e, float d) {
        try { return Float.parseFloat(e.getText().toString()); } catch (Exception x) { return d; }
    }

    static String duration(long ms) {
        long m = ms / 60000;
        if (m < 1) return "under 1 min";
        if (m < 60) return m + " min";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    /** Light level on a log scale, with the start threshold marked. */
    static final class LightBar extends View {
        float lux = 0, thr = 50;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        LightBar(Context c) { super(c); }
        float pos(float v) { return Math.min(1f, (float) (Math.log10(1 + Math.max(0, v)) / Math.log10(10001))); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), y = getHeight() / 2f, h = dp(getContext(), 4), t = dp(getContext(), 1);
            p.setColor(LINE); c.drawRoundRect(0, y - h / 2, w, y + h / 2, h, h, p);
            p.setColor(lux >= thr ? AMBER : DIM); c.drawRoundRect(0, y - h / 2, w * pos(lux), y + h / 2, h, h, p);
            p.setColor(TXT); c.drawRect(w * pos(thr) - t, y - 9 * t, w * pos(thr) + t, y + 9 * t, p);
        }
    }
}
