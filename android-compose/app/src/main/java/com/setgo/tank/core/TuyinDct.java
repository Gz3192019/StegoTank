package com.setgo.tank.core;

/** 正交 8x8 II 型 DCT 及其逆（与 src/dct.js 一致）。 */
public final class TuyinDct {
    private static final int N = TuyinConstants.BLOCK_SIZE;
    private static final double SCALE_DC = Math.sqrt(1.0 / N);
    private static final double SCALE_AC = Math.sqrt(2.0 / N);
    private static final double[][] COS = buildCos();

    private static double[][] buildCos() {
        double[][] t = new double[N][N];
        for (int k = 0; k < N; k++) {
            for (int n = 0; n < N; n++) {
                t[k][n] = Math.cos(Math.PI * (2 * n + 1) * k / (2.0 * N));
            }
        }
        return t;
    }

    private TuyinDct() { }

    public static double[] dct1d(double[] input) {
        double[] out = new double[N];
        for (int k = 0; k < N; k++) {
            double sum = 0;
            double[] row = COS[k];
            for (int n = 0; n < N; n++) sum += input[n] * row[n];
            out[k] = sum * (k == 0 ? SCALE_DC : SCALE_AC);
        }
        return out;
    }

    public static double[] idct1d(double[] input) {
        double[] out = new double[N];
        for (int n = 0; n < N; n++) {
            double sum = 0;
            for (int k = 0; k < N; k++) {
                sum += (k == 0 ? SCALE_DC : SCALE_AC) * input[k] * COS[k][n];
            }
            out[n] = sum;
        }
        return out;
    }

    public static double[][] dct2d(double[][] block) {
        double[][] rows = new double[N][];
        for (int i = 0; i < N; i++) rows[i] = dct1d(block[i]);
        double[][] out = new double[N][N];
        double[] column = new double[N];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) column[i] = rows[i][j];
            double[] transformed = dct1d(column);
            for (int i = 0; i < N; i++) out[i][j] = transformed[i];
        }
        return out;
    }

    public static double[][] idct2d(double[][] block) {
        double[][] columns = new double[N][];
        double[] column = new double[N];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) column[i] = block[i][j];
            columns[j] = idct1d(column);
        }
        double[][] out = new double[N][N];
        for (int i = 0; i < N; i++) {
            double[] row = new double[N];
            for (int j = 0; j < N; j++) row[j] = columns[j][i];
            out[i] = idct1d(row);
        }
        return out;
    }
}
