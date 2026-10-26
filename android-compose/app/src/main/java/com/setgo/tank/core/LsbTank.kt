package com.setgo.tank.core

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LSB 隐写引擎（借鉴 RobinDavid/LSB-Steganography 与 aagarwal1012/Image-Steganography-Library-Android）。
 *
 * - 载体：PNG 无损位图（ARGB_8888），只写 RGB 三通道，每通道低 2 位（共 6 bit/像素）
 * - 数据头：2B 魔数 "ST" | 1B 类型(0=文本,1=文件) | 1B 标志(bit0=加密) | 4B 载荷长度(大端) | 16B IV(仅加密时)
 * - 加密：密码 → SHA-256 派生 16B 密钥，AES/CBC/PKCS5Padding；IV 随机生成并随头存储
 * - 提取：按头读取，魔数不符或密码错误时抛出友好异常
 */
object LsbTank {

    /** 数据类型：文本（UTF-8）/ 任意文件（二进制） */
    const val TYPE_TEXT = 0
    const val TYPE_FILE = 1

    class LsbException(message: String) : Exception(message)

    /** 可用容量（字节）：6 bit/像素 */
    fun capacityOf(pixelCount: Int): Int = pixelCount * 3 * 2 / 8

    /**
     * 嵌入：把 data（文本或文件字节）按 type 藏进 carrier，返回新位图。
     * password 为空/空白 → 不加密。
     */
    fun encode(carrier: Bitmap, type: Int, data: ByteArray, password: String?): Bitmap {
        val w = carrier.width
        val h = carrier.height
        val px = IntArray(w * h)
        carrier.getPixels(px, 0, w, 0, 0, w, h)

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

        val all = ByteArrayOutputStream().apply {
            write(head.toByteArray()); write(payload)
        }.toByteArray()

        val needBits = all.size * 8
        val capBits = px.size * 3 * 2
        if (needBits > capBits) {
            throw LsbException("carrier too small: need ${needBits / 8} B, capacity ${capBits / 8} B")
        }

        // 位流顺序：通道 R(16) → G(8) → B(0) 循环，每通道先低位(bit0)后高位(bit1)
        var bitIdx = 0
        for (byte in all) {
            for (b in 7 downTo 0) {
                val bit = (byte.toInt() shr b) and 1
                val unit = bitIdx / 2
                val pixelIdx = unit / 3
                val chan = CHAN_SHIFT[unit % 3]
                val pos = bitIdx % 2
                var v = px[pixelIdx]
                val cur = (v shr chan) and 0xFF
                val nv = if (pos == 0) (cur and 0xFE) or bit else (cur and 0xFD) or (bit shl 1)
                px[pixelIdx] = (v and (0xFF shl chan).inv()) or (nv shl chan)
                bitIdx++
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 提取：按头读取载荷；password 与嵌入时一致（加密时），不一致抛"密码错误"。 */
    fun decode(stego: Bitmap, password: String?): Result {
        val w = stego.width
        val h = stego.height
        val px = IntArray(w * h)
        stego.getPixels(px, 0, w, 0, 0, w, h)

        val bits = px.size * 3 * 2
        if (bits < 8 * 10) throw LsbException("image too small")

        // 头：4B magic/type/flags + 4B len (+16B IV)
        var bitIdx = 0
        fun nextByte(): Int {
            if (bitIdx + 8 > bits) throw LsbException("corrupted header")
            var v = 0
            repeat(8) {
                val unit = bitIdx / 2
                val pixelIdx = unit / 3
                val chan = CHAN_SHIFT[unit % 3]
                val pos = bitIdx % 2
                val cur = (px[pixelIdx] shr chan) and 0xFF
                val bit = if (pos == 0) cur and 1 else (cur shr 1) and 1
                v = (v shl 1) or bit
                bitIdx++
            }
            return v
        }
        fun nextBytes(n: Int): ByteArray = ByteArray(n) { nextByte().toByte() }

        val magic = nextByte()
        val magic2 = nextByte()
        if (magic != 'S'.code || magic2 != 'T'.code) throw LsbException("not an LSB image")
        val type = nextByte()
        val flags = nextByte()
        val len = (nextByte() shl 24) or (nextByte() shl 16) or (nextByte() shl 8) or nextByte()

        val encrypted = (flags and 1) == 1
        val iv = if (encrypted) nextBytes(16) else null
        if (len < 0 || bitIdx + len * 8 > bits) throw LsbException("corrupted payload length")

        val payload = nextBytes(len)
        val data = if (encrypted) {
            val pwd = password?.takeIf { it.isNotBlank() }
                ?: throw LsbException("password required")
            aesDecrypt(payload, pwd, iv!!)
        } else {
            payload
        }
        return Result(type, data)
    }

    data class Result(val type: Int, val data: ByteArray)

    private val CHAN_SHIFT = intArrayOf(16, 8, 0)

    private fun writeIntBE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

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
            throw LsbException("wrong password")
        }
    }
}
