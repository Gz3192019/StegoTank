package com.setgo.tank.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** LSB 嵌入历史：记录每次嵌入操作（时间/类型/内容摘要/大小/是否加密），便于提取时找回。 */
object LsbHistory {

    data class Entry(
        val ts: Long,          // 时间戳
        val type: Int,         // LsbTank.TYPE_TEXT / TYPE_FILE
        val summary: String,   // 文本=完整内容；文件=文件名
        val size: Int,         // 载荷字节数
        val encrypted: Boolean // 是否加密
    )

    private const val PREFS = "tuyin_history"
    private const val KEY = "entries"

    fun add(context: Context, entry: Entry) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString(KEY, "[]") ?: "[]")
        val obj = JSONObject().apply {
            put("ts", entry.ts)
            put("type", entry.type)
            put("summary", entry.summary)
            put("size", entry.size)
            put("encrypted", entry.encrypted)
        }
        arr.put(obj)
        // 最多保留 50 条，新的在前
        val newest = JSONArray()
        for (i in (arr.length() - 1) downTo 0) newest.put(arr.get(i))
        while (newest.length() > 50) newest.remove(newest.length() - 1)
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
                type = o.optInt("type", LsbTank.TYPE_TEXT),
                summary = o.optString("summary", ""),
                size = o.optInt("size", 0),
                encrypted = o.optBoolean("encrypted", false)
            ))
        }
        return out
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
