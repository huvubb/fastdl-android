package com.fastdl.android;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Lightweight optical glass: transmission, refractive rim and moving-looking specular bands. */
final class LiquidGlassDrawable extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private int tint;
    private final float radius;

    LiquidGlassDrawable(int tint, float radius) {
        this.tint = tint;
        this.radius = radius;
        rim.setStyle(Paint.Style.STROKE);
        shine.setStyle(Paint.Style.STROKE);
        shine.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override protected void onBoundsChange(android.graphics.Rect bounds) {
        super.onBoundsChange(bounds);
        box.set(bounds.left + 1, bounds.top + 1, bounds.right - 1, bounds.bottom - 1);
    }

    @Override public void draw(Canvas canvas) {
        int a = Color.alpha(tint);
        int r = Color.red(tint), g = Color.green(tint), b = Color.blue(tint);
        fill.setShader(new LinearGradient(0, box.top, 0, box.bottom,
                new int[]{Color.argb(Math.min(145, a + 55), 255, 255, 255), tint,
                        Color.argb(Math.max(12, a - 12), r, g, b)},
                new float[]{0f, .42f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box, radius, radius, fill);

        rim.setStrokeWidth(1.4f);
        rim.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom,
                Color.argb(210, 255, 255, 255), Color.argb(70, 255, 255, 255), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box, radius, radius, rim);

        float glow = Math.max(30, Math.min(150, a + 40));
        shine.setStrokeWidth(Math.max(2f, box.width() * .006f));
        shine.setShader(new RadialGradient(box.left + box.width() * .2f,
                box.top + box.height() * .1f, box.width() * .65f,
                new int[]{Color.argb((int) glow, 255, 255, 255), Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        Path arc = new Path();
        arc.moveTo(box.left + radius, box.top + 2);
        arc.quadTo(box.left + box.width() * .22f, box.top + box.height() * .1f,
                box.left + box.width() * .52f, box.top + 2);
        canvas.drawPath(arc, shine);
        shine.setShader(null);
        shine.setColor(Color.argb(55, 255, 255, 255));
        shine.setStrokeWidth(1f);
        canvas.drawRoundRect(new RectF(box.left + 2, box.top + 2, box.right - 2, box.bottom - 2), radius - 2, radius - 2, shine);
    }

    @Override public void setAlpha(int alpha) { tint = Color.argb(alpha, Color.red(tint), Color.green(tint), Color.blue(tint)); invalidateSelf(); }
    @Override public void setColorFilter(android.graphics.ColorFilter filter) { fill.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
