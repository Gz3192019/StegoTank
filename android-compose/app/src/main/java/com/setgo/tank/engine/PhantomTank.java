package com.setgo.tank.engine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * 幻影坦克（Phantom Tank）：利用 PNG alpha 通道实现双背景显像。
 *
 * 数学原理（灰度模式精确解）：
 *   白底显示：T = X·α + 255·(1-α)
 *   黑底显示：I = X·α
 *   联立反解：α = (I - T)/255 + 1，X = I / α
 * 彩色模式逐通道独立求解，表图暗于里图的通道会被裁剪（轻微偏差）。
 */
public final class PhantomTank {

    public static final int MODE_GRAY = 0;   // 灰度：两图转亮度求解，白/黑底显像最精确
    public static final int MODE_COLOR = 1;  // 彩色：逐通道求解

    private PhantomTank() {}

    /** 把两张图 contain 居中对齐到同一画布。 */
    public static Bitmap alignTo(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        float s = Math.min((float) w / src.getWidth(), (float) h / src.getHeight());
        int dw = Math.round(src.getWidth() * s);
        int dh = Math.round(src.getHeight() * s);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(src, null,
                new android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), p);
        return out;
    }

    /** 生成幻影坦克图（ARGB，alpha 通道承载显像信息）。 */
    public static Bitmap make(Bitmap top, Bitmap bottom, int mode) {
        int w = Math.max(top.getWidth(), bottom.getWidth());
        int h = Math.max(top.getHeight(), bottom.getHeight());
        Bitmap ta = alignTo(top, w, h);
        Bitmap tb = alignTo(bottom, w, h);

        int n = w * h;
        int[] pa = new int[n];
        int[] pb = new int[n];
        int[] po = new int[n];
        ta.getPixels(pa, 0, w, 0, 0, w, h);
        tb.getPixels(pb, 0, w, 0, 0, w, h);

        for (int i = 0; i < n; i++) {
            int a = pa[i], b = pb[i];
            int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff, aa = (a >> 24) & 0xff;
            int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff, ba = (b >> 24) & 0xff;
            int alphaOut;
            if (mode == MODE_GRAY) {
                double T = 0.299 * ar + 0.587 * ag + 0.114 * ab;
                double I = 0.299 * br + 0.587 * bg + 0.114 * bb;
                double alpha = (I - T) / 255.0 + 1.0;
                if (alpha < 0.05) alpha = 0.05;
                if (alpha > 1.0) alpha = 1.0;
                double X = I / alpha;
                int v = clamp255(X);
                alphaOut = clamp255(alpha * 255);
                po[i] = (alphaOut << 24) | (v << 16) | (v << 8) | v;
            } else {
                int r = solve(ar, br);
                int g = solve(ag, bg);
                int b0 = solve(ab, bb);
                alphaOut = Math.min(aa, ba);
                po[i] = (alphaOut << 24) | (r << 16) | (g << 8) | b0;
            }
        }

        ta.recycle();
        tb.recycle();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(po, 0, w, 0, 0, w, h);
        return out;
    }

    /** 单通道求解：α=(I-T)/255+1，X=I/α。 */
    private static int solve(int T, int I) {
        double alpha = (I - T) / 255.0 + 1.0;
        if (alpha < 0.05) alpha = 0.05;
        if (alpha > 1.0) alpha = 1.0;
        return clamp255(I / alpha);
    }

    private static int clamp255(double v) {
        return (int) (v < 0 ? 0 : v > 255 ? 255 : Math.round(v));
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
