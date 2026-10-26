package com.setgo.tank.animation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.nav.transition.NavGesture
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavRole
import top.yukonga.miuix.kmp.nav.transition.NavSettle
import top.yukonga.miuix.kmp.nav.transition.NavSettlePhase
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeEdge
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navDirectionalTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 图隐套件页面过渡 —— 移植 ReSukiSU 的 AOSP 卡片返回方案
 * （灵感致敬：github.com/ReSukiSU/ReSukiSU 与 miuix-nav）。
 *
 * 优雅的来源：
 *  1. FastOutExtraSlowIn：快启动、极慢收尾，松手后页面“滑行到站”而不是“嗖”一下到位；
 *  2. 弹性回弹 bounce：松手越快，卡片 scale 先微弹鼓起再回落（阻尼正弦），手感“肉”而有弹性；
 *  3. 垂直跟手：页面跟随手指上下轻微漂移（阻尼缓入），物理感强；
 *  4. 缩放 + 手势跟手：预测性返回时卡片以屏幕中心为轴缩小，同时随手势方向水平位移
 *     （往左推往左移/往右推往右移），松手后从当前位置滑出（ReSukiSU 风格）；
 *  5. 像素吸附：scale/translation 吸附到整像素，杜绝子像素模糊；
 *  6. 分阶段淡出：位移为主，淡出集中在特定区间，不全程线性淡。
 */

/** 官方 FastOutExtraSlowIn 的工程实现（分段三次贝塞尔，knot 在 (1/6, 0.4)） */
internal val FastOutExtraSlowIn: Easing = run {
    val knotX = 0.166666f
    val knotY = 0.4f
    val first = CubicBezierEasing(0.05f / knotX, 0f, 0.133333f / knotX, 0.06f / knotY)
    val second = CubicBezierEasing(
        (0.208333f - knotX) / (1f - knotX),
        (0.82f - knotY) / (1f - knotY),
        (0.25f - knotX) / (1f - knotX),
        (1f - knotY) / (1f - knotY),
    )
    Easing { fraction ->
        if (fraction < knotX) knotY * first.transform(fraction / knotX)
        else knotY + (1f - knotY) * second.transform((fraction - knotX) / (1f - knotX))
    }
}

/** 跟手进度整形：返回时让页面运动比手指“滞后一点点” */
internal val BackGestureEasing: Easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

private const val BOUNCE_STIFFNESS = 200f
private const val BOUNCE_DAMPING = 0.75f
private const val BOUNCE_MAX_KICK = 1000f
private const val BOUNCE_MIN_KICK = 120f
private const val OPEN_FADE_START = 0.12f
private const val OPEN_FADE_SPAN = 0.71f
private const val CLOSE_FADE_START = 0.21f
private const val CLOSE_FADE_SPAN = 0.74f
private const val CLASSIC_FADE_DURATION = 83f
private const val OPEN_FADE_OFFSET = 50f
private const val CLOSE_FADE_OFFSET = 35f
private const val CROSS_ACTIVITY_MIN_SCALE = 0.68f

private val CrossActivityDrift = 96.dp
private val CrossActivityEdgeMargin = 8.dp

private fun topProgress(depth: Float): Float = (1f + depth).coerceIn(0f, 1f)
private fun coverProgress(depth: Float): Float = depth.coerceIn(0f, 1f)

/** 页面渲染宽吸附到整像素，防子像素模糊 */
private fun snapScaleToPixelExtent(scale: Float, extent: Float): Float =
    if (extent <= 0f) scale else round(scale * extent) / extent

/** 页面左边缘吸附到整像素（translation 相应微调） */
private fun snapTranslationToPixelEdge(translation: Float, scale: Float, extent: Float): Float {
    if (extent <= 0f) return translation
    val edge = extent * (1f - scale) / 2f + translation
    return round(edge) - extent * (1f - scale) / 2f
}

/** 跟手进度整形：有手势时反向经 BackGestureEasing，无手势时线性 */
private fun shapedTopProgress(progress: Float, gesture: NavGesture?): Float =
    if (gesture == null) progress else 1f - BackGestureEasing.transform((1f - progress).coerceIn(0f, 1f))

/** 弹性回弹：commit 时按松手速度叠加阻尼正弦过冲（scale 先鼓后收） */
private fun bounceScale(settle: NavSettle?, gesture: NavGesture?): Float {
    if (settle == null || settle.phase != NavSettlePhase.Commit || gesture == null) return 1f
    val factor = if (gesture.swipeEdge != NavSwipeEdge.None) 2f else 1f
    val floorKick = if (gesture.progress < 0.1f) BOUNCE_MIN_KICK else 0f
    val kick = (abs(settle.releaseVelocity) * 100f * (1f - CROSS_ACTIVITY_MIN_SCALE) * factor)
        .coerceIn(floorKick, BOUNCE_MAX_KICK)
    if (kick <= 0f) return 1f
    val omega = sqrt(BOUNCE_STIFFNESS)
    val omegaD = omega * sqrt(1f - BOUNCE_DAMPING * BOUNCE_DAMPING)
    val t = settle.elapsedMillis / 1000f
    val overlay = -(kick / omegaD) * exp(-BOUNCE_DAMPING * omega * t) * sin(omegaD * t)
    return ((100f + overlay) / 100f).coerceAtMost(1f)
}

/** 垂直跟手：页面跟随手指上下轻微漂移（阻尼缓入缓出，幅度受 scale 限制） */
private fun crossActivityYShift(
    gesture: NavGesture?,
    height: Float,
    scale: Float,
    density: Density,
): Float {
    if (gesture == null || height <= 0f) return 0f
    val rawDelta = gesture.touchY - gesture.initialTouchY
    val half = height / 2f
    val ratio = min(half, abs(rawDelta)) / half
    val damped = 1f - (1f - ratio) * (1f - ratio)
    val marginPx = with(density) { CrossActivityEdgeMargin.toPx() }
    val maxShift = ((height - height * scale) / 2f - marginPx).coerceAtLeast(0f)
    return maxShift * damped * (if (rawDelta < 0f) -1f else 1f)
}

// ---------------------------------------------------------------- push / pop（程序化进出）

private val ClassicActivityMotion = NavMotion(
    programmatic = NavSettleSpec.Tween(durationMillis = 450, easing = FastOutExtraSlowIn),
)

/** 进入二级页：新页从右侧漂入 96dp 淡入，下层页左移让位，无暗化 */
private val ClassicActivityOpen: NavTransition = navGraphicsTransition(
    motion = ClassicActivityMotion,
    scrim = { 0f },
) { scope ->
    val depth = scope.relativeDepth
    val driftPx = with(scope.density) { CrossActivityDrift.toPx() }
    if (depth <= 0f) {
        val progress = topProgress(depth)
        translationX = (1f - progress) * driftPx
        alpha = if (scope.role == NavRole.Incoming) {
            val settle = scope.settle
            if (settle != null) {
                ((settle.elapsedMillis - OPEN_FADE_OFFSET) / CLASSIC_FADE_DURATION)
                    .coerceIn(0f, 1f)
            } else {
                ((progress - OPEN_FADE_START) / OPEN_FADE_SPAN).coerceIn(0f, 1f)
            }
        } else {
            1f
        }
    } else {
        translationX = -coverProgress(depth) * driftPx
    }
}

/** 程序化返回：当前页右移漂出快速淡出，下层页从左回位 */
private val ClassicActivityClose: NavTransition = navGraphicsTransition(
    motion = ClassicActivityMotion,
    scrim = { 0f },
) { scope ->
    val depth = scope.relativeDepth
    val driftPx = with(scope.density) { CrossActivityDrift.toPx() }
    if (depth <= 0f) {
        val progress = topProgress(depth)
        translationX = (1f - progress) * driftPx
        alpha = if (scope.role == NavRole.Outgoing) {
            val settle = scope.settle
            if (settle != null) {
                (1f - (settle.elapsedMillis - CLOSE_FADE_OFFSET) / CLASSIC_FADE_DURATION)
                    .coerceIn(0f, 1f)
            } else {
                ((progress - CLOSE_FADE_START) / CLOSE_FADE_SPAN).coerceIn(0f, 1f)
            }
        } else {
            1f
        }
    } else {
        translationX = -coverProgress(depth) * driftPx
    }
}

// ---------------------------------------------------------------- 预测性返回（手势跟手）

/**
 * 预测性返回（左缘右滑，ReSukiSU 卡片缩放 + 手势跟手风格）：
 *  - 顶页：scale 1.0→0.68 居中缩小 + 卡片随手势方向水平位移（往左推往左移/往右推往右移）
 *    + 垂直跟手 + commit 弹性回弹 + 从当前位置沿手势方向滑出 + 快速淡出
 *  - 下层页：完全正常全屏显示、不做任何缩放/位移（位置正常摆放），跟手时上方 scrim 增强防穿帮
 */
private val CrossActivityPredictive: NavTransition = navGraphicsTransition(
    opaqueDepth = 1f,
    motion = NavMotion(
        // commit 速率曲线：FastOutExtraSlowIn 快启动 → 极慢收尾，松手“滑行到站”的节奏感
        // 时长 380ms：退场快，避免“点一级页时二级页还在拖尾”的可交互窗口
        commit = NavSettleSpec.Tween(durationMillis = 380, easing = FastOutExtraSlowIn),
        cancel = NavSettleSpec.Spring(stiffness = 1500f),
    ),
    scrim = { scope ->
        val settle = scope.settle
        val gesture = scope.gesture
        // 一级页"露出程度"：完全被盖 d=1 → 露出 0；完全露出 d=0 → 露出 1
        val revealed = (1f - scope.relativeDepth.coerceIn(0f, 1f))
        when {
            // 返回提交：遮罩跟随二级卡片消失逐渐淡出（min 接续跟手值，避免跳变）
            settle?.phase == NavSettlePhase.Commit ->
                min(revealed, (1f - settle.elapsedMillis / 380f).coerceIn(0f, 1f))
            // 跟手：遮罩随一级页露出逐渐增强
            gesture != null -> revealed
            // 取消/回盖：遮罩随露出减少平滑回退
            settle?.phase == NavSettlePhase.Cancel -> revealed
            // 程序化/静止：无遮罩
            else -> 0f
        }
    },
) { scope ->
    // 深度驱动判定：顶页（返回中退出）depth 0→-1（≤0）；被覆盖的一级页 depth 1→0（>0），区间不重叠。
    val depth = scope.relativeDepth
    val gesture = scope.gesture
    val settle = scope.settle
    val committing = settle?.phase == NavSettlePhase.Commit
    val widthPx = scope.layoutSize.width.toFloat()
    val heightPx = scope.layoutSize.height.toFloat()
    val bounce = bounceScale(settle, gesture)
    // 手势方向：跟手位移朝向手势推进的方向（左缘右滑 → 卡片右移让位；右缘左滑 → 卡片左移）
    val sign = if (gesture?.swipeEdge == NavSwipeEdge.Right) -1f else 1f
    // 跟手位移幅度：推到底时水平移动 26% 屏宽（配合缩放，形成"卡片被推开"的物理感）
    val followPx = widthPx * 0.26f
    if (depth <= 0f) {
        // ---- 顶页（正在退出）：d 0 → -1，缩放 + 手势方向位移（ReSukiSU 卡片风格）
        val progress = topProgress(depth)
        if (scope.role == NavRole.Outgoing && committing) {
            // commit 阶段：只由 depth 驱动（progress 1→0 单调），不读 gesture.progress 实时值。
            // 旧实现用 (1 - gesture.progress) 算"松手缩小值"，但松手后 gesture.progress 会被
            // 框架复位为 0 → committedScale 跳回 1.0 全尺寸 → 表现为"结束瞬间放大一帧"。
            // scale 随 depth 快速缩小（快启动）；位移先接续松手位置再沿手势方向线性滑出；
            // 淡出集中在滑出尾声（后 35% 时间），保证"缩小 + 滑出"过程全程可见、不直接消失。
            val eased = progress
            val t = (settle.elapsedMillis / 380f).coerceIn(0f, 1f)
            val base = CROSS_ACTIVITY_MIN_SCALE + (1f - CROSS_ACTIVITY_MIN_SCALE) * eased
            val settleShrink = 1f - 0.08f * (1f - eased)
            scaleX = snapScaleToPixelExtent(base * settleShrink * bounce, widthPx)
            scaleY = scaleX
            // 位移：接续松手跟手位置（(1-eased)*followPx 连续）→ 沿手势方向二次加速滑出
            // （t² 越滑越快，终点 80% 屏宽，迅速离场；卡片大小此时已缩到位保持不变）
            val slide = t * t * widthPx * 0.8f
            translationX = snapTranslationToPixelEdge(
                sign * (1f - eased) * followPx + sign * slide,
                scaleX,
                widthPx,
            )
            // 淡出：最后 35% 时间从 1 → 0（滑出过程全程可见，尾部才淡）
            alpha = ((1f - t) / 0.35f).coerceIn(0f, 1f)
            translationY = 0f
        } else {
            // 跟手阶段：scale 1.0→0.68 居中缩小 + 卡片随手势方向水平位移（往左推往左移/往右推往右移）
            val easedProgress = shapedTopProgress(progress, gesture)
            scaleX = snapScaleToPixelExtent(
                (CROSS_ACTIVITY_MIN_SCALE + (1f - CROSS_ACTIVITY_MIN_SCALE) * easedProgress) * bounce,
                widthPx,
            )
            scaleY = scaleX
            // 跟手阶段 alpha 恒 1：页面跟手全程可见、不透明。淡出完全交给 commit 尾部。
            // （此前把 alpha 绑在 depth 上，系统触发返回（震动）瞬间深度快速跳到底，
            //   alpha 随之归零 → 卡片“直接透明”；恒 1 后深度怎么跳都不透明。）
            alpha = 1f
            translationX = snapTranslationToPixelEdge(
                sign * (1f - easedProgress) * followPx,
                scaleX,
                widthPx,
            )
            translationY = snapTranslationToPixelEdge(
                crossActivityYShift(gesture, heightPx, scaleX, scope.density),
                scaleX,
                heightPx,
            )
        }
    } else {
        // ---- 下层页（回到显示）：d 1 → 0，完全正常全屏显示、不做任何缩放/位移
        // 一级页保持 1.0 比例、无偏移、alpha 1（全屏正常摆放，绝不跟着缩放或空白）
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        translationX = 0f
        translationY = 0f
    }
}

/**
 * 图隐套件页面过渡：
 *  - push（进入二级页）：右侧漂入淡入（无暗化）
 *  - pop（程序化返回）：右侧漂出快速淡出
 *  - predictivePop（左缘右滑）：AOSP 卡片跟手返回（贴边 + 垂直跟手 + 弹性回弹）
 */
internal val AospSuiteTransition: NavTransition = navDirectionalTransition(
    push = ClassicActivityOpen,
    pop = ClassicActivityClose,
    predictivePop = CrossActivityPredictive,
)
