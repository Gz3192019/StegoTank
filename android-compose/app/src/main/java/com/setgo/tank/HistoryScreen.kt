package com.setgo.tank

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.setgo.tank.core.UnifiedHistory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 统一历史记录页：聚合所有功能模块（LSB / RAC / 幻影 / 光棱 / 混淆）的操作记录，点击跳转对应模块并加载图片。 */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenModule: (String, String) -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var entries by remember { mutableStateOf(UnifiedHistory.list(context)) }
    var status by remember { mutableStateOf("") }

    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize().background(SuBg)) {
        SuiteFadingTopBar(
            title = t("历史记录", "歷史記錄", "History"),
            scrollOffset = scrollState.value.toFloat(),
            onBack = onBack,
            trailing = {
                if (entries.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 14.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                UnifiedHistory.clear(context)
                                entries = emptyList()
                                status = t("已清空历史", "已清空歷史", "History cleared")
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(t("清空", "清空", "Clear"), style = TextStyle(color = Color(0xFFE5484D), fontSize = 13.sp))
                    }
                }
            }
        )
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(
                    t("暂无操作记录", "暫無操作記錄", "No records yet"),
                    style = TextStyle(color = SuSub, fontSize = 14.sp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 72.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries) { e ->
                    val thumb = e.thumb
                    SuiteCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (thumb != null) onOpenModule(e.module, thumb) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SuSegBg),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumb != null) {
                                    AsyncImage(
                                        model = File(thumb),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                                    )
                                } else {
                                    BasicText(moduleTag(e.module), style = TextStyle(color = SuSub, fontSize = 14.sp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BasicText(
                                        moduleLabel(e.module),
                                        style = TextStyle(color = SuTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(actionColor(e.action).copy(alpha = 0.14f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        BasicText(
                                            actionLabel(e.action),
                                            style = TextStyle(color = actionColor(e.action), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                BasicText(
                                    fmt.format(Date(e.ts)),
                                    style = TextStyle(color = SuSub, fontSize = 12.sp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            BasicText("›", style = TextStyle(color = SuSub, fontSize = 20.sp))
                        }
                    }
                }
            }
        }
        if (status.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                BasicText(status, style = TextStyle(color = SuSub, fontSize = 13.sp))
            }
        }
    }
}

/** 模块 id → 三语名称 */
@Composable
private fun moduleLabel(module: String): String = when (module) {
    "lsb" -> t("LSB 隐写", "LSB 隱寫", "LSB Stegano")
    "rac" -> t("RAC 图隐", "RAC 圖隱", "RAC Stegano")
    "phantom" -> t("幻影坦克", "幻影坦克", "Phantom Tank")
    "prism" -> t("光棱坦克", "光稜坦克", "Prism Tank")
    "arnold" -> t("图片混淆", "圖片混淆", "Image Scramble")
    else -> module
}

/** 模块 id → 无缩略图时占位字 */
private fun moduleTag(module: String): String = when (module) {
    "lsb" -> "位"
    "rac" -> "隐"
    "phantom" -> "幻"
    "prism" -> "棱"
    "arnold" -> "混"
    else -> "图"
}

/** 操作类型 id → 三语名称 */
@Composable
private fun actionLabel(action: String): String = when (action) {
    "encode" -> t("嵌入", "嵌入", "Encode")
    "save" -> t("保存", "保存", "Save")
    "scramble" -> t("混淆", "混淆", "Scramble")
    "restore" -> t("还原", "還原", "Restore")
    else -> action
}

/** 操作类型 id → 标签颜色 */
private fun actionColor(action: String): Color = when (action) {
    "encode" -> Color(0xFF3482FF)
    "save" -> Color(0xFF00B96B)
    "scramble" -> Color(0xFFFF7A00)
    "restore" -> Color(0xFF9C5BFF)
    else -> Color(0xFF8A8A8E)
}
