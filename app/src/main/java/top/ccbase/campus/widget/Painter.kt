package top.ccbase.campus.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.LruCache
import kotlin.math.max
import kotlin.random.Random

/**
 * 画完主体之后的三道收尾工序 —— 暗角、颗粒、去色阶。
 *
 * ## 为什么单独拿出来做
 * 2026-10-20 用户：「桌面小控件使用动态主题时总是出现不自然的闪烁」+「图库要比 wallpaper 还优质」。
 * 两句话指向同一件事：**画面底子不够，靠动画补不回来**。
 * - 渐变一旦落到 8bit，暗部就会出现色阶台阶（banding）。台阶在每帧的位置随机漂，
 *   低帧率下看到的就是"整块画面在闪" —— 这比动得不顺更刺眼。
 * - 颗粒是低帧率动画唯一能借的"抖动源"：它把硬边磨软，也把色阶打碎。
 *   但颗粒**必须在屏幕空间固定**（同一套主题 40 帧用同一张噪点图），
 *   随机噪点每帧都变，5fps 下就是"沙沙闪"，比不加更糟。
 *
 * 所以这里三件事都按"每帧完全一样"实现：暗角只跟尺寸有关，颗粒是一次生成、反复贴的位图，
 * 抖动是同一条 Bayer 表。换帧只换主体，收尾永远一致。
 */
object Painter {

    /** 颗粒图按尺寸缓存：同一套主题 40 帧共用一张，屏上位置永远不动。 */
    private val grains = LruCache<Long, Bitmap>(4)

    /**
     * 暗角：四角压暗 8~14%，中心不动。
     *
     * 用二层径向渐变而不是"一张黑图"：中心 78% 半径内完全透明，边缘 12% 强度，
     * 这样中间的文字区域一点不受影响，只有四角呼吸感。
     */
    fun vignette(c: Canvas, w: Int, h: Int, strength: Float = 0.12f) {
        if (w <= 0 || h <= 0) return
        val r = max(w, h) * 0.62f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w / 2f, h / 2f, r,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.argb((strength * 120).toInt(), 0, 0, 0),
                    Color.argb((strength * 255).toInt(), 0, 0, 0),
                ),
                floatArrayOf(0f, 0.62f, 0.88f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    /**
     * 颗粒：1.5~2.5% 强度的静态噪点。
     *
     * 固定种子 → 同一尺寸永远同一张噪点图；换帧不重算，所以不会"沙沙闪"。
     */
    fun grain(c: Canvas, w: Int, h: Int, strength: Float = 0.022f) {
        if (w <= 0 || h <= 0) return
        val key = (w.toLong() shl 20) or h.toLong()
        var bmp = grains.get(key)
        if (bmp == null) {
            val n = IntArray(w * h)
            val rnd = Random(0x5EED_2026)
            for (i in n.indices) {
                // 一半提亮一半压暗，均值为 0，不会整体变灰
                val v = (rnd.nextFloat() * 2f - 1f)
                val a = (kotlin.math.abs(v) * 255f * strength).toInt().coerceIn(0, 255)
                n[i] = if (v > 0) Color.argb(a, 255, 255, 255) else Color.argb(a, 0, 0, 0)
            }
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(n, 0, w, 0, 0, w, h)
            grains.put(key, bmp)
        }
        c.drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /**
     * 光溢出（bloom）：亮部周围淡淡散开一层。
     *
     * 做法是"降采样 → 升采样 → SCREEN 叠加"：真做阈值提取要多一次全屏 pAss，这里用
     * 1/6 缩放的自身当"亮部代理"已经够用 —— 大片暗部缩下去还是暗的，叠上去几乎不动；
     * 亮带/光团缩下去会被抹开，叠回去就是柔和的光晕。这是"廉价感"和"贵感"之间性价比最高的一步。
     */
    fun bloom(c: Canvas, src: Bitmap, strength: Float = 0.20f) {
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return
        // ⚠️ 必须先从**副本**缩放：src 就是 c 正在画的位图（bmp），
        // 直接把画布自己的位图 createScaledBitmap 再 drawBitmap 回去，等于一边读一边写同一块像素，
        // 真机上结果是整帧变成透明 —— 用户 2026-10-20 截图「桌面卡片透明了」就是这么来的。
        val base = src.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val small = Bitmap.createScaledBitmap(base, (w / 6).coerceAtLeast(4), (h / 6).coerceAtLeast(4), true)
        val up = Bitmap.createScaledBitmap(small, w, h, true)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (strength * 255).toInt().coerceIn(0, 255)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
        }
        c.drawBitmap(up, 0f, 0f, paint)
        if (small !== up) small.recycle()
        up.recycle()
        base.recycle()
    }

    /**
     * 柔焦：把整帧"略糊一层"再叠回去。
     *
     * 低帧率下最扎眼的是硬边（粒子、光带边缘）——它们一帧一动，读成"贴纸在瞬移"。
     * 叠一层 1/3 降采样的自身，边缘变软，眼睛读成"焦外光斑"，这是壁纸感的关键之一。
     */
    fun soften(c: Canvas, src: Bitmap, strength: Float = 0.35f) {
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return
        // 同 bloom：先复制再缩放，别拿画布自己的位图当源
        val base = src.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val small = Bitmap.createScaledBitmap(base, (w / 3).coerceAtLeast(4), (h / 3).coerceAtLeast(4), true)
        val up = Bitmap.createScaledBitmap(small, w, h, true)
        c.drawBitmap(up, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (strength * 255).toInt().coerceIn(0, 255)
        })
        if (small !== up) small.recycle()
        up.recycle()
        base.recycle()
    }

    /**
     * 文字底幕（画进位图，不用布局里的矩形 View —— 那个会把圆角填平）。
     * 2026-10-20 用户截图：本地素材是白底线稿，白字直接看不见。
     * 亮度越高的素材压得越狠：亮底 0xB0 顶部、暗底 0x66。
     */
    fun scrim(c: Canvas, w: Int, h: Int, bright: Boolean) {
        if (w <= 0 || h <= 0) return
        val top = if (bright) 0x99000000.toInt() else 0x4D000000
        val bottom = if (bright) 0x4D000000 else 0x14000000
        val p = Paint()
        p.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(top, top / 2, bottom),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    /** 抽样估亮度（0..255）：用来决定底幕压多重 */
    fun brightness(b: Bitmap): Int {
        var sum = 0L
        var n = 0
        val step = (b.width / 12).coerceAtLeast(1)
        val stepY = (b.height / 12).coerceAtLeast(1)
        var y = stepY / 2
        while (y < b.height) {
            var x = step / 2
            while (x < b.width) {
                val px = b.getPixel(x, y)
                sum += (((px shr 16) and 0xFF) * 77 + ((px shr 8) and 0xFF) * 150 + (px and 0xFF) * 29) shr 8
                n++
                x += step
            }
            y += stepY
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    private val BAYER = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5,
    )

    /**
     * 4×4 有序抖动：每个颜色通道 ±1/2 LSB。
     *
     * 8bit 输出处理不了的深色渐变，靠这一步把色阶台阶拆成肉眼看不见的网点。
     * 只动 RGB，alpha 一律不碰 —— 圆角靠 alpha，动了就会出毛边。
     */
    fun dither(b: Bitmap) {
        val w = b.width
        val h = b.height
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val p = px[i]
                val a = p ushr 24
                if (a == 0) continue
                // 一半像素 +1、一半 -1（均值 0，画面不变亮也不变暗），把台阶拆成网点
                val bayer = BAYER[(y and 3) * 4 + (x and 3)]
                val add = if (bayer < 8) 1 else -1
                fun ch(v: Int) = (v + add).coerceIn(0, 255)
                px[i] = (a shl 24) or (ch((p shr 16) and 0xFF) shl 16) or
                    (ch((p shr 8) and 0xFF) shl 8) or ch(p and 0xFF)
            }
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }
}
