package top.ccbase.campus.widget

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 低频大形渲染内核（CPU 版 fbm 流场）。
 *
 * ## 为什么再造一个画笔
 * `DynPainter` 的画法是「竖向渐变 + 几团 glow + 一批粒子」。粒子在 60fps 下还行，
 * 到桌面 5fps 就是"贴纸在瞬移"；渐变 + 一团 glow 缩成缩略图更是六款一个样
 * （2026-10-20 自查对照图：极光/星云/丝绸/呼吸四款几乎分不出来）。
 *
 * 壁纸之所以"贵"，靠的是**多尺度结构**：一层极低频的色场定基调，一层中频域扭曲定形态，
 * 一层高频噪声定纹理，再叠光溢出与暗角。这一套用 fbm + domain warp 画最直接，
 * 而且天然满足低帧率的要求 —— 画面里没有硬边、没有小元素，动起来只像在呼吸。
 *
 * ## 闭合
 * 所有随时间变化的地方只走 `sin/cos(2π · k · t/T)`，`k` 是整数（见 [Preset]），
 * 所以第 N-1 帧接回第 0 帧时相位差恰好是 2π 的整数倍，**严格闭合**，不需要额外淡出。
 *
 * 尺寸按"每帧 13 万像素"预算走，一次全算完约 3~5ms/帧（真机），40 帧不到 0.2s。
 */
object FlowPainter {

    fun supports(kind: DynPainter.Kind): Boolean = when (kind) {
        DynPainter.Kind.AURORA, DynPainter.Kind.NEBULA, DynPainter.Kind.SILK,
        DynPainter.Kind.BREATHE, DynPainter.Kind.WAVES, DynPainter.Kind.STARS,
        -> true
        else -> false
    }

    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h shr 13)) * 1274126177
        return ((h xor (h shr 16)) and 0x7FFFFFFF) / 2147483647f
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    private fun noise(x: Float, y: Float): Float {
        val xi = floor(x)
        val yi = floor(y)
        val xf = x - xi
        val yf = y - yi
        val ix = xi.toInt()
        val iy = yi.toInt()
        val u = smooth(xf)
        val v = smooth(yf)
        val a = hash(ix, iy)
        val b = hash(ix + 1, iy)
        val c = hash(ix, iy + 1)
        val d = hash(ix + 1, iy + 1)
        val ab = a + (b - a) * u
        val cd = c + (d - c) * u
        return ab + (cd - ab) * v
    }

    private fun fbm(x: Float, y: Float, octaves: Int = 4, gain: Float = 0.5f): Float {
        var v = 0f
        var amp = 0.5f
        var px = x
        var py = y
        for (i in 0 until octaves) {
            v += amp * noise(px, py)
            px *= 2.03f
            py *= 2.03f
            amp *= gain
        }
        return v
    }

    /** ridged：把 fbm 折成"丝状/云丝"，星云和高光带用它 */
    private fun ridged(x: Float, y: Float, octaves: Int = 4): Float {
        var v = 0f
        var amp = 0.5f
        var px = x
        var py = y
        for (i in 0 until octaves) {
            val n = 1f - abs(noise(px, py) * 2f - 1f)
            v += amp * n * n
            px *= 2.07f
            py *= 2.07f
            amp *= 0.5f
        }
        return v
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = clamp01((x - e0) / (e1 - e0 + 1e-6f))
        return t * t * (3f - 2f * t)
    }

    private fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
    private fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
    private fun b(c: Int) = (c and 0xFF) / 255f
    private fun pack(r: Float, g: Float, b: Float, a: Float = 1f): Int {
        val ri = (clamp01(r) * 255f + 0.5f).toInt()
        val gi = (clamp01(g) * 255f + 0.5f).toInt()
        val bi = (clamp01(b) * 255f + 0.5f).toInt()
        val ai = (clamp01(a) * 255f + 0.5f).toInt()
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /**
     * 画一帧。
     *
     * @param t 秒（帧序号 × 步长）
     * @param cycle 循环长度 T —— 所有相位都对它取整
     */
    fun frame(
        kind: DynPainter.Kind,
        t: Float,
        w: Int,
        h: Int,
        accent: Int,
        cycle: Float,
    ): Bitmap {
        val px = IntArray(w * h)
        val aspect = w.toFloat() / h.toFloat()
        val T = cycle.coerceAtLeast(1f)
        val tau = 2f * PI.toFloat()

        // 整数倍频：1 / 2 / 3 / 5 分散开，任何两个元素都不会"一起回到起点"
        fun ph(k: Int, off: Float = 0f) = tau * (k * (t / T) + off)

        // 辅助色：每款叠一个"陪衬色"，靠低频 field 在三色之间过渡。
        // 2026-10-20 对照图问题：「六款几乎一个紫」—— 单靠 accent 一个颜色怎么调都是同一张。
        val sec = when (kind) {
            DynPainter.Kind.AURORA -> 0xFF39D3C0.toInt()   // 青绿
            DynPainter.Kind.NEBULA -> 0xFFFF7BC0.toInt()   // 品红
            DynPainter.Kind.SILK -> 0xFF8FB8FF.toInt()     // 冷蓝
            DynPainter.Kind.WAVES -> 0xFF7FD8FF.toInt()    // 青
            DynPainter.Kind.BREATHE -> 0xFFFFC98A.toInt()  // 暖橙
            else -> 0xFFB39DFF.toInt()                      // 淡紫
        }
        val ar0 = r(accent)
        val ag0 = g(accent)
        val ab0 = b(accent)

        // 各款的骨架参数（scale 决定特征尺寸：越小越"大块"）
        val scale: Float
        val warpAmt: Float
        val octaves: Int
        when (kind) {
            DynPainter.Kind.AURORA -> { scale = 1.6f; warpAmt = 1.15f; octaves = 4 }
            DynPainter.Kind.NEBULA -> { scale = 2.1f; warpAmt = 1.45f; octaves = 5 }
            DynPainter.Kind.SILK -> { scale = 2.6f; warpAmt = 0.85f; octaves = 4 }
            DynPainter.Kind.BREATHE -> { scale = 1.1f; warpAmt = 0.55f; octaves = 3 }
            DynPainter.Kind.STARS -> { scale = 1.8f; warpAmt = 0.9f; octaves = 4 }
            else -> { scale = 1.7f; warpAmt = 1.0f; octaves = 4 }   // WAVES
        }

        val driftA = ph(1)                       // 整体漂移：一圈一个循环
        val driftB = ph(1, 0.37f)
        val bulge = ph(2)                        // 中频域扭曲
        val ripple = ph(3)                       // 细纹
        val shimmer = ph(5)                      // 高光呼吸

        val sr0 = r(sec)
        val sg0 = g(sec)
        val sb0 = b(sec)

        for (y in 0 until h) {
            val v = y.toFloat() / h
            for (x in 0 until w) {
                val u = x.toFloat() / w
                // 归一化坐标：保持长宽比，噪声不会被拉扁
                val nx = (u - 0.5f) * aspect * scale
                val ny = (v - 0.5f) * scale

                // domain warp：用两层噪声把坐标揉一下，边界自然卷曲
                val wx = fbm(nx * 1.3f + cos(driftA) * 0.7f, ny * 1.3f + sin(driftA) * 0.7f, 3) - 0.5f
                val wy = fbm(nx * 1.3f + sin(driftB) * 0.7f + 5.2f, ny * 1.3f + cos(driftB) * 0.7f + 1.3f, 3) - 0.5f
                val qx = nx + wx * warpAmt
                val qy = ny + wy * warpAmt

                val field = fbm(qx, qy, octaves)          // 0..~0.9
                val fil = ridged(qx * 1.7f, qy * 1.7f)    // 丝状层

                // 低频混色：画面左半偏 accent、右半偏 sec，色相自然过渡（不再"一片紫"）
                val mixF = smoothstep(0.18f, 0.86f, field * 0.75f + u * 0.35f)
                val ar = lerp(ar0, sr0, mixF * 0.65f)
                val ag = lerp(ag0, sg0, mixF * 0.65f)
                val ab = lerp(ab0, sb0, mixF * 0.65f)

                // 底：深色，带一点强调色的呼吸
                var baseR = lerp(0.045f, ar * 0.30f, field * 0.9f)
                var baseG = lerp(0.040f, ag * 0.30f, field * 0.9f)
                var baseB = lerp(0.075f, ab * 0.34f, field * 0.9f)

                var light = 0f
                when (kind) {
                    DynPainter.Kind.AURORA -> {
                        // 三条斜向彩带：y 方向叠 sin，带域被 field 拉弯
                        val bandC = v - 0.5f + (u - 0.5f) * 0.26f + (field - 0.45f) * 0.55f
                        // 5.2 而不是 9：带太密就成了斑马纹（第一版对照图就是这样）
                        // 相位里只许出现整数倍频（sin(ripple) 而不是 sin(ripple*0.8)）：
                        // 0.8 这种非整数系数会让"循环长度"变短，接缝处直接跳一下
                        val s = sin(bandC * 3.4f + sin(ripple) * 0.5f)
                        val core = smoothstep(0.80f, 1.0f, abs(s))   // 细亮芯，只有靠近峰值才亮
                        val wide = smoothstep(0.15f, 0.95f, abs(s))
                        light = core * 0.30f + wide * 0.22f + fil * 0.12f
                    }
                    DynPainter.Kind.NEBULA -> {
                        // 星云：丝状层 + 中心外推的密度，越靠中心越亮
                        val dx = (u - (0.5f + 0.06f * cos(bulge))) * aspect
                        val dy = v - (0.48f + 0.05f * sin(bulge))
                        val d = sqrt(dx * dx + dy * dy)
                        val halo = (1f - smoothstep(0.05f, 0.95f, d))
                        light = halo * (fil * 1.25f + field * 0.35f)
                    }
                    DynPainter.Kind.SILK -> {
                        // 丝绸褶：把 domain warp 的相位当布纹，亮脊沿褶皱走
                        // 褶皱要少而宽：第一版 1.55 的密度在大屏上是"指纹"，缩到 0.9 才像布的起伏
                        val fold = sin((qx * 0.9f + qy * 0.55f) * PI.toFloat() + cos(driftA) * 0.9f)
                        val ridge = 1f - abs(fold)
                        val width = 0.34f + 0.30f * field      // 褶宽随场变化，边缘不至于等距
                        light = smoothstep(1f - width, 1f, ridge) * (0.18f + fil * 0.16f)
                    }
                    DynPainter.Kind.BREATHE -> {
                        // 单光源呼吸：一团柔光缓慢胀缩，别的都不动
                        val dx = (u - 0.5f) * aspect
                        val dy = v - 0.5f
                        val d = sqrt(dx * dx + dy * dy)
                        val breathe = 0.42f + 0.05f * sin(driftA)
                        light = (1f - smoothstep(0f, breathe, d)).pow(1.8f) * (0.52f + 0.10f * sin(shimmer)) +
                            field * 0.08f
                    }
                    DynPainter.Kind.WAVES -> {
                        // 潮汐：水平带，带间距不均匀（靠 field 扰），亮线细
                        val line = sin((v * 2.6f + (field - 0.45f) * 1.4f) * PI.toFloat() + sin(driftA) * 0.4f)
                        val crest = smoothstep(0.93f, 1.0f, abs(line))
                        // 上淡下浓：像远处海面上的光脊，不是等距条纹
                        light = crest * (0.06f + 0.13f * v) + fil * 0.09f
                    }
                    else -> {
                        // STARS：低频色场 + 少量细亮点（不闪，只随相位缓慢明暗）
                        light = field * 0.35f + fil * 0.25f
                    }
                }

                // 强调色上色：highlight 往暖白偏，暗部往深蓝偏
                // 亮部只"提亮"，不冲纯白（原来 0.55 的混白比例让整张图发灰发脏）
                val hiR = lerp(ar, 1f, 0.30f)
                val hiG = lerp(ag, 0.99f, 0.24f)
                val hiB = lerp(ab, 0.97f, 0.16f)
                var rr = baseR + (hiR - baseR) * clamp01(light * 1.15f)
                var gg = baseG + (hiG - baseG) * clamp01(light * 1.15f)
                var bb = baseB + (hiB - baseB) * clamp01(light * 1.15f)

                // 内发光：亮芯附近再抬一点，避免"平涂"
                val glow = clamp01(light) * 0.10f
                rr += glow * ar
                gg += glow * ag
                bb += glow * ab

                px[y * w + x] = pack(rr, gg, bb)
            }
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }
}
