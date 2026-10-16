package com.setgo.tank

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 设置页（v0.1.32 宽松版）：主题颜色（莫奈取色 + 色块，联动全 App 卡片）/ 预返回 / 语言 / 历史记录 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenHistory: () -> Unit = {}) {
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().background(SuBg)) {
        SuiteFadingTopBar(
            title = t("设置", "設定", "Settings"),
            scrollOffset = scrollState.value.toFloat(),
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ===== 主题颜色（联动所有卡片）=====
            SuiteCard {
                SuiteTileRow(
                    t("主题颜色", "主題顏色", "Accent Color"),
                    t("莫奈取色跟随壁纸，色块联动全 App 卡片与背景", "莫奈取色跟隨桌布，色塊聯動全 App 卡片與背景", "Monet follows wallpaper; swatches link all cards and background")
                ) { }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MonetSwatch(selected = suiteMonet) { suiteMonet = true }
                    AccentSwatch(Color(0xFF3482FF), suiteMonet, suiteAccent) { suiteMonet = false; suiteAccent = Color(0xFF3482FF) }
                    AccentSwatch(Color(0xFF00B96B), suiteMonet, suiteAccent) { suiteMonet = false; suiteAccent = Color(0xFF00B96B) }
                    AccentSwatch(Color(0xFF9C5BFF), suiteMonet, suiteAccent) { suiteMonet = false; suiteAccent = Color(0xFF9C5BFF) }
                    AccentSwatch(Color(0xFFFF7A00), suiteMonet, suiteAccent) { suiteMonet = false; suiteAccent = Color(0xFFFF7A00) }
                    AccentSwatch(Color(0xFFE5484D), suiteMonet, suiteAccent) { suiteMonet = false; suiteAccent = Color(0xFFE5484D) }
                    AccentSwatch(Color(0xFFF5F5F7), suiteMonet, suiteAccent) { suiteMonet = false; suiteAccent = Color(0xFFF5F5F7) }
                }
                if (suiteMonet) {
                    Spacer(Modifier.height(10.dp))
                    val ctx = LocalContext.current
                    val monetHex = remember { systemAccentSeed(ctx) and 0xFFFFFF }
                    BasicText(
                        t("莫奈取色：#%06X".format(monetHex), "莫奈取色：#%06X".format(monetHex), "Monet: #%06X".format(monetHex)),
                        style = TextStyle(color = SuSub, fontSize = 12.sp)
                    )
                }
            }

            // ===== 预返回 =====
            SuiteCard {
                SuiteTileRow(
                    t("预返回", "預返回", "Predictive back"),
                    t("开启后，二级页返回回上级并带滑动过渡动画（预返回）", "開啟後，二級頁返回回上級並帶滑動過渡動畫（預返回）", "Back to previous page with slide transition animation (predictive back)"),
                    trailing = { SuiteSwitch(checked = suiteBackGesture, onCheckedChange = { suiteBackGesture = it }) }
                )
            }

            // ===== 语言（点击展开选项）=====
            SuiteCard {
                var langExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { langExpanded = !langExpanded }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(t("语言", "語言", "Language"), style = TextStyle(color = SuTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                        BasicText(suiteLang.label, style = TextStyle(color = SuSub, fontSize = 12.sp))
                    }
                    BasicText(
                        text = if (langExpanded) "⌄" else "›",
                        style = TextStyle(color = SuSub, fontSize = 22.sp),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                AnimatedVisibility(
                    visible = langExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Spacer(Modifier.height(4.dp))
                        SuiteLang.entries.forEach { lang ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { suiteLang = lang; langExpanded = false }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText(
                                    text = lang.label,
                                    style = TextStyle(
                                        color = if (suiteLang == lang) SuAccentFg else SuTitle,
                                        fontSize = 15.sp,
                                        fontWeight = if (suiteLang == lang) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                if (suiteLang == lang) {
                                    Box(
                                        modifier = Modifier.size(18.dp).clip(CircleShape).background(suiteAccent),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        BasicText("✓", style = TextStyle(color = suiteAccent.onAccentFg(), fontSize = 11.sp, fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ===== 历史记录（聚合所有功能模块）=====
            SuiteCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenHistory)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(t("历史记录", "歷史記錄", "History"), style = TextStyle(color = SuTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                        BasicText(t("LSB / RAC / 幻影 / 光棱 / 混淆的操作记录，点击可跳转", "LSB / RAC / 幻影 / 光棱 / 混淆的操作記錄，點擊可跳轉", "Records from LSB / RAC / Phantom / Prism / Scramble; tap to jump"), style = TextStyle(color = SuSub, fontSize = 12.sp))
                    }
                    BasicText("›", style = TextStyle(color = SuSub, fontSize = 22.sp), modifier = Modifier.padding(start = 8.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 莫奈取色圆点：四色渐变 */
private val BrushSweep = Brush.sweepGradient(
    listOf(
        Color(0xFF34C759), Color(0xFF007AFF), Color(0xFFAF52DE),
        Color(0xFFFF9500), Color(0xFF34C759)
    )
)

/** 莫奈取色色块 */
@Composable
private fun MonetSwatch(selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) Color(0xFF3482FF).copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(BrushSweep),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                BasicText("莫", style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun AccentSwatch(color: Color, monet: Boolean, current: Color, onClick: () -> Unit) {
    val selected = !monet && current == color
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) color.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                BasicText("✓", style = TextStyle(color = color.onAccentFg(), fontSize = 14.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}
