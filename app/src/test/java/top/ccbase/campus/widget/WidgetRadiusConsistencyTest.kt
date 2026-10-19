package top.ccbase.campus.widget

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.ccbase.campus.R

/**
 * **所有主题的圆角必须一样**（2026-10-19 用户："我希望小组件的所有主题的圆角跟这个一样"，
 * 指的是他桌面上小米天气那块卡）。
 *
 * 以前是两条路各说各话：
 *   · 纯色预置 → XML drawable 里写死 `corners radius=20dp`；
 *   · 图片主题 → `WidgetArt.radiusFor(h, density)`（12% 夹 12~24dp）；
 *   · 动态帧 → `WidgetFrames` 里传 `density = 1f`，圆角只剩 24px（≈9dp），方得明显。
 * 结果同一屏上三种圆角。
 *
 * 现在统一成 `WidgetArt.RADIUS_DP = 16f`（= 天气卡实测 r/h≈0.157 换算到 161dp）。
 * 这条测试从**位图里量**圆角，不是读常量 —— 常量对了但某条路没走它，照样会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // 要读像素
class WidgetRadiusConsistencyTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** 从位图里量圆角：逐行找第一个不透明像素，回到直边那一行的 dy 就是半径。 */
    private fun measureRadius(bmp: android.graphics.Bitmap): Int {
        var xStraight = Int.MAX_VALUE
        val insets = ArrayList<Pair<Int, Int>>()
        val rows = minOf(bmp.height, 200)
        for (dy in 0 until rows) {
            var x = -1
            for (px in 0 until bmp.width) {
                if ((bmp.getPixel(px, dy) ushr 24) > 40) { x = px; break }
            }
            if (x < 0) continue
            insets += dy to x
            if (dy > rows / 2) xStraight = minOf(xStraight, x)
        }
        // 直边靠下半部分定，再从顶往下找"已经到直边"的那一行
        xStraight = insets.filter { it.first > rows / 2 }.minOfOrNull { it.second } ?: return -1
        return insets.firstOrNull { it.second <= xStraight + 1 }?.first ?: -1
    }

    private fun bgOf(spec: String, w: Int = 880, h: Int = 443): android.graphics.Bitmap? {
        val host = FrameLayout(ctx)
        TodayWidgetProvider.viewsSized(
            ctx,
            WidgetModel(
                status = "自习中", dateLine = "10月19日 周一", week = "第 8 周",
                headline = "自习", meta = "20:30-22:05 · C5 楼 1 区 301",
                rows = emptyList(), footer = "第 8 周 · 周一",
            ),
            w, h, spec,
        ).apply(ctx, host).also { host.addView(it) }
        val v = host.getChildAt(0).findViewById<ImageView>(R.id.widget_bg_img)
        return (v?.drawable as? BitmapDrawable)?.bitmap
    }

    @Test
    fun `纯色与图片主题的圆角一致`() {
        val density = ctx.resources.displayMetrics.density
        val want = WidgetArt.radiusFor(443, density)
        val specs = listOf(
            WidgetThemes.presets.first().id,
            WidgetThemes.imageThemes.first().id,
            WidgetThemes.imageThemes.last().id,
        )
        val got = specs.map { spec ->
            val bmp = bgOf(spec) ?: throw AssertionError("$spec 没画出底图")
            spec to measureRadius(bmp)
        }
        // 位图会先按预算等比缩小（半径同比例缩），所以这里不比对绝对 dp，
        // 只比"三种主题量到的半径是否是同一个数"—— 这正是用户看到的"有的圆有的方"。
        val rs = got.map { it.second }
        assertTrue("量不到圆角：$got", rs.all { it > 5 })
        assertTrue("三种主题的圆角不一致：$got（差 ${rs.max() - rs.min()}px）", rs.max() - rs.min() <= 2)
        assertTrue("圆角小得不像话：$want", want > 10f)
    }
}
