package com.setgo.tank.core;

/** 颜色助手（BT.601），与 src/color.js 一致。 */
public final class TuyinColor {
    private TuyinColor() { }

    /** 从 RGBA 字节数组提取亮度平面。 */
    public static double[] toLuma(byte[] rgba, int width, int height) {
        double[] out = new double[width * height];
        for (int i = 0; i < out.length; i++) {
            int r = rgba[i * 4] & 0xff;
            int g = rgba[i * 4 + 1] & 0xff;
            int b = rgba[i * 4 + 2] & 0xff;
            out[i] = 0.299 * r + 0.587 * g + 0.114 * b;
        }
        return out;
    }

    /** 重建 RGB：让 BT.601 亮度尽可能等于 targetY，同时保留原色度。 */
    public static int[] reconstructRgb(int r, int g, int b, double targetY) {
        double luma = 0.299 * r + 0.587 * g + 0.114 * b;
        double delta = targetY - luma;
        double r2 = clamp255(r + delta);
        double g2 = clamp255(g + delta);
        double b2 = clamp255(b + delta);

        double error = targetY - (0.299 * r2 + 0.587 * g2 + 0.114 * b2);
        double[][] order = { { 1, 0.587 }, { 0, 0.299 }, { 2, 0.114 } };
        for (int k = 0; k < order.length && Math.abs(error) > 0.35; k++) {
            int channel = (int) order[k][0];
            double weight = order[k][1];
            double value = clamp255(current(r2, g2, b2, channel) + error / weight);
            if (channel == 0) r2 = value;
            else if (channel == 1) g2 = value;
            else b2 = value;
            error = targetY - (0.299 * r2 + 0.587 * g2 + 0.114 * b2);
        }
        if (Math.abs(error) > 0.35) {
            r2 = g2 = b2 = targetY;
        }

        int r0 = (int) Math.round(r2);
        int g0 = (int) Math.round(g2);
        int b0 = (int) Math.round(b2);
        int bestR = r0, bestG = g0, bestB = b0;
        double bestError = Math.abs(0.299 * r0 + 0.587 * g0 + 0.114 * b0 - targetY);
        for (int dr = -1; dr <= 1; dr++) {
            for (int dg = -1; dg <= 1; dg++) {
                for (int db = -1; db <= 1; db++) {
                    int rr = r0 + dr, gg = g0 + dg, bb = b0 + db;
                    if (rr < 0 || rr > 255 || gg < 0 || gg > 255 || bb < 0 || bb > 255) continue;
                    double e = Math.abs(0.299 * rr + 0.587 * gg + 0.114 * bb - targetY);
                    if (e < bestError) {
                        bestError = e;
                        bestR = rr;
                        bestG = gg;
                        bestB = bb;
                    }
                }
            }
        }
        return new int[] { bestR, bestG, bestB };
    }

    private static double current(double r, double g, double b, int channel) {
        if (channel == 0) return r;
        if (channel == 1) return g;
        return b;
    }

    private static double clamp255(double v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }
}
