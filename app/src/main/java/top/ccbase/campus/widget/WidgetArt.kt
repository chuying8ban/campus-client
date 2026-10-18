package top.ccbase.campus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache

/**
 * 小组件的底图 —— 用 Canvas 现画，而不是塞一张张固定图片。
 *
 * 为什么这么做（2026-10-18 用户："太简陋了"）：原来的纯色主题就是一块 `#0B0910` 的圆角矩形，
 * 在桌面上等于一条黑边，什么质感都没有。RemoteViews 又不能写自定义 View，
 * 所以把"好看的底"**画成位图**贴进 ImageView —— 渐变、柔光、描边、圆角全都能做，
 * 而且颜色跟着主题走（换色相时底图也一起变，不用再堆几十张图片）。
 *
 * 缓存：同一尺寸 + 同一套色只画一次。位图要过 binder，尺寸按桌面实际大小算（不按设计稿），
 * 一张 4×2 的卡通常 1000×480 上下、几十 KB，比随包图片还小。
 */
object WidgetArt {

    private val cache = LruCache<String, Bitmap>(12)

    /**
     * @param accent 主题强调色（柔光与描边用它）
     * @param base   底色（深层到底色做轻微渐变）
     */
    fun background(w: Int, h: Int, base: Int, accent: Int, light: Boolean, radiusPx: Float): Bitmap {
        val key = "$w$h${base}${accent}$light${radiusPx.toInt()}"
        cache.get(key)?.let { return it }

        val ww = w.coerceAtLeast(1)
        val hh = h.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // ① 竖直渐变：顶部比底色亮一点点（看得出"有光从上面来"），底部回到深色
        val top = blend(base, accent, if (light) 0.05f else 0.16f, alphaKeep = 1f)
        val bottom = blend(base, 0xFF000000.toInt(), if (light) 0.03f else 0.35f, alphaKeep = 1f)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, ww * 0.35f, hh.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        val rect = RectF(0f, 0f, ww.toFloat(), hh.toFloat())
        c.drawRoundRect(rect, radiusPx, radiusPx, body)

        // ② 左上角一团柔光：卡片的"眼神光"，没有它整块是平的
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                ww * 0.12f, -hh * 0.15f, ww * 0.85f,
                intArrayOf(withAlpha(accent, if (light) 0.28f else 0.42f), AColor.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRoundRect(rect, radiusPx, radiusPx, glow)

        // ③ 细描边：让卡片从壁纸上"立"起来；浅色主题用暗描边，深色用亮描边
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Math.max(1.5f, hh * 0.004f)
            color = if (light) 0x14000000 else 0x24FFFFFF
        }
        c.drawRoundRect(
            RectF(stroke.strokeWidth / 2, stroke.strokeWidth / 2, ww - stroke.strokeWidth / 2, hh - stroke.strokeWidth / 2),
            radiusPx, radiusPx, stroke,
        )

        cache.put(key, bmp)
        return bmp
    }

    /** 圆角大小：桌面小组件的通用观感，取短边的 ~13%，再夹在 16~34dp 之间。 */
    fun radiusFor(h: Int, density: Float): Float {
        val byHeight = h * 0.13f
        return byHeight.coerceIn(16f * density, 34f * density)
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun blend(a: Int, b: Int, t: Float, alphaKeep: Float = 1f): Int {
        fun ch(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * t).toInt().coerceIn(0, 255)
        }
        val alpha = ((a ushr 24) and 0xFF) * alphaKeep / 1f
        return ((alpha.toInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
    }

    fun clear() = cache.evictAll()
}
