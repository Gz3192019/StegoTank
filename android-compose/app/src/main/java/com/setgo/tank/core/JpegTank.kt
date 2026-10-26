package com.setgo.tank.core

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * JPEG（JSteg）隐写引擎：自写基线 JPEG 编解码（SOF0 / 4:2:0 / 标准量化与 Huffman 表），
 * 在量化后 DCT 系数上做 JSteg 式 LSB 嵌入（跳过 DC 与 0、±1 系数）。
 *
 * 数据头格式与 LSB 完全一致（"ST" | type | flags | len | IV | payload），密码 AES/CBC 可选。
 * 算法致敬 jsteg / libjpeg（标准 JPEG 工具链），实现为纯 Kotlin。
 */
object JpegTank {

    const val TYPE_TEXT = 0
    const val TYPE_FILE = 1
    const val QUALITY = 80

    class JpegException(message: String) : Exception(message)

    data class Result(val type: Int, val data: ByteArray)

    /* ---------------- 容量 ---------------- */

    /** 可用容量（字节）：按 80 质量 DCT+量化后，可嵌入的 AC 系数位（跳过 DC/0/±1） */
    fun capacityOf(bmp: Bitmap): Int {
        val (w, h) = fitDims(bmp.width, bmp.height)
        val ycc = rgbToYcc(bmp, w, h)
        val cw = (w + 1) / 2; val ch = (h + 1) / 2
        var bits = 0
        for (by in 0 until h step 16) {
            for (bx in 0 until w step 16) {
                repeat(2) { iy ->
                    repeat(2) { ix ->
                        bits += usableBits(dctQuant(ycc.y, w, h, bx + ix * 8, by + iy * 8, 0))
                    }
                }
                bits += usableBits(dctQuant(ycc.cb, cw, ch, bx / 2, by / 2, 1))
                bits += usableBits(dctQuant(ycc.cr, cw, ch, bx / 2, by / 2, 1))
            }
        }
        return bits / 8
    }

    /** 单个 8x8 块中可嵌入的位数（zigzag 序 AC，|q|>1） */
    private fun usableBits(coeff: IntArray): Int {
        var n = 0
        for (i in 1 until 64) {
            val q = coeff[ZIGZAG[i]]
            if (q > 1 || q < -1) n++
        }
        return n
    }

    /* ---------------- 嵌入 ---------------- */

    /**
     * 嵌入：把 data 按 type 藏进 carrier，返回 JSteg JPEG 字节。
     * password 空 → 不加密。容量不足抛 JpegException。
     */
    fun encode(carrier: Bitmap, type: Int, data: ByteArray, password: String?): ByteArray {
        val pwd = password?.takeIf { it.isNotBlank() }
        val encrypted = pwd != null
        val iv = if (encrypted) ByteArray(16).also { SecureRandom().nextBytes(it) } else null
        val payload = if (encrypted) aesEncrypt(data, pwd!!, iv!!) else data

        val head = ByteArrayOutputStream()
        head.write('S'.code); head.write('T'.code)
        head.write(type)
        head.write(if (encrypted) 1 else 0)
        writeIntBE(head, payload.size)
        if (iv != null) head.write(iv)
        val all = ByteArrayOutputStream().apply { write(head.toByteArray()); write(payload) }.toByteArray()

        val (w, h) = fitDims(carrier.width, carrier.height)
        val ycc = rgbToYcc(carrier, w, h)
        val cw = (w + 1) / 2; val ch = (h + 1) / 2

        val blocks = mutableListOf<Pair<IntArray, Int>>() // (coeff, component 0/1/2)
        val qBlk = mutableListOf<Int>()                    // 可嵌入系数所在块索引
        val qPos = mutableListOf<Int>()                    // 该系数在块内的 zigzag 索引(1..63)

        for (by in 0 until h step 16) {
            for (bx in 0 until w step 16) {
                repeat(2) { iy ->
                    repeat(2) { ix ->
                        val c = dctQuant(ycc.y, w, h, bx + ix * 8, by + iy * 8, 0)
                        blocks.add(c to 0)
                        for (k in 1 until 64) {
                            val v = c[ZIGZAG[k]]
                            if (v > 1 || v < -1) { qBlk.add(blocks.size - 1); qPos.add(k) }
                        }
                    }
                }
                for (comp in 1..2) {
                    val arr = if (comp == 1) ycc.cb else ycc.cr
                    val c = dctQuant(arr, cw, ch, bx / 2, by / 2, comp)
                    blocks.add(c to comp)
                    for (k in 1 until 64) {
                        val v = c[ZIGZAG[k]]
                        if (v > 1 || v < -1) { qBlk.add(blocks.size - 1); qPos.add(k) }
                    }
                }
            }
        }

        val capBits = qBlk.size
        val needBits = all.size * 8
        if (needBits > capBits) {
            throw JpegException("content too large: need ${needBits / 8} B, capacity ${capBits / 8} B")
        }

        // 按位嵌入（bit 流顺序 = 块顺序 + zigzag 顺序）
        var bi = 0
        for (byte in all) {
            for (b in 7 downTo 0) {
                val bit = (byte.toInt() shr b) and 1
                val coeff = blocks[qBlk[bi]].first
                val pos = qPos[bi]
                val v = coeff[pos]
                coeff[pos] = if ((v and 1) == bit) v else if (v > 0) v - 1 + (bit shl 1) else v + 1 - (bit shl 1)
                bi++
            }
        }

        return encodeJpeg(w, h, blocks)
    }

    /** 熵编码写标准基线 JPEG 文件（DQT 写 80 质量表，与量化一致） */
    private fun encodeJpeg(w: Int, h: Int, blocks: List<Pair<IntArray, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)                     // SOI
        out.write(0xFF); out.write(0xE0); writeMarkerLen(out, 16)  // APP0 JFIF
        out.write('J'.code); out.write('F'.code); out.write('I'.code); out.write('F'.code); out.write(0)
        out.write(1); out.write(1)
        out.write(0); out.write(0); out.write(1); out.write(0); out.write(1); out.write(0); out.write(0)
        out.write(0xFF); out.write(0xDB); writeMarkerLen(out, 2 + 1 + 64 + 1 + 64)  // DQT
        out.write(0); for (i in 0 until 64) out.write(QUANT_LUMA_80[i])
        out.write(1); for (i in 0 until 64) out.write(QUANT_CHROMA_80[i])
        out.write(0xFF); out.write(0xC0); writeMarkerLen(out, 17)   // SOF0
        out.write(8)
        out.write(h shr 8); out.write(h and 0xFF)
        out.write(w shr 8); out.write(w and 0xFF)
        out.write(3)
        out.write(1); out.write(0x22); out.write(0)
        out.write(2); out.write(0x11); out.write(1)
        out.write(3); out.write(0x11); out.write(1)
        writeDht(out, 0x00, DC_BITS0, DC_VAL0)
        writeDht(out, 0x10, AC_BITS0, AC_VAL0)
        writeDht(out, 0x01, DC_BITS1, DC_VAL1)
        writeDht(out, 0x11, AC_BITS1, AC_VAL1)
        out.write(0xFF); out.write(0xDA); writeMarkerLen(out, 12)   // SOS
        out.write(3)
        out.write(1); out.write(0x00)
        out.write(2); out.write(0x11)
        out.write(3); out.write(0x11)
        out.write(0); out.write(63); out.write(0)

        val bw = BitWriter(out)
        var prevY = 0; var prevCb = 0; var prevCr = 0
        for ((coeff, comp) in blocks) {
            val prev = when (comp) { 0 -> prevY; 1 -> prevCb; else -> prevCr }
            val diff = coeff[0] - prev
            val dcCat = sizeCategory(diff)
            when (comp) {
                0 -> { bw.huff(DC0, dcCat); prevY = coeff[0] }
                1 -> { bw.huff(DC1, dcCat); prevCb = coeff[0] }
                else -> { bw.huff(DC1, dcCat); prevCr = coeff[0] }
            }
            bw.bitsSigned(diff, dcCat)
            val acTable = if (comp == 0) AC0 else AC1
            var run = 0
            var i = 1
            while (i < 64) {
                val v = coeff[ZIGZAG[i]]
                if (v == 0) {
                    run++
                    if (run == 16) { bw.huff(acTable, 0xF0); run = 0 }
                    i++
                    continue
                }
                val cat = sizeCategory(v)
                bw.huff(acTable, (run shl 4) or cat)
                bw.bitsSigned(v, cat)
                run = 0
                i++
            }
            if (run > 0) bw.huff(acTable, 0x00)
        }
        bw.finish()
        out.write(0xFF); out.write(0xD9)                     // EOI
        return out.toByteArray()
    }

    /* ---------------- 提取 ---------------- */

    /** 提取：从 JSteg JPEG 字节读载荷；password 一致时解密。 */
    fun decode(bytes: ByteArray, password: String?): Result {
        val parsed = parseJpeg(bytes)
        val sb = StringBuilder()
        for (blk in parsed.coeffs) {
            for (k in 1 until 64) {
                val v = blk[ZIGZAG[k]]
                if (v > 1 || v < -1) sb.append(if ((v and 1) == 1) '1' else '0')
            }
        }
        val bitStr = sb.toString()
        if (bitStr.length < 8 * 12) throw JpegException("not a JSteg JPEG or data corrupted")

        var bitIdx = 0
        fun nextByte(): Int {
            if (bitIdx + 8 > bitStr.length) throw JpegException("corrupted header")
            var v = 0
            repeat(8) { v = (v shl 1) or (bitStr[bitIdx++].toString().toInt()) }
            return v
        }
        fun nextBytes(n: Int): ByteArray = ByteArray(n) { nextByte().toByte() }

        val magic = nextByte(); val magic2 = nextByte()
        if (magic != 'S'.code || magic2 != 'T'.code) throw JpegException("not a JSteg JPEG")
        val type = nextByte()
        val flags = nextByte()
        val len = (nextByte() shl 24) or (nextByte() shl 16) or (nextByte() shl 8) or nextByte()
        val encrypted = (flags and 1) == 1
        val iv = if (encrypted) nextBytes(16) else null
        if (len < 0 || bitIdx + len * 8 > bitStr.length) throw JpegException("corrupted payload length")
        val payload = nextBytes(len)
        val data = if (encrypted) {
            val pwd = password?.takeIf { it.isNotBlank() } ?: throw JpegException("password required")
            aesDecrypt(payload, pwd, iv!!)
        } else payload
        return Result(type, data)
    }

    private data class Sof(val w: Int, val h: Int, val comps: List<IntArray>)

    private data class Parsed(val coeffs: List<IntArray>)

    /** 提取量化 DCT 系数（供隐写检测复用）；maxMcu>0 时只解码前 maxMcu 个 MCU（大图防卡顿）。 */
    internal fun parseCoeffs(bytes: ByteArray, maxMcu: Int = 0): List<IntArray> =
        parseJpeg(bytes, maxMcu.takeIf { it > 0 }).coeffs

    /** 解析 JPEG：SOF0/DQT/DHT/SOS → 熵解码量化系数（maxMcu>0 截断） */
    private fun parseJpeg(bytes: ByteArray, maxMcu: Int? = null): Parsed {
        var pos = 2
        var sof: Sof? = null
        val huffTables = arrayOfNulls<HuffTable>(4)
        while (pos + 4 <= bytes.size) {
            if (bytes[pos].toInt() and 0xFF != 0xFF) { pos++; continue }
            val marker = bytes[pos + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7)) { pos += 2; continue }
            val len = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            if (marker == 0xDA) {
                sof = sof ?: throw JpegException("SOF0 missing")
                return Parsed(huffmanDecodeScan(bytes, pos + 2 + len, sof, huffTables, maxMcu))
            }
            when (marker) {
                0xC4 -> { // DHT
                    var p = pos + 4
                    val end = pos + 2 + len
                    while (p < end) {
                        val info = bytes[p].toInt() and 0xFF
                        val tc = info shr 4; val th = info and 0x0F
                        val bits = IntArray(16)
                        var n = 0
                        for (i in 0 until 16) { bits[i] = bytes[p + 1 + i].toInt() and 0xFF; n += bits[i] }
                        val vals = IntArray(n)
                        for (i in 0 until n) vals[i] = bytes[p + 17 + i].toInt() and 0xFF
                        huffTables[(tc shl 1) or th] = buildHuff(bits, vals)
                        p += 17 + n
                    }
                }
                0xC0, 0xC1, 0xC2 -> {
                    if (marker == 0xC0) {
                        val h = ((bytes[pos + 5].toInt() and 0xFF) shl 8) or (bytes[pos + 6].toInt() and 0xFF)
                        val w = ((bytes[pos + 7].toInt() and 0xFF) shl 8) or (bytes[pos + 8].toInt() and 0xFF)
                        val nComp = bytes[pos + 9].toInt() and 0xFF
                        val comps = mutableListOf<IntArray>()
                        for (i in 0 until nComp) {
                            val id = bytes[pos + 10 + i * 3].toInt() and 0xFF
                            val samp = bytes[pos + 11 + i * 3].toInt() and 0xFF
                            val tq = bytes[pos + 12 + i * 3].toInt() and 0xFF
                            comps.add(intArrayOf(id, (samp shr 4) and 0xF, samp and 0xF, tq))
                        }
                        sof = Sof(w, h, comps)
                    } else throw JpegException("only baseline JPEG supported")
                }
            }
            pos += 2 + len
        }
        throw JpegException("scan data not found")
    }

    /** 熵解码扫描数据 → 各块量化系数（Y 2x2 + Cb + Cr 的 MCU 顺序，与嵌入一致；maxMcu 截断） */
    private fun huffmanDecodeScan(
        bytes: ByteArray, start: Int, sof: Sof, huff: Array<HuffTable?>, maxMcu: Int?
    ): List<IntArray> {
        val mcuW = (sof.w + 15) / 16
        val mcuH = (sof.h + 15) / 16
        val total = maxMcu?.let { min(it, mcuW * mcuH) } ?: (mcuW * mcuH)
        val out = mutableListOf<IntArray>()

        val br = BitReader(bytes, start)
        var prevY = 0; var prevCb = 0; var prevCr = 0
        repeat(total) {
            repeat(4) {
                val dc = decodeBlockDC(br, huff[0]!!, prevY)
                prevY = dc
                out.add(decodeBlockAC(br, huff[2]!!, dc))
            }
            val cb = decodeBlockDC(br, huff[1]!!, prevCb)
            prevCb = cb
            out.add(decodeBlockAC(br, huff[3]!!, cb))
            val cr = decodeBlockDC(br, huff[1]!!, prevCr)
            prevCr = cr
            out.add(decodeBlockAC(br, huff[3]!!, cr))
        }
        return out
    }

    private fun decodeBlockDC(br: BitReader, table: HuffTable, prev: Int): Int {
        val cat = br.huff(table)
        if (cat == 0) return prev
        val bits = br.bits(cat)
        val diff = if (bits < (1 shl (cat - 1))) bits - (1 shl cat) + 1 else bits
        return prev + diff
    }

    private fun decodeBlockAC(br: BitReader, table: HuffTable, dc: Int): IntArray {
        val coeff = IntArray(64)
        coeff[0] = dc
        var k = 1
        while (k < 64) {
            val sym = br.huff(table)
            val run = sym shr 4
            val cat = sym and 0x0F
            if (cat == 0) { if (run == 15) k += 16 else break }
            else {
                val bits = br.bits(cat)
                val v = if (bits < (1 shl (cat - 1))) bits - (1 shl cat) + 1 else bits
                k += run
                if (k < 64) coeff[ZIGZAG[k]] = v
                k++
            }
        }
        return coeff
    }

    /* ---------------- YCbCr / DCT / 量化 ---------------- */

    private class Ycc(val y: IntArray, val cb: IntArray, val cr: IntArray)

    private fun rgbToYcc(bmp: Bitmap, w: Int, h: Int): Ycc {
        val sw = bmp.width; val sh = bmp.height
        val px = IntArray(sw * sh)
        bmp.getPixels(px, 0, sw, 0, 0, sw, sh)
        val y = IntArray(w * h)
        val cb = IntArray(w * h)
        val cr = IntArray(w * h)
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                val sx = min(xx, sw - 1); val sy = min(yy, sh - 1)
                val p = px[sy * sw + sx]
                val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
                y[yy * w + xx] = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                cb[yy * w + xx] = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                cr[yy * w + xx] = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
            }
        }
        // 4:2:0 降采样（2x2 平均）
        val cw = (w + 1) / 2; val ch = (h + 1) / 2
        val cbSub = IntArray(cw * ch); val crSub = IntArray(cw * ch)
        for (yy in 0 until ch) {
            for (xx in 0 until cw) {
                var s1 = 0; var s2 = 0; var n = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val yy2 = min(yy * 2 + dy, h - 1); val xx2 = min(xx * 2 + dx, w - 1)
                    s1 += cb[yy2 * w + xx2]; s2 += cr[yy2 * w + xx2]; n++
                }
                cbSub[yy * cw + xx] = s1 / n; crSub[yy * cw + xx] = s2 / n
            }
        }
        return Ycc(y, cbSub, crSub)
    }

    /** 一维 DCT 核表（含 0.5·cu 系数，两次应用即得标准 2D DCT，替代逐系数重算 cos） */
    private val DCT1D: Array<FloatArray> = Array(8) { u ->
        val cu = if (u == 0) 0.70710678f else 1f
        FloatArray(8) { x -> (0.5f * cu) * cos(((2 * x + 1) * u * Math.PI) / 16.0).toFloat() }
    }

    /** 8x8 fDCT + 量化（JPEG 标准，quality 80 缩放表）。arr 为组件平面，stride 为其宽度。 */
    private fun dctQuant(arr: IntArray, stride: Int, arrH: Int, bx: Int, by: Int, comp: Int): IntArray {
        val f = FloatArray(64)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val yy = by + y; val xx = bx + x
                val v = if (yy < arrH && xx < stride) arr[yy * stride + xx] else 128
                f[y * 8 + x] = (v - 128).toFloat()
            }
        }
        // 两次一维 DCT（行-列分离）：tmp[v*8+x] = Σ_y f[y*8+x] * D[v][y]，再 Σ_x tmp * D[u][x]
        val tmp = FloatArray(64)
        for (x in 0 until 8) {
            for (v in 0 until 8) {
                val drow = DCT1D[v]
                var s = 0f
                for (y in 0 until 8) s += f[y * 8 + x] * drow[y]
                tmp[v * 8 + x] = s
            }
        }
        val out = IntArray(64)
        val qtab = if (comp == 0) QUANT_LUMA_80 else QUANT_CHROMA_80
        for (u in 0 until 8) {
            val drow = DCT1D[u]
            for (v in 0 until 8) {
                var sum = 0f
                for (x in 0 until 8) sum += tmp[v * 8 + x] * drow[x]
                out[v * 8 + u] = (sum / qtab[v * 8 + u]).roundToInt()
            }
        }
        return out
    }

    /* ---------------- Huffman / 位流 ---------------- */

    private class HuffTable(
        val codes: IntArray,
        val lens: IntArray,
        val lookup: Array<HashMap<Int, Int>?> = arrayOfNulls(17)
    )

    private fun buildHuff(bits: IntArray, vals: IntArray): HuffTable {
        val codes = IntArray(256); val lens = IntArray(256)
        val lookup = arrayOfNulls<HashMap<Int, Int>>(17)
        var code = 0; var k = 0
        for (len in 1..16) {
            for (i in 0 until bits[len - 1]) {
                codes[vals[k]] = code
                lens[vals[k]] = len
                (lookup[len] ?: HashMap<Int, Int>().also { lookup[len] = it })[code] = vals[k]
                k++; code++
            }
            code = code shl 1
        }
        return HuffTable(codes, lens, lookup)
    }

    private class BitWriter(private val out: ByteArrayOutputStream) {
        private var acc = 0
        private var n = 0
        fun huff(t: HuffTable, sym: Int) {
            bits(t.codes[sym], t.lens[sym])
        }
        fun bitsSigned(v: Int, count: Int) {
            val x = if (v >= 0) v else v - 1
            bits(x, count)
        }
        fun bits(v: Int, count: Int) {
            var c = count
            var x = v
            while (c > 0) {
                val bit = (x ushr (c - 1)) and 1
                acc = (acc shl 1) or bit
                n++
                if (n == 8) { out.write(acc); if (acc == 0xFF) out.write(0); acc = 0; n = 0 }
                c--
            }
        }
        fun finish() {
            if (n > 0) {
                acc = acc shl (8 - n)
                out.write(acc)
                if (acc == 0xFF) out.write(0)
                acc = 0; n = 0
            }
        }
    }

    private class BitReader(val bytes: ByteArray, start: Int) {
        private var pos = start
        private var acc = 0
        private var n = 0
        fun huff(t: HuffTable): Int {
            var code = 0
            var len = 0
            while (true) {
                code = (code shl 1) or nextBit()
                len++
                t.lookup[len]?.get(code)?.let { return it }
            }
        }
        fun bits(count: Int): Int {
            var v = 0
            repeat(count) { v = (v shl 1) or nextBit() }
            return v
        }
        private fun nextBit(): Int {
            if (n == 0) {
                var b = bytes[pos].toInt() and 0xFF
                pos++
                if (b == 0xFF && pos < bytes.size) {
                    // 0xFF 数据字节本身是 8 位有效数据；其后为填充 00（或 marker），跳过
                    pos++
                }
                acc = b; n = 8
            }
            val bit = (acc shr 7) and 1
            acc = (acc shl 1) and 0xFF
            n--
            return bit
        }
    }

    private fun sizeCategory(v: Int): Int {
        if (v == 0) return 0
        val x = if (v < 0) -v.toDouble() else v.toDouble()
        return floor(log2(x)).toInt() + 1
    }

    /* ---------------- 文件辅助 ---------------- */

    private fun fitDims(w0: Int, h0: Int): Pair<Int, Int> {
        val longest = max(w0, h0)
        val capped = min(longest, 2048) // 上限 2048，防止超大图全量 DCT 卡顿
        val scale = capped.toFloat() / ((longest + 15) / 16 * 16).toFloat()
        val w = max(16, ((w0 * scale).roundToInt() + 7) / 8 * 8)
        val h = max(16, ((h0 * scale).roundToInt() + 7) / 8 * 8)
        return w to h
    }

    private fun writeMarkerLen(out: ByteArrayOutputStream, len: Int) {
        out.write(len shr 8); out.write(len and 0xFF)
    }

    private fun writeDht(out: ByteArrayOutputStream, tcTh: Int, bits: IntArray, vals: IntArray) {
        out.write(0xFF); out.write(0xC4)
        writeMarkerLen(out, 2 + 1 + 16 + vals.size)
        out.write(tcTh)
        for (b in bits) out.write(b)
        for (v in vals) out.write(v)
    }

    private fun writeIntBE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    /* ---------------- 加密（与 LSB 相同：SHA-256 派生密钥 + AES/CBC） ---------------- */

    private fun aesKey(pwd: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(pwd.toByteArray(Charsets.UTF_8)).copyOf(16)

    private fun aesEncrypt(data: ByteArray, pwd: String, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey(pwd), "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun aesDecrypt(data: ByteArray, pwd: String, iv: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey(pwd), "AES"), IvParameterSpec(iv))
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw JpegException("wrong password")
        }
    }

    /* ---------------- 标准 JPEG 表 ---------------- */

    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
    )

    private val QUANT_LUMA = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99
    )

    private val QUANT_CHROMA = intArrayOf(
        17, 18, 24, 47, 99, 99, 99, 99,
        18, 21, 26, 66, 99, 99, 99, 99,
        24, 26, 56, 99, 99, 99, 99, 99,
        47, 66, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99
    )

    private val QUANT_LUMA_80: IntArray
    private val QUANT_CHROMA_80: IntArray

    init {
        val scale = (200 - 2 * QUALITY) / 100f  // 80 -> 0.4
        QUANT_LUMA_80 = IntArray(64) { max(1, (QUANT_LUMA[it] * scale).roundToInt()) }
        QUANT_CHROMA_80 = IntArray(64) { max(1, (QUANT_CHROMA[it] * scale).roundToInt()) }
    }

    private val DC_BITS0 = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    private val DC_VAL0 = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
    private val DC_BITS1 = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
    private val DC_VAL1 = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

    private val AC_BITS0 = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 125)
    private val AC_BITS1 = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 119)

    private val AC_VAL0 = intArrayOf(
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
        0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
        0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
        0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
        0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
        0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
        0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
        0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
        0xf9, 0xfa
    )

    private val AC_VAL1 = intArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
        0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
        0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
        0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
        0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
        0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
        0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
        0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
        0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
        0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
        0xf9, 0xfa
    )

    private val DC0: HuffTable by lazy { buildHuff(DC_BITS0, DC_VAL0) }
    private val AC0: HuffTable by lazy { buildHuff(AC_BITS0, AC_VAL0) }
    private val DC1: HuffTable by lazy { buildHuff(DC_BITS1, DC_VAL1) }
    private val AC1: HuffTable by lazy { buildHuff(AC_BITS1, AC_VAL1) }
}
