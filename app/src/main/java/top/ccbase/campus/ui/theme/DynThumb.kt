package top.ccbase.campus.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import top.ccbase.campus.widget.DynPainter

/**
 * 动态主题的**静态缩略图**（2026-10-19 用户："每次点动态主题都要等半天，我希望能够快速出现"）。
 *
 * 之前库里的每一格都是活的 [DynamicBackground]：一屏 6~8 个 Canvas 各自 60fps 跑动效，
 * 光是进页面就要把 72 款里的前几屏全部点火 —— 中端机上"点一下要等半天"就是这么来的，
 * 而且页面上永远有 6~8 个动画在抢帧。
 *
 * 现在每格只画**一帧**（`t` 取一个有代表性的时刻，保证每款都有东西可看），
 * 画完就缓存；点进去真正应用时才会跑动画。
 */
object DynThumb {

    private val cache = LruCache<String, Bitmap>(32)

    /**
     * @param w 目标位图宽（px）。高度按 2:1 出图，卡片是 16:9 左右，`Crop` 裁掉一点没关系。
     */
    fun bitmap(kind: DynKind, argb: Int, w: Int): Bitmap {
        val ww = w.coerceIn(80, 720)
        val hh = (ww / 2).coerceAtLeast(40)
        val key = kind.id + "|" + argb + "|" + ww
        cache.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // t 取 3.7s：花瓣/雪/雨都落了一段，极光/星云也流动过了，不会是一张"空底"。
        runCatching { DynPainter.frame(c, kind, 3.7f, ww.toFloat(), hh.toFloat(), argb) }
        cache.put(key, bmp)
        return bmp
    }
}
