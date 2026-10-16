package com.setgo.tank

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.setgo.tank.animation.AospSuiteTransition
import com.setgo.tank.navigation.Route
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 液态玻璃主色（半透明白） */
private val GlassTop = Color(0xE6FFFFFF)
private val GlassBottom = Color(0x8CFFFFFF)
private val GlassBorder = Color(0x99FFFFFF)
private val GlassHighlight = Color(0xCCFFFFFF)
private val GlassShadow = Color(0x33000000)

/** 图隐套件功能项 */
data class FeatureItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val color: Color,
    val tag: String,
    val avatarRes: Int
)

val features = listOf(
    FeatureItem(
        id = "rac", title = "RAC 图隐", tag = "隐",
        subtitle = "把一张图，藏进另一张图",
        color = Color(0xFF3482FF),
        avatarRes = R.drawable.avatar_rac
    ),
    FeatureItem(
        id = "phantom", title = "幻影坦克", tag = "幻",
        subtitle = "同一张图随观察方式呈现不同画面",
        color = Color(0xFF9C5BFF),
        avatarRes = R.drawable.avatar_phantom
    ),
    FeatureItem(
        id = "prism", title = "光棱坦克", tag = "棱",
        subtitle = "棱镜级光学变换隐写",
        color = Color(0xFF00B96B),
        avatarRes = R.drawable.avatar_prism
    ),
    FeatureItem(
        id = "arnold", title = "图片混淆", tag = "混",
        subtitle = "Arnold 置乱，图像像素级打乱",
        color = Color(0xFFFF7A00),
        avatarRes = R.drawable.avatar_arnold
    ),
    FeatureItem(
        id = "lsb", title = "LSB 隐写", tag = "位",
        subtitle = "把文字或文件藏进像素末位",
        color = Color(0xFF00A3C4),
        avatarRes = R.drawable.avatar_coil
    ),
    FeatureItem(
        id = "jpeg", title = "JPEG 隐写", tag = "J",
        subtitle = "JSteg · DCT 系数隐写，抗再压缩",
        color = Color(0xFF00A3C4),
        avatarRes = R.drawable.avatar_coil
    ),
    FeatureItem(
        id = "detect", title = "隐写检测", tag = "检",
        subtitle = "扫描图片是否疑似藏入数据",
        color = Color(0xFFFF375F),
        avatarRes = R.drawable.avatar_dev
    )
)

/** 底部主导航：功能区 / 关于区 */
enum class MainTab {
    Features, About
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 恢复持久化主题（ReSukiSU 模式：SharedPreferences，重启不丢）
        loadThemePrefs(applicationContext)
        // 沉浸式：状态栏/导航栏透明，内容延伸
        enableEdgeToEdge()
        setContent {
            AppTheme {
                TuyinSuiteApp()
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    // 深色模式写死：始终浅色。瓷白底 + 主题色染色是本产品特色，不做深色适配（不跟随系统深浅）
    suiteDark = false
    // 主题联动设置页：莫奈取色 / 调色板样式 / 颜色规格 / 主题色全部联动
    // 莫奈取色：直接读系统动态色种子（Android 12+ system_accent1_500）作为 keyColor，
    // 走同一条主题链路 → 导航/按钮/卡片/背景整体联动（ReSukiSU 同款方案，比 miuix MonetSystem 可靠）
    val context = LocalContext.current
    val controller = remember(suiteAccent, suiteMonet, suitePaletteStyle, suiteColorSpec) {
        val mode = ColorSchemeMode.Light
        val effectiveKey = if (suiteMonet) Color(systemAccentSeed(context)) else suiteAccent
        val palette = when (suitePaletteStyle) {
            SuitePaletteStyle.TonalSpot -> ThemePaletteStyle.TonalSpot
            SuitePaletteStyle.Vibrant -> ThemePaletteStyle.Vibrant
            SuitePaletteStyle.Expressive -> ThemePaletteStyle.Expressive
            SuitePaletteStyle.Rainbow -> ThemePaletteStyle.Rainbow
            SuitePaletteStyle.FruitSalad -> ThemePaletteStyle.FruitSalad
            SuitePaletteStyle.Monochrome -> ThemePaletteStyle.Monochrome
        }
        val spec = if (suiteColorSpec == SuiteColorSpec.Spec2025) ThemeColorSpec.Spec2025 else ThemeColorSpec.Spec2021
        ThemeController(
            colorSchemeMode = mode,
            keyColor = effectiveKey,
            paletteStyle = palette,
            colorSpec = spec
        )
    }
    // 莫奈取色：取到系统动态色后写入 suiteAccent → 卡片/导航/按钮全链路联动
    LaunchedEffect(suiteMonet) {
        if (suiteMonet) {
            suiteAccent = Color(systemAccentSeed(context))
        }
    }
    // 主题状态持久化：任何主题/语言/预返回变化都落盘
    LaunchedEffect(suiteAccent, suiteMonet, suitePaletteStyle, suiteColorSpec, suiteLang, suiteBackGesture) {
        saveThemePrefs(context)
    }
    MiuixTheme(
        controller = controller,
        content = content
    )
}

/** 系统动态色种子：Android 12+ 取壁纸主色（莫奈取色），低版本回退默认蓝。
 * 优先 WallpaperManager.getWallpaperColors()（官方壁纸色 API，不依赖厂商对
 * system_accent 的实现），失败再回退 system_accent1_500 / 默认蓝。 */
internal fun systemAccentSeed(context: Context): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0xFF3482FF.toInt()
    val fromWallpaper = runCatching {
        val wm = context.getSystemService(Context.WALLPAPER_SERVICE) as? android.app.WallpaperManager
        wm?.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
    }.getOrNull()
    if (fromWallpaper != null) return fromWallpaper
    return runCatching { context.getColor(android.R.color.system_accent1_500) }.getOrDefault(0xFF3482FF.toInt())
}

@Composable
fun TuyinSuiteApp() {
    // miuix NavDisplay 导航栈：预测性返回 / 跟手滑动 / 返回动画全部由框架驱动
    // （relativeDepth 连续模型 → 视觉是深度的纯函数，跟手与 settle 复用同一几何，无跳变无卡顿）
    val backStack = rememberNavBackStack<Route>(Route.Main)
    val navigator = remember(backStack) { NavController(backStack) }
    // 主页 tab（功能区 / 关于）：tab 切换不产生返回栈
    var tab by remember { mutableStateOf(MainTab.Features) }

    // 返回过渡特效：圆角裁剪跟随系统圆角 + 轻暗化（MIUI 风格返回）
    val sysCorner = rememberNavSystemCornerRadius()
    val cornerRadius = if (sysCorner > 0.dp) sysCorner else 28.dp
    val effects = remember(cornerRadius) {
        NavDisplayEffects(
            enableCornerClip = true,
            cornerClipRadius = cornerRadius,
            cornerClipMode = NavCornerClipMode.All,
            dimAmount = 0.35f,
            backdropColor = Color(0xFFF4F4F6)
        )
    }

    NavDisplay(
        backStack = backStack,
        onBack = { navigator.pop() },
        // 预返回开关：开 → 跟手 AOSP 卡片过渡；关 → Miuix 默认滑动过渡
        transition = if (suiteBackGesture) AospSuiteTransition else NavTransitions.MiuixDefault,
        effects = effects,
        modifier = Modifier.fillMaxSize()
    ) {
        // 主页（栈底）：功能区 / 关于 双 tab + 底部悬浮导航
        entry<Route.Main>(swipeDismiss = NavSwipeDirection.None) {
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    MainTab.Features -> HomeScreen(
                        onOpenFeature = { navigator.push(Route.Feature(it.id)) },
                        onOpenSettings = { navigator.push(Route.Settings) }
                    )
                    MainTab.About -> AboutScreen()
                }
                FloatingNavBar(
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 18.dp)
                )
            }
        }
        // 功能二级页：支持左→右跟手返回
        entry<Route.Feature>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            FeatureScreen(
                item = features.first { it.id == route.id },
                onBack = { navigator.pop() },
                onOpenHistory = { navigator.push(Route.History) },
                loadPath = route.loadPath
            )
        }
        // 设置页：支持左→右跟手返回
        entry<Route.Settings>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            SettingsScreen(
                onBack = { navigator.pop() },
                onOpenHistory = { navigator.push(Route.History) }
            )
        }
        // 历史记录页：支持左→右跟手返回；点击记录跳转对应模块并加载图片
        entry<Route.History>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            HistoryScreen(
                onBack = { navigator.pop() },
                onOpenModule = { module, path -> navigator.push(Route.Feature(module, path)) }
            )
        }
    }
}

@Composable
private fun FloatingNavBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(32.dp),
                spotColor = GlassShadow,
                ambientColor = GlassShadow
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(GlassTop, GlassBottom),
                    endY = 260f
                ),
                shape = RoundedCornerShape(32.dp)
            )
            .border(1.dp, GlassBorder, RoundedCornerShape(32.dp))
            .drawWithContent {
                drawContent()
                // 顶部内侧高光（液态玻璃边缘）
                drawLine(
                    color = GlassHighlight,
                    start = Offset(size.width * 0.16f, 3.5.dp.toPx()),
                    end = Offset(size.width * 0.84f, 3.5.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem(
            selected = selected == MainTab.Features,
            icon = Icons.Filled.Home,
            label = t("功能区", "功能區", "Features"),
            onClick = { onSelect(MainTab.Features) }
        )
        NavItem(
            selected = selected == MainTab.About,
            icon = Icons.Filled.Info,
            label = t("关于", "關於", "About"),
            onClick = { onSelect(MainTab.About) }
        )
    }
}

@Composable
private fun NavItem(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(27.dp))
            .background(
                if (selected) {
                    Brush.verticalGradient(
                        colors = listOf(Color(0x59FFFFFF), Color(0x26FFFFFF)),
                        endY = 120f
                    )
                } else {
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                },
                RoundedCornerShape(27.dp)
            )
            .then(
                if (selected) {
                    Modifier.border(1.dp, GlassBorder, RoundedCornerShape(27.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) SuAccentFg else Color(0xFF9A9AA0),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        BasicText(
            text = label,
            style = TextStyle(
                color = if (selected) SuAccentFg else Color(0xFF9A9AA0),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
