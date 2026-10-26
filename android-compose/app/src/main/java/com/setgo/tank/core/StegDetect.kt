package com.setgo.tank.core

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 隐写检测（启发式，只作"判断提示"，不保证精确）。
 *
 * 主判据（高置信→疑似）：
 *  1) LSB 魔数：位流前 4 字节 "ST" → 图隐套件 LSB 格式（确定）。
 *  2) 幻影通道：灰度模式 alpha 被调成半透明且 RGB 三通道相等；
 *     alphaTransRatio>0.15 且 grayRatio>0.8 → 幻影灰度（确定）；alphaTransRatio>0.25 → 疑似 alpha 异常。
 *  3) LSB 相邻相同率（bit0 或 bit1 <0.42）→ 疑似随机化改写。
 *  4) LSB 卡方检验（Westfeld，窗口扫描）：顺序嵌入会拉平相邻亮度对 (2k,2k+1) 频率，
 *     卡方 p 值接近 1 → 最低位疑似被随机化（对图隐套件 LSB 顺序嵌入灵敏）。
 *  5) JPEG DCT 卡方检验（JSteg）：量化 DCT 系数幅值相邻对 (2k,2k+1) 频率趋平 → 疑似 JSteg 嵌入。
 *
 * 弱线索（仅供参考，不改变主级别）：
 *  - highContrast：亮度两极聚集 → 疑似光棱双显混合。
 *  - blocky：8x8 分块边界不连续明显 → 疑似 RAC 分块调制。
 */
object StegDetect {

    /** LSB 卡方：全图像素低于此值 → 样本不足（小图统计波动大，p 虚高，不做判定） */
    private const val MIN_LSB_PIXELS = 8192
    /** LSB 卡方：窗口累计像素低于此值 → 该窗口不参与（1/8 窗口在 64×64 图上只有 ~512 像素） */
    private const val MIN_LSB_WINDOW_PIXELS = 2048
    /** DCT 卡方：总块数低于此值 → 样本不足（实测 512 块以下自然图 p 波动可达 0.3+） */
    private const val MIN_DCT_BLOCKS = 512
    /** DCT 卡方：窗口块数低于此值 → 该窗口不参与（64 块窗口自然图 p 仍可能到 0.4） */
    private const val MIN_DCT_WINDOW_BLOCKS = 64
    /** RAC 分块检测：边界样本数低于此值 → 样本不足不判定（低分辨率图块边界样本少，比值波动大） */
    private const val MIN_BLOCKY_SAMPLES = 200
    /** RAC 强证据：8x8 块边界三通道同步跳变率下限（RAC 调制 RGB 同步；压缩伪影实测 36-43%，RAC 实测 92%） */
    private const val RAC_SYNC_RATIO = 0.75f
    /** RAC 强证据：块边界跳变幅度中位数下限（RAC 调制偏移大，实测中位 12；压缩伪影实测中位 1-2） */
    private const val RAC_AMP_MEDIAN = 8

    data class Result(
        val hasMagic: Boolean,       // LSB 魔数
        val payloadBytes: Int?,      // 载荷长度
        val encrypted: Boolean?,     // 加密标志
        val lsbMatchRatio: Float,    // bit0 相邻相同率（全通道）
        val lsbMatchRatio1: Float,   // bit1 相邻相同率（全通道）
        val chRatioR: Float,         // bit0 R 通道相同率
        val chRatioG: Float,         // bit0 G 通道相同率
        val chRatioB: Float,         // bit0 B 通道相同率
        val alphaTransRatio: Float,  // 半透明像素占比
        val grayRatio: Float,        // RGB 相等像素占比
        val highContrast: Boolean,   // 光棱弱线索
        val blocky: Boolean,         // RAC 分块弱线索（仅无损图，参考）
        val racStrong: Boolean,      // RAC 强证据：8x8 网格三通道同步大幅调制（与格式无关）
        val chiPR: Float,            // LSB 卡方 p：R 通道（0..1，接近 1 越像顺序嵌入）
        val chiPG: Float,            // LSB 卡方 p：G 通道
        val chiPB: Float,            // LSB 卡方 p：B 通道
        val chiPMax: Float,          // 三通道最大 p
        val lsbSampleLow: Boolean    // LSB 卡方样本不足（图像过小，结果不可靠）
    )

    /** JPEG JSteg 检测结果 */
    data class DctChiResult(
        val pMax: Float,      // 多窗口（前 1/4、前 1/2、全量）最大 p，接近 1 越像 JSteg 嵌入
        val pFull: Float,     // 全量窗口 p
        val zeroRatio: Float, // AC 零系数占比（全量，参考线索）
        val sampled: Int,     // 参与统计的 AC 系数总数
        val sampleLow: Boolean // 块数不足，结果不可靠（pMax/pFull 恒为 0，不参与判定）
    )

    /**
     * @param lossy 原图是否为有损压缩格式（JPEG/WEBP）。有损压缩的 8x8 块效应是编码固有伪影，
     *              与 RAC 分块调制无关，故跳过 RAC 分块检测（避免低分辨率/压缩图系统性误报）。
     */
    fun analyze(bitmap: Bitmap, lossy: Boolean = false): Result {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 4 || h < 4) return Result(false, null, null, 0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f, false, false, false, 0f, 0f, 0f, 0f, true)

        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        val magic = readBitInt(px, 0, 16)
        var payloadBytes: Int? = null
        var encrypted: Boolean? = null
        if (magic == 0x5354) { // 'S','T'
            val flags = readBitInt(px, 16, 8)
            val len = readBitInt(px, 24, 32)
            encrypted = (flags and 1) == 1
            payloadBytes = len
        }

        val bit0 = bitMatchRatio(px, w, h, 0)
        val bit1 = bitMatchRatio(px, w, h, 1)
        val (cr, cg, cb) = channelBit0(px, w, h)
        val (alphaRatio, grayRatio) = phantomStats(px)
        val (hc, blk, rac) = weakSignals(px, w, h, lossy)
        val chi = chiSquareChannels(px)
        val pMax = maxOf(chi.pR, chi.pG, chi.pB)

        return Result(
            hasMagic = magic == 0x5354,
            payloadBytes = payloadBytes,
            encrypted = encrypted,
            lsbMatchRatio = bit0,
            lsbMatchRatio1 = bit1,
            chRatioR = cr,
            chRatioG = cg,
            chRatioB = cb,
            alphaTransRatio = alphaRatio,
            grayRatio = grayRatio,
            highContrast = hc,
            blocky = blk,
            racStrong = rac,
            chiPR = chi.pR,
            chiPG = chi.pG,
            chiPB = chi.pB,
            chiPMax = pMax,
            lsbSampleLow = chi.sampleLow
        )
    }

    /* ---------------- LSB 卡方（Westfeld） ---------------- */

    private data class ChiResult(val pR: Float, val pG: Float, val pB: Float, val sampleLow: Boolean)

    /**
     * 按像素顺序累积 R/G/B 三通道亮度直方图，在 1/8/1/4/1/2/100% 窗口分别求卡方 p 值，取各通道最大值。
     * 顺序 LSB 嵌入只改写图前部 → 前部窗口 p 显著升高；自然图整体 p 低。
     *
     * 低分辨率保护：全图像素 < MIN_LSB_PIXELS 或窗口累计像素 < MIN_LSB_WINDOW_PIXELS 时，
     * 样本量不足以支撑卡方检验（bin 计数低 → p 虚高），返回 sampleLow=true 且 p 恒为 0，不做判定。
     */
    private fun chiSquareChannels(px: IntArray): ChiResult {
        val n = px.size
        if (n < MIN_LSB_PIXELS) return ChiResult(0f, 0f, 0f, true)
        val hist = Array(3) { IntArray(256) }
        val best = floatArrayOf(0f, 0f, 0f) // 初始 0：自然图 p 极低时如实上报，避免被 0.5 中性值掩盖
        val marks = intArrayOf(n / 8 - 1, n / 4 - 1, n / 2 - 1, n - 1)
        var mi = 0
        for (i in 0 until n) {
            val p = px[i]
            hist[0][(p shr 16) and 0xFF]++
            hist[1][(p shr 8) and 0xFF]++
            hist[2][p and 0xFF]++
            if (i == marks[mi]) {
                if (i + 1 >= MIN_LSB_WINDOW_PIXELS) { // 窗口样本足够才参与统计
                    for (c in 0..2) {
                        val pv = chiP(hist[c])
                        if (pv > best[c]) best[c] = pv
                    }
                }
                mi++
                if (mi >= marks.size) break
            }
        }
        return ChiResult(best[0], best[1], best[2], false)
    }

    /** 单通道亮度直方图的卡方 p 值：相邻对 (2k,2k+1) 频率趋平 → p 接近 1（LSB 疑似随机化）。 */
    private fun chiP(hist: IntArray): Float {
        var chi2 = 0.0
        var groups = 0
        for (k in 0 until 128) {
            val x = hist[2 * k]
            val y = hist[2 * k + 1]
            val s = (x + y).toDouble()
            if (s > 0) {
                chi2 += (x - y).toDouble() * (x - y) / s
                groups++
            }
        }
        if (groups < 2) return 0.5f
        return chi2PValue(chi2, groups - 1).toFloat()
    }

    /* ---------------- JPEG DCT 卡方（JSteg） ---------------- */

    /**
     * 对量化 DCT 系数（JpegTank.parseCoeffs 输出）做幅值直方图卡方：
     * JSteg 顺序嵌入改写前部系数的 LSB，使 |v| 相邻对 (2k,2k+1) 频率趋平，
     * p 值从自然图的 ~0 显著上升（DCT 直方图仅少量幅值组有样本，p 上限受波动限制，
     * 通常 0.3~0.7；判据是相对自然图的明显上升，而非绝对接近 1）。
     * 分别统计 前 1/8 / 1/4 / 1/2 / 全量 四个窗口，取最大 p。
     */
    fun dctChiSquare(coeffs: List<IntArray>): DctChiResult {
        val total = coeffs.size
        if (total == 0) return DctChiResult(0f, 0f, 0f, 0, true)
        // 低分辨率保护：块数 < MIN_DCT_BLOCKS 时样本不足（实测 512 块以下自然图 p 波动可达 0.3+），
        // pMax/pFull 恒为 0，不做判定；仍上报零系数率作参考。
        if (total < MIN_DCT_BLOCKS) {
            var zero = 0
            var nz = 0
            for (blk in coeffs) {
                for (k in 1 until 64) {
                    if (blk[k] == 0) zero++ else nz++
                }
            }
            val totalAc = zero + nz
            return DctChiResult(
                pMax = 0f,
                pFull = 0f,
                zeroRatio = if (totalAc > 0) zero.toFloat() / totalAc else 0f,
                sampled = totalAc,
                sampleLow = true
            )
        }
        val windows = intArrayOf(
            maxOf(1, total / 8),
            maxOf(1, total / 4),
            maxOf(1, total / 2),
            total
        )
        var best = 0f // 初始 0：自然状 p≈0 时如实上报
        var pFull = 0f
        for (win in windows) {
            if (win < MIN_DCT_WINDOW_BLOCKS) continue // 窗口块数过少不参与（64 块窗口自然图 p 仍可能到 0.4）
            val p = dctChiFor(coeffs, 0, win)
            if (win == total) pFull = p
            if (p > best) best = p
        }
        var zero = 0
        var nz = 0
        for (blk in coeffs) {
            for (k in 1 until 64) {
                if (blk[k] == 0) zero++ else nz++
            }
        }
        val totalAc = zero + nz
        return DctChiResult(
            pMax = best,
            pFull = pFull,
            zeroRatio = if (totalAc > 0) zero.toFloat() / totalAc else 0f,
            sampled = totalAc,
            sampleLow = false
        )
    }

    private fun dctChiFor(coeffs: List<IntArray>, from: Int, to: Int): Float {
        val hist = IntArray(4096) // |v| 幅值直方图（正负合并，v=0 跳过）
        var groups0 = 0
        for (i in from until to) {
            val blk = coeffs[i]
            for (k in 1 until 64) {
                val v = blk[k]
                if (v == 0) continue
                val a = if (v < 0) -v else v
                if (a < 4096) hist[a]++
                else groups0 = 1 // 极端幅值跳过，仅标记有样本
            }
        }
        var chi2 = 0.0
        var groups = 0
        for (k in 1 until 2048) {
            val x = hist[2 * k]
            val y = hist[2 * k + 1]
            val s = (x + y).toDouble()
            if (s > 0) {
                chi2 += (x - y).toDouble() * (x - y) / s
                groups++
            }
        }
        if (groups < 2) return 0.5f
        return chi2PValue(chi2, groups - 1).toFloat()
    }

    /* ---------------- 卡方 CDF（近似） ---------------- */

    /** 卡方分布右尾概率 p = P(X² > chi2)。Wilson–Hilferty 变换 → 标准正态。 */
    private fun chi2PValue(chi2: Double, df: Int): Double {
        if (df <= 0) return 0.5
        val d = df.toDouble()
        val z = ((chi2 / d).pow(1.0 / 3.0) - (1.0 - 2.0 / (9.0 * d))) / sqrt(2.0 / (9.0 * d))
        return 1.0 - normalCdf(z)
    }

    /** 标准正态 CDF（Abramowitz & Stegun 7.1.26，|误差| < 1e-7） */
    private fun normalCdf(z0: Double): Double {
        if (z0 < 0) return 1.0 - normalCdf(-z0)
        val t = 1.0 / (1.0 + 0.33267 * z0)
        return 1.0 - 0.5 * exp(-z0 * z0 / 2.0) * (0.4361836 * t - 0.1201676 * t * t + 0.9372980 * t * t * t)
    }

    /* ---------------- 原有启发式（保留） ---------------- */

    /** 读位流第 from 位起共 n 位的整数（大端） */
    private fun readBitInt(px: IntArray, fromBit: Int, n: Int): Int {
        var v = 0
        repeat(n) { i ->
            val bitIdx = fromBit + i
            val unit = bitIdx / 2
            val pixelIdx = unit / 3
            val chan = CHAN[unit % 3]
            val pos = bitIdx % 2
            val cur = (px[pixelIdx] shr chan) and 0xFF
            val bit = if (pos == 0) cur and 1 else (cur shr 1) and 1
            v = (v shl 1) or bit
        }
        return v
    }

    /** 抽样相邻像素对在指定 bit 位深的相同率（RGB 全通道） */
    private fun bitMatchRatio(px: IntArray, w: Int, h: Int, bit: Int): Float {
        var match = 0
        var total = 0
        var sample = 0
        for (y in 0 until h step 3) {
            val rowBase = y * w
            for (x in 0 until w - 1 step 3) {
                val a = px[rowBase + x]
                val b = px[rowBase + x + 1]
                for (c in 0..2) {
                    val shift = CHAN[c]
                    val la = (a shr (shift + bit)) and 1
                    val lb = (b shr (shift + bit)) and 1
                    total++
                    if (la == lb) match++
                }
                sample++
                if (sample >= 12000) return match.toFloat() / total.toFloat()
            }
        }
        return if (total == 0) 0.5f else match.toFloat() / total.toFloat()
    }

    /** 抽样相邻像素对 bit0 的分通道相同率（R/G/B） */
    private fun channelBit0(px: IntArray, w: Int, h: Int): Triple<Float, Float, Float> {
        val m = IntArray(3)
        val c = IntArray(3)
        var sample = 0
        for (y in 0 until h step 3) {
            val rowBase = y * w
            for (x in 0 until w - 1 step 3) {
                val a = px[rowBase + x]
                val b = px[rowBase + x + 1]
                for (ch in 0..2) {
                    val shift = CHAN[ch]
                    val la = (a shr shift) and 1
                    val lb = (b shr shift) and 1
                    c[ch]++
                    if (la == lb) m[ch]++
                }
                sample++
                if (sample >= 12000) break
            }
            if (sample >= 12000) break
        }
        fun ratio(idx: Int): Float = if (c[idx] > 0) m[idx].toFloat() / c[idx] else 0.5f
        return Triple(ratio(0), ratio(1), ratio(2))
    }

    /** 抽样统计半透明占比与 RGB 相等（灰度）占比 */
    private fun phantomStats(px: IntArray): Pair<Float, Float> {
        var alphaTrans = 0
        var gray = 0
        var total = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val a = (p ushr 24) and 0xff
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            if (a in 1..254) alphaTrans++
            if (r == g && g == b) gray++
            total++
            i += 4
            if (total >= 6000) break
        }
        if (total == 0) return 0f to 0f
        return alphaTrans.toFloat() / total to gray.toFloat() / total
    }

    /**
     * 弱线索：光棱亮度两极聚集 + RAC 分块痕迹。
     *
     * RAC 分块调制是 8x8 网格上的三通道同步大幅偏移，与格式无关（即使存为 JPEG/WEBP，
     * 像素级调制痕迹依然保留：边界跳变幅度大、R/G/B 同向）；而有损压缩伪影幅度小（量化误差）
     * 且主要落在亮度（实测同步率 36-43%、幅度中位 1-2），两者可用三通道同步率 + 跳变幅度中位数区分。
     *
     * 返回 Triple(highContrast, blocky弱线索, racStrong强证据)：
     *  - blocky（弱线索，仅无损图）：8x8 边界差 > 块内 1.35 倍 —— PNG 图不该有块效应，有即异常；
     *  - racStrong（强证据，与格式无关）：边界样本足 + 三通道同步率 > 75% + 跳变幅度中位 ≥ 8 + 边界差比值 > 1.35。
     */
    private fun weakSignals(px: IntArray, w: Int, h: Int, lossy: Boolean): Triple<Boolean, Boolean, Boolean> {
        var dark = 0
        var bright = 0
        var total2 = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val L = 0.299 * (p shr 16 and 0xff) + 0.587 * (p shr 8 and 0xff) + 0.114 * (p and 0xff)
            if (L < 64f) dark++
            else if (L > 191f) bright++
            total2++
            i += 3
            if (total2 >= 4000) break
        }
        val darkRatio = if (total2 > 0) dark.toFloat() / total2 else 0f
        val brightRatio = if (total2 > 0) bright.toFloat() / total2 else 0f
        val highContrast = darkRatio > 0.28f && brightRatio > 0.28f

        val bs = 8
        var eSum = 0.0
        var eCnt = 0
        var iSum = 0.0
        var iCnt = 0
        var syncMatch = 0
        var syncTotal = 0
        val amp = IntArray(12000) // 块边界跳变幅度样本（取三通道最大差），排序取中位
        var ampN = 0
        var row = 0
        while (row < h && row + 1 < h && eCnt < 12000) {
            val base = row * w
            var col = 0
            while (col < w - 1) {
                val a = px[base + col]
                val b = px[base + col + 1]
                if ((col % bs) == bs - 1) {
                    val dr = (b shr 16 and 0xff) - (a shr 16 and 0xff)
                    val dg = (b shr 8 and 0xff) - (a shr 8 and 0xff)
                    val db = (b and 0xff) - (a and 0xff)
                    eSum += Math.abs(dr) + Math.abs(dg) + Math.abs(db)
                    eCnt++
                    // 三通道同步：R/G/B 同向跳变
                    if (dr != 0 && ((dr > 0) == (dg > 0)) && ((dr > 0) == (db > 0))) syncMatch++
                    syncTotal++
                    if (ampN < 12000) {
                        val m = maxOf(Math.abs(dr), Math.abs(dg), Math.abs(db))
                        amp[ampN++] = m
                    }
                } else {
                    val d = Math.abs(lum(a) - lum(b))
                    iSum += d
                    iCnt++
                }
                col++
            }
            row += 3
        }
        val eAvg = if (eCnt > 0) eSum / eCnt else 0.0
        val iAvg = if (iCnt > 0) iSum / iCnt else 0.0
        val ratio = if (iAvg > 0.0) eAvg / iAvg else 0.0
        val syncRatio = if (syncTotal > 0) syncMatch.toFloat() / syncTotal else 0f
        // 跳变幅度中位数
        var ampMed = 0
        if (ampN > 0) {
            java.util.Arrays.sort(amp, 0, ampN)
            ampMed = amp[ampN / 2]
        }

        // 弱线索：仅无损图（有损图 8x8 边界差偏大是压缩常态，比值阈值无区分力）
        val blocky = !lossy && eCnt >= MIN_BLOCKY_SAMPLES && iAvg > 0.0 && ratio > 1.35
        // 强证据：与格式无关 —— 三通道同步大幅调制才是 RAC 特征
        val racStrong = eCnt >= MIN_BLOCKY_SAMPLES && iAvg > 0.0 &&
            ratio > 1.35 && syncRatio > RAC_SYNC_RATIO && ampMed >= RAC_AMP_MEDIAN

        return Triple(highContrast, blocky, racStrong)
    }

    private fun lum(p: Int): Double =
        0.299 * (p shr 16 and 0xff) + 0.587 * (p shr 8 and 0xff) + 0.114 * (p and 0xff)

    private val CHAN = intArrayOf(16, 8, 0)
}
