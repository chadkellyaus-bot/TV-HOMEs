package org.tvhome.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;
import java.io.File;
import java.util.Arrays;
import java.util.Random;

/** Full-screen wallpaper that cross-fades to the next one. Holds at most two bitmaps in memory. */
public class WallpaperView extends View {
    private Bitmap cur, next;
    private float fade = 1f;
    private boolean released;
    private ValueAnimator anim;
    private int index = (int) (System.currentTimeMillis() / 60000 % 1000);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect(), dst = new Rect();

    public WallpaperView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
    }

    /** Loads the next wallpaper in the background (your photos first, then built-in scenes). */
    public void showNext() {
        released = false;
        final int i = index++;
        final File dir = getContext().getExternalFilesDir("wallpapers");
        new Thread(() -> {
            File[] photos = dir == null ? null
                    : dir.listFiles((d, n) -> n.toLowerCase().matches(".*\\.(jpe?g|png|webp)"));
            if (photos != null) Arrays.sort(photos);
            int np = photos == null ? 0 : photos.length;
            int k = i % (np + Scenes.COUNT);
            Bitmap b = k < np ? decode(photos[k]) : null;
            if (b == null) b = Scenes.render(i % Scenes.COUNT);
            final Bitmap nb = b;
            post(() -> show(nb));
        }).start();
    }

    /** Frees the bitmaps while another app is on screen. */
    public void release() {
        released = true;
        if (anim != null) anim.cancel();
        if (cur != null) { cur.recycle(); cur = null; }
        if (next != null) { next.recycle(); next = null; }
        invalidate();
    }

    private void show(Bitmap nb) {
        if (released) { nb.recycle(); return; }
        if (anim != null) anim.end();
        next = nb;
        fade = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1500);
        anim.addUpdateListener(a -> { fade = (float) a.getAnimatedValue(); invalidate(); });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                Bitmap old = cur;
                cur = next;
                next = null;
                fade = 1f;
                if (old != null) old.recycle();
                invalidate();
            }
        });
        anim.start();
    }

    private static Bitmap decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 1920) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override protected void onDraw(Canvas c) {
        dst.set(0, 0, getWidth(), getHeight());
        if (cur != null) { paint.setAlpha(255); drawFill(c, cur); }
        if (next != null) { paint.setAlpha((int) (fade * 255)); drawFill(c, next); }
    }

    private void drawFill(Canvas c, Bitmap b) {  // centre-crop to fill the screen
        float s = Math.max((float) getWidth() / b.getWidth(), (float) getHeight() / b.getHeight());
        int sw = (int) (getWidth() / s), sh = (int) (getHeight() / s);
        src.set((b.getWidth() - sw) / 2, (b.getHeight() - sh) / 2, (b.getWidth() + sw) / 2, (b.getHeight() + sh) / 2);
        c.drawBitmap(b, src, dst, paint);
    }

    /** Stylised nature scenes drawn in code, so the app needs no image files. */
    static final class Scenes {
        static final int COUNT = 5;
        // sky top, sky bottom, sun/moon, then four ridges from far to near
        private static final int[][] PALETTES = {
            {0xFF2B1B4D, 0xFFFF9A6B, 0xFFFFE2B0, 0xFF8E5A8C, 0xFF5A3D7A, 0xFF35224F, 0xFF1D1230},  // dawn
            {0xFF3A7BD5, 0xFFBFE3FF, 0xFFFFFFFF, 0xFF9DB9D6, 0xFF6C93B8, 0xFF3F6A8F, 0xFF22435F},  // alpine day
            {0xFF1B1F4B, 0xFFE8735A, 0xFFFFC27A, 0xFF7A4A7E, 0xFF4B2F63, 0xFF2A1B45, 0xFF150E26},  // dusk
            {0xFF7FA79A, 0xFFE6EFE6, 0xFFFFFFF0, 0xFF9BB8AA, 0xFF6F9484, 0xFF456A5C, 0xFF263F37},  // misty forest
            {0xFF050B1F, 0xFF12385A, 0xFFE8F1FF, 0xFF1C3C5E, 0xFF14304B, 0xFF0C2136, 0xFF06111D}   // moonlit night
        };

        static Bitmap render(int n) {
            final int w = 1280, h = 720;
            int[] p = PALETTES[n % COUNT];
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new LinearGradient(0, 0, 0, h * 0.75f, p[0], p[1], Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            Random r = new Random(n * 7919L + 13);
            if (n % COUNT == 4) {
                paint.setShader(null);
                paint.setColor(0xCCFFFFFF);
                for (int i = 0; i < 160; i++)
                    c.drawCircle(r.nextInt(w), r.nextInt((int) (h * 0.6f)), 0.8f + r.nextFloat() * 1.4f, paint);
            }
            float sx = w * (0.25f + r.nextFloat() * 0.5f), sy = h * (0.28f + r.nextFloat() * 0.12f), sr = h * 0.07f;
            paint.setShader(new RadialGradient(sx, sy, sr * 5, (p[2] & 0x00FFFFFF) | 0x66000000,
                    p[2] & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(sx, sy, sr * 5, paint);
            paint.setShader(null);
            paint.setColor(p[2]);
            c.drawCircle(sx, sy, sr, paint);
            for (int layer = 0; layer < 4; layer++) {
                float base = h * (0.52f + layer * 0.13f), amp = h * (0.16f - layer * 0.025f);
                float f1 = 1.5f + r.nextFloat() * 2f, f2 = 4f + r.nextFloat() * 4f, ph = r.nextFloat() * 6.28f;
                Path path = new Path();
                path.moveTo(0, h);
                for (int x = 0; x <= w; x += 8) {
                    float t = (float) x / w;
                    float y = base - amp * (0.6f * (float) Math.sin(t * f1 * 6.28f + ph)
                            + 0.4f * (float) Math.sin(t * f2 * 6.28f + ph * 2));
                    path.lineTo(x, y);
                }
                path.lineTo(w, h);
                path.close();
                paint.setColor(p[3 + layer]);
                c.drawPath(path, paint);
            }
            return b;
        }
    }
}

