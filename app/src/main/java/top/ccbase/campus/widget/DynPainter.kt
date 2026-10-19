package top.ccbase.campus.widget

import android.graphics.Bitmap
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
        MATRIX("matrix", "流码", "代码 数字雨 黑客 赛博 流动"),

        // 2026-10-19 用户："我希望小控件主题以及app主题里更多的是缓动的主题，
        // 类似于 wallpaper 里面的那些壁纸" —— 前面那些（落樱/飘雪/流码）是"贴纸在动"，
        // 这几支走的是整屏缓缓流动那一挂，几乎不闪、久看不烦。
        SILK("silk", "绫罗", "丝带 流动 柔和 渐变 极简"),
        DUSK("dusk", "暮色", "天幕 渐变 呼吸 黄昏 安静"),
        RIPPLES("ripples", "涟漪", "水波 圆环 扩散 平静 湖面");

        companion object {
            fun of(id: String): Kind? = entries.firstOrNull { it.id == id }
        }
    }

    private const val DEEP = 0xFF07060C.toInt()

    /**
     * 一个"循环"的秒数 = 帧数 × 帧间隔，由 [WidgetFrames] 在抽帧前设进来。
     *
     * 2026-10-19 用户："动画的重点在缓动，类似 wallpaper 里的动态壁纸"。
     * 小组件做不到 60 帧，只能预渲染 N 帧让桌面轮播 —— 那"像壁纸"的关键就变成两件事：
     *   1. **循环必须闭合**：帧 N-1 接回帧 0 时画面要接得上，否则每 6 秒硬跳一下，
     *      再慢再柔也一眼看出是幻灯片（以前周期是 20~40 秒、只抽 1.28 秒，必然跳）；
     *   2. **每段位移都要有缓动**：统一用 sin/cos（两端速度为 0）驱动，
     *      而不是线性 `t * speed % 1` 那种匀速直线。
     * 所以这里所有周期都按 [cycle] 的整数倍/约数来，配合交叉淡入就是连续的光影流动。
     */
    @Volatile
    var cycle: Float = 6f

    /** 归一化相位：0→1，t 走完 [cycle] 正好回 0（[cycles] 个来回）。 */
    private fun lf(t: Float, cycles: Int, off: Float): Float {
        val v = (t / cycle) * cycles + off
        return v - kotlin.math.floor(v)
    }

    /** 同上，但返回弧度：可直接喂 sin/cos，两端天然是缓动的。 */
    private fun lp(t: Float, cycles: Int, off: Float): Float = lf(t, cycles, off) * 2f * PI.toFloat()

    /**
     * 画一帧。
     *
     * @param t 秒（可以很大，内部只取模）
     * @param accent 强调色（ARGB）
     */
    fun frame(c: Canvas, kind: Kind, t: Float, w: Float, h: Float, accent: Int, dark: Boolean = true) {
        if (w <= 0f || h <= 0f) return
        // 底色掺一点强调色（2026-10-19 用户："很多动态主题根本用不了，用了之后主题就变成纯黑色"）：
        // 原来的 #07060C 在手机上就是纯黑，动效那几团光又都很淡，整屏看着像"没生效"。
        // 掺 18% 的强调色之后，同一个动效立刻有"颜色"可认，和纯黑背景一眼区分开。
        val base = if (dark) mix(DEEP, accent, 0.18f) else 0xFFF4F1EC.toInt()
        // 复用同一个 Paint：原来每帧 new 一个（60fps → 每秒 60 个），白给 GC 添活
        val paint = PAINT.get()
        val rect = RECT.get()
        paint.reset()
        paint.isAntiAlias = true

        // 渐变底色是**每帧都一样**的 → 画一次存成 1/4 分辨率的底图，之后每帧只是一张贴图。
        // 原来每帧都要建 1~3 个 LinearGradient 再涂满整屏，这是"动态主题卡"的主要来源之一。
        fun fillVertical(vararg stops: Pair<Float, Int>) {
            val colors = stops.map { it.second }.toIntArray()
            val pos = stops.map { it.first }.toFloatArray()
            val key = kind.id + "|" + w.toInt() + "x" + h.toInt() + "|" + base + "|" + colors.contentHashCode()
            var bmp = baseCache[key]
            if (bmp == null) {
                bmp = buildBaseBitmap(key, w, h, colors, pos)
                baseCache[key] = bmp
            }
            rect.set(0f, 0f, w, h)
            c.drawBitmap(bmp, null, rect, paint)
        }

        // 性能：以前每团光都拿渐变 shader **涂满整屏**（星云 18 团、光斑 26 团 = 每帧二十多次全屏渐变填充，
        // 中端机上就是"动态主题卡"。改成只画那个圆本身，观感几乎一样，填充面积降两个数量级。
        // 光团：原来每团每帧新建一个 RadialGradient（26 团就是 26 个 shader/帧）。
        // 现在用一张预渲染的白色光晕贴图 + 颜色滤镜，画一个圆就是一次贴图。
        fun glow(cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
            val rr = r.coerceAtLeast(1f)
            val sprite = glowSprite()
            val a = (alpha.coerceIn(0f, 1f) * 255).toInt().coerceIn(1, 255)
            paint.reset()
            paint.isAntiAlias = true
            paint.alpha = a
            paint.colorFilter = tintCache.getOrPut(color) {
                android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
            }
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawBitmap(sprite, null, rect, paint)
            paint.colorFilter = null
            paint.alpha = 255
        }

        when (kind) {
            Kind.AURORA -> {
                fillVertical(0f to base, 0.5f to mix(base, 0xFF0C0A18.toInt(), 0.33f), 1f to base)
                // 周期原来是 21/27/34 秒：4 帧只覆盖 1.28 秒，等于整个周期走了 6% ——
                // 真机上"极光"看着就是一张静态渐变（实测两帧之间最大只差 3% 亮度），
                // 而图库里写的是"会动的小组件背景"。改成 5/7.5/10 秒，1.28 秒能走出 1/4 周期，
                // 幅度也抬了一档，肉眼能看出光带在漂。
                // 三条光带：一条循环走完一次，相位错开 —— 闭合、无跳、全正弦（两端速度为 0）
                val bands = listOf(
                    accent to 0.0f,
                    mix(accent, 0xFF39D3C0.toInt(), 0.55f) to 0.34f,
                    mix(accent, 0xFFFF7BC0.toInt(), 0.5f) to 0.68f,
                )
                bands.forEachIndexed { i, (col, off) ->
                    val p = lp(t, 1, off)
                    glow(
                        w * (0.5f + 0.34f * sin(p)),
                        h * (0.30f + 0.18f * cos(p) + i * 0.17f),
                        w * (0.55f + 0.13f * sin(p * 2f)),
                        col, 0.42f,
                    )
                }
            }

            Kind.STARS -> {
                fillVertical(0f to mix(base, 0xFF0A0A1A.toInt(), 0.44f), 1f to base)
                val rnd = rnd(20261018)
                repeat(140) {
                    val x = rnd.nextFloat() * w
                    val y = rnd.nextFloat() * h * 0.92f
                    val phase = rnd.nextFloat() * 6.28f
                    val b = 0.35f + rnd.nextFloat() * 0.5f
                    val a = (b * (0.55f + 0.45f * sin(lp(t, 6, phase)))).coerceIn(0f, 1f)
                    paint.color = withAlpha(Color.WHITE, a * (if (dark) 1f else 0.5f))
                    c.drawCircle(x, y, 0.7f + rnd.nextFloat() * 1.5f, paint)
                }
                val cx = w * (0.5f + 0.25f * sin(lp(t, 1, 0f)))
                paint.shader = LinearGradient(
                    cx - w * 0.6f, h * 0.75f, cx + w * 0.6f, h * 0.15f,
                    intArrayOf(Color.TRANSPARENT, withAlpha(accent, 0.18f), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP,
                )
                c.drawRect(0f, 0f, w, h, paint)
                paint.shader = null
            }

            Kind.BREATHE -> {
                fillVertical(0f to base, 1f to mix(base, 0xFF0B0913.toInt(), 0.44f))
                val k = 0.5f + 0.5f * sin(lp(t, 1, 0f))
                glow(w * 0.5f, h * 0.34f, w * (0.55f + 0.18f * k), accent, 0.22f + 0.16f * k)
                glow(w * 0.5f, h, w * 0.8f, accent, 0.10f - 0.05f * k)
            }

            Kind.PETALS -> {
                fillVertical(0f to mix(base, 0xFF120B14.toInt(), 0.39f), 1f to base)
                val rnd = rnd(8)
                repeat(80) {
                    val x0 = rnd.nextFloat() * w
                    val drop = 1 + rnd.nextInt(2)          // 一个循环落 1~2 趟，闭合
                    val sway = 18f + rnd.nextFloat() * 40f
                    val phase = rnd.nextFloat()
                    val sz = 4f + rnd.nextFloat() * 6f
                    val y = (lf(t, drop, phase) * 1.15f - 0.08f) * h
                    val x = x0 + sway * sin(lp(t, 1, phase))
                    paint.color = withAlpha(0xFFFFB7D5.toInt(), 0.42f + rnd.nextFloat() * 0.45f)
                    rect.set(x, y, x + sz * 1.6f, y + sz)
                    c.drawOval(rect, paint)
                }
            }

            Kind.SNOW -> {
                fillVertical(0f to mix(base, 0xFF0B1220.toInt(), 0.44f), 1f to base)
                val rnd = rnd(31)
                repeat(90) {
                    val x0 = rnd.nextFloat() * w
                    val drop = 1 + rnd.nextInt(2)
                    val phase = rnd.nextFloat()
                    val sz = 1.8f + rnd.nextFloat() * 3.2f
                    val y = (lf(t, drop, phase) * 1.1f - 0.05f) * h
                    val x = x0 + (10f + rnd.nextFloat() * 20f) * sin(lp(t, 1, phase))
                    paint.color = withAlpha(Color.WHITE, 0.35f + rnd.nextFloat() * 0.5f)
                    c.drawCircle(x, y, sz, paint)
                }
            }

            Kind.RAIN -> {
                fillVertical(0f to mix(base, 0xFF0A0F14.toInt(), 0.39f), 1f to base)
                paint.strokeWidth = Math.max(1f, w * 0.0016f)
                val rnd = rnd(77)
                repeat(110) {
                    val x0 = rnd.nextFloat() * (w * 1.2f) - w * 0.1f
                    val drop = 2 + rnd.nextInt(2)
                    val len = h * (0.03f + rnd.nextFloat() * 0.05f)
                    val y = (lf(t, drop, rnd.nextFloat()) * 1.2f - 0.1f) * h
                    paint.color = withAlpha(0xFFBFD8E8.toInt(), 0.10f + rnd.nextFloat() * 0.16f)
                    c.drawLine(x0, y, x0 - len * 0.25f, y + len, paint)
                }
            }

            Kind.FIREFLIES -> {
                fillVertical(0f to mix(base, 0xFF0A120C.toInt(), 0.41f), 1f to base)
                val rnd = rnd(2026)
                repeat(40) {
                    val bx = rnd.nextFloat() * w
                    val by = rnd.nextFloat() * h
                    val ax = 20f + rnd.nextFloat() * 60f
                    val ay = 15f + rnd.nextFloat() * 45f
                    val ph = rnd.nextFloat() * 6.28f
                    val x = bx + ax * sin(lp(t, 1, ph))
                    val y = by + ay * cos(lp(t, 1, ph * 0.5f))
                    val a = (0.35f + 0.65f * (0.5f + 0.5f * sin(lp(t, 2, ph)))).coerceIn(0f, 1f)
                    glow(x, y, 26f + 22f * a, 0xFFB7FF8A.toInt(), 0.30f * a)
                    paint.color = withAlpha(0xFFE8FFC8.toInt(), a)
                    c.drawCircle(x, y, 1.8f, paint)
                }
            }

            // ── 缓动三支（2026-10-19）────────────────────────────────────
            // 共同点：周期 20~40 秒、只用柔光、没有硬边、没有闪烁。
            // 参照的是"动态壁纸"那种整屏在呼吸的感觉，而不是往画面上撒东西。
            Kind.SILK -> {
                fillVertical(
                    0f to base,
                    0.45f to mix(base, accent, 0.14f),
                    1f to mix(base, 0xFF05040A.toInt(), 0.5f),
                )
                // 四条丝带：相位错开，横向缓慢蛇行
                for (i in 0 until 4) {
                    val ph = i * 0.25f
                    val y = h * (0.22f + i * 0.19f) + h * 0.06f * sin(lp(t, 1, ph))
                    val x = w * (0.5f + 0.30f * sin(lp(t, 1, ph + 0.33f)))
                    glow(x, y, w * 0.62f, mix(accent, 0xFFF3E7FF.toInt(), 0.25f + i * 0.12f), 0.16f)
                    glow(w - x, h - y, w * 0.5f, mix(accent, 0xFF9BE7FF.toInt(), 0.3f), 0.10f)
                }
            }

            Kind.DUSK -> {
                fillVertical(
                    0f to mix(base, 0xFF120B1E.toInt(), 0.55f),
                    0.62f to mix(base, accent, 0.26f),
                    1f to mix(base, 0xFF03020A.toInt(), 0.45f),
                )
                // 地平线一团慢光，30 秒一呼一吸
                val breathe = 0.5f + 0.5f * sin(lp(t, 1, 0f))
                glow(w * 0.5f, h * (0.60f + 0.03f * breathe), w * (0.55f + 0.08f * breathe),
                    mix(accent, 0xFFFFC98A.toInt(), 0.45f), 0.16f + 0.10f * breathe)
                // 高空一层很淡的冷色，压住整屏不让它发灰
                glow(w * 0.25f, h * 0.18f, w * 0.5f, mix(accent, 0xFF8FA8FF.toInt(), 0.4f), 0.08f)
            }

            Kind.RIPPLES -> {
                fillVertical(0f to mix(base, 0xFF061018.toInt(), 0.4f), 1f to base)
                // 同心圆一圈圈推开，各自 24 秒一个周期、错开起跑
                paint.style = Paint.Style.STROKE
                val cx = w * (0.5f + 0.04f * sin(lp(t, 1, 0f)))
                val cy = h * (0.55f + 0.03f * cos(lp(t, 1, 0.25f)))
                for (i in 0 until 6) {
                    val phase = lf(t, 1, i / 6f)
                    val r = (0.08f + phase * 0.75f) * Math.max(w, h)
                    val a = (1f - phase) * 0.16f
                    paint.strokeWidth = Math.max(1.2f, h * 0.0045f)
                    paint.color = withAlpha(mix(accent, 0xFFA8E6FF.toInt(), 0.35f), a)
                    rect.set(cx - r, cy - r, cx + r, cy + r)
                    c.drawOval(rect, paint)
                }
                paint.style = Paint.Style.FILL
                glow(cx, cy, w * 0.35f, mix(accent, 0xFFBFE9FF.toInt(), 0.4f), 0.10f)
            }

            Kind.WAVES -> {
                fillVertical(0f to mix(base, 0xFF07121C.toInt(), 0.44f), 1f to base)
                val rows = 6
                paint.style = Paint.Style.STROKE
                for (i in 0 until rows) {
                    val yBase = h * (0.30f + i * 0.11f)
                    val amp = h * (0.020f + i * 0.006f)
                    val cycles = 1 + i % 2
                    paint.strokeWidth = Math.max(1.2f, h * 0.004f)
                    paint.color = withAlpha(mix(accent, 0xFF7FD4FF.toInt(), 0.4f), 0.30f - i * 0.03f)
                    var x = 0f
                    val step = Math.max(4f, w / 120f)
                    val path = android.graphics.Path()
                    var first = true
                    while (x <= w) {
                        val y = yBase + amp * sin((x / w) * 3.2f + lp(t, cycles, i * 0.08f))
                        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                        x += step
                    }
                    c.drawPath(path, paint)
                }
                paint.style = Paint.Style.FILL
            }

            Kind.BOKEH -> {
                fillVertical(0f to mix(base, 0xFF140F0A.toInt(), 0.41f), 1f to base)
                val rnd = rnd(404)
                repeat(26) {
                    val x = rnd.nextFloat() * w
                    val y = rnd.nextFloat() * h
                    val r = w * (0.04f + rnd.nextFloat() * 0.12f)
                    val ph = rnd.nextFloat() * 6.28f
                    val a = 0.08f + 0.10f * (0.5f + 0.5f * sin(lp(t, 1, ph)))
                    val col = if (rnd.nextBoolean()) accent else 0xFFFFC46B.toInt()
                    glow(x, y, r, col, a)
                }
            }

            Kind.NEBULA -> {
                fillVertical(0f to mix(base, 0xFF0B0716.toInt(), 0.5f), 1f to base)
                val rnd = rnd(99)
                repeat(18) {
                    val ph = rnd.nextFloat() * 6.28f
                    val x = w * (0.2f + 0.6f * rnd.nextFloat()) + w * 0.08f * sin(lp(t, 1, ph))
                    val y = h * rnd.nextFloat() + h * 0.05f * cos(lp(t, 1, ph * 0.5f))
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
                fillVertical(0f to mix(base, 0xFF08131C.toInt(), 0.44f), 1f to base)
                val rnd = rnd(11)
                paint.style = Paint.Style.STROKE
                repeat(26) {
                    val x0 = rnd.nextFloat() * w
                    val rise = 1 + rnd.nextInt(2)
                    val r = 6f + rnd.nextFloat() * 26f
                    val ph = rnd.nextFloat()
                    val y = h - lf(t, rise, ph) * 1.1f * h
                    val x = x0 + 12f * sin(lp(t, 1, ph))
                    paint.strokeWidth = Math.max(1.2f, r * 0.10f)
                    paint.color = withAlpha(0xFFBFE9FF.toInt(), 0.22f + rnd.nextFloat() * 0.20f)
                    c.drawCircle(x, y, r, paint)
                }
                paint.style = Paint.Style.FILL
            }

            Kind.MATRIX -> {
                fillVertical(0f to mix(base, 0xFF04120A.toInt(), 0.5f), 1f to base)
                paint.strokeWidth = Math.max(1.5f, w * 0.002f)
                val cols = Math.max(8, (w / Math.max(18f, w / 30f)).toInt())
                val colW = w / cols
                val rnd = rnd(20261018)
                for (i in 0 until cols) {
                    val drop = 2 + rnd.nextInt(2)
                    val len = h * (0.10f + rnd.nextFloat() * 0.25f)
                    val off = rnd.nextFloat()
                    val y = (lf(t, drop, off) * 1.4f - 0.2f) * (h + len) - len
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
        val paint = PAINT.get()
        paint.reset()
        paint.isAntiAlias = true
        val rect = RECT.get()
        val a0 = strength.coerceIn(0f, 1f)

        when (kind) {
            Kind.PETALS -> {
                val rnd = rnd(8)
                repeat(44) {
                    val x0 = rnd.nextFloat() * w
                    val speed = 0.10f + rnd.nextFloat() * 0.22f
                    val sway = 18f + rnd.nextFloat() * 40f
                    val phase = rnd.nextFloat() * 6.28f
                    val sz = 3f + rnd.nextFloat() * 5f
                    val y = ((t * speed + rnd.nextFloat()) % 1.15f) * h - h * 0.08f
                    val x = x0 + sway * sin(t * 0.9f + phase)
                    paint.color = withAlpha(0xFFFFC0D8.toInt(), (0.25f + rnd.nextFloat() * 0.45f) * a0)
                    rect.set(x, y, x + sz * 1.7f, y + sz)
                    c.drawOval(rect, paint)
                }
            }

            Kind.SNOW -> {
                val rnd = rnd(31)
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
                val rnd = rnd(404)
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
                val rnd = rnd(20261018)
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
                val rnd = rnd(2026)
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
                val rnd = rnd(11)
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

    // ── 每帧都要用的对象，全部复用 ─────────────────────────────────────
    // 60fps 下每帧 new Paint / RectF / Gradient，等于每秒给 GC 递几十个对象；
    // 中端机上一卡就是这么来的。
    // ThreadLocal：小组件抽帧在 IO 线程跑、App 背景在 UI 线程跑，
    // 共用同一个 Paint/RectF 会互相踩状态。
    private val PAINT = ThreadLocal.withInitial { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    private val RECT = ThreadLocal.withInitial { android.graphics.RectF() }
    private val RND = ThreadLocal.withInitial { java.util.Random(0L) }

    /** 每帧重置种子 → 参数序列和原来 `Random(seed)` 完全一致，但不新建对象。 */
    private fun rnd(seed: Int): java.util.Random = RND.get().also { it.setSeed(seed.toLong()) }

    private val tintCache = HashMap<Int, android.graphics.PorterDuffColorFilter>(8)

    /** 渐变底色缓存：一张 1/4 分辨率的底图，最多留 3 份（主题色/尺寸变了就重建）。 */
    private val baseCache = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 3
    }

    private fun buildBaseBitmap(
        key: String, w: Float, h: Float, colors: IntArray, pos: FloatArray,
    ): Bitmap {
        val s = 0.25f
        val bw = (w * s).toInt().coerceAtLeast(1)
        val bh = (h * s).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, bh.toFloat(), colors, pos, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
        return bmp
    }

    /** 白色光晕贴图（中心实、边缘透），用颜色滤镜染成任意色。 */
    private var glowBmp: Bitmap? = null
    private fun glowSprite(): Bitmap {
        glowBmp?.let { return it }
        val n = 128
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            n / 2f, n / 2f, n / 2f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(n / 2f, n / 2f, n / 2f, p)
        glowBmp = bmp
        return bmp
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
