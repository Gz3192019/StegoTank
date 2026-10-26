package com.setgo.tank

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 开发者头像缓存：
 *  - 首次打包内置头像（R.drawable.tuyin_avatar）
 *  - 首次打开应用时尝试一次网络获取，成功后写入 files 缓存覆盖内置
 *  - 之后不再主动请求；无网时显示内置或已缓存头像
 */
object AvatarCache {

    private const val PREFS = "tuyin_avatar_prefs"
    private const val KEY_ATTEMPTED = "avatar_attempted"
    private const val AVATAR_URL = "https://avatars.githubusercontent.com/u/275639613?v=4"

    private fun file(context: Context): File = File(context.filesDir, "tuyin_avatar.png")

    /** 是否已有缓存头像文件 */
    fun hasCached(context: Context): Boolean = file(context).exists()

    /** 返回可显示的本地头像：优先缓存文件，否则 null（调用方回退内置资源） */
    fun cachedFile(context: Context): File? = file(context).takeIf { it.exists() }

    /** 首次打开时执行的一次性网络获取（仅一次；失败静默，保留内置）。onDone(成功?) 在主线程回调 */
    fun ensureOnce(context: Context, scope: CoroutineScope, onDone: (Boolean) -> Unit = {}) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ATTEMPTED, false)) return
        scope.launch(Dispatchers.IO) {
            var ok = false
            try {
                val conn = URL(AVATAR_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    // 简单防呆：拒绝异常大小的响应
                    if (bytes.isNotEmpty() && bytes.size < 8 * 1024 * 1024) {
                        file(context).writeBytes(bytes)
                        ok = true
                    }
                }
            } catch (_: Exception) {
                // 无网 / 超时 / 失败：保留内置头像
            } finally {
                prefs.edit().putBoolean(KEY_ATTEMPTED, true).apply()
                withContext(Dispatchers.Main) { onDone(ok) }
            }
        }
    }
}
