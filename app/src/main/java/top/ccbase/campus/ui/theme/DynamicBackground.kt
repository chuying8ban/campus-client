package top.ccbase.campus.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import top.ccbase.campus.widget.DynPainter
import top.ccbase.campus.widget.WidgetFrames
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 动态主题（2026-10-18）。
 *
 * 真正的画法在 [DynPainter]（纯 `android.graphics`）—— 这里只做三件事：
 *  ① 用 30fps 的时钟推进时间 `t`；
 *  ② 把它画进 Compose 的 Canvas；
 *  ③ 提供"动态主题库"的预设表和 spec 解析（`anim:<kind>:<argb>`）。
 *
 * 为什么 App 与小组件必须共用同一个画笔：小组件那边是把同样的调用**预渲染成 24 帧**
 * 再交给 flipper 轮播。两边如果各写一套，用户会发现"App 里是极光、桌面上是别的东西"。
 *
 * 省电三件事：30fps；页面不可见时 LaunchedEffect 会被取消，动画自然停；粒子数固定不随分辨率涨。
 */
typealias DynKind = DynPainter.Kind

/** `DynKind.hint` 的文案（写在动态主题卡下面那一行小字） */
val DynKind.hint: String
    get() = when (this) {
        DynKind.AURORA -> "缓慢流动的彩带"
        DynKind.STARS -> "星星在闪，银河在漂"
        DynKind.BREATHE -> "光晕一起一伏"
        DynKind.PETALS -> "花瓣往下飘"
        DynKind.SNOW -> "细雪缓缓落下"
        DynKind.RAIN -> "斜雨，带风的斜度"
        DynKind.FIREFLIES -> "夏天夜里的光点"
        DynKind.WAVES -> "一层层推上来的潮"
        DynKind.BOKEH -> "虚化的暖色光斑"
        DynKind.NEBULA -> "慢慢翻涌的星云"
        DynKind.BUBBLES -> "气泡往上升"
        DynKind.MATRIX -> "往下掉的绿字流"
    }

/** 动态背景的 id 前缀，和 `preset:` / `photo` / `solid` 并列存在 bgChoice 里 */
const val ANIM_PREFIX = "anim:"

/** spec 形如 `anim:aurora:ffa06bff`（kind:argb 的十六进制） */
fun animSpec(kind: DynKind, accent: Color): String =
    ANIM_PREFIX + kind.id + ":" + Integer.toHexString(accent.toArgb())

/** 解析 spec；老版本存的 `anim:aurora` 没有色值，按默认强调色算 */
fun parseAnim(spec: String): Pair<DynKind, Int>? {
    if (!spec.startsWith(ANIM_PREFIX)) return null
    val parts = spec.removePrefix(ANIM_PREFIX).split(":")
    val kind = DynKind.of(parts.getOrElse(0) { "" }) ?: return null
    val argb = runCatching { parts.getOrNull(1)?.toLong(16)?.toInt() }.getOrNull()
        ?: 0xFFA06BFF.toInt()
    return kind to argb
}

/**
 * 2026-10-19 用户："小控件和 app 的动态主题要足够流畅"。
 *
 * 原来是 `while(true){ t = …; delay(33) }` —— 固定 30fps + 线程计时，跟屏幕刷新不同步：
 * 手机 60/120Hz，33ms 的 tick 会不断和 vsync 错位，看起来就是"一顿一顿"。
 * 改成 `withFrameNanos`：**跟着 vsync 走**，能 60fps 就 60fps，掉帧时自然少画几帧（不会越拖越远）。
 */
private const val FRAME_MS = 0L

/**
 * 背景时钟（2026-10-19 用户："目前动态主题还是太卡，做流畅一些"）。
 *
 * 跟 vsync 走之后暴露了另一个问题：**每一帧都在占 UI 线程**。滚动时列表和背景抢同一帧的预算，
 * 中端机上就是"滑动一顿一顿"。所以这里加一层自适应：
 *  - 正常情况 60fps（gap 16ms）；
 *  - 一旦实测帧间隔连续偏大（>26ms），自动降到约 30fps（gap 33ms），把预算让给列表和点击；
 *  - 之后一直保持降档，不再来回切（来回切本身就是抖动源）。
 */
@Composable
private fun rememberDynTime(vararg keys: Any?): Float {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(*keys) {
        var start = -1L
        var last = -1L
        var gapNs = 16_000_000L
        var slowStreak = 0
        while (true) {
            withFrameNanos { now ->
                if (start < 0L) { start = now; last = now }
                val dt = now - last
                if (dt > 26_000_000L) slowStreak++ else if (slowStreak > 0) slowStreak--
                // 连续 20 帧都跟不上（约 1 秒），就降一档
                if (slowStreak >= 20 && gapNs < 33_000_000L) gapNs = 33_000_000L
                if (now - last >= gapNs) {
                    last = now
                    t = (now - start) / 1_000_000_000f
                }
            }
        }
    }
    return t
}

@Composable
fun DynamicBackground(kind: DynKind, accent: Color, modifier: Modifier = Modifier) {
    // 读 t 的地方是 Canvas 的 draw lambda —— 只触发重绘，不重组整棵树
    val t = rememberDynTime(kind, accent)
    val argb = accent.toArgb()
    Canvas(modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            DynPainter.frame(canvas.nativeCanvas, kind, t, size.width, size.height, argb)
        }
    }
}

// ── 让一张二次元图动起来（动态壁纸那种）────────────────────────────────────

/**
 * 2026-10-18 用户："动态主题不仅仅是那些粒子特效，而是要有动态二次元图片那种，类似于动态壁纸"。
 *
 * 做法：图做**微推近**（Ken Burns，1.03±0.03）+ 前景飘一层樱瓣/雪/光斑/星点。
 * 只叠粒子会像"贴纸在动"，只推近又太闷，两个一起才像壁纸。
 */
const val ANIMPHOTO_PREFIX = "animphoto:"

fun animPhotoSpec(effect: WidgetFrames.ImgEffect): String = ANIMPHOTO_PREFIX + effect.id

fun parseAnimPhoto(spec: String): WidgetFrames.ImgEffect? {
    if (!spec.startsWith(ANIMPHOTO_PREFIX)) return null
    return WidgetFrames.ImgEffect.of(spec.removePrefix(ANIMPHOTO_PREFIX))
}

@Composable
fun DynamicPhotoBackground(
    bitmap: ImageBitmap,
    effect: WidgetFrames.ImgEffect,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val t = rememberDynTime(bitmap, effect)
    val argb = accent.toArgb()
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val phase = (t / 3.2f) * 2f * PI.toFloat()
        val zoom = 1.03f + 0.03f * sin(phase)
        val scale = maxOf(w / bitmap.width, h / bitmap.height) * zoom
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val dx = (w - dw) / 2f + w * 0.012f * sin(phase * 0.7f)
        val dy = (h - dh) / 2f + h * 0.010f * cos(phase * 0.9f)
        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(dx.toInt(), dy.toInt()),
            dstSize = IntSize(dw.toInt(), dh.toInt()),
            filterQuality = FilterQuality.Low,
        )
        if (effect != WidgetFrames.ImgEffect.DRIFT) {
            val kind = when (effect) {
                WidgetFrames.ImgEffect.PETALS -> DynKind.PETALS
                WidgetFrames.ImgEffect.SNOW -> DynKind.SNOW
                WidgetFrames.ImgEffect.BOKEH -> DynKind.BOKEH
                WidgetFrames.ImgEffect.STARS -> DynKind.STARS
                WidgetFrames.ImgEffect.FIREFLIES -> DynKind.FIREFLIES
                WidgetFrames.ImgEffect.BUBBLES -> DynKind.BUBBLES
                WidgetFrames.ImgEffect.DRIFT -> DynKind.PETALS
            }
            drawIntoCanvas { canvas ->
                DynPainter.particles(canvas.nativeCanvas, kind, t, w, h, argb, 0.6f)
            }
        }
    }
}

// ── 动态主题库 ────────────────────────────────────────────────────────────

/** 色系：同一种动效配不同色调，用户在库里挑的是"画面"，不是参数。 */
data class DynTint(val label: String, val argb: Int, val tags: String)

val DYN_TINTS = listOf(
    DynTint("紫", 0xFFA06BFF.toInt(), "紫 梦幻 夜空"),
    DynTint("青", 0xFF39D3C0.toInt(), "青 薄荷 清爽"),
    DynTint("粉", 0xFFFF7BC0.toInt(), "粉 少女 浪漫"),
    DynTint("橙", 0xFFFF9A5A.toInt(), "橙 暖 夕阳"),
    DynTint("蓝", 0xFF6FA8FF.toInt(), "蓝 海 冷"),
    DynTint("绿", 0xFF7BD88F.toInt(), "绿 自然 森林"),
)

/** 一条可选的动态主题 = 动效 × 色系 */
data class DynPreset(
    val id: String,
    val kind: DynKind,
    val tint: DynTint,
) {
    val name: String get() = kind.label + "·" + tint.label
    /** 搜索命中用：动效名 + 动效标签 + 色系名 + 色系标签 */
    val haystack: String get() = kind.label + " " + kind.tags + " " + tint.label + " " + tint.tags
    val spec: String get() = ANIM_PREFIX + kind.id + ":" + Integer.toHexString(tint.argb)
}

/** 12 种动效 × 6 个色系 = 72 款。用户要"大量的动态图片"，这是它的"图片"。 */
val DYN_PRESETS: List<DynPreset> = DynKind.entries.flatMap { k ->
    DYN_TINTS.map { t -> DynPreset(k.id + "." + t.label, k, t) }
}

fun dynPresetsMatching(q: String): List<DynPreset> {
    val needle = q.trim()
    if (needle.isEmpty()) return DYN_PRESETS
    val keys = needle.split(' ').filter { it.isNotBlank() }
    return DYN_PRESETS.filter { p -> keys.all { p.haystack.contains(it, ignoreCase = true) } }
}
