package com.setgo.tank.engine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * 图片混淆（Arnold 猫映射置乱）。
 *
 * 正变换： (x', y') = (x + y, x + 2y) mod n
 * 逆变换： (x,  y)  = (2x - y, -x + y) mod n
 * 迭代次数即密钥：加密 N 轮、解密 N 轮即恢复。非正方形图自动补白边为正方形，
 * 恢复后裁回原尺寸。变换有周期性，N 过大可能回到近似原图，建议 1~200。
 */
public final class ArnoldScramble {

    private ArnoldScramble() {}

    /** 非正方形图居中补白边成正方形。 */
    public static Bitmap padSquare(Bitmap src) {
        int n = Math.max(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(0xFFFFFFFF);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(src, null,
                new android.graphics.RectF((n - src.getWidth()) / 2f, (n - src.getHeight()) / 2f,
                        (n + src.getWidth()) / 2f, (n + src.getHeight()) / 2f), p);
        return out;
    }

    /** 一轮置乱（reverse=false 正变换 / true 逆变换），输入输出同尺寸正方形。 */
    public static Bitmap transform(Bitmap square, boolean reverse) {
        int n = square.getWidth();
        int[] src = new int[n * n];
        int[] dst = new int[n * n];
        square.getPixels(src, 0, n, 0, 0, n, n);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int nx, ny;
                if (!reverse) {
                    nx = mod(x + y, n);
                    ny = mod(x + 2 * y, n);
                } else {
                    nx = mod(2 * x - y, n);
                    ny = mod(-x + y, n);
                }
                dst[ny * n + nx] = src[y * n + x];
            }
        }
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        out.setPixels(dst, 0, n, 0, 0, n, n);
        return out;
    }

    /** 加密：对（补边后的）图连续置乱 iterations 轮。 */
    public static Bitmap scramble(Bitmap img, int iterations) {
        Bitmap cur = padSquare(img);
        for (int i = 0; i < iterations; i++) {
            Bitmap next = transform(cur, false);
            if (i > 0) cur.recycle();
            cur = next;
        }
        return cur;
    }

    /** 解密：对（正方形）置乱图连续逆变换 iterations 轮。 */
    public static Bitmap unscramble(Bitmap square, int iterations) {
        Bitmap cur = square;
        for (int i = 0; i < iterations; i++) {
            Bitmap next = transform(cur, true);
            if (i > 0) cur.recycle();
            cur = next;
        }
        return cur;
    }

    /** 从正方形结果裁回原始宽高。 */
    public static Bitmap crop(Bitmap square, int w, int h) {
        if (square.getWidth() == w && square.getHeight() == h) return square;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(square, null, new android.graphics.RectF(0, 0, w, h), p);
        return out;
    }

    private static int mod(int a, int n) {
        a %= n;
        return a < 0 ? a + n : a;
    }
}
