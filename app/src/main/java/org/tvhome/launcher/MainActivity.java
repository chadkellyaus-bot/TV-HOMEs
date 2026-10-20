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
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
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

/** Minimal Apple TV-style home screen: wallpaper, clock, a dock of favourite apps, TV inputs and an All apps grid. */
public class MainActivity extends Activity {
    // First-run dock: the streaming apps on this TV. Edit freely; unknown packages are skipped.
    private static final String DEFAULT_DOCK = "com.netflix.ninja,au.com.stan.and,"
            + "com.amazon.amazonvideo.livingroom,com.disney.disneyplus,com.wbd.stream,"
            + "com.google.android.youtube.tv,com.spotify.tv.android";
    private static final long ROTATE_MS = 10 * 60 * 1000;
    /** Extra for "am start" that opens the Inputs panel (used by scripts/tv-improve.ps1 to check it). */
    private static final String EXTRA_SHOW_INPUTS = "show_inputs";

    private static class App { String pkg, cls; CharSequence label; }

    private static class Item {
        final Drawable icon; final CharSequence name; final Runnable click, longClick;
        Item(Drawable icon, CharSequence name, Runnable click, Runnable longClick) {
            this.icon = icon; this.name = name; this.click = click; this.longClick = longClick;
        }
    }

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
    private HorizontalScrollView dockScroll;
    private LinearLayout dock;
    private TextView label;
    private View overlay;        // All apps grid or Inputs panel, when open
    private View inputsPanel;
    private int focusIndex;      // dock position to return to
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

        View topShade = new View(this);  // keeps the clock readable on bright photos
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000}));
        root.addView(topShade, new FrameLayout.LayoutParams(-1, px(170), Gravity.TOP));

        View shade = new View(this);     // darkens the bottom so the dock is readable
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xA0000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, px(240), Gravity.BOTTOM));

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
        dockScroll = new HorizontalScrollView(this);  // scrolls only if the dock is wider than the screen
        dockScroll.setHorizontalScrollBarEnabled(false);
        dockScroll.setFillViewport(true);
        dockScroll.setClipChildren(false);
        dockScroll.setClipToPadding(false);
        dockScroll.setPadding(px(24), 0, px(24), 0);
        dockScroll.addView(dock, new FrameLayout.LayoutParams(-2, -2));
        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(20);
        label.setGravity(Gravity.CENTER);
        label.setShadowLayer(6, 0, 2, 0x99000000);
        bottom.addView(dockScroll, new LinearLayout.LayoutParams(-1, -2));
        bottom.addView(label);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.setMargins(0, 0, 0, px(40));
        root.addView(bottom, bp);
        setContentView(root);

        IntentFilter f = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { dirty = true; }
        }, f);

        if (getIntent().getBooleanExtra(EXTRA_SHOW_INPUTS, false)) handler.post(this::showInputs);
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
        closeOverlay(false);
        super.onStop();
    }

    @Override protected void onNewIntent(Intent i) {  // Home pressed while already here
        super.onNewIntent(i);
        setIntent(i);
        if (i.getBooleanExtra(EXTRA_SHOW_INPUTS, false)) handler.post(this::showInputs);
        else closeOverlay(true);
    }

    @Override public void onBackPressed() {  // never leave the home screen
        closeOverlay(true);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_TV_INPUT) {  // the remote's Input button, when it reaches us
            if (overlay != null && overlay == inputsPanel) closeOverlay(true);
            else showInputs();
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
        List<Item> items = new ArrayList<>();
        for (String p : prefs.getString("dock", DEFAULT_DOCK).split(",")) {
            final App a = find(p);
            if (a == null) continue;  // not installed or disabled
            items.add(new Item(icon(a), a.label, () -> launch(a), () -> { togglePin(a.pkg); buildDock(); }));
        }
        if (!visibleInputs().isEmpty()) items.add(new Item(inputsIcon(), "Inputs", this::showInputs, null));
        items.add(new Item(allAppsIcon(), "All apps", this::showGrid, null));
        final Intent si = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo sr = pm.resolveActivity(si, 0);
        if (sr != null) items.add(new Item(sr.loadIcon(pm), "Settings", () -> startActivity(si), null));

        // size the tiles so the whole dock fits on screen (the TV draws its interface at 1280x720)
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int size = (screenW - px(64)) / items.size() - 2 * px(8);
        size = Math.max(px(56), Math.min(px(96), size));

        dock.removeAllViews();
        for (int i = 0; i < items.size(); i++) dock.addView(cell(items.get(i), size, i));
        final View target = dock.getChildAt(Math.min(focusIndex, dock.getChildCount() - 1));
        dock.post(() -> { if (overlay == null && target != null) target.requestFocus(); });
    }

    private View cell(final Item it, int size, final int index) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(it.icon);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setBackgroundColor(0x22FFFFFF);
        final float radius = size * 0.19f;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
        v.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(px(8), px(14), px(8), px(14));
        v.setLayoutParams(lp);
        v.setOnFocusChangeListener((view, focused) -> {
            float s = focused ? 1.18f : 1f;
            view.animate().scaleX(s).scaleY(s).setDuration(140).start();
            if (focused) {
                label.setText(it.name);
                focusIndex = index;
            }
        });
        v.setOnClickListener(x -> it.click.run());
        if (it.longClick != null) v.setOnLongClickListener(x -> { it.longClick.run(); return true; });  // hold OK to remove
        return v;
    }

    // ---- overlays ----

    private void openOverlay(View v) {
        closeOverlay(false);
        overlay = v;
        dockScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);  // keep focus inside the overlay
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private void closeOverlay(boolean rebuildDock) {
        if (overlay == null) return;
        root.removeView(overlay);
        overlay = null;
        inputsPanel = null;
        dockScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (rebuildDock) buildDock();
    }

    // ---- all apps ----

    private void showGrid() {
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
        openOverlay(g);
        g.requestFocus();
    }

    // ---- TV inputs (HDMI, antenna) ----

    /** The TV's physical inputs, antenna first. Devices plugged into an HDMI port are shown as that port. */
    private List<TvInputInfo> visibleInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim != null) {
                for (TvInputInfo info : tim.getTvInputList())
                    if (info.getParentId() == null && !info.isHidden(this)) out.add(info);
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, (x, y) -> Integer.compare(x.getType() == TvInputInfo.TYPE_TUNER ? 0 : 1,
                y.getType() == TvInputInfo.TYPE_TUNER ? 0 : 1));
        return out;
    }

    private CharSequence inputLabel(TvInputInfo info) {
        CharSequence s = null;
        try {
            s = info.loadCustomLabel(this);  // a name you gave the input in Settings
        } catch (Throwable ignored) {
        }
        return TextUtils.isEmpty(s) ? info.loadLabel(this) : s;
    }

    private void launchInput(TvInputInfo info) {
        Uri uri = info.isPassthroughInput()
                ? TvContract.buildChannelUriForPassthroughInput(info.getId())  // HDMI, AV
                : TvContract.buildChannelsUriForInput(info.getId());           // antenna / live TV
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + inputLabel(info), Toast.LENGTH_SHORT).show();
        }
    }

    private void showInputs() {
        List<TvInputInfo> inputs = visibleInputs();
        if (inputs.isEmpty()) {
            Toast.makeText(this, "No TV inputs found", Toast.LENGTH_SHORT).show();
            return;
        }
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(0xCC000000);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        TextView title = new TextView(this);
        title.setText("Inputs");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        box.addView(title);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        for (final TvInputInfo info : inputs) {
            final TextView b = new TextView(this);
            b.setText(inputLabel(info));
            b.setTextColor(Color.WHITE);
            b.setTextSize(20);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine();
            b.setEllipsize(TextUtils.TruncateAt.END);
            b.setMinWidth(px(130));
            b.setPadding(px(22), px(16), px(22), px(16));
            final GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x33FFFFFF);
            bg.setCornerRadius(px(14));
            b.setBackground(bg);
            b.setFocusable(true);
            b.setOnFocusChangeListener((v, focused) -> {
                bg.setColor(focused ? 0xFFFFFFFF : 0x33FFFFFF);
                b.setTextColor(focused ? Color.BLACK : Color.WHITE);
                v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(120).start();
            });
            b.setOnClickListener(v -> {
                closeOverlay(false);
                launchInput(info);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(px(10), px(24), px(10), px(10));
            row.addView(b, lp);
        }
        HorizontalScrollView rowScroll = new HorizontalScrollView(this);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.setFillViewport(true);
        rowScroll.setClipChildren(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setPadding(px(48), 0, px(48), 0);
        rowScroll.addView(row, new FrameLayout.LayoutParams(-2, -2));
        box.addView(rowScroll, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(box, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        openOverlay(panel);
        inputsPanel = panel;
        row.getChildAt(0).requestFocus();
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

    /** A TV screen with an arrow going into it, drawn in code. */
    private Drawable inputsIcon() {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                float u = b.width() / 10f;
                p.setColor(Color.WHITE);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u * 0.6f);
                p.setStrokeCap(Paint.Cap.ROUND);
                RectF screen = new RectF(b.left + u * 1.8f, b.top + u * 2.4f, b.right - u * 1.8f, b.top + u * 6.9f);
                c.drawRoundRect(screen, u * 0.7f, u * 0.7f, p);
                c.drawLine(b.left + u * 3.6f, b.top + u * 8.1f, b.right - u * 3.6f, b.top + u * 8.1f, p);
                float cy = screen.centerY();
                c.drawLine(b.left + u * 3.4f, cy, b.left + u * 6.2f, cy, p);
                c.drawLine(b.left + u * 5.0f, cy - u * 1.2f, b.left + u * 6.2f, cy, p);
                c.drawLine(b.left + u * 5.0f, cy + u * 1.2f, b.left + u * 6.2f, cy, p);
            }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(ColorFilter f) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
}
