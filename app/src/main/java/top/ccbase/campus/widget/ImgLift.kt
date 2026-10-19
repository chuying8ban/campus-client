package top.ccbase.campus.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * 把"黑得看不出画面"的图抬一档。
 *
 * 2026-10-19 用户："使用图片主题也只显示为黑色"。他那张"我的图"实测平均亮度 20/255
 * （中心像素 #010c29）—— 是从一段暗色动图里抽的帧。铺到小组件上跟纯黑没区别，
 * 于是无论怎么修渲染管线都像"没主题"。
 *
 * 规则：平均亮度 < 50 才动，倍数封顶 3 倍；不碰本来就正常的图（宁可不动，也别把
 * 用户喜欢的暗调风格洗白）。
 */
object ImgLift {

    fun mean(bmp: Bitmap): Int = runCatching {
        val s = Bitmap.createScaledBitmap(bmp, 16, 16, true)
        var sum = 0L
        for (y in 0 until s.height) for (x in 0 until s.width) {
            val p = s.getPixel(x, y)
            sum += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        (sum / (s.width * s.height)).toInt()
    }.getOrDefault(0)

    fun lift(bmp: Bitmap, floor: Int = 50, target: Int = 95): Bitmap = runCatching {
        val m = mean(bmp)
        if (m >= floor || m <= 0) return@runCatching bmp
        val k = (target.toFloat() / m).coerceIn(1f, 3f)
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(k, k, k, 1f) })
        c.drawBitmap(bmp, 0f, 0f, paint)
        out
    }.getOrDefault(bmp)
}
