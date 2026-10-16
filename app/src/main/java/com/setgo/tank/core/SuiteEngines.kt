package com.setgo.tank.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 图隐套件三大图片玩法引擎：
 * - 幻影坦克 Mirage Tank：PNG Alpha 双背景显像（白底=表图，黑底=里图）
 * - 光棱坦克 Prism Tank：亮度/曝光技巧（正常亮度=表图，拉曝光=里图）
 * - 图片混淆 Arnold：双模猫映射像素置乱（尺寸不变，同参可逆）
 */
object SuiteEngines {

    // ---------- 幻影坦克 ----------

    /**
     * 合成幻影坦克图。
     * 白背景显示表图，黑背景显示里图（透明通道双显）。
     * gray=true 灰度模式（最精确）：a = (I-T)/255 + 1，输出灰度；
     * gray=false 彩色模式：逐通道 a = (Ic-Tc)/255 + 1。
     * frontBright 额外提亮表图、backDark 额外压暗里图可增强效果；scale 为输出缩放（1=原大）。
     */
    /**
     * 合成幻影坦克图（全彩参数版，参考 TankFactory/Mirage_Colored）。
     * 白背景显示表图，黑背景显示里图（透明通道双显）。
     *
     * @param backMix   里图混合权重 0~1：越大里图越保真（渐近灰度解），越小表图越保真（渐近彩色解）
     * @param frontGain 表图亮度（色阶缩放）0.2~3，1=原样
     * @param frontDesat 表图去色程度 0~1：0 全彩，1 全灰
     * @param backGain  里图亮度（色阶缩放）0.2~3，1=原样
     * @param backDesat 里图去色程度 0~1
     * @param scale     输出缩放（1=原大）
     */
    fun mirageTank(
        front: Bitmap, back: Bitmap,
        backMix: Float = 0.5f,
        frontGain: Float = 1f,
        frontDesat: Float = 0f,
        backGain: Float = 1f,
        backDesat: Float = 0f,
        scale: Float = 1f
    ): Bitmap {
        val w0 = max(front.width, back.width)
        val h0 = max(front.height, back.height)
        val sc = scale.coerceIn(0.25f, 1f)
        val w = (w0 * sc).toInt().coerceAtLeast(1)
        val h = (h0 * sc).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fScaled = scaleToContain(front, w, h)
        val bScaled = scaleToContain(back, w, h)
        val fx = (w - fScaled.width) / 2; val fy = (h - fScaled.height) / 2
        val bx = (w - bScaled.width) / 2; val by = (h - bScaled.height) / 2
        val fg = frontGain.coerceIn(0.2f, 3f)
        val bg = backGain.coerceIn(0.2f, 3f)
        val fd = frontDesat.coerceIn(0f, 1f)
        val bd = backDesat.coerceIn(0f, 1f)
        val mix = backMix.coerceIn(0f, 1f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val f = if (x in fx until fx + fScaled.width && y in fy until fy + fScaled.height) fScaled.getPixel(x - fx, y - fy) else 0xFFFFFFFF.toInt()
                val b = if (x in bx until bx + bScaled.width && y in by until by + bScaled.height) bScaled.getPixel(x - bx, y - by) else 0xFF000000.toInt()
                // 表图：增益（色阶缩放）+ 去色
                val fR0 = (Color.red(f) * fg).toInt().coerceAtMost(255)
                val fG0 = (Color.green(f) * fg).toInt().coerceAtMost(255)
                val fB0 = (Color.blue(f) * fg).toInt().coerceAtMost(255)
                val fL = lum(fR0, fG0, fB0)
                val tArr = intArrayOf(
                    (fR0 * (1 - fd) + fL * fd).toInt(),
                    (fG0 * (1 - fd) + fL * fd).toInt(),
                    (fB0 * (1 - fd) + fL * fd).toInt()
                )
                // 里图：增益 + 去色
                val bR0 = (Color.red(b) * bg).toInt().coerceAtMost(255)
                val bG0 = (Color.green(b) * bg).toInt().coerceAtMost(255)
                val bB0 = (Color.blue(b) * bg).toInt().coerceAtMost(255)
                val bL = lum(bR0, bG0, bB0)
                val iArr = intArrayOf(
                    (bR0 * (1 - bd) + bL * bd).toInt(),
                    (bG0 * (1 - bd) + bL * bd).toInt(),
                    (bB0 * (1 - bd) + bL * bd).toInt()
                )
                // 灰度解（理论精确：白底=表亮度、黑底=里亮度）
                val aG = ((bL - fL) / 255.0 + 1.0).coerceIn(0.05, 1.0)
                // 彩色逐通道解
                var aMin = 1.0
                val rgbC = FloatArray(3)
                for (c in 0 until 3) {
                    var ac = (iArr[c] - tArr[c]) / 255.0 + 1.0
                    ac = ac.coerceIn(0.05, 1.0)
                    if (ac < aMin) aMin = ac
                    rgbC[c] = (iArr[c] / ac).toFloat().coerceAtMost(255f)
                }
                // 混合权重：alpha 在灰度解与彩色解之间插值；RGB 同步插值
                val aEff = (aG * mix + aMin * (1 - mix)).coerceIn(0.05, 1.0)
                val r = (rgbC[0] * (1 - mix) + (iArr[0] / aEff) * mix).toInt().coerceAtMost(255)
                val g = (rgbC[1] * (1 - mix) + (iArr[1] / aEff) * mix).toInt().coerceAtMost(255)
                val bl = (rgbC[2] * (1 - mix) + (iArr[2] / aEff) * mix).toInt().coerceAtMost(255)
                out.setPixel(x, y, Color.argb((aEff * 255).toInt(), r, g, bl))
            }
        }
        if (fScaled !== front) fScaled.recycle()
        if (bScaled !== back) bScaled.recycle()
        return out
    }

    /** 等比缩放到目标画布内（contain），不足画布时透明填充由调用方控制。 */
    /** 预览降采样：最长边缩到 maxSide，供滑块实时预览使用（全分辨率留给最终结果/保存） */
    fun previewOf(bmp: Bitmap, maxSide: Int = 480): Bitmap {
        val m = max(bmp.width, bmp.height)
        if (m <= maxSide) return bmp
        val sc = maxSide.toFloat() / m
        val w = (bmp.width * sc).toInt().coerceAtLeast(1)
        val h = (bmp.height * sc).toInt().coerceAtLeast(1)
        return scaleToContain(bmp, w, h)
    }

    private fun scaleToContain(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val s = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val nw = (src.width * s).toInt().coerceAtLeast(1)
        val nh = (src.height * s).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    /** 把带透明通道的图叠加到指定背景色上，用于预览幻影坦克效果。 */
    fun compositeOn(bmp: Bitmap, background: Int): Bitmap {
        val w = bmp.width; val h = bmp.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bgR = Color.red(background); val bgG = Color.green(background); val bgB = Color.blue(background)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = bmp.getPixel(x, y)
                val a = Color.alpha(p) / 255f
                val r = (Color.red(p) * a + bgR * (1 - a)).toInt().coerceIn(0, 255)
                val g = (Color.green(p) * a + bgG * (1 - a)).toInt().coerceIn(0, 255)
                val b = (Color.blue(p) * a + bgB * (1 - a)).toInt().coerceIn(0, 255)
                out.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return out
    }

    // ---------- 光棱坦克（原版算法，参考 Uyanide/Mirage_Decode） ----------

    /** 里图像素摆放方式（生成时按此模式把里图像素穿插进表图） */
    enum class PrismMethod(val label: String, val hint: String) {
        Chess("棋盘格", "斜线棋盘，隔一像素放里图"),
        Gap2("隔 2", "斜线隔 2 像素放里图"),
        Gap3("隔 3", "斜线隔 3 像素放里图"),
        Gap5("隔 5", "斜线隔 5 像素放里图"),
        Col1("奇偶列", "按列交替放里图"),
        Row1("奇偶行", "按行交替放里图")
    }

    /** 显影时表图区域像素的处理方式 */
    enum class CoverProcess(val label: String, val hint: String) {
        LuAvg("左上平均", "用左上邻居均值填充"),
        LCopy("复制左侧", "复制左边像素"),
        UCopy("复制上方", "复制上方像素"),
        Trans("透明", "置为透明（保留原图）"),
        Black("黑色", "置为黑色"),
        White("白色", "置为白色")
    }

    /**
     * 合成光棱坦克图（原版算法）。
     * 里图像素按 method 穿插到表图中：里图压缩到低亮度（×innerThreshold/255），
     * 表图压缩后加偏移（×u + coverThreshold）；反向模式两者互换偏移。
     * coverGray=true 时表图区域转灰度（原版"表图是否取灰度"）。
     */
    fun prismTank(
        front: Bitmap, back: Bitmap,
        innerThreshold: Int = 40,
        coverThreshold: Int = 60,
        method: PrismMethod = PrismMethod.Chess,
        coverGray: Boolean = true,
        reverse: Boolean = false
    ): Bitmap {
        val w = max(front.width, back.width)
        val h = max(front.height, back.height)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fScaled = scaleToContain(front, w, h)
        val bScaled = scaleToContain(back, w, h)
        val fx = (w - fScaled.width) / 2; val fy = (h - fScaled.height) / 2
        val bx = (w - bScaled.width) / 2; val by = (h - bScaled.height) / 2
        val c = (innerThreshold / 255f).coerceIn(0f, 1f)
        val u = (1f - coverThreshold / 255f).coerceIn(0f, 1f)
        val off = (255 - innerThreshold).coerceIn(0, 255)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val f = if (x in fx until fx + fScaled.width && y in fy until fy + fScaled.height) fScaled.getPixel(x - fx, y - fy) else 0xFFFFFFFF.toInt()
                val bk = if (x in bx until bx + bScaled.width && y in by until by + bScaled.height) bScaled.getPixel(x - bx, y - by) else 0xFF000000.toInt()
                val isInner = when (method) {
                    PrismMethod.Chess -> (x + y) % 2 == 0
                    PrismMethod.Gap2 -> (x + y) % 3 == 0
                    PrismMethod.Gap3 -> (x + y) % 4 == 0
                    PrismMethod.Gap5 -> (x + y) % 6 == 0
                    PrismMethod.Col1 -> x % 2 == 0
                    PrismMethod.Row1 -> y % 2 == 0
                }
                val r: Int; val g: Int; val b: Int
                if (isInner) {
                    // 里图像素：正向压缩到低亮度；反向压缩后加偏移（抬到高位）
                    val rr = Color.red(bk); val gg = Color.green(bk); val bb = Color.blue(bk)
                    if (!reverse) {
                        r = (rr * c).toInt(); g = (gg * c).toInt(); b = (bb * c).toInt()
                    } else {
                        r = (rr * c).toInt() + off; g = (gg * c).toInt() + off; b = (bb * c).toInt() + off
                    }
                } else {
                    // 表图像素：正向压缩 + 偏移（保持较高亮度）；反向压缩到低亮度
                    val gray = lum(Color.red(f), Color.green(f), Color.blue(f))
                    val rr = if (coverGray) gray else Color.red(f)
                    val gg = if (coverGray) gray else Color.green(f)
                    val bb = if (coverGray) gray else Color.blue(f)
                    if (!reverse) {
                        r = (rr * u).toInt() + coverThreshold
                        g = (gg * u).toInt() + coverThreshold
                        b = (bb * u).toInt() + coverThreshold
                    } else {
                        r = (rr * u).toInt(); g = (gg * u).toInt(); b = (bb * u).toInt()
                    }
                }
                out.setPixel(x, y, Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)))
            }
        }
        if (fScaled !== front) fScaled.recycle()
        if (bScaled !== back) bScaled.recycle()
        return out
    }

    /**
     * 光棱坦克显影（原版算法）：灰度低于（或高于）阈值视为里图区域并线性放大，
     * 其余为表图区域，按 cover 方式处理。reverse=true 时改为高于阈值放大。
     * 内部使用 IntArray 批量读写像素（替代逐像素 getPixel/setPixel），大图性能提升数倍。
     */
    fun prismReveal(
        bmp: Bitmap,
        threshold: Int = 120,
        reverse: Boolean = false,
        cover: CoverProcess = CoverProcess.LuAvg
    ): Bitmap {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val th = threshold.coerceIn(1, 255)
        val e = if (reverse) 255f / (255 - th) else 255f / th
        for (y in 0 until h) {
            var idx = y * w
            for (x in 0 until w) {
                val p = px[idx]
                val gray = lum(Color.red(p), Color.green(p), Color.blue(p))
                val r: Int; val g: Int; val b: Int
                if (!reverse) {
                    if (gray <= th) {
                        r = (Color.red(p) * e).toInt().coerceAtMost(255)
                        g = (Color.green(p) * e).toInt().coerceAtMost(255)
                        b = (Color.blue(p) * e).toInt().coerceAtMost(255)
                    } else {
                        val c = processCoverArr(px, w, x, y, cover)
                        r = Color.red(c); g = Color.green(c); b = Color.blue(c)
                    }
                } else {
                    if (gray >= th) {
                        r = ((Color.red(p) - th) * e).toInt().coerceIn(0, 255)
                        g = ((Color.green(p) - th) * e).toInt().coerceIn(0, 255)
                        b = ((Color.blue(p) - th) * e).toInt().coerceIn(0, 255)
                    } else {
                        val c = processCoverArr(px, w, x, y, cover)
                        r = Color.red(c); g = Color.green(c); b = Color.blue(c)
                    }
                }
                out[idx] = Color.rgb(r, g, b)
                idx++
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** IntArray 版表图区域处理（读数组，避免 native 像素访问） */
    private fun processCoverArr(px: IntArray, w: Int, x: Int, y: Int, cover: CoverProcess): Int {
        return when (cover) {
            CoverProcess.LuAvg -> {
                if (x > 0 && y > 0) {
                    val a = px[(y - 1) * w + (x - 1)]
                    val l = px[y * w + (x - 1)]
                    val u = px[(y - 1) * w + x]
                    Color.rgb(
                        (Color.red(a) + Color.red(l) + Color.red(u)) / 3,
                        (Color.green(a) + Color.green(l) + Color.green(u)) / 3,
                        (Color.blue(a) + Color.blue(l) + Color.blue(u)) / 3
                    )
                } else 0xFF000000.toInt()
            }
            CoverProcess.LCopy -> if (x > 0) px[y * w + (x - 1)] else 0xFF000000.toInt()
            CoverProcess.UCopy -> if (y > 0) px[(y - 1) * w + x] else 0xFF000000.toInt()
            CoverProcess.Trans -> 0x00000000
            CoverProcess.Black -> 0xFF000000.toInt()
            CoverProcess.White -> 0xFFFFFFFF.toInt()
        }
    }

    // ---------- 图片混淆（Arnold 猫映射，双模，尺寸不变） ----------

    /**
     * Arnold 置乱 / 还原。正向：x'=(x+y)%w, y'=(x+2y)%h；逆向：x=(2x'-y')%w, y=(-x'+y')%h。
     * reverse=false 置乱、true 还原；iterations 为迭代轮数（还原必须与置乱相同）。
     */
    fun arnold(bmp: Bitmap, iterations: Int, reverse: Boolean): Bitmap {
        val w = bmp.width; val h = bmp.height
        val n = iterations.coerceIn(1, 20)
        var cur = bmp
        val tmp = IntArray(w * h)
        for (it in 0 until n) {
            val src = cur
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val nx: Int; val ny: Int
                    if (reverse) {
                        nx = mod(2 * x - y, w)
                        ny = mod(-x + y, h)
                    } else {
                        nx = mod(x + y, w)
                        ny = mod(x + 2 * y, h)
                    }
                    tmp[ny * w + nx] = src.getPixel(x, y)
                }
            }
            if (cur !== bmp) cur.recycle()
            cur = Bitmap.createBitmap(tmp, w, h, Bitmap.Config.ARGB_8888)
        }
        return cur
    }

    private fun mod(v: Int, m: Int): Int {
        val r = v % m
        return if (r < 0) r + m else r
    }

    private fun lum(r: Int, g: Int, b: Int): Int =
        ((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)

    private fun lum(c: Int): Int = lum(Color.red(c), Color.green(c), Color.blue(c))

    // ---------- 图片混淆（多算法，参考 2195517546/ObfuscationUtils） ----------

    enum class ScrambleAlgo(val label: String, val needsKey: Boolean, val hint: String) {
        Tomato("全局置乱", true, "数字密钥，全局像素置乱"),
        Block("分块", true, "8×8 分块，块内按密钥打乱"),
        RowPixel("行像素", true, "逐行像素按密钥打乱"),
        PerPixel("像素级", true, "逐像素通道交换 + 异或"),
        PicEncryptRow("行平移", true, "浮点密钥，按行循环平移"),
        PicEncryptRowColumn("行列平移", true, "浮点密钥，行列双重平移"),
        Sort("排序", false, "按亮度排序，无需密钥"),
        Random("种子随机", true, "种子随机置乱"),
        Gilbert("Gilbert曲线", false, "Gilbert 曲线重排，固定偏移，无需密钥")
    }

    /**
     * 多算法图片混淆 / 还原。所有算法可逆：混淆与还原必须使用相同算法与密钥。
     * 密钥要求：Tomato 数字；PicEncryptRow/PicEncryptRowColumn 浮点；其余任意字符串；Sort 忽略密钥。
     */
    fun obfuscate(bmp: Bitmap, algo: ScrambleAlgo, key: String, reverse: Boolean): Bitmap {
        val w = bmp.width; val h = bmp.height
        val n = w * h
        val px = IntArray(n)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        when (algo) {
            ScrambleAlgo.Tomato -> {
                val seed = key.trim().toLongOrNull() ?: 114514L
                val perm = permutation(n, seed)
                if (reverse) applyInverse(px, perm) else applyPerm(px, perm)
            }
            ScrambleAlgo.Random -> {
                val seed = key.hashCode().toLong()
                val perm = permutation(n, seed)
                if (reverse) applyInverse(px, perm) else applyPerm(px, perm)
            }
            ScrambleAlgo.Block -> {
                val seed = key.hashCode().toLong()
                val bs = 8
                val out2 = IntArray(n)
                for (by in 0 until h step bs) {
                    for (bx in 0 until w step bs) {
                        val bw = minOf(bs, w - bx); val bh = minOf(bs, h - by)
                        val cells = ArrayList<Int>(bw * bh)
                        for (yy in by until by + bh) for (xx in bx until bx + bw) cells.add(yy * w + xx)
                        val perm = permutation(cells.size, seed + (by / bs) * 131L + (bx / bs) * 17L + 7L)
                        if (!reverse) {
                            for (i in cells.indices) out2[cells[i]] = px[cells[perm[i]]]
                        } else {
                            for (i in cells.indices) out2[cells[perm[i]]] = px[cells[i]]
                        }
                    }
                }
                System.arraycopy(out2, 0, px, 0, n)
            }
            ScrambleAlgo.RowPixel -> {
                val seed = key.hashCode().toLong()
                for (y in 0 until h) {
                    val perm = permutation(w, seed + y * 7919L)
                    val row = IntArray(w)
                    val src = y * w
                    if (!reverse) {
                        for (x in 0 until w) row[x] = px[src + perm[x]]
                    } else {
                        for (x in 0 until w) row[perm[x]] = px[src + x]
                    }
                    System.arraycopy(row, 0, px, src, w)
                }
            }
            ScrambleAlgo.PerPixel -> {
                val rnd = java.util.Random(key.hashCode().toLong())
                for (i in 0 until n) {
                    val p = px[i]
                    var r = (p ushr 16) and 0xFF; var g = (p ushr 8) and 0xFF; var b = p and 0xFF
                    val mode = rnd.nextInt(3)
                    val xv = rnd.nextInt(256)
                    if (!reverse) {
                        when (mode) { 0 -> { val t = r; r = g; g = t }; 1 -> { val t = g; g = b; b = t }; 2 -> { val t = r; r = b; b = t } }
                        r = r xor xv; g = g xor xv; b = b xor xv
                    } else {
                        r = r xor xv; g = g xor xv; b = b xor xv
                        when (mode) { 0 -> { val t = r; r = g; g = t }; 1 -> { val t = g; g = b; b = t }; 2 -> { val t = r; r = b; b = t } }
                    }
                    px[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                }
            }
            ScrambleAlgo.PicEncryptRow -> {
                val f = key.trim().toDoubleOrNull() ?: 0.114514
                val seed = kotlin.math.abs((f * 1000000).toLong()).coerceAtLeast(1L)
                for (y in 0 until h) {
                    val shift = ((y + 1) * seed % w).toInt()
                    val src = y * w
                    val row = IntArray(w)
                    if (!reverse) {
                        for (x in 0 until w) row[(x + shift) % w] = px[src + x]
                    } else {
                        for (x in 0 until w) row[((x - shift) % w + w) % w] = px[src + x]
                    }
                    System.arraycopy(row, 0, px, src, w)
                }
            }
            ScrambleAlgo.PicEncryptRowColumn -> {
                val f = key.trim().toDoubleOrNull() ?: 0.114514
                val seed = kotlin.math.abs((f * 1000000).toLong()).coerceAtLeast(1L)
                val tmp = IntArray(n)
                for (y in 0 until h) {
                    val shift = ((y + 1) * seed % w).toInt()
                    val src = y * w
                    if (!reverse) { for (x in 0 until w) tmp[src + (x + shift) % w] = px[src + x] }
                    else { for (x in 0 until w) tmp[src + ((x - shift) % w + w) % w] = px[src + x] }
                }
                for (x in 0 until w) {
                    val shift = ((x + 1) * seed % h).toInt()
                    if (!reverse) { for (y in 0 until h) px[((y + shift) % h) * w + x] = tmp[y * w + x] }
                    else { for (y in 0 until h) px[(((y - shift) % h + h) % h) * w + x] = tmp[y * w + x] }
                }
            }
            ScrambleAlgo.Sort -> {
                val idx = (0 until n).sortedBy { lum(px[it]) }
                val tmp = px.copyOf()
                if (!reverse) { for (i in 0 until n) px[i] = tmp[idx[i]] }
                else { for (i in 0 until n) px[idx[i]] = tmp[i] }
            }
            ScrambleAlgo.Gilbert -> {
                // Gilbert 空间填充曲线：把二维像素映射为一维序列，再沿序列按固定偏移循环平移。
                // 偏移量固定（无需密钥）：floor((√5-1)/2 * totalPixels) % totalPixels，正反互为逆。
                val curve = gilbertCurve(w, h)
                val off = gilbertShift(n)
                val tmp = IntArray(n)
                if (!reverse) {
                    for (i in 0 until n) tmp[curve[(i + off) % n]] = px[curve[i]]
                } else {
                    for (i in 0 until n) tmp[curve[i]] = px[curve[(i + off) % n]]
                }
                System.arraycopy(tmp, 0, px, 0, n)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun permutation(n: Int, seed: Long): IntArray {
        val perm = IntArray(n) { it }
        val rnd = java.util.Random(seed)
        for (i in n - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = perm[i]; perm[i] = perm[j]; perm[j] = t
        }
        return perm
    }

    private fun applyPerm(px: IntArray, perm: IntArray) {
        val tmp = px.copyOf()
        for (i in px.indices) px[i] = tmp[perm[i]]
    }

    private fun applyInverse(px: IntArray, perm: IntArray) {
        val tmp = px.copyOf()
        for (i in px.indices) px[perm[i]] = tmp[i]
    }

    // ---------- Gilbert 空间填充曲线（参考 moxuangenet 图片混淆 / jakubcerveny-gilbert 算法） ----------

    /** Gilbert 固定偏移量：floor((√5-1)/2 * totalPixels) % totalPixels，无密钥、固定、自动可逆。 */
    private fun gilbertShift(totalPixels: Int): Int {
        val n = totalPixels
        if (n <= 0) return 0
        return (floor(((sqrt(5.0) - 1.0) / 2.0) * n) % n).toInt()
    }

    /**
     * 生成覆盖 w×h 图像的 Gilbert 曲线访问顺序（恰好 w*h 个点）。
     * 算法：jakubcerveny/gilbert 官方 generate2d（BSD-2-Clause）——广义 Hilbert 空间填充曲线，
     * 对任意矩形直接递归分治，无 2 的幂限制、无越界点。
     */
    private fun gilbertCurve(w: Int, h: Int): IntArray {
        val curve = IntArray(w * h)
        var count = 0
        fun rec(x: Int, y: Int, ax: Int, ay: Int, bx: Int, by: Int) {
            val cw = abs(ax + ay)
            val ch = abs(bx + by)
            val dax = if (ax > 0) 1 else if (ax < 0) -1 else 0
            val day = if (ay > 0) 1 else if (ay < 0) -1 else 0
            val dbx = if (bx > 0) 1 else if (bx < 0) -1 else 0
            val dby = if (by > 0) 1 else if (by < 0) -1 else 0
            if (ch == 1) {
                var cx = x; var cy = y
                for (i in 0 until cw) { curve[count++] = cy * w + cx; cx += dax; cy += day }
                return
            }
            if (cw == 1) {
                var cx = x; var cy = y
                for (i in 0 until ch) { curve[count++] = cy * w + cx; cx += dbx; cy += dby }
                return
            }
            var ax2 = Math.floorDiv(ax, 2); var ay2 = Math.floorDiv(ay, 2)
            var bx2 = Math.floorDiv(bx, 2); var by2 = Math.floorDiv(by, 2)
            val w2 = abs(ax2 + ay2)
            val h2 = abs(bx2 + by2)
            if (2 * cw > 3 * ch) {
                if (w2 % 2 == 1 && cw > 2) { ax2 += dax; ay2 += day }
                rec(x, y, ax2, ay2, bx, by)
                rec(x + ax2, y + ay2, ax - ax2, ay - ay2, bx, by)
            } else {
                if (h2 % 2 == 1 && ch > 2) { bx2 += dbx; by2 += dby }
                rec(x, y, bx2, by2, ax2, ay2)
                rec(x + bx2, y + by2, ax, ay, bx - bx2, by - by2)
                rec(x + (ax - dax) + (bx2 - dbx), y + (ay - day) + (by2 - dby), -bx2, -by2, -(ax - ax2), -(ay - ay2))
            }
        }
        if (w >= h) rec(0, 0, w, 0, 0, h) else rec(0, 0, 0, h, w, 0)
        return curve
    }

    private fun floor(v: Double): Double = kotlin.math.floor(v)
}
