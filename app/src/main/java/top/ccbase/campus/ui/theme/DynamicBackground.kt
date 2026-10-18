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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 动态背景（2026-10-18 用户："还要有动态主题，app 和小组件都要有"）。
 *
 * 四条，全部用 Canvas 现画，不依赖视频/GIF —— 一张 1080p 的动图循环至少要几十 MB，
 * 而且掉帧；画出来的东西只吃 CPU 一点点，还能跟着当前强调色走。
 *
 * 省电三件事：
 *  ① 自己控帧，30fps 就够（这些运动都以"十几秒一个来回"为单位，60fps 看不出差别）；
 *  ② 页面不可见时 LaunchedEffect 会被 Compose 取消，动画自然停；
 *  ③ 粒子数量写死上限（120），不按分辨率放大。
 */
enum class DynKind(val id: String, val label: String, val hint: String) {
    AURORA("aurora", "极光", "缓慢流动的彩带"),
    STARS("stars", "星轨", "星星在闪，云在漂"),
    BREATHE("breathe", "呼吸", "光晕一起一伏"),
    PETALS("petals", "落樱", "花瓣往下飘");

    companion object {
        fun of(id: String): DynKind? = entries.firstOrNull { it.id == id }
    }
}

/** 动态背景的 id 前缀，和 `preset:` / `photo` / `solid` 并列存在 bgChoice 里 */
const val ANIM_PREFIX = "anim:"

/** 30fps：这些运动没有一处快到需要 60fps */
private const val FRAME_MS = 33L

@Composable
fun DynamicBackground(kind: DynKind, accent: Color, modifier: Modifier = Modifier) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(kind) {
        val start = System.currentTimeMillis()
        while (true) {
            t = (System.currentTimeMillis() - start) / 1000f
            delay(FRAME_MS)
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val deep = Color(0xFF07060C)
        when (kind) {
            DynKind.AURORA -> {
                drawRect(Brush.verticalGradient(listOf(deep, Color(0xFF0C0A18), deep)))
                // 三条彩带：各自不同的周期，叠起来就不会"整块一起动"
                val bands = listOf(
                    Triple(accent, 21f, 0.0f),
                    Triple(accent.lerp(Color(0xFF39D3C0), 0.55f), 27f, 2.1f),
                    Triple(accent.lerp(Color(0xFFFF7BC0), 0.5f), 34f, 4.2f),
                )
                bands.forEachIndexed { i, (c, period, phase) ->
                    val p = (t / period) * 2f * PI.toFloat() + phase
                    val cx = w * (0.5f + 0.34f * sin(p))
                    val cy = h * (0.30f + 0.12f * cos(p * 1.3f) + i * 0.16f)
                    val r = w * (0.55f + 0.10f * sin(p * 0.7f))
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(c.copy(alpha = 0.34f), Color.Transparent),
                            center = Offset(cx, cy),
                            radius = r,
                        ),
                    )
                }
            }
            DynKind.STARS -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0A1A), Color(0xFF120E22), deep)))
                // 星点：位置固定（种子写死），只有亮度在变 —— 看起来是"闪"，不是"乱飞"
                val rnd = Random(20261018)
                repeat(120) {
                    val x = rnd.nextFloat() * w
                    val y = rnd.nextFloat() * h * 0.92f
                    val phase = rnd.nextFloat() * 6.28f
                    val base = 0.35f + rnd.nextFloat() * 0.5f
                    val a = (base * (0.55f + 0.45f * sin(t * 1.6f + phase))).coerceIn(0f, 1f)
                    val rr = (0.7f + rnd.nextFloat() * 1.5f)
                    drawCircle(Color.White.copy(alpha = a), radius = rr, center = Offset(x, y))
                }
                // 一条慢慢横移的银河
                val p = (t / 40f) * 2f * PI.toFloat()
                val cx = w * (0.5f + 0.25f * sin(p))
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(Color.Transparent, accent.copy(alpha = 0.18f), Color.Transparent),
                        start = Offset(cx - w * 0.6f, h * 0.75f),
                        end = Offset(cx + w * 0.6f, h * 0.15f),
                    ),
                )
            }
            DynKind.BREATHE -> {
                drawRect(Brush.verticalGradient(listOf(deep, Color(0xFF0B0913))))
                val p = (t / 9f) * 2f * PI.toFloat()
                val k = 0.5f + 0.5f * sin(p)
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.22f + 0.16f * k), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.34f),
                        radius = w * (0.55f + 0.18f * k),
                    ),
                )
                // 底部回一点暗光，整屏不至于"上面亮下面死"
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.10f - 0.05f * k), Color.Transparent),
                        center = Offset(w * 0.5f, h),
                        radius = w * 0.8f,
                    ),
                )
            }
            DynKind.PETALS -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF120B14), Color(0xFF1A0F1A), deep)))
                val rnd = Random(8)
                repeat(70) {
                    val x0 = rnd.nextFloat() * w
                    val speed = 0.10f + rnd.nextFloat() * 0.22f   // 屏高/秒
                    val sway = 18f + rnd.nextFloat() * 40f
                    val phase = rnd.nextFloat() * 6.28f
                    val sizePx = 2.5f + rnd.nextFloat() * 4f
                    val y = ((t * speed + rnd.nextFloat()) % 1.15f) * h - h * 0.08f
                    val x = x0 + sway * sin(t * 0.9f + phase)
                    val alpha = 0.20f + rnd.nextFloat() * 0.45f
                    drawOval(
                        color = Color(0xFFFFC0D8).copy(alpha = alpha),
                        topLeft = Offset(x, y),
                        size = Size(sizePx * 1.6f, sizePx),
                    )
                }
            }
        }
        // 统一压一层顶部渐变，保证状态栏上的字看得清（和静态背景同一口径）
        drawRect(
            Brush.verticalGradient(
                colors = listOf(Color(0x55000000), Color.Transparent),
                endY = h * 0.35f,
            ),
        )
    }
}

private fun Color.lerp(other: Color, t: Float): Color = Color(
    red = red + (other.red - red) * t,
    green = green + (other.green - green) * t,
    blue = blue + (other.blue - blue) * t,
    alpha = 1f,
)
