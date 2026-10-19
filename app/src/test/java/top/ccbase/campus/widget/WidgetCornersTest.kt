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
 * 「圆角有时会消失」这件事的守卫（2026-10-19 用户截图：卡片四角是直角）。
 *
 * 三种会让四角变直的路子，这里各守一条：
 *  1. 底图/帧本身没画圆角 → 角落不透；
 *  2. 可读性幕被当成**矩形 View** 盖上去 → 角落被填实（这次的真凶）；
 *  3. 幕画进位图时没跟着圆角裁 → 一样是直角。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetCornersTest {

    private fun solid(w: Int = 400, h: Int = 200): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.WHITE)
        }

    private fun alphaAt(b: Bitmap, x: Int, y: Int): Int = Color.alpha(b.getPixel(x, y))

    @Test
    fun `圆角位图四个角必须是透明的`() {
        val rounded = WidgetArt.rounded(solid(), 40f)
        assertEquals("左上角应透明", 0, alphaAt(rounded, 0, 0))
        assertEquals("右上角应透明", 0, alphaAt(rounded, rounded.width - 1, 0))
        assertEquals("左下角应透明", 0, alphaAt(rounded, 0, rounded.height - 1))
        assertEquals("右下角应透明", 0, alphaAt(rounded, rounded.width - 1, rounded.height - 1))
        assertEquals("正中间必须不透明", 255, alphaAt(rounded, rounded.width / 2, rounded.height / 2))
    }

    @Test
    fun `可读性幕画在位图里时不许把圆角填平`() {
        val rounded = WidgetArt.rounded(solid(), 40f)
        val veiled = WidgetArt.veil(rounded, 0x8CFFFFFF.toInt(), 40f)
        assertEquals("左上角被幕填平了", 0, alphaAt(veiled, 0, 0))
        assertEquals("右下角被幕填平了", 0, alphaAt(veiled, veiled.width - 1, veiled.height - 1))
        // 中间要真的被压亮了（幕生效），否则就是"悄悄没画"
        val mid = veiled.getPixel(veiled.width / 2, veiled.height / 2)
        assertEquals("幕没压到中间", 255, Color.alpha(mid))
        assertTrue("中间应该是白的（白幕压在白底上）", Color.red(mid) > 250)
    }

    @Test
    fun `透明幕等于不处理`() {
        val rounded = WidgetArt.rounded(solid(), 40f)
        val same = WidgetArt.veil(rounded, 0, 40f)
        assertTrue("alpha=0 的幕不应该复制位图", same === rounded)
    }
}
