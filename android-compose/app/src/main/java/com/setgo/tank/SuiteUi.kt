package com.setgo.tank

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import kotlin.math.roundToInt

/** 全局明暗状态：恒为浅色（深色模式写死，不做深色适配；SuBg/SuTitle 等暗色分支保留不触发） */
var suiteDark by mutableStateOf(false)

/** 套件公共配色（明暗联动：主题模式切换时背景/文字/卡片整体变色；背景极淡染主题色，接近纯白） */
internal val SuBg: Color get() = if (suiteDark)
    blendColors(Color(0xFF17171A), suiteAccent, 0.04f)
else
    blendColors(Color(0xFFF5F5F7), suiteAccent, 0.06f)
internal val SuTitle: Color get() = if (suiteDark) Color(0xFFF2F2F4) else Color(0xFF1F1F1F)
internal val SuSub: Color get() = if (suiteDark) Color(0xFF9A9AA0) else Color(0xFF8A8A8E)
internal val SuSegBg: Color get() = if (suiteDark) Color(0xFF2C2C30) else Color(0xFFEBEBEF)

/** 颜色是否为浅色（相对亮度 > 0.5）：用于主题色过浅时保证前景文字对比度 */
internal fun Color.isLight(): Boolean {
    val lum = 0.299f * red + 0.587f * green + 0.114f * blue
    return lum > 0.55f
}

/** 主题色用作"前景文字/图标"时的安全色：主题色过浅（如瓷白）时退化到深色文字，保证可读 */
internal val SuAccentFg: Color get() = if (suiteAccent.isLight()) SuTitle else suiteAccent

/** 主题色用作"背景"时其上文字的安全色：主题色过浅时用深色文字，否则用白色 */
internal fun Color.onAccentFg(): Color = if (isLight()) SuTitle else Color.White

/** 颜色混合：a 与 b 按 t(0..1) 线性插值 */
internal fun blendColors(a: Color, b: Color, t: Float): Color {
    val ta = a.toArgb(); val tb = b.toArgb()
    fun ch(sh: Int): Int {
        val ca = (ta shr sh) and 0xFF
        val cb = (tb shr sh) and 0xFF
        return (ca * (1f - t) + cb * t).toInt().coerceIn(0, 255)
    }
    return Color(android.graphics.Color.argb(255, ch(16), ch(8), ch(0)))
}

/**
 * 卡片容器色：随主题色推导（Material You primaryContainer 思路）
 * 浅色：白 + 主题色 18%；深色：深底 + 主题色 30% → 明显带主题色调，与淡背景形成对比
 */
internal val SuCardBg: Color get() = if (suiteDark)
    blendColors(Color(0xFF232326), suiteAccent, 0.30f)
else
    blendColors(Color.White, suiteAccent, 0.18f)

/** 卡片微染主题色：RAC 私有卡、关于页卡等默认白卡片，强度对齐 SuCardBg（0.18）保证与其他页卡片风格统一且可见 */
internal val SuCardTint: Color get() = SuCardBg

/** 全局主题色（设置页可切换，联动全 App 主色） */
var suiteAccent by mutableStateOf(Color(0xFF3482FF))

/** 莫奈取色：跟随系统壁纸动态色（设置页选项） */
var suiteMonet by mutableStateOf(false)

/** 主题色语义（跟随设置联动） */
internal val SuBlue get() = suiteAccent

/** 语言设置 */
enum class SuiteLang(val label: String) {
    Simplified("简体中文"), Traditional("繁體中文"), English("English")
}
var suiteLang by mutableStateOf(SuiteLang.Simplified)

/** 三语翻译：简体 / 繁体 / 英文 */
internal fun t(zh: String, zhTw: String, en: String): String = when (suiteLang) {
    SuiteLang.Simplified -> zh
    SuiteLang.Traditional -> zhTw
    SuiteLang.English -> en
}

/** 二级页是否用系统返回键回上级（设置项，默认开启） */
var suiteBackGesture by mutableStateOf(true)

/** 调色板样式（Material You 取色风格） */
enum class SuitePaletteStyle(val label: String) {
    TonalSpot("Tonal Spot"), Vibrant("Vibrant"), Expressive("Expressive"),
    Rainbow("Rainbow"), FruitSalad("Fruit Salad"), Monochrome("Monochrome")
}
var suitePaletteStyle by mutableStateOf(SuitePaletteStyle.TonalSpot)

/** 颜色规格（Material 取色规格） */
enum class SuiteColorSpec(val label: String) {
    Spec2021("Spec 2021"), Spec2025("Spec 2025")
}
var suiteColorSpec by mutableStateOf(SuiteColorSpec.Spec2025)

/** 顶部渐隐栏：背景从上到下由实色渐隐至透明；标题随滚动从左移到中央（HyperLight 式） */
@Composable
internal fun SuiteFadingTopBar(
    title: String,
    scrollOffset: Float,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable BoxScope.() -> Unit)? = null
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val t = (scrollOffset / 60f).coerceIn(0f, 1f)
    val sizeSp = if (onBack != null) 21f - 3f * t else 34f - 12f * t
    val style = TextStyle(
        fontSize = sizeSp.sp,
        fontWeight = FontWeight.Bold,
        color = SuTitle.copy(alpha = 1f - 0.25f * t)
    )
    val textWidthPx = with(density) {
        textMeasurer.measure(AnnotatedString(title), style).size.width.toFloat()
    }
    val leftBase = with(density) { if (onBack != null) 72.dp.toPx() else 24.dp.toPx() }
    val endX = (screenWidthPx - textWidthPx) / 2f
    val x = leftBase + (endX - leftBase) * t
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(SuBg.copy(alpha = 0.98f), SuBg.copy(alpha = 0f)),
                    startY = 0f,
                    endY = with(density) { 170.dp.toPx() }
                )
            )
            .statusBarsPadding()
            .height(64.dp)
    ) {
        BasicText(
            text = title,
            style = style,
            modifier = Modifier.offset { IntOffset(x.roundToInt(), 0) }
        )
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = "‹",
                    style = TextStyle(fontSize = 30.sp, color = SuTitle),
                    modifier = Modifier.padding(start = 6.dp, top = 2.dp)
                )
            }
        }
        trailing?.invoke(this)
    }
}

/** 双 tab 切换（嵌入/提取、混淆/还原） */
@Composable
internal fun SuiteModeSwitch(
    mode: Boolean,
    onMode: (Boolean) -> Unit,
    leftLabel: String,
    rightLabel: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SuSegBg)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SuiteModeTab(leftLabel, !mode) { onMode(false) }
        SuiteModeTab(rightLabel, mode) { onMode(true) }
    }
}

@Composable
internal fun RowScope.SuiteModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (selected) SuTitle else SuSub,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        )
    }
}

/** 磁贴行：标题 + 副标题 + 尾部（去掉单字方框头像，标题顶格；有真图标时可在尾部自行放） */
@Composable
internal fun SuiteTileRow(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = SuTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            if (subtitle.isNotEmpty()) {
                BasicText(subtitle, style = TextStyle(color = SuSub, fontSize = 12.sp))
            }
        }
        trailing?.invoke()
    }
}

/** 卡片容器 */
@Composable
internal fun SuiteCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardColors(SuCardBg, SuTitle)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp, 14.dp, 16.dp, 14.dp)) { content() }
    }
}

/** 主按钮（蓝色实心） */
@Composable
internal fun SuiteMainButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) SuBlue else Color(0xFFC7C7CC))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = suiteAccent.onAccentFg(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        )
    }
}

/** 幽灵按钮（白底描边） */
@Composable
internal fun SuiteGhostButton(text: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SuCardBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(text, style = TextStyle(color = if (accent.isLight()) SuTitle else accent, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

/** 选图卡：16:9 长条，虚线感占位 */
@Composable
internal fun SuitePickCard(
    title: String,
    hint: String,
    color: Color,
    bmp: Bitmap?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardColors(SuCardTint, SuTitle)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText("＋", style = TextStyle(color = color, fontSize = 26.sp, fontWeight = FontWeight.Light))
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(title, style = TextStyle(color = SuTitle, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    Spacer(Modifier.height(2.dp))
                    BasicText(hint, style = TextStyle(color = SuSub, fontSize = 12.sp))
                }
            }
        }
    }
}

/** 结果展示卡 */
@Composable
internal fun SuiteResultCard(title: String, bmp: Bitmap?, emptyHint: String) {
    SuiteCard {
        SuiteTileRow(title, "") { }
        Spacer(Modifier.height(10.dp))
        if (bmp != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SuCardBg),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SuSegBg),
                contentAlignment = Alignment.Center
            ) {
                BasicText(emptyHint, style = TextStyle(color = SuSub, fontSize = 13.sp))
            }
        }
    }
}

/** 参数滑条行（miuix 官方 Slider，细版） */
@Composable
internal fun SuiteParamSlider(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(label, style = TextStyle(color = SuSub, fontSize = 12.sp), modifier = Modifier.weight(1f))
        BasicText("$value", style = TextStyle(color = SuTitle, fontSize = 12.sp, fontWeight = FontWeight.Medium))
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = min.toFloat()..max.toFloat(),
        height = 20.dp,
        colors = SliderDefaults.sliderColors(
            foregroundColor = SuBlue,
            backgroundColor = Color(0xFFE2E2E7),
            thumbColor = Color.White
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 看图：全屏黑底预览，点击任意处关闭（支持放大查看原图/结果） */
@Composable
internal fun SuiteImageViewer(bmp: Bitmap?, onDismiss: () -> Unit) {
    if (bmp == null) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(8.dp)
        )
    }
}

/** 分段选择按钮（灰度/彩色、输出尺寸等）；调用处需处于 Row 内并传 Modifier.weight */
@Composable
internal fun SuiteSegButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) suiteAccent.copy(alpha = 0.14f) else SuSegBg)
            .then(
                if (selected) Modifier.border(1.dp, suiteAccent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = if (selected) SuAccentFg else SuSub,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        )
    }
}


/** 自绘开关：miuix Switch 在自定义主题下不渲染，改用自绘（track + 圆形 thumb） */
@Composable
internal fun SuiteSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .width(48.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) suiteAccent else Color(0xFFD8D8DC))
            .clickable { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                BasicText(
                    text = "✓",
                    style = TextStyle(color = SuAccentFg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}


// ---------- 主题状态持久化（ReSukiSU 模式：SharedPreferences，重启恢复） ----------

private const val THEME_PREFS = "tuyin_theme"

internal fun loadThemePrefs(context: android.content.Context) {
    val sp = context.getSharedPreferences(THEME_PREFS, android.content.Context.MODE_PRIVATE)
    suiteMonet = sp.getBoolean("monet", false)
    suiteAccent = Color(sp.getInt("accent", 0xFF3482FF.toInt()))
    suitePaletteStyle = when (sp.getString("palette", "TonalSpot")) {
        "Vibrant" -> SuitePaletteStyle.Vibrant
        "Expressive" -> SuitePaletteStyle.Expressive
        "Rainbow" -> SuitePaletteStyle.Rainbow
        "FruitSalad" -> SuitePaletteStyle.FruitSalad
        "Monochrome" -> SuitePaletteStyle.Monochrome
        else -> SuitePaletteStyle.TonalSpot
    }
    suiteColorSpec = if (sp.getBoolean("spec2025", false)) SuiteColorSpec.Spec2025 else SuiteColorSpec.Spec2021
    suiteLang = when (sp.getString("lang", "Simplified")) {
        "Traditional" -> SuiteLang.Traditional
        "English" -> SuiteLang.English
        else -> SuiteLang.Simplified
    }
    suiteBackGesture = sp.getBoolean("backGesture", true)
}

internal fun saveThemePrefs(context: android.content.Context) {
    val sp = context.getSharedPreferences(THEME_PREFS, android.content.Context.MODE_PRIVATE)
    sp.edit()
        .putBoolean("monet", suiteMonet)
        .putInt("accent", suiteAccent.toArgb())
        .putString("palette", suitePaletteStyle.name)
        .putBoolean("spec2025", suiteColorSpec == SuiteColorSpec.Spec2025)
        .putString("lang", suiteLang.name)
        .putBoolean("backGesture", suiteBackGesture)
        .apply()
}
