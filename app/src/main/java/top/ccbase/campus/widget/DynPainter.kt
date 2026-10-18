package top.ccbase.campus.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 动态主题的**绘制内核**（2026-10-18）。
 *
 * 为什么单独抽出来：同一种"会动的背景"要出现在两个地方 —— App 页面（Compose Canvas）和
 * 桌面小组件（RemoteViews 里只有静态 ImageView）。如果各写一遍，两边早晚长得不一样。
 * 所以真正的画法只有这一份（纯 `android.graphics`），App 侧用 `drawIntoCanvas` 包一层，
 * 小组件侧按时间**预渲染成 N 帧**再交给 flipper 轮播。
 *
 * 约定：所有种类都必须"t 驱动"，不持有状态；同一个 (kind, t) 画出来必须一模一样（可重放）。
 */
object DynPainter {

    enum class Kind(val id: String, val label: String, val tags: String) {
        AURORA("aurora", "极光", "极光 彩带 流动 夜空 梦幻"),
        STARS("stars", "星轨", "星星 银河 闪烁 夜空 璀璨"),
        BREATHE("breathe", "呼吸", "光晕 呼吸 柔和 单色"),
        PETALS("petals", "落樱", "樱花 花瓣 飘落 春天 浪漫"),
        SNOW("snow", "飘雪", "雪 冬天 白点 飘落 寒冷"),
        RAIN("rain", "细雨", "雨 线条 雨天 忧郁 阴天"),
        FIREFLIES("fireflies", "萤火", "萤火虫 光点 夏夜 呼吸"),
        WAVES("waves", "潮汐", "波浪 海 线条 水平 潮汐"),
        BOKEH("bokeh", "光斑", "散景 光斑 虚化 暖 夜景"),
        NEBULA("nebula", "星云", "星云 宇宙 云雾 浩瀚"),
        BUBBLES("bubbles", "气泡", "气泡 上升 水 清透 蓝"),
        MATRIX("matrix", "流码", "代码 数字雨 黑客 赛博 流动");

        companion object {
            fun of(id: String): Kind? = entries.firstOrNull { it.id == id }
        }
    }

    private const val DEEP = 0xFF07060C.toInt()

    /**
     * 画一帧。
     *
     * @param t 秒（可以很大，内部只取模）
     * @param accent 强调色（ARGB）
     */
    fun frame(c: Canvas, kind: Kind, t: Float, w: Float, h: Float, accent: Int, dark: Boolean = true) {
        if (w <= 0f || h <= 0f) return
        val base = if (dark) DEEP else 0xFFF4F1EC.toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun fillVertical(vararg stops: Pair<Float, Int>) {
            val colors = stops.map { it.second }.toIntArray()
            val pos = stops.map { it.first }.toFloatArray()
            paint.shader = LinearGradient(0f, 0f, 0f, h, colors, pos, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        fun glow(cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
            paint.shader = RadialGradient(
                cx, cy, r.coerceAtLeast(1f),
                intArrayOf(withAlpha(color, alpha), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        when (kind) {
            Kind.AURORA -> {
                fillVertical(0f to base, 0.5f to mix(base, 0xFF0C0A18.toInt(), 0.6f), 1f to base)
                val bands = listOf(
                    Triple(accent, 21f, 0.0f),
                    Triple(mix(accent, 0xFF39D3C0.toInt(), 0.55f), 27f, 2.1f),
                    Triple(mix(accent, 0xFFFF7BC0.toInt(), 0.5f), 34f, 4.2f),
                )
                bands.forEachIndexed { i, (col, period, phase) ->
                    val p = (t / period) * 2f * PI.toFloat() + phase
                    glow(
                        w * (0.5f + 0.34f * sin(p)),
                        h * (0.30f + 0.12f * cos(p * 1.3f) + i * 0.16f),
                        w * (0.55f + 0.10f * sin(p * 0.7f)),
                        col, 0.34f,
                    )
                }
            }

            Kind.STARS -> {
                fillVertical(0f to mix(base, 0xFF0A0A1A.toInt(), 0.8f), 1f to base)
                val rnd = Random(20261018)
                repeat(140) {
                    val x = rnd.nextFloat() * w
                    val y = rnd.nextFloat() * h * 0.92f
                    val phase = rnd.nextFloat() * 6.28f
                    val b = 0.35f + rnd.nextFloat() * 0.5f
                    val a = (b * (0.55f + 0.45f * sin(t * 1.6f + phase))).coerceIn(0f, 1f)
                    paint.color = withAlpha(Color.WHITE, a * (if (dark) 1f else 0.5f))
                    c.drawCircle(x, y, 0.7f + rnd.nextFloat() * 1.5f, paint)
                }
                val p = (t / 40f) * 2f * PI.toFloat()
                val cx = w * (0.5f + 0.25f * sin(p))
                paint.shader = LinearGradient(
                    cx - w * 0.6f, h * 0.75f, cx + w * 0.6f, h * 0.15f,
                    intArrayOf(Color.TRANSPARENT, withAlpha(accent, 0.18f), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP,
                )
                c.drawRect(0f, 0f, w, h, paint)
                paint.shader = null
            }

            Kind.BREATHE -> {
                fillVertical(0f to base, 1f to mix(base, 0xFF0B0913.toInt(), 0.8f))
                val k = 0.5f + 0.5f * sin((t / 9f) * 2f * PI.toFloat())
                glow(w * 0.5f, h * 0.34f, w * (0.55f + 0.18f * k), accent, 0.22f + 0.16f * k)
                glow(w * 0.5f, h, w * 0.8f, accent, 0.10f - 0.05f * k)
            }

            Kind.PETALS -> {
                fillVertical(0f to mix(base, 0xFF120B14.toInt(), 0.7f), 1f to base)
                val rnd = Random(8)
                repeat(80) {
                    val x0 = rnd.nextFloat() * w
                    val speed = 0.10f + rnd.nextFloat() * 0.22f
                    val sway = 18f + rnd.nextFloat() * 40f
                    val phase = rnd.nextFloat() * 6.28f
                    val sz = 2.5f + rnd.nextFloat() * 4f
                    val y = ((t * speed + rnd.nextFloat()) % 1.15f) * h - h * 0.08f
                    val x = x0 + sway * sin(t * 0.9f + phase)
                    paint.color = withAlpha(0xFFFFC0D8.toInt(), 0.20f + rnd.nextFloat() * 0.45f)
                    c.drawOval(RectF(x, y, x + sz * 1.6f, y + sz), paint)
                }
            }

            Kind.SNOW -> {
                fillVertical(0f to mix(base, 0xFF0B1220.toInt(), 0.8f), 1f to base)
                val rnd = Random(31)
                repeat(90) {
                    val x0 = rnd.nextFloat() * w
                    val speed = 0.06f + rnd.nextFloat() * 0.14f
                    val phase = rnd.nextFloat() * 6.28f
                    val sz = 1.2f + rnd.nextFloat() * 2.6f
                    val y = ((t * speed + rnd.nextFloat()) % 1.1f) * h - h * 0.05f
                    val x = x0 + (10f + rnd.nextFloat() * 20f) * sin(t * 0.5f + phase)
                    paint.color = withAlpha(Color.WHITE, 0.35f + rnd.nextFloat() * 0.5f)
                    c.drawCircle(x, y, sz, paint)
                }
            }

            Kind.RAIN -> {
                fillVertical(0f to mix(base, 0xFF0A0F14.toInt(), 0.7f), 1f to base)
                paint.strokeWidth = Math.max(1f, w * 0.0016f)
                val rnd = Random(77)
                repeat(110) {
                    val x0 = rnd.nextFloat() * (w * 1.2f) - w * 0.1f
                    val speed = 0.9f + rnd.nextFloat() * 0.9f
                    val len = h * (0.03f + rnd.nextFloat() * 0.05f)
                    val y = ((t * speed + rnd.nextFloat() * 2f) % 1.2f) * h - h * 0.1f
                    paint.color = withAlpha(0xFFBFD8E8.toInt(), 0.10f + rnd.nextFloat() * 0.16f)
                    c.drawLine(x0, y, x0 - len * 0.25f, y + len, paint)
                }
            }

            Kind.FIREFLIES -> {
                fillVertical(0f to mix(base, 0xFF0A120C.toInt(), 0.75f), 1f to base)
                val rnd = Random(2026)
                repeat(40) {
                    val bx = rnd.nextFloat() * w
                    val by = rnd.nextFloat() * h
                    val ax = 20f + rnd.nextFloat() * 60f
                    val ay = 15f + rnd.nextFloat() * 45f
                    val ph = rnd.nextFloat() * 6.28f
                    val x = bx + ax * sin(t * 0.35f + ph)
                    val y = by + ay * cos(t * 0.27f + ph * 1.7f)
                    val a = (0.35f + 0.65f * (0.5f + 0.5f * sin(t * 1.1f + ph))).coerceIn(0f, 1f)
                    glow(x, y, 26f + 22f * a, 0xFFB7FF8A.toInt(), 0.30f * a)
                    paint.color = withAlpha(0xFFE8FFC8.toInt(), a)
                    c.drawCircle(x, y, 1.8f, paint)
                }
            }

            Kind.WAVES -> {
                fillVertical(0f to mix(base, 0xFF07121C.toInt(), 0.8f), 1f to base)
                val rows = 6
                paint.style = Paint.Style.STROKE
                for (i in 0 until rows) {
                    val yBase = h * (0.30f + i * 0.11f)
                    val amp = h * (0.020f + i * 0.006f)
                    val period = 7f + i * 2.5f
                    paint.strokeWidth = Math.max(1.2f, h * 0.004f)
                    paint.color = withAlpha(mix(accent, 0xFF7FD4FF.toInt(), 0.4f), 0.30f - i * 0.03f)
                    var x = 0f
                    val step = Math.max(4f, w / 120f)
                    val path = android.graphics.Path()
                    var first = true
                    while (x <= w) {
                        val y = yBase + amp * sin((x / w) * 3.2f + t * (1.6f / period) * 2f * PI.toFloat() + i)
                        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                        x += step
                    }
                    c.drawPath(path, paint)
                }
                paint.style = Paint.Style.FILL
            }

            Kind.BOKEH -> {
                fillVertical(0f to mix(base, 0xFF140F0A.toInt(), 0.75f), 1f to base)
                val rnd = Random(404)
                repeat(26) {
                    val x = rnd.nextFloat() * w
                    val y = rnd.nextFloat() * h
                    val r = w * (0.04f + rnd.nextFloat() * 0.12f)
                    val ph = rnd.nextFloat() * 6.28f
                    val a = 0.08f + 0.10f * (0.5f + 0.5f * sin(t * 0.6f + ph))
                    val col = if (rnd.nextBoolean()) accent else 0xFFFFC46B.toInt()
                    glow(x, y, r, col, a)
                }
            }

            Kind.NEBULA -> {
                fillVertical(0f to mix(base, 0xFF0B0716.toInt(), 0.9f), 1f to base)
                val rnd = Random(99)
                repeat(18) {
                    val ph = rnd.nextFloat() * 6.28f
                    val x = w * (0.2f + 0.6f * rnd.nextFloat()) + w * 0.08f * sin(t * 0.12f + ph)
                    val y = h * rnd.nextFloat() + h * 0.05f * cos(t * 0.10f + ph)
                    val r = w * (0.18f + rnd.nextFloat() * 0.35f)
                    val col = when (rnd.nextInt(3)) {
                        0 -> accent
                        1 -> 0xFFB06BFF.toInt()
                        else -> 0xFFFF6BC4.toInt()
                    }
                    glow(x, y, r, col, 0.07f + rnd.nextFloat() * 0.07f)
                }
            }

            Kind.BUBBLES -> {
                fillVertical(0f to mix(base, 0xFF08131C.toInt(), 0.8f), 1f to base)
                val rnd = Random(11)
                paint.style = Paint.Style.STROKE
                repeat(26) {
                    val x0 = rnd.nextFloat() * w
                    val speed = 0.05f + rnd.nextFloat() * 0.12f
                    val r = 6f + rnd.nextFloat() * 26f
                    val ph = rnd.nextFloat() * 6.28f
                    val y = h - ((t * speed + rnd.nextFloat()) % 1.1f) * h
                    val x = x0 + 12f * sin(t * 0.7f + ph)
                    paint.strokeWidth = Math.max(1.2f, r * 0.10f)
                    paint.color = withAlpha(0xFFBFE9FF.toInt(), 0.22f + rnd.nextFloat() * 0.20f)
                    c.drawCircle(x, y, r, paint)
                }
                paint.style = Paint.Style.FILL
            }

            Kind.MATRIX -> {
                fillVertical(0f to mix(base, 0xFF04120A.toInt(), 0.9f), 1f to base)
                paint.strokeWidth = Math.max(1.5f, w * 0.002f)
                val cols = Math.max(8, (w / Math.max(18f, w / 30f)).toInt())
                val colW = w / cols
                val rnd = Random(20261018)
                for (i in 0 until cols) {
                    val speed = 0.25f + rnd.nextFloat() * 0.5f
                    val len = h * (0.10f + rnd.nextFloat() * 0.25f)
                    val off = rnd.nextFloat() * 2f
                    val y = ((t * speed + off) % 1.4f) * (h + len) - len
                    val x = colW * (i + 0.5f)
                    paint.color = withAlpha(0xFF7BFFA8.toInt(), 0.30f)
                    c.drawLine(x, y, x, y + len, paint)
                    paint.color = withAlpha(0xFFD8FFE4.toInt(), 0.55f)
                    c.drawCircle(x, y + len, Math.max(1.6f, colW * 0.12f), paint)
                }
            }
        }

        // 统一压一层顶部渐变，保证状态栏上的字看得清（和静态背景同一口径）
        paint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.35f,
            intArrayOf(0x55000000, Color.TRANSPARENT), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h * 0.35f, paint)
        paint.shader = null
    }

    /**
     * **只画粒子层**，不画底色 —— 用在"让一张二次元图动起来"上（2026-10-18 用户：
     * "动态主题不仅仅是那些粒子特效，而是要有动态二次元图片那种，类似于动态壁纸"）。
     *
     * 图本身做微动（见 `DynamicPhotoBackground` / `WidgetFrames.photo`），这里负责往图上叠
     * 樱瓣/雪/光斑/星点 —— 看上去就是壁纸在呼吸，而不是一块纯粒子面板。
     *
     * @param strength 0~1，越大粒子越明显（压在插画上不能用满）
     */
    fun particles(c: Canvas, kind: Kind, t: Float, w: Float, h: Float, accent: Int, strength: Float = 0.8f) {
        if (w <= 0f || h <= 0f || strength <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val a0 = strength.coerceIn(0f, 1f)

        when (kind) {
            Kind.PETALS -> {
                val rnd = Random(8)
                repeat(44) {
                    val x0 = rnd.nextFloat() * w
                    val speed = 0.10f + rnd.nextFloat() * 0.22f
                    val sway = 18f + rnd.nextFloat() * 40f
                    val phase = rnd.nextFloat() * 6.28f
                    val sz = 3f + rnd.nextFloat() * 5f
                    val y = ((t * speed + rnd.nextFloat()) % 1.15f) * h - h * 0.08f
                    val x = x0 + sway * sin(t * 0.9f + phase)
                    paint.color = withAlpha(0xFFFFC0D8.toInt(), (0.25f + rnd.nextFloat() * 0.45f) * a0)
                    c.drawOval(RectF(x, y, x + sz * 1.7f, y + sz), paint)
                }
            }

            Kind.SNOW -> {
                val rnd = Random(31)
                repeat(60) {
                    val x0 = rnd.nextFloat() * w
                    val speed = 0.06f + rnd.nextFloat() * 0.14f
                    val phase = rnd.nextFloat() * 6.28f
                    val sz = 1.4f + rnd.nextFloat() * 3f
                    val y = ((t * speed + rnd.nextFloat()) % 1.1f) * h - h * 0.05f
                    val x = x0 + (10f + rnd.nextFloat() * 20f) * sin(t * 0.5f + phase)
                    paint.color = withAlpha(Color.WHITE, (0.35f + rnd.nextFloat() * 0.5f) * a0)
                    c.drawCircle(x, y, sz, paint)
                }
            }

            Kind.BOKEH -> {
                val rnd = Random(404)
                repeat(18) {
                    val x = rnd.nextFloat() * w
                    val y = rnd.nextFloat() * h
                    val r = w * (0.04f + rnd.nextFloat() * 0.10f)
                    val ph = rnd.nextFloat() * 6.28f
                    val a = (0.10f + 0.12f * (0.5f + 0.5f * sin(t * 0.6f + ph))) * a0
                    val col = if (rnd.nextBoolean()) accent else 0xFFFFC46B.toInt()
                    paint.shader = RadialGradient(
                        x, y, r, intArrayOf(withAlpha(col, a), Color.TRANSPARENT),
                        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
                    )
                    c.drawCircle(x, y, r, paint)
                    paint.shader = null
                }
            }

            Kind.STARS -> {
                val rnd = Random(20261018)
                repeat(70) {
                    val x = rnd.nextFloat() * w
                    val y = rnd.nextFloat() * h
                    val phase = rnd.nextFloat() * 6.28f
                    val a = (0.5f * (0.55f + 0.45f * sin(t * 1.6f + phase))) * a0
                    paint.color = withAlpha(Color.WHITE, a)
                    c.drawCircle(x, y, 0.8f + rnd.nextFloat() * 1.4f, paint)
                }
            }

            Kind.FIREFLIES -> {
                val rnd = Random(2026)
                repeat(24) {
                    val bx = rnd.nextFloat() * w
                    val by = rnd.nextFloat() * h
                    val ax = 20f + rnd.nextFloat() * 60f
                    val ay = 15f + rnd.nextFloat() * 45f
                    val ph = rnd.nextFloat() * 6.28f
                    val x = bx + ax * sin(t * 0.35f + ph)
                    val y = by + ay * cos(t * 0.27f + ph * 1.7f)
                    val a = (0.35f + 0.65f * (0.5f + 0.5f * sin(t * 1.1f + ph)))

                    paint.shader = RadialGradient(
                        x, y, 26f + 20f * a,
                        intArrayOf(withAlpha(0xFFB7FF8A.toInt(), 0.28f * a * a0), Color.TRANSPARENT),
                        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
                    )
                    c.drawCircle(x, y, 26f, paint)
                    paint.shader = null
                }
            }

            Kind.BUBBLES -> {
                val rnd = Random(11)
                paint.style = Paint.Style.STROKE
                repeat(20) {
                    val x0 = rnd.nextFloat() * w
                    val speed = 0.05f + rnd.nextFloat() * 0.12f
                    val r = 6f + rnd.nextFloat() * 22f
                    val ph = rnd.nextFloat() * 6.28f
                    val y = h - ((t * speed + rnd.nextFloat()) % 1.1f) * h
                    val x = x0 + 12f * sin(t * 0.7f + ph)
                    paint.strokeWidth = Math.max(1.1f, r * 0.10f)
                    paint.color = withAlpha(0xFFBFE9FF.toInt(), (0.22f + rnd.nextFloat() * 0.20f) * a0)
                    c.drawCircle(x, y, r, paint)
                }
                paint.style = Paint.Style.FILL
            }

            else -> Unit // 其余动效只有底色才成立，叠在照片上没意义
        }
    }

    private fun withAlpha(color: Int, a: Float): Int {
        val alpha = (a.coerceIn(0f, 1f) * 255).toInt()
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
