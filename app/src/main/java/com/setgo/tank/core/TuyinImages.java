package com.setgo.tank.core;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 图片工具：解码/缩放/JPEG 阶梯压缩/相册保存（对齐 src/browser.js 语义）。
 *  v1.6：解码改为按目标长边 2 的幂采样（避免全尺寸驻留），缩放/压缩中间位图及时回收，
 *        显著降低大封面增强时的内存峰值（此前拉到 4096/自定义会 OOM 闪退）。 */
public final class TuyinImages {
    /** 长边上限（对齐 JS capLongEdge）。 */
    public static final int MAX_LONG = 4096;
    /** 秘密图最长边上限（对齐 JS secretMax 默认）。 */
    public static final int SECRET_MAX = 1280;

    private TuyinImages() { }

    /* ---------------- 解码 ---------------- */

    /** 解码 Uri 为 ImageData，可选按目标长边缩放；先采样解码再精确缩放，应用 EXIF 旋转。
     *  targetLong>0 时按目标长边上限采样解码（0 时用 MAX_LONG 上限）。 */
    public static TuyinCore.ImageData decodeUri(Context ctx, Uri uri, int targetLong) throws IOException {
        int limit = targetLong > 0 ? targetLong : MAX_LONG;
        Bitmap sampled = decodeBitmapSampled(ctx, uri, limit);
        if (sampled == null) throw new IOException("无法解码图片");
        Bitmap oriented = applyExifRotation(ctx, uri, sampled);
        if (oriented != sampled) sampled.recycle();
        return capLongEdge(oriented, limit);
    }
    /** 解码为保留 alpha 通道的 Bitmap（供幻影通道等 alpha 检测使用；不经过抹 alpha 的 ImageData 链路）。 */
    public static Bitmap decodeKeepAlpha(Context ctx, Uri uri, int targetLong) throws IOException {
        int limit = targetLong > 0 ? targetLong : MAX_LONG;
        Bitmap sampled = decodeBitmapSampled(ctx, uri, limit);
        if (sampled == null) throw new IOException("无法解码图片");
        Bitmap oriented = applyExifRotation(ctx, uri, sampled);
        if (oriented != sampled) sampled.recycle();
        return capLongKeepAlpha(oriented, limit);
    }

    /** 仅缩放限长边并保留 alpha（与 capLongEdge 不同：不经过 bitmapToImageData）。 */
    private static Bitmap capLongKeepAlpha(Bitmap src, int limit) {
        int w = src.getWidth(), h = src.getHeight();
        if (Math.max(w, h) <= limit) return src;
        float s = Math.min((float) limit / w, (float) limit / h);
        int nw = Math.max(1, Math.round(w * s));
        int nh = Math.max(1, Math.round(h * s));
        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
        if (scaled != src) src.recycle();
        return scaled;
    }

    /** 按目标长边采样解码：先只读尺寸，2 的幂采样使解码后长边 ≈ 目标，避免全尺寸位图驻留。 */
    public static Bitmap decodeBitmapSampled(Context ctx, Uri uri, int targetLong) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        int srcW = bounds.outWidth, srcH = bounds.outHeight;
        if (srcW <= 0 || srcH <= 0) return null;
        int sample = 1;
        int longest = Math.max(srcW, srcH);
        while (longest / (sample * 2) >= targetLong) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inSampleSize = sample;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(is, null, opts);
        }
    }

    /** 按目标长边采样解码本地文件（历史记录缩略图跳转加载等），不抛受检异常，失败返回 null。 */
    public static Bitmap decodeFileScaled(String path, int targetLong) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int srcW = bounds.outWidth, srcH = bounds.outHeight;
            if (srcW <= 0 || srcH <= 0) return null;
            int sample = 1;
            int longest = Math.max(srcW, srcH);
            while (longest / (sample * 2) >= targetLong) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Bitmap applyExifRotation(Context ctx, Uri uri, Bitmap src) {
        try {
            int rotation = 0;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    ExifInterface exif = new ExifInterface(is);
                    int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    switch (ori) {
                        case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                        case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                        case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                        default: rotation = 0;
                    }
                }
            }
            if (rotation == 0) return src;
            Matrix m = new Matrix();
            m.postRotate(rotation);
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Exception e) {
            return src;
        }
    }

    /** 字节 JPEG 解码后应用 EXIF 旋转（提取结果方向保险）。 */
    public static Bitmap applyExifRotationBytes(byte[] jpeg, Bitmap src) {
        if (src == null) return src;
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
            int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotation = 0;
            switch (ori) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                default: rotation = 0;
            }
            if (rotation == 0) return src;
            Matrix m = new Matrix();
            m.postRotate(rotation);
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Exception e) {
            return src;
        }
    }

    /** 长边不超过 limit 的等比缩放（对齐 JS capLongEdge）；缩放后回收输入位图。 */
    public static TuyinCore.ImageData capLongEdge(Bitmap src, int limit) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (Math.max(w, h) > limit) {
            double scale = limit / (double) Math.max(w, h);
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
            src.recycle();
            src = scaled;
        }
        TuyinCore.ImageData out = bitmapToImageData(src);
        if (!src.isRecycled()) src.recycle();
        return out;
    }

    public static TuyinCore.ImageData bitmapToImageData(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            int p = pixels[i];
            rgba[i * 4] = (byte) ((p >> 16) & 0xff);
            rgba[i * 4 + 1] = (byte) ((p >> 8) & 0xff);
            rgba[i * 4 + 2] = (byte) (p & 0xff);
            rgba[i * 4 + 3] = (byte) 255;
        }
        return new TuyinCore.ImageData(rgba, w, h);
    }

    public static Bitmap imageDataToBitmap(TuyinCore.ImageData img) {
        int w = img.width;
        int h = img.height;
        int[] pixels = new int[w * h];
        byte[] rgba = img.rgba;
        for (int i = 0; i < w * h; i++) {
            int r = rgba[i * 4] & 0xff;
            int g = rgba[i * 4 + 1] & 0xff;
            int b = rgba[i * 4 + 2] & 0xff;
            pixels[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }

    /** 高质量重采样（对齐 JS resizeImageData）；中间位图用后即回收。 */
    public static TuyinCore.ImageData resizeImageData(TuyinCore.ImageData img, int width, int height) {
        Bitmap src = imageDataToBitmap(img);
        Bitmap scaled = Bitmap.createScaledBitmap(src, width, height, true);
        src.recycle();
        TuyinCore.ImageData out = bitmapToImageData(scaled);
        scaled.recycle();
        return out;
    }

    /* ---------------- 秘密图 JPEG 阶梯压缩（对齐 JS fitSecretJpeg） ---------------- */

    public static byte[] fitSecretJpeg(TuyinCore.ImageData source, int maxBytes, int maxLong) {
        int cap = Math.max(32, Math.min(maxLong, Math.max(source.width, source.height)));
        int[] baseDims = { 640, 560, 480, 400, 320, 256, 192, 128, 96, 64, 48, 32 };
        int[] dims = new int[baseDims.length + 1];
        int n = 0;
        boolean hasCap = false;
        for (int d : baseDims) {
            if (d <= cap) {
                dims[n++] = d;
                if (d == cap) hasCap = true;
            }
        }
        if (cap > 640 && !hasCap) {
            System.arraycopy(dims, 0, dims, 1, n);
            dims[0] = cap;
            n++;
        }
        if (n == 0) dims[n++] = cap;

        Bitmap base = imageDataToBitmap(source);
        int[] qualities = { 80, 65, 50, 40, 32, 25 };
        try {
            for (int i = 0; i < n; i++) {
                int maxDim = dims[i];
                int width = source.width;
                int height = source.height;
                if (Math.max(width, height) > maxDim) {
                    double scale = maxDim / (double) Math.max(width, height);
                    width = Math.max(1, (int) Math.round(width * scale));
                    height = Math.max(1, (int) Math.round(height * scale));
                }
                // 白底画布（对齐 JS fillStyle '#ffffff'，避免透明变黑）
                Bitmap canvas = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(canvas);
                c.drawColor(0xFFFFFFFF);
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                c.drawBitmap(base, null, new android.graphics.Rect(0, 0, width, height), paint);

                for (int quality : qualities) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    boolean ok = canvas.compress(Bitmap.CompressFormat.JPEG, quality, baos);
                    if (ok && baos.size() <= maxBytes) {
                        return baos.toByteArray();
                    }
                }
                canvas.recycle();
            }
            return null;
        } finally {
            base.recycle();
        }
    }

    /* ---------------- 保存到相册 ---------------- */

    public static boolean saveBitmapToGallery(Context ctx, Bitmap bitmap, String name, String mime, int quality) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, mime);
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/图隐");
                Uri uri = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                    if (os == null) return false;
                    return compressToStream(bitmap, mime, quality, os);
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "图隐");
                if (!dir.exists() && !dir.mkdirs()) return false;
                File file = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    return compressToStream(bitmap, mime, quality, fos);
                }
            }
        } catch (Throwable e) {
            // 捕获 OutOfMemoryError 等，避免保存大 PNG 时崩溃；返回 false 提示保存失败
            return false;
        }
    }

    /** 原样保存字节（JSteg JPEG 等不可再压缩产物）到相册（Pictures/图隐）。 */
    public static boolean saveBytesToGallery(Context ctx, byte[] bytes, String name, String mime) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, mime);
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/图隐");
                Uri uri = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                    if (os == null) return false;
                    os.write(bytes);
                    return true;
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "图隐");
                if (!dir.exists() && !dir.mkdirs()) return false;
                File file = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(bytes);
                    return true;
                }
            }
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean compressToStream(Bitmap bitmap, String mime, int quality, OutputStream os) {
        Bitmap.CompressFormat format = "image/jpeg".equals(mime)
                ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
        return bitmap.compress(format, quality, os);
    }
}
