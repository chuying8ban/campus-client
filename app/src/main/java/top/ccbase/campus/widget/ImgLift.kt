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
        // 直接改像素，不用 Canvas + ColorMatrixColorFilter：
        // 那条路在单测环境（Robolectric）里画出来是全黑，改完就分不清"图真黑"还是"环境不支持"。
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            if (a == 0) continue
            val r = (((p shr 16) and 0xFF) * k).toInt().coerceAtMost(255)
            val g = (((p shr 8) and 0xFF) * k).toInt().coerceAtMost(255)
            val b = ((p and 0xFF) * k).toInt().coerceAtMost(255)
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }.getOrDefault(bmp)
}
