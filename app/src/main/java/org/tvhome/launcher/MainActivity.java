package org.tvhome.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal Apple TV-style home screen: wallpaper, clock, a dock of favourite apps, and an All apps grid. */
public class MainActivity extends Activity {
    // First-run dock: the streaming apps on this TV. Edit freely; unknown packages are skipped.
    private static final String DEFAULT_DOCK = "com.netflix.ninja,au.com.stan.and,"
            + "com.amazon.amazonvideo.livingroom,com.disney.disneyplus,com.wbd.stream,"
            + "com.google.android.youtube.tv,com.spotify.tv.android";
    private static final long ROTATE_MS = 10 * 60 * 1000;

    private static class App { String pkg, cls; CharSequence label; }

    private final List<App> apps = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            wallpaper.showNext();
            handler.postDelayed(this, ROTATE_MS);
        }
    };
    private SharedPreferences prefs;
    private PackageManager pm;
    private FrameLayout root;
    private WallpaperView wallpaper;
    private LinearLayout dock;
    private TextView label;
    private GridView grid;
    private float dp;
    private boolean dirty = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        pm = getPackageManager();
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        root.setClipChildren(false);
        wallpaper = new WallpaperView(this);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);  // darkens the bottom so the dock is readable
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xB0000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, px(300), Gravity.BOTTOM));

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END);
        clockBox.addView(clock("h:mm", 44));
        clockBox.addView(clock("EEEE d MMMM", 18));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.setMargins(0, px(40), px(64), 0);
        root.addView(clockBox, cp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setClipChildren(false);
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(20);
        label.setGravity(Gravity.CENTER);
        bottom.addView(dock);
        bottom.addView(label);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.setMargins(0, 0, 0, px(48));
        root.addView(bottom, bp);
        setContentView(root);

        IntentFilter f = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { dirty = true; }
        }, f);
    }

    @Override protected void onStart() {
        super.onStart();
        if (dirty) { loadApps(); dirty = false; }
        buildDock();
        wallpaper.showNext();
        handler.postDelayed(rotate, ROTATE_MS);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(rotate);
        wallpaper.release();  // give the memory back while another app is open
        closeGrid();
        super.onStop();
    }

    @Override protected void onNewIntent(Intent i) {  // Home pressed while already here
        super.onNewIntent(i);
        closeGrid();
    }

    @Override public void onBackPressed() {  // never leave the home screen
        closeGrid();
    }

    // ---- apps ----

    private void loadApps() {
        Map<String, App> byPkg = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || byPkg.containsKey(p)) continue;
                App a = new App();
                a.pkg = p;
                a.cls = ri.activityInfo.name;
                a.label = ri.loadLabel(pm);
                byPkg.put(p, a);
            }
        }
        apps.clear();
        apps.addAll(byPkg.values());
        Collections.sort(apps, (x, y) -> x.label.toString().compareToIgnoreCase(y.label.toString()));
    }

    private App find(String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private Drawable icon(App a) {
        try {
            return pm.getActivityIcon(new ComponentName(a.pkg, a.cls));
        } catch (Exception e) {
            return pm.getDefaultActivityIcon();
        }
    }

    private void launch(App a) {
        Intent i = new Intent(Intent.ACTION_MAIN).setClassName(a.pkg, a.cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    /** Adds the app to the dock, or removes it if it's already there. Returns true if now pinned. */
    private boolean togglePin(String pkg) {
        List<String> l = new ArrayList<>(Arrays.asList(prefs.getString("dock", DEFAULT_DOCK).split(",")));
        boolean removed = l.remove(pkg);
        if (!removed) l.add(pkg);
        prefs.edit().putString("dock", TextUtils.join(",", l)).apply();
        return !removed;
    }

    // ---- dock ----

    private void buildDock() {
        dock.removeAllViews();
        for (String p : prefs.getString("dock", DEFAULT_DOCK).split(",")) {
            final App a = find(p);
            if (a == null) continue;  // not installed or disabled
            View v = cell(icon(a), a.label);
            v.setOnClickListener(x -> launch(a));
            v.setOnLongClickListener(x -> { togglePin(a.pkg); buildDock(); return true; });  // hold OK to remove
            dock.addView(v);
        }
        View all = cell(allAppsIcon(), "All apps");
        all.setOnClickListener(x -> showGrid());
        dock.addView(all);
        final Intent si = new Intent(Settings.ACTION_SETTINGS);
        ResolveInfo sr = pm.resolveActivity(si, 0);
        if (sr != null) {
            View s = cell(sr.loadIcon(pm), "Settings");
            s.setOnClickListener(x -> startActivity(si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            dock.addView(s);
        }
        final View first = dock.getChildAt(0);
        dock.post(() -> first.requestFocus());
    }

    private View cell(Drawable icon, final CharSequence name) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(icon);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setBackgroundColor(0x22FFFFFF);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(18));
            }
        });
        v.setClipToOutline(true);
        v.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(96), px(96));
        lp.setMargins(px(10), px(14), px(10), px(14));
        v.setLayoutParams(lp);
        v.setOnFocusChangeListener((view, focused) -> {
            float s = focused ? 1.18f : 1f;
            view.animate().scaleX(s).scaleY(s).setDuration(140).start();
            if (focused) label.setText(name);
        });
        return v;
    }

    // ---- all apps ----

    private void showGrid() {
        if (grid != null) return;
        GridView g = new GridView(this);
        g.setNumColumns(8);
        g.setBackgroundColor(0xE6000000);
        g.setPadding(px(64), px(48), px(64), px(48));
        g.setClipToPadding(false);
        g.setVerticalSpacing(px(16));
        g.setHorizontalSpacing(px(16));
        g.setSelector(new ColorDrawable(0x44FFFFFF));
        g.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int i) { return apps.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) {
                TextView t = cv instanceof TextView ? (TextView) cv : new TextView(MainActivity.this);
                App a = apps.get(i);
                Drawable d = icon(a);
                d.setBounds(0, 0, px(72), px(72));
                t.setCompoundDrawables(null, d, null, null);
                t.setCompoundDrawablePadding(px(8));
                t.setText(a.label);
                t.setTextColor(Color.WHITE);
                t.setTextSize(15);
                t.setGravity(Gravity.CENTER_HORIZONTAL);
                t.setSingleLine();
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(px(8), px(12), px(8), px(12));
                return t;
            }
        });
        g.setOnItemClickListener((p, v, pos, id) -> launch(apps.get(pos)));
        g.setOnItemLongClickListener((p, v, pos, id) -> {  // hold OK to add/remove from the dock
            boolean pinned = togglePin(apps.get(pos).pkg);
            Toast.makeText(this, pinned ? "Added to dock" : "Removed from dock", Toast.LENGTH_SHORT).show();
            return true;
        });
        grid = g;
        root.addView(g, new FrameLayout.LayoutParams(-1, -1));
        g.requestFocus();
    }

    private void closeGrid() {
        if (grid == null) return;
        root.removeView(grid);
        grid = null;
        buildDock();
    }

    // ---- helpers ----

    private int px(int n) { return (int) (n * dp + 0.5f); }

    private TextClock clock(String format, int sp) {
        TextClock t = new TextClock(this);
        t.setFormat12Hour(format);
        t.setFormat24Hour(format);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setShadowLayer(8, 0, 2, 0x99000000);
        t.setGravity(Gravity.END);
        return t;
    }

    /** A 2x2 grid of rounded squares, drawn in code. */
    private Drawable allAppsIcon() {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                float s = b.width() / 7f;
                p.setColor(Color.WHITE);
                for (int r = 0; r < 2; r++)
                    for (int q = 0; q < 2; q++) {
                        float x = b.left + s * (1.2f + q * 2.6f), y = b.top + s * (1.2f + r * 2.6f);
                        c.drawRoundRect(x, y, x + s * 2.2f, y + s * 2.2f, s * 0.5f, s * 0.5f, p);
                    }
            }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(ColorFilter f) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
}
