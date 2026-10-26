package com.setgo.tank.engine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * 光棱坦克（Prism Tank）：亮度域双显。
 *
 * 合成：out = wa·表图 + wb·隐藏图（默认 wa≈0.55, wb≈0.22）
 *   - 正常观看（曝光 1.0×）：表图主导可见；
 *   - 拉高曝光（≥3×）：表图过曝趋白消失，隐藏图浮现。
 * 可处理彩色图片，是幻影坦克的亮度域变体。
 */
public final class PrismTank {

    public static final double DEFAULT_WA = 0.55;
    public static final double DEFAULT_WB = 0.22;

    private PrismTank() {}

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

    /** 合成光棱坦克图。 */
    public static Bitmap make(Bitmap cover, Bitmap hidden, double wa, double wb) {
        int w = Math.max(cover.getWidth(), hidden.getWidth());
        int h = Math.max(cover.getHeight(), hidden.getHeight());
        Bitmap ca = alignTo(cover, w, h);
        Bitmap ha = alignTo(hidden, w, h);

        int n = w * h;
        int[] pc = new int[n];
        int[] ph = new int[n];
        int[] po = new int[n];
        ca.getPixels(pc, 0, w, 0, 0, w, h);
        ha.getPixels(ph, 0, w, 0, 0, w, h);

        for (int i = 0; i < n; i++) {
            int c = pc[i], hd = ph[i];
            int cr = (c >> 16) & 0xff, cg = (c >> 8) & 0xff, cb = c & 0xff;
            int hr = (hd >> 16) & 0xff, hg = (hd >> 8) & 0xff, hb = hd & 0xff;
            int r = clamp255(wa * cr + wb * hr);
            int g = clamp255(wa * cg + wb * hg);
            int b = clamp255(wa * cb + wb * hb);
            po[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
        }

        ca.recycle();
        ha.recycle();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(po, 0, w, 0, 0, w, h);
        return out;
    }

    /** 曝光预览：把合成图每通道乘以曝光倍数后钳制到 0..255。 */
    public static Bitmap render(Bitmap src, double exposure) {
        int w = src.getWidth(), h = src.getHeight();
        int n = w * h;
        int[] px = new int[n];
        int[] po = new int[n];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < n; i++) {
            int c = px[i];
            int r = clamp255(((c >> 16) & 0xff) * exposure);
            int g = clamp255(((c >> 8) & 0xff) * exposure);
            int b = clamp255((c & 0xff) * exposure);
            po[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(po, 0, w, 0, 0, w, h);
        return out;
    }

    private static int clamp255(double v) {
        return (int) (v < 0 ? 0 : v > 255 ? 255 : Math.round(v));
    }
}
