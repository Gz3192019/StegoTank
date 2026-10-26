package com.setgo.tank.core;

import java.util.HashMap;
import java.util.Map;

/**
 * RAC-Hide 核心编解码（与 src/core.js 一致）。
 * 图像以 { rgba: byte[] (r,g,b,a 交错, 0..255), width, height } 表示。
 */
public final class TuyinCore {
    /** RGBA 图像表示。 */
    public static class ImageData {
        public final byte[] rgba;
        public final int width;
        public final int height;

        public ImageData(byte[] rgba, int width, int height) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
        }
    }

    public static final class Options {
        public int ppb = 2;
        public int repeat = 1;
        public int nsym = 48;
        public int marginMin = 40;
        public double marginGain = 1.4;
        public int marginMax = 200;
        public int secretMax = 1280;

        public Options() { }
    }

    private static final Map<Integer, TuyinReedSolomon> RS_CACHE = new HashMap<>();

    private static TuyinReedSolomon rsFor(int nsym) {
        TuyinReedSolomon codec = RS_CACHE.get(nsym);
        if (codec == null) {
            codec = new TuyinReedSolomon(nsym);
            RS_CACHE.put(nsym, codec);
        }
        return codec;
    }

    private static Options normalizeOptions(Options options) {
        Options merged = new Options();
        if (options != null) {
            merged.ppb = options.ppb;
            merged.repeat = options.repeat;
            merged.nsym = options.nsym;
            merged.marginMin = options.marginMin;
            merged.marginGain = options.marginGain;
            merged.marginMax = options.marginMax;
            merged.secretMax = options.secretMax;
        }
        if (merged.ppb < 1 || merged.ppb > TuyinConstants.COEFFICIENT_PAIRS.length) {
            throw new IllegalArgumentException("ppb must be in 1.." + TuyinConstants.COEFFICIENT_PAIRS.length);
        }
        if (merged.repeat < 1 || merged.repeat > 5) {
            throw new IllegalArgumentException("repeat must be in 1..5");
        }
        if (merged.nsym < 8 || merged.nsym > 64) {
            throw new IllegalArgumentException("nsym must be in 8..64");
        }
        return merged;
    }

    private TuyinCore() { }

    /** 能装进图片的载荷字节数（含 11 字节头）。 */
    public static int capacityBytes(int width, int height, Options options) {
        Options o = normalizeOptions(options);
        int bw = width / TuyinConstants.BLOCK_SIZE;
        int bh = height / TuyinConstants.BLOCK_SIZE;
        int totalSlots = bw * bh * o.ppb;
        int rmax = totalSlots / TuyinConstants.CODEWORD_BITS;
        int maxCodewords = rmax / o.repeat;
        return Math.max(0, maxCodewords * (255 - o.nsym) - TuyinConstants.PAYLOAD_HEADER_BYTES);
    }

    /** 组装自描述头 + 载荷体。 */
    public static byte[] buildPayload(byte[] jpeg, Options options) {
        Options o = normalizeOptions(options);
        byte[] payload = new byte[TuyinConstants.PAYLOAD_HEADER_BYTES + jpeg.length];
        for (int i = 0; i < 4; i++) payload[i] = (byte) TuyinConstants.MAGIC[i];
        payload[4] = (byte) o.ppb;
        payload[5] = (byte) o.repeat;
        payload[6] = (byte) o.nsym;
        int len = jpeg.length;
        payload[7] = (byte) ((len >>> 24) & 0xff);
        payload[8] = (byte) ((len >>> 16) & 0xff);
        payload[9] = (byte) ((len >>> 8) & 0xff);
        payload[10] = (byte) (len & 0xff);
        System.arraycopy(jpeg, 0, payload, TuyinConstants.PAYLOAD_HEADER_BYTES, jpeg.length);
        return payload;
    }

    private static byte[] encodeStream(byte[] payload, Options o) {
        int messageBytes = 255 - o.nsym;
        int numCodewords = (payload.length + messageBytes - 1) / messageBytes;
        byte[] padded = new byte[numCodewords * messageBytes];
        System.arraycopy(payload, 0, padded, 0, payload.length);

        TuyinReedSolomon codec = rsFor(o.nsym);
        byte[] encoded = new byte[numCodewords * 255];
        for (int c = 0; c < numCodewords; c++) {
            byte[] msg = new byte[messageBytes];
            System.arraycopy(padded, c * messageBytes, msg, 0, messageBytes);
            byte[] ew = codec.encode(msg);
            System.arraycopy(ew, 0, encoded, c * 255, 255);
        }

        int[] encodedBits = TuyinBits.bytesToBits(encoded);
        byte[] repeated = new byte[encodedBits.length * o.repeat];
        for (int i = 0; i < encodedBits.length; i++) {
            for (int r = 0; r < o.repeat; r++) repeated[i * o.repeat + r] = (byte) encodedBits[i];
        }
        return repeated;
    }

    /** 嵌入进度回调：frac 为 0..1。 */
    public interface EmbedProgress {
        void onProgress(float frac);
    }

    /** 进度节流：按 1% 步进上报，避免高频回调。 */
    private static final class ProgressReporter {
        private final EmbedProgress cb;
        private final long total;
        private long done;
        private float last = -1f;

        ProgressReporter(EmbedProgress cb, long total) {
            this.cb = cb;
            this.total = Math.max(1L, total);
        }

        void step(long n) {
            done += n;
            float f = (float) Math.min(1.0, (double) done / total);
            if (f - last >= 0.01f || f >= 1f) {
                last = f;
                cb.onProgress(f);
            }
        }
    }

    /** 把载荷嵌入封面图。 */
    public static ImageData embed(ImageData imageData, byte[] payload, Options options) {
        return embed(imageData, payload, options, null);
    }

    /** 把载荷嵌入封面图，带进度回调（算法与无回调版本完全一致）。 */
    public static ImageData embed(ImageData imageData, byte[] payload, Options options, EmbedProgress progress) {
        Options o = normalizeOptions(options);
        byte[] data = imageData.rgba;
        int width = imageData.width;
        int height = imageData.height;
        int bw = width / TuyinConstants.BLOCK_SIZE;
        int bh = height / TuyinConstants.BLOCK_SIZE;
        int totalSlots = bw * bh * o.ppb;
        int rmax = totalSlots / TuyinConstants.CODEWORD_BITS;
        if (rmax < 1) throw new IllegalArgumentException("cover image is too small to embed anything");
        // 总工作量：像素标尺生成(width*height) + DCT 块嵌入(bw*bh) + 输出重建(width*height)
        long totalWork = (long) width * height * 2 + (long) bw * bh;
        ProgressReporter reporter = progress == null ? null : new ProgressReporter(progress, totalWork);

        int messageBytes = 255 - o.nsym;
        int maxCodewords = rmax / o.repeat;
        if (payload.length > maxCodewords * messageBytes) {
            throw new IllegalArgumentException(
                "payload of " + payload.length + " B exceeds capacity of " + (maxCodewords * messageBytes) + " B");
        }

        byte[] repeated = encodeStream(payload, o);

        byte[] slotBits = new byte[totalSlots];
        java.util.Arrays.fill(slotBits, (byte) -1);
        for (int k = 0; k < repeated.length; k++) {
            slotBits[TuyinInterleave.slotForIndex(k, rmax)] = repeated[k];
        }

        // v1.9 分块流式：预构建 112x112 标尺格点（内存极小），逐块采样并计算灰度/标尺/输出，
        // 消除原版 rulerField(float WxH)/rulerRgb(double 3xWxH)/gray(double WxH) 三个全图大数组，
        // 内存峰值与图片尺寸解耦（仅剩 data 与 out 两份 byte[]），逐像素结果与原版完全一致。
        double[] lattice = TuyinRuler.lattice(width, height);
        double room = 255 - 2 * o.marginMin;
        int blockSize = TuyinConstants.BLOCK_SIZE;
        byte[] out = new byte[width * height * 4];
        int blk = blockSize * blockSize;
        double[] grayBlock = new double[blk];
        double[] rgbBlockR = new double[blk];
        double[] rgbBlockG = new double[blk];
        double[] rgbBlockB = new double[blk];
        double[][] block = new double[blockSize][blockSize];

        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                int base = (by * bw + bx) * o.ppb;
                boolean used = false;
                for (int p = 0; p < o.ppb; p++) {
                    if (slotBits[base + p] >= 0) { used = true; break; }
                }

                // 1) 逐像素标尺 + 灰度（块级暂存，替代整图 rulerField/rulerRgb/gray）
                for (int i = 0; i < blockSize; i++) {
                    int py = by * blockSize + i;
                    for (int j = 0; j < blockSize; j++) {
                        int px = bx * blockSize + j;
                        int idx = py * width + px;
                        int r = data[idx * 4] & 0xff;
                        int g = data[idx * 4 + 1] & 0xff;
                        int b = data[idx * 4 + 2] & 0xff;
                        double y = 0.299 * r + 0.587 * g + 0.114 * b;
                        double cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b;
                        double cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
                                + TuyinRuler.sample(lattice, width, height, px, py);
                        if (cb < 0) cb = 0;
                        else if (cb > 255) cb = 255;
                        double ncr = cr - 128;
                        double ncb = cb - 128;
                        double nr = y + 1.402 * ncr;
                        double ng = y - 0.344136 * ncb - 0.714136 * ncr;
                        double nb = y + 1.772 * ncb;
                        int bi = i * blockSize + j;
                        rgbBlockR[bi] = nr;
                        rgbBlockG[bi] = ng;
                        rgbBlockB[bi] = nb;
                        grayBlock[bi] = 0.299 * nr + 0.587 * ng + 0.114 * nb;
                    }
                }

                // 2) DCT 嵌入（仅当本块有载荷位）
                if (used) {
                    double bmn = Double.POSITIVE_INFINITY;
                    double bmx = Double.NEGATIVE_INFINITY;
                    for (int i = 0; i < blockSize; i++) {
                        for (int j = 0; j < blockSize; j++) {
                            double v = grayBlock[i * blockSize + j] - 128;
                            block[i][j] = v;
                            if (v < bmn) bmn = v;
                            if (v > bmx) bmx = v;
                        }
                    }
                    if (bmx - bmn > room) {
                        double mid = (bmn + bmx) / 2;
                        double scale = room / (bmx - bmn);
                        for (int i = 0; i < blockSize; i++) {
                            for (int j = 0; j < blockSize; j++) {
                                block[i][j] = mid + (block[i][j] - mid) * scale;
                            }
                        }
                    }

                    double[][] coeffs = TuyinDct.dct2d(block);
                    for (int p = 0; p < o.ppb; p++) {
                        int bit = slotBits[base + p];
                        if (bit < 0) continue;
                        int[][] pair = TuyinConstants.COEFFICIENT_PAIRS[p];
                        int[] a = pair[0];
                        int[] bIdx = pair[1];
                        double c1 = coeffs[a[0]][a[1]];
                        double c2 = coeffs[bIdx[0]][bIdx[1]];
                        double d = c1 - c2;
                        double avgMag = (Math.abs(c1) + Math.abs(c2)) / 2;
                        double margin = Math.min(Math.max(o.marginMin, o.marginGain * avgMag), o.marginMax);
                        double target = bit == 1 ? margin : -margin;
                        if (bit == 1 && d >= target) continue;
                        if (bit == 0 && d <= target) continue;
                        double delta = (target - d) / 2;
                        coeffs[a[0]][a[1]] += delta;
                        coeffs[bIdx[0]][bIdx[1]] -= delta;
                    }

                    double[][] modulated = TuyinDct.idct2d(coeffs);
                    double mn = Double.POSITIVE_INFINITY;
                    double mx = Double.NEGATIVE_INFINITY;
                    for (int i = 0; i < blockSize; i++) {
                        for (int j = 0; j < blockSize; j++) {
                            double v = modulated[i][j];
                            if (v < mn) mn = v;
                            if (v > mx) mx = v;
                        }
                    }
                    double shift = 0;
                    if (mx > 127) shift = 127 - mx;
                    else if (mn < -128) shift = -128 - mn;
                    for (int i = 0; i < blockSize; i++) {
                        for (int j = 0; j < blockSize; j++) {
                            grayBlock[i * blockSize + j] = modulated[i][j] + shift + 128;
                        }
                    }
                }

                // 3) 输出重建（块级）
                for (int i = 0; i < blockSize; i++) {
                    int py = by * blockSize + i;
                    for (int j = 0; j < blockSize; j++) {
                        int px = bx * blockSize + j;
                        int idx = py * width + px;
                        int bi = i * blockSize + j;
                        int[] rgb = TuyinColor.reconstructRgb(
                            (int) Math.round(rgbBlockR[bi]),
                            (int) Math.round(rgbBlockG[bi]),
                            (int) Math.round(rgbBlockB[bi]),
                            grayBlock[bi]);
                        out[idx * 4] = (byte) rgb[0];
                        out[idx * 4 + 1] = (byte) rgb[1];
                        out[idx * 4 + 2] = (byte) rgb[2];
                        out[idx * 4 + 3] = (byte) 255;
                    }
                }

                if (reporter != null) reporter.step(blk * 2 + 1);
            }
        }
        return new ImageData(out, width, height);
    }

    /** 从隐写图提取载荷（暴力搜 (ppb, repeat, nsym)）。 */
    public static ExtractResult extract(ImageData imageData) {
        byte[] data = imageData.rgba;
        int width = imageData.width;
        int height = imageData.height;
        int bw = width / TuyinConstants.BLOCK_SIZE;
        int bh = height / TuyinConstants.BLOCK_SIZE;
        int totalBlocks = bw * bh;
        if (totalBlocks == 0) return null;

        // v1.9 分块流式：逐块计算 luma（块级 8x8），消除原版全图 gray(double WxH) 大数组，
        // 提取结果与原版逐字节一致（luma 公式不变）。
        int pairCount = TuyinConstants.COEFFICIENT_PAIRS.length;
        int[] allDeltas = new int[totalBlocks * pairCount];
        int blockSize = TuyinConstants.BLOCK_SIZE;
        double[][] block = new double[blockSize][blockSize];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                for (int i = 0; i < blockSize; i++) {
                    for (int j = 0; j < blockSize; j++) {
                        int idx = (by * blockSize + i) * width + (bx * blockSize + j);
                        int r = data[idx * 4] & 0xff;
                        int g = data[idx * 4 + 1] & 0xff;
                        int b = data[idx * 4 + 2] & 0xff;
                        block[i][j] = 0.299 * r + 0.587 * g + 0.114 * b - 128;
                    }
                }
                double[][] coeffs = TuyinDct.dct2d(block);
                int base = (by * bw + bx) * pairCount;
                for (int p = 0; p < pairCount; p++) {
                    int[][] pair = TuyinConstants.COEFFICIENT_PAIRS[p];
                    int[] a = pair[0];
                    int[] bIdx = pair[1];
                    allDeltas[base + p] = (int) Math.round(coeffs[a[0]][a[1]] - coeffs[bIdx[0]][bIdx[1]]);
                }
            }
        }

        int[] magic = TuyinConstants.MAGIC;
        for (int ppb = 1; ppb <= pairCount; ppb++) {
            int totalSlots = totalBlocks * ppb;
            int rmax = totalSlots / TuyinConstants.CODEWORD_BITS;
            if (rmax < 2) continue;

            byte[] slotBits = new byte[totalSlots];
            for (int blockIndex = 0; blockIndex < totalBlocks; blockIndex++) {
                int base = blockIndex * ppb;
                int deltaBase = blockIndex * pairCount;
                for (int p = 0; p < ppb; p++) {
                    slotBits[base + p] = (byte) (allDeltas[deltaBase + p] >= TuyinConstants.READ_THRESHOLD ? 1 : 0);
                }
            }

            for (int repeat = 1; repeat <= 5; repeat++) {
                byte[] firstBits;
                if (repeat > 1) {
                    byte[] raw = TuyinInterleave.deinterleave(slotBits, rmax, TuyinConstants.CODEWORD_BITS * repeat);
                    firstBits = new byte[TuyinConstants.CODEWORD_BITS];
                    for (int i = 0; i < TuyinConstants.CODEWORD_BITS; i++) firstBits[i] = raw[i * repeat];
                } else {
                    firstBits = TuyinInterleave.deinterleave(slotBits, rmax, TuyinConstants.CODEWORD_BITS);
                }
                byte[] firstBytes = TuyinBits.bitsToBytes(firstBits);

                for (int nsym = 8; nsym <= 64; nsym++) {
                    TuyinReedSolomon codec = rsFor(nsym);
                    byte[] first = codec.decode(firstBytes);
                    if (first == null) continue;
                    if ((first[0] & 0xff) != magic[0] || (first[1] & 0xff) != magic[1]
                            || (first[2] & 0xff) != magic[2] || (first[3] & 0xff) != magic[3]) {
                        continue;
                    }
                    int headerPpb = first[4] & 0xff;
                    int headerRepeat = first[5] & 0xff;
                    int headerNsym = first[6] & 0xff;
                    if (headerPpb != ppb || headerRepeat != repeat || headerNsym != nsym
                            || headerPpb < 1 || headerPpb > pairCount
                            || headerRepeat < 1 || headerRepeat > 5) {
                        continue;
                    }

                    int messageBytes = 255 - nsym;
                    int len = ((first[7] & 0xff) << 24) | ((first[8] & 0xff) << 16)
                            | ((first[9] & 0xff) << 8) | (first[10] & 0xff);
                    if (len <= 0 || len > TuyinConstants.MAX_PAYLOAD_BYTES) continue;
                    int numCodewords = (TuyinConstants.PAYLOAD_HEADER_BYTES + len + messageBytes - 1) / messageBytes;
                    if (numCodewords > rmax) continue;

                    byte[] payload = decodeStream(slotBits, rmax, repeat, numCodewords, messageBytes, codec);
                    if (payload == null) continue;

                    byte[] jpeg = new byte[len];
                    System.arraycopy(payload, TuyinConstants.PAYLOAD_HEADER_BYTES, jpeg, 0, len);
                    return new ExtractResult(jpeg, ppb, repeat, nsym);
                }
            }
        }
        return null;
    }

    public static final class ExtractResult {
        public final byte[] jpeg;
        public final int ppb;
        public final int repeat;
        public final int nsym;

        public ExtractResult(byte[] jpeg, int ppb, int repeat, int nsym) {
            this.jpeg = jpeg;
            this.ppb = ppb;
            this.repeat = repeat;
            this.nsym = nsym;
        }
    }

    private static byte[] decodeStream(byte[] slotBits, int rmax, int repeat,
                                       int numCodewords, int messageBytes, TuyinReedSolomon codec) {
        byte[] allBits;
        if (repeat > 1) {
            byte[] raw = TuyinInterleave.deinterleave(slotBits, rmax, numCodewords * TuyinConstants.CODEWORD_BITS * repeat);
            allBits = new byte[numCodewords * TuyinConstants.CODEWORD_BITS];
            for (int j = 0; j < allBits.length; j++) allBits[j] = raw[j * repeat];
        } else {
            allBits = TuyinInterleave.deinterleave(slotBits, rmax, numCodewords * TuyinConstants.CODEWORD_BITS);
        }
        byte[] allBytes = TuyinBits.bitsToBytes(allBits);
        byte[] payload = new byte[numCodewords * messageBytes];
        for (int c = 0; c < numCodewords; c++) {
            byte[] cw = new byte[255];
            System.arraycopy(allBytes, c * 255, cw, 0, 255);
            byte[] message = codec.decode(cw);
            if (message == null) return null;
            System.arraycopy(message, 0, payload, c * messageBytes, messageBytes);
        }
        return payload;
    }
}
