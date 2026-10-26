package com.setgo.tank.core;

import java.util.Arrays;

/**
 * 空间标尺 — 分辨率无关的几何元数据（与 src/ruler.js 一致）。
 * 将封面宽高以平滑低幅度亮度偏移写入固定 GxG 网格的 Cb 通道；
 * 缩放前后相对位置不变，解码端可恢复原始尺寸。
 */
public final class TuyinRuler {
    private static final int G = 112;
    private static final double D = 6.0;
    private static final int MAGIC_BYTE = 0xa5;
    private static final int MAGIC_BYTE2 = 0x5a;
    private static final int RS_PARITY = 4;
    private static final int K = 80;
    private static final long SEED = 0x51edL;
    private static final int MAX_DIM = 65535;

    /** 格点对：一半水平相邻、一半垂直相邻，再确定性洗牌。 */
    private static final int[][] PAIRS = buildPairs();

    /** 每个标尺位重复次数。 */
    private static final int R = PAIRS.length / K;

    private static int[][] buildPairs() {
        int[] tmp = new int[G * G / 2 + G * G / 2];
        int n = 0;
        int[][] pairs = new int[G * G][4];
        for (int j = 0; j < G; j++) {
            for (int i = 0; i + 1 < G; i += 2) pairs[n++] = new int[] { i, j, i + 1, j };
        }
        for (int i = 0; i < G; i++) {
            for (int j = 0; j + 1 < G; j += 2) pairs[n++] = new int[] { i, j, i, j + 1 };
        }
        long state = SEED;
        for (int k = n - 1; k > 0; k--) {
            state = (state * 1664525L + 1013904223L) & 0xFFFFFFFFL;
            double rr = state / 4294967296.0;
            int r = (int) Math.floor(rr * (k + 1));
            int[] t = pairs[k];
            pairs[k] = pairs[r];
            pairs[r] = t;
        }
        int[][] out = new int[n][4];
        for (int i = 0; i < n; i++) out[i] = pairs[i];
        return out;
    }

    private TuyinRuler() { }

    /** 打包封面尺寸为 RS 保护的标尺位。 */
    public static byte[] encodeRulerBits(int coverW, int coverH) {
        byte[] data = {
            (byte) MAGIC_BYTE,
            (byte) MAGIC_BYTE2,
            (byte) ((coverW >> 8) & 0xff),
            (byte) (coverW & 0xff),
            (byte) ((coverH >> 8) & 0xff),
            (byte) (coverH & 0xff),
        };
        byte[] encoded = new TuyinReedSolomon(RS_PARITY).encode(data);
        byte[] bits = new byte[K];
        for (int i = 0; i < K; i++) bits[i] = (byte) ((encoded[i >> 3] & 0xff) >> (7 - (i & 7)) & 1);
        return bits;
    }

    /** 解码标尺位为 [coverW, coverH]，失败返回 null。 */
    public static int[] decodeRulerBits(byte[] bits) {
        byte[] bytes = new byte[K / 8];
        for (int i = 0; i < K; i++) {
            if (bits[i] != 0) bytes[i >> 3] |= (byte) (1 << (7 - (i & 7)));
        }
        byte[] decoded;
        try {
            decoded = new TuyinReedSolomon(RS_PARITY).decode(bytes);
        } catch (Exception e) {
            return null;
        }
        if (decoded == null) return null;
        int b0 = decoded[0] & 0xff, b1 = decoded[1] & 0xff;
        if (b0 != MAGIC_BYTE || b1 != MAGIC_BYTE2) return null;
        int coverW = ((decoded[2] & 0xff) << 8) | (decoded[3] & 0xff);
        int coverH = ((decoded[4] & 0xff) << 8) | (decoded[5] & 0xff);
        if (coverW < 16 || coverW > MAX_DIM || coverH < 16 || coverH > MAX_DIM) return null;
        return new int[] { coverW, coverH };
    }

    /** 构建平滑亮度偏移场（width x height）。 */
    public static float[] buildRulerField(int coverW, int coverH, int width, int height) {
        return bilinear(lattice(coverW, coverH), height, width);
    }

    /** 构建 GxG 格点场（分辨率无关，内存极小），供逐像素单点采样复用。
     *  v1.9：新增，供分块流式嵌入按需采样标尺，避免整图 float 数组驻留。 */
    public static double[] lattice(int coverW, int coverH) {
        byte[] bits = encodeRulerBits(coverW, coverH);
        double[] lat = new double[G * G];
        for (int k = 0; k < K; k++) {
            double sign = bits[k] != 0 ? 1 : -1;
            for (int r = 0; r < R; r++) {
                int[] p = PAIRS[k * R + r];
                lat[p[1] * G + p[0]] += sign * D;
                lat[p[3] * G + p[2]] -= sign * D;
            }
        }
        return lat;
    }

    /** 单点双线性采样：与 buildRulerField 的 bilinear 对同一 (x,y) 逐像素一致。 */
    public static float sample(double[] lattice, int width, int height, int x, int y) {
        double v = ((y + 0.5) * G) / height - 0.5;
        int j0 = (int) Math.floor(v);
        double t = v - j0;
        int j0c = clampInt(j0, 0, G - 1);
        int j1c = clampInt(j0 + 1, 0, G - 1);
        double u = ((x + 0.5) * G) / width - 0.5;
        int i0 = (int) Math.floor(u);
        double s = u - i0;
        int i0c = clampInt(i0, 0, G - 1);
        int i1c = clampInt(i0 + 1, 0, G - 1);
        double a = lattice[j0c * G + i0c] * (1 - s) + lattice[j0c * G + i1c] * s;
        double b = lattice[j1c * G + i0c] * (1 - s) + lattice[j1c * G + i1c] * s;
        return (float) (a * (1 - t) + b * t);
    }

    private static float[] bilinear(double[] lattice, int height, int width) {
        float[] out = new float[height * width];
        for (int y = 0; y < height; y++) {
            double v = ((y + 0.5) * G) / height - 0.5;
            int j0 = (int) Math.floor(v);
            double t = v - j0;
            int j0c = clampInt(j0, 0, G - 1);
            int j1c = clampInt(j0 + 1, 0, G - 1);
            for (int x = 0; x < width; x++) {
                double u = ((x + 0.5) * G) / width - 0.5;
                int i0 = (int) Math.floor(u);
                double s = u - i0;
                int i0c = clampInt(i0, 0, G - 1);
                int i1c = clampInt(i0 + 1, 0, G - 1);
                double a = lattice[j0c * G + i0c] * (1 - s) + lattice[j0c * G + i1c] * s;
                double b = lattice[j1c * G + i0c] * (1 - s) + lattice[j1c * G + i1c] * s;
                out[y * width + x] = (float) (a * (1 - t) + b * t);
            }
        }
        return out;
    }

    private static int[] cellBounds(int n) {
        int[] bounds = new int[G + 1];
        for (int i = 0; i <= G; i++) bounds[i] = (int) Math.round((i * n) / (double) G);
        return bounds;
    }

    /** 读取格点并解码标尺位（chroma 优先，luma 兼容旧版）。 */
    private static int[] readLattice(byte[] rgb, int width, int height, boolean useLuma) {
        int[] xs = cellBounds(width);
        int[] ys = cellBounds(height);
        double[] lattice = new double[G * G];
        for (int j = 0; j < G; j++) {
            int y0 = ys[j], y1 = ys[j + 1];
            for (int i = 0; i < G; i++) {
                int x0 = xs[i], x1 = xs[i + 1];
                double sum = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int p = (y * width + x) * 3;
                        int rr = rgb[p] & 0xff, gg = rgb[p + 1] & 0xff, bb = rgb[p + 2] & 0xff;
                        sum += useLuma
                            ? 0.299 * rr + 0.587 * gg + 0.114 * bb
                            : 128 - 0.168736 * rr - 0.331264 * gg + 0.5 * bb;
                        n++;
                    }
                }
                lattice[j * G + i] = n != 0 ? sum / n : 0;
            }
        }

        byte[] bits = new byte[K];
        for (int k = 0; k < K; k++) {
            double acc = 0;
            for (int r = 0; r < R; r++) {
                int[] q = PAIRS[k * R + r];
                acc += lattice[q[1] * G + q[0]] - lattice[q[3] * G + q[2]];
            }
            bits[k] = (byte) (acc > 0 ? 1 : 0);
        }
        return decodeRulerBits(bits);
    }

    /** 从 RGB 字节恢复封面尺寸，先 chroma 后 luma。 */
    public static int[] readRulerSize(byte[] rgb, int width, int height) {
        int[] got = readLattice(rgb, width, height, false);
        if (got != null) return got;
        return readLattice(rgb, width, height, true);
    }

    /** 额外要求恢复的宽高比与观测一致，拒绝误报。 */
    public static int[] detectRulerSize(byte[] rgb, int width, int height) {
        int[] got = readRulerSize(rgb, width, height);
        if (got == null) return null;
        double expected = got[0] / (double) got[1];
        double observed = width / (double) height;
        if (Math.abs(expected - observed) / observed > 0.06) return null;
        return got;
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
