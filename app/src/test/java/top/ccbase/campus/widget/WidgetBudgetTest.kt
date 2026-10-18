package top.ccbase.campus.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 位图预算（2026-10-19 用户："小控件的主题换了没有反应"）。
 *
 * 现场的坑：MIUI 报 `OPTION_APPWIDGET_MIN_WIDTH=900dp`，×2.75 密度 = 2475×1375 ≈ 340 万像素，
 * 一张 ARGB 位图 13.6MB。原来"先按视图尺寸画、画完再缩"，峰值内存直接把人踩死；
 * 4 帧那条路还有 0.24 的下限，最后交出去的帧加起来 3MB，超了 binder 上限被桌面整块丢掉。
 *
 * 这组测试钉死：**不管 launcher 报多大，交出去的位图总量都在预算内**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetBudgetTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `按预算取尺寸_340万像素会等比缩到12万内`() {
        val (w, h) = TodayWidgetProvider.fitTo(120_000, 2475, 1375)
        assertTrue("像素总量要进预算", w.toLong() * h <= 120_000)
        val ratio = w.toFloat() / h
        assertEquals("等比缩放，形状不能变", 2475f / 1375f, ratio, 0.02f)
    }

    @Test
    fun `超大的小组件尺寸也能画出静态底图_且不超过预算`() {
        val bmp: Bitmap? = TodayWidgetProvider.backgroundBitmapFor(ctx, "default", 2475, 1375)
        assertNotNull("再大的格子也必须画得出底图，不能退化成纯色", bmp)
        assertTrue("底图不许超预算", bmp!!.width.toLong() * bmp.height <= 130_000)
    }

    @Test
    fun `动态帧在超大尺寸下总量不超过1MB`() {
        val frames = WidgetFrames.forSpec(ctx, "dynw:aurora:${0xFF8AE9D6.toInt()}", 2475, 1375)
        assertNotNull("动效规格必须出帧", frames)
        val bytes = frames!!.sumOf { it.width.toLong() * it.height * 4 }
        assertTrue("4 帧加起来 $bytes 字节，超过 binder 的 1MB", bytes <= 1_000_000)
    }
}
