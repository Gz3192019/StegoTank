package com.setgo.tank.core;

/** 位/字节转换（高位在前），与 src/bits.js 一致。 */
public final class TuyinBits {
    private TuyinBits() { }

    /** 字节 → 每字节一位的位数组。 */
    public static int[] bytesToBits(byte[] bytes) {
        int[] bits = new int[bytes.length * 8];
        int p = 0;
        for (byte value : bytes) {
            int b = value & 0xff;
            for (int k = 7; k >= 0; k--) bits[p++] = (b >> k) & 1;
        }
        return bits;
    }

    /** 位数组 → 打包字节，尾部不整字节丢弃。 */
    public static byte[] bitsToBytes(int[] bits) {
        int n = bits.length / 8;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int byteVal = 0;
            for (int k = 0; k < 8; k++) byteVal = (byteVal << 1) | (bits[i * 8 + k] & 1);
            out[i] = (byte) byteVal;
        }
        return out;
    }

    /** byte[] 版（位以 0/1 字节存储）。 */
    public static byte[] bitsToBytes(byte[] bits) {
        int n = bits.length / 8;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int byteVal = 0;
            for (int k = 0; k < 8; k++) byteVal = (byteVal << 1) | (bits[i * 8 + k] & 1);
            out[i] = (byte) byteVal;
        }
        return out;
    }
}
