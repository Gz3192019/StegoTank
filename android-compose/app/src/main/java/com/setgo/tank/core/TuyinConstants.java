package com.setgo.tank.core;

/** RAC-Hide 算法常量（与 src/constants.js 一一对应）。 */
public final class TuyinConstants {
    /** DCT 块边长。 */
    public static final int BLOCK_SIZE = 8;
    /** 载荷魔数，ASCII "STG1"。 */
    public static final int[] MAGIC = { 0x53, 0x54, 0x47, 0x31 };
    /** 一个 RS 码字位数（255 字节）。 */
    public static final int CODEWORD_BITS = 255 * 8;
    /** 载荷头长度：magic(4)+ppb(1)+repeat(1)+nsym(1)+length(4)。 */
    public static final int PAYLOAD_HEADER_BYTES = 11;
    /** 提取器接受的最大载荷（字节）。 */
    public static final int MAX_PAYLOAD_BYTES = 10 * 1024 * 1024;
    /** 提取器判定 |C1-C2| 的阈值。 */
    public static final int READ_THRESHOLD = 2;
    /** 系数对（低频→中频，成对系数量化步长相近，差符号稳健）。 */
    public static final int[][][] COEFFICIENT_PAIRS = {
        { { 0, 1 }, { 1, 0 } },
        { { 0, 2 }, { 2, 0 } },
        { { 1, 1 }, { 0, 3 } },
        { { 3, 0 }, { 2, 1 } },
        { { 1, 2 }, { 0, 4 } },
        { { 4, 0 }, { 3, 1 } },
        { { 2, 2 }, { 1, 3 } },
        { { 0, 5 }, { 5, 0 } },
        { { 3, 2 }, { 2, 3 } },
        { { 1, 4 }, { 4, 1 } },
        { { 0, 6 }, { 6, 0 } },
        { { 2, 4 }, { 4, 2 } },
    };
    public static final int PAIR_COUNT = COEFFICIENT_PAIRS.length;
    /** 默认嵌入参数（均衡档）。 */
    public static final int[] DEFAULT_PPB = { 2, 1, 48, 40, 14, 200 }; // ppb, repeat, nsym, marginMin, marginGain*10, marginMax

    private TuyinConstants() { }
}
