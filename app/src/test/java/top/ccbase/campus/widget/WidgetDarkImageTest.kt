package top.ccbase.campus.widget

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 暗图必须被抬起来（2026-10-19 用户："静止时总是不显示，点微动或其他动态特效就有了"）。
 *
 * 微动走帧分支（3.4.18 已加 ImgLift），静止走 imageBitmap → bgBitmap，那条路漏了提亮 ——
 * 同一张图，一个选择看得见、一个是黑块。这里守的是提亮本身的行为。
 *
 * 注意：图形全部用 `setPixels` 造。Robolectric 的 `Canvas.drawColor` / `BitmapFactory.decodeFile`
 * 都不真的落像素（解出来/画出来是全黑），用它断言只会测到影子实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetDarkImageTest {

    private fun filled(w: Int, h: Int, color: Int): Bitmap {
        val px = IntArray(w * h) { color }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    @Test
    fun `暗图会被抬亮`() {
        val dark = filled(160, 90, Color.rgb(0x03, 0x1A, 0x56))
        val before = ImgLift.mean(dark)
        assertTrue("前提：这张图本来就该判为暗（实测 $before）", before in 1..49)

        val after = ImgLift.mean(ImgLift.lift(dark))
        assertTrue("提亮后应该明显变亮：$before → $after", after > 45)
        assertTrue("不该超过 255", after <= 255)
    }

    @Test
    fun `本来就够亮的图不许被改色`() {
        val bright = filled(64, 64, Color.rgb(0xC0, 0xC8, 0xD0))
        assertTrue(ImgLift.mean(bright) >= 50)
        assertSame("亮度够的图必须原样返回", bright, ImgLift.lift(bright))
    }

    @Test
    fun `提亮不改变 alpha`() {
        val semi = filled(32, 32, Color.argb(0x80, 0x04, 0x08, 0x10))
        val out = ImgLift.lift(semi)
        assertEquals(0x80, out.getPixel(1, 1) ushr 24)
    }
}
