package com.setgo.tank.core

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 统一历史记录：聚合所有功能模块（LSB 隐写 / RAC 图隐 / 幻影坦克 / 光棱坦克 / 图片混淆）的操作记录。
 * 每条记录 = 时间 + 模块 + 操作类型 + 缩略图；最多 20 条，新的在前；SharedPreferences 存 JSON，缩略图存 filesDir。
 */
object UnifiedHistory {

    data class Entry(
        val ts: Long,          // 时间戳
        val module: String,    // 模块 id：lsb / rac / phantom / prism / arnold
        val action: String,    // 操作类型 id：encode / save / scramble / restore
        val thumb: String?     // 缩略图本地文件绝对路径（无图时为 null）
    )

    private const val PREFS = "tuyin_uni_history"
    private const val KEY = "entries"
    private const val MAX = 20
    private const val THUMB_DIR = "uni_history"

    /** 记录一条操作（bitmap 非空时生成缩略图并落盘） */
    fun add(context: Context, module: String, action: String, bitmap: Bitmap?) {
        val thumb = bitmap?.let { saveThumb(context, it) }
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString(KEY, "[]") ?: "[]")
        val obj = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("module", module)
            put("action", action)
            put("thumb", thumb ?: "")
        }
        arr.put(obj)
        // 最多保留 20 条，新的在前
        val newest = JSONArray()
        for (i in (arr.length() - 1) downTo 0) newest.put(arr.get(i))
        while (newest.length() > MAX) newest.remove(newest.length() - 1)
        sp.edit().putString(KEY, newest.toString()).apply()
    }

    fun list(context: Context): List<Entry> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString(KEY, "[]") ?: "[]")
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Entry(
                ts = o.optLong("ts", 0L),
                module = o.optString("module", ""),
                action = o.optString("action", ""),
                thumb = o.optString("thumb", "").ifEmpty { null }
            ))
        }
        return out
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        // 清理缩略图文件
        try {
            val dir = File(context.filesDir, THUMB_DIR)
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) { }
    }

    /** 生成缩略图：最长边 240px，JPEG 80 压缩，存 filesDir/uni_history/thumb_<ts>.jpg */
    private fun saveThumb(context: Context, bmp: Bitmap): String? {
        return try {
            val dir = File(context.filesDir, THUMB_DIR)
            if (!dir.exists()) dir.mkdirs()
            val scale = 240f / maxOf(bmp.width, bmp.height)
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(bmp, w, h, true)
            val f = File(dir, "thumb_${System.currentTimeMillis()}.jpg")
            FileOutputStream(f).use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            if (small !== bmp) small.recycle()
            f.absolutePath
        } catch (_: Throwable) { null }
    }
}
