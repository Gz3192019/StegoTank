package com.setgo.tank.navigation

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 图隐套件页面路由。
 * 必须 @Serializable（返回栈跨配置/进程恢复）且实现 Parcelable。
 */
@Serializable
sealed interface Route : NavKey, Parcelable {

    /** 主页：功能区 / 关于 双 tab 容器（栈底） */
    @Parcelize
    @Serializable
    data object Main : Route

    /** 功能二级页（RAC / 幻影 / 光棱 / 混淆 / LSB 等）；loadPath 为历史记录跳转携带的图片路径 */
    @Parcelize
    @Serializable
    data class Feature(val id: String, val loadPath: String? = null) : Route

    /** 设置页 */
    @Parcelize
    @Serializable
    data object Settings : Route

    /** 嵌入历史页 */
    @Parcelize
    @Serializable
    data object History : Route
}
