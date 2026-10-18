package top.ccbase.campus.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import top.ccbase.campus.widget.DynPainter

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

/** 30fps：这些运动没有一处快到需要 60fps */
private const val FRAME_MS = 33L

@Composable
fun DynamicBackground(kind: DynKind, accent: Color, modifier: Modifier = Modifier) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(kind, accent) {
        val start = System.currentTimeMillis()
        while (true) {
            t = (System.currentTimeMillis() - start) / 1000f
            delay(FRAME_MS)
        }
    }
    val argb = accent.toArgb()
    Canvas(modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            DynPainter.frame(canvas.nativeCanvas, kind, t, size.width, size.height, argb)
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
