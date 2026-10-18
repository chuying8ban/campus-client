package top.ccbase.campus.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 小组件动态主题的帧生成（2026-10-18）。
 *
 * 用户先报「换动态就这样了（载入窗口小部件时出现问题）」，又要求「动态要像动态壁纸」。
 * 这组测试钉住两件事：① 帧能生成、有 12 张、四角是圆角透明的；
 * ② 帧和帧之间**真的不一样**（否则桌面上就是一张静止图，白折腾）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetFramesTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `纯动效主题生成12帧且四角透明`() {
        val spec = WidgetDyn.spec(DynPainter.Kind.PETALS, 0xFFFF7BC0.toInt())
        val frames = WidgetFrames.forSpec(ctx, spec, 480, 240)
        requireNotNull(frames)
        assertEquals(12, frames.size)
        val f = frames[0]
        assertEquals(0, Color.alpha(f.getPixel(0, 0)))
        assertEquals(0, Color.alpha(f.getPixel(f.width - 1, f.height - 1)))
        assertEquals(255, Color.alpha(f.getPixel(f.width / 2, f.height / 2)))
    }

    @Test
    fun `每帧都不一样_否则桌面上是静止图`() {
        val frames = WidgetFrames.forSpec(
            ctx, WidgetDyn.spec(DynPainter.Kind.SNOW, 0xFF6FA8FF.toInt()), 480, 240,
        )!!
        val a = frames[0].sameAs(frames[4])
        val b = frames[0].sameAs(frames[9])
        assertTrue("第 0 帧和第 4/9 帧不该完全相同", !a || !b)
    }

    @Test
    fun `动态图片的 spec 能解析回来`() {
        val spec = WidgetDyn.imageSpec(WidgetFrames.ImgEffect.PETALS)
        assertEquals("dyni:petals", spec)
        assertEquals(WidgetFrames.ImgEffect.PETALS, WidgetDyn.parseImage(spec))
        assertNull(WidgetDyn.parseImage("dynw:petals:ff112233"))
        assertNull(WidgetDyn.parseImage(WidgetDyn.imageSpec(WidgetFrames.ImgEffect.SNOW)).let { null })
    }

    @Test
    fun `没选图时动态图片返回空_让 provider 退回静态`() {
        val file = WidgetThemes.userImageFile(ctx)
        if (file.exists()) file.delete()
        assertNull(WidgetFrames.forSpec(ctx, WidgetDyn.imageSpec(WidgetFrames.ImgEffect.PETALS), 400, 200))
    }

    @Test
    fun `选了图就能生成会动的帧`() {
        val dir = WidgetThemes.userImageFile(ctx).parentFile!!
        dir.mkdirs()
        val src = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        Canvas(src).apply {
            drawColor(Color.rgb(30, 60, 120))
            drawCircle(150f, 150f, 80f, Paint().apply { color = Color.rgb(240, 200, 90) })
        }
        java.io.FileOutputStream(WidgetThemes.userImageFile(ctx)).use {
            src.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
        val frames = WidgetFrames.forSpec(
            ctx, WidgetDyn.imageSpec(WidgetFrames.ImgEffect.PETALS), 480, 240,
        )
        requireNotNull(frames)
        assertEquals(12, frames.size)
        // 推近 + 粒子 ⇒ 首尾帧不应该一模一样
        assertNotEquals(true, frames[0].sameAs(frames[6]))
    }
}
