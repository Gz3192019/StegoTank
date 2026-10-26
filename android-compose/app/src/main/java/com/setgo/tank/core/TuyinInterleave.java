package com.setgo.tank.core;

/** 全局交织，与 src/interleave.js 一致。 */
public final class TuyinInterleave {
    private TuyinInterleave() { }

    /** 逻辑位索引 → 物理槽索引。 */
    public static int slotForIndex(int k, int rmax) {
        return (k % TuyinConstants.CODEWORD_BITS) * rmax + k / TuyinConstants.CODEWORD_BITS;
    }

    /** 从物理槽数组读回 count 个逻辑位。 */
    public static byte[] deinterleave(byte[] slotBits, int rmax, int count) {
        byte[] out = new byte[count];
        for (int k = 0; k < count; k++) out[k] = slotBits[slotForIndex(k, rmax)];
        return out;
    }
}
