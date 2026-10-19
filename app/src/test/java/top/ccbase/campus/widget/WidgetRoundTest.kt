package top.ccbase.campus.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 桌面小组件"默认圆角"这件事，只有在**位图**层面能验证。
 *
 * 2026-10-18 用户截图：卡片四角是直角。根因是图片主题走 `scaleToFill()` 拿到的是一整块
 * 矩形位图，直接塞给 RemoteViews 的 ImageView —— RemoteViews 里没有 `clipToOutline`、
 * 没有 `ShapeableImageView`，圆角只能画进位图（四角必须是透明的）。
 *
 * 这个测试真的画、真的读像素：角上透明、中间不透明，圆角才叫"有了"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetRoundTest {

    private fun solid(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).drawColor(Color.RED)
        return b
    }

    @Test
    fun `圆角位图的四角必须是透明的`() {
        val src = solid(400, 200)
        val radius = WidgetArt.radiusFor(200, 1f)
        // 2026-10-19 用户："所有主题的圆角跟这个一样" → 曾统一 25dp；
        // 2026-10-20 又改："这里的圆角太明显了，改小一点" → 16dp（矮卡仍按 h*0.2 收）
        assertTrue("半径应取 min(16dp, h*0.2)，实得 $radius", radius in 15f..16f)
        val out = WidgetArt.rounded(src, radius)

        assertEquals("左上角", 0, Color.alpha(out.getPixel(0, 0)))
        assertEquals("右上角", 0, Color.alpha(out.getPixel(out.width - 1, 0)))
        assertEquals("左下角", 0, Color.alpha(out.getPixel(0, out.height - 1)))
        assertEquals("右下角", 0, Color.alpha(out.getPixel(out.width - 1, out.height - 1)))
        assertEquals("正中要保留原色", 255, Color.alpha(out.getPixel(out.width / 2, out.height / 2)))
    }

    @Test
    fun `圆角内的像素不能被动过`() {
        val src = solid(300, 150)
        val radius = WidgetArt.radiusFor(150, 1f)
        val out = WidgetArt.rounded(src, radius)
        val inset = (radius + 4).toInt()
        assertEquals(255, Color.alpha(out.getPixel(inset, inset)))
        assertEquals(Color.RED, out.getPixel(out.width / 2, out.height / 2))
    }

    @Test
    fun `radiusFor 在常见卡片高度上都给出适当的圆角`() {
        // 4×2 卡在 density 3 下高约 480px；16dp×3 = 48px（矮卡按 20% 收，h=280 → 56 → 取 48）
        listOf(280, 360, 480, 620).forEach { h ->
            val r = WidgetArt.radiusFor(h, 3f)
            assertTrue("h=$h 的圆角 $r 太小，桌面上会看着是直角", r >= 47f)
            assertTrue("h=$h 的圆角 $r 太大，会变成胶囊", r <= 48.01f)
        }
        // 关键回归：动态帧（WidgetFrames）以前传 density=1f，圆角只有 16px，和静态主题对不上
        assertEquals("同高度下密度不能再被忽略", 48f, WidgetArt.radiusFor(480, 3f), 0.01f)
    }

    @Test
    fun `透明源图不会崩`() {
        val b = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        val out = WidgetArt.rounded(b, 20f)
        assertEquals(120, out.width)
        Paint() // 只是为了确保 Paint 在本地可用，别让 lint 报未使用
    }
}
