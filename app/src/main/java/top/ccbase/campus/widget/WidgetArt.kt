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

    /**
     * 圆角大小：默认就要是**一眼看得出是圆角矩形**（用户 2026-10-18："小部件我希望默认为圆角矩形"）。
     *
     * 原来是短边 13%、夹 16~34dp —— 4×2 的卡在小米桌面上算出来才 20dp 出头，配上导出位图后
     * 看起来接近直角。现在取短边 20%、夹 22~46dp，和系统图标组的圆角一个量级。
     *
     * 注意：这个值只对"画布的圆角"有意义；图片主题/动态帧是**位图**，必须再走 [rounded]。
     */
    fun radiusFor(h: Int, density: Float): Float {
        val byHeight = h * 0.20f
        return byHeight.coerceIn(22f * density, 46f * density)
    }

    /**
     * 把一张**矩形**位图裁成圆角（图片主题、动态主题的帧都用它）。
     *
     * 为什么必须做：桌面上的小组件是 `RemoteViews` + `ImageView`，没有任何"圆角"属性可用
     * （`clipToOutline` / `ShapeableImageView` 都不在允许列表里）。所以圆角只能**画进位图**里，
     * 位图四角必须是透明的 —— 否则桌面上就是一块直角矩形（2026-10-18 用户截图就是这个）。
     *
     * 圆角位图 + **压在里面的暗蒙版**（照片主题用）。
     *
     * 蒙版不能交给布局里那层 `widget_scrim`：那是一整块矩形 View，盖上去正好把四个圆角
     * 重新填成直角（2026-10-18 用户："没有做到完全圆角"）。所以画进位图、跟着圆角一起裁。
     */
    /**
     * 圆角 + 蒙版一次画成一张新位图。
     *
     * **不要**复用 [rounded] 的缓存：那是共享位图，每次再往上叠一层渐变会越刷越黑，
     * 而且同尺寸的不同帧会命中同一个 key → 轮播六帧长得一模一样。
     */
    fun roundedWithScrim(src: Bitmap, radiusPx: Float, topAlpha: Int, bottomAlpha: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val path = android.graphics.Path().apply {
            addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radiusPx, radiusPx, android.graphics.Path.Direction.CW)
        }
        c.save()
        c.clipPath(path)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val paint = Paint()
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(topAlpha, bottomAlpha), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        c.restore()
        return out
    }

    fun rounded(src: Bitmap, radiusPx: Float): Bitmap {
        if (radiusPx <= 0f) return src
        val key = "r" + System.identityHashCode(src) + radiusPx.toInt()
        cache.get(key)?.let { return it }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = android.graphics.BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        c.drawRoundRect(
            RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
            radiusPx, radiusPx, paint,
        )
        cache.put(key, out)
        return out
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
