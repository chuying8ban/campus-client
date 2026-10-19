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
 * 这组测试钉住两件事：① 帧能生成、有 6 张、四角是圆角透明的；
 * ② 帧和帧之间**真的不一样**（否则桌面上就是一张静止图，白折腾）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetFramesTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * 真机上「载入窗口小部件时出现问题」和「主题显示不上」的共同元凶：一次性塞给桌面的位图太多。
     *
     * RemoteViews 的位图是连像素一起走 binder 的，一次事务超过 ~1MB 就会被 launcher 丢弃。
     * 这条预算从 8MB 收到 **1MB**（2026-10-19），因为 8MB 那版实测在 MIUI 上表现就是"桌面只剩文字"。
     */
    @Test
    /**
     * 位图兜底现在只交 1 帧（12 帧塞不进 binder 的 1MB），所以这里改成钉"每帧都小、帧数对得上"，
     * 而不是钉整组 ≤1MB —— 整组是落盘给桌面按 URI 读的，不走 binder。
     */
    fun `每帧都压在位图预算内`() {
        val spec = WidgetDyn.spec(DynPainter.Kind.PETALS, 0xFFFF7BC0.toInt())
        val frames = requireNotNull(WidgetFrames.forSpec(ctx, spec, 1200, 600))
        assertEquals("帧数固定", WidgetFrames.FRAMES, frames.size)
        val perFrame = frames.maxOf { it.width.toLong() * it.height }
        assertTrue("单帧 ${perFrame} 像素太肥", perFrame <= 40_000)
    }

    /** 帧要落盘：provider 被系统杀掉重建后不该重画一遍（换主题那几秒就是花在重画上） */
    @Test
    fun `帧会写进磁盘缓存`() {
        val spec = WidgetDyn.spec(DynPainter.Kind.SNOW, 0xFF8FB8FF.toInt())
        requireNotNull(WidgetFrames.forSpec(ctx, spec, 480, 240))
        val dirs = java.io.File(ctx.cacheDir, "wframes").listFiles() ?: emptyArray()
        assertTrue("没有落盘目录", dirs.isNotEmpty())
        val files = dirs.flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.name.startsWith("f") && it.name.endsWith(".png") }
        assertEquals("PNG 数量应等于帧数", WidgetFrames.FRAMES, files.size)
        assertTrue("PNG 是空的", files.all { it.length() > 0 })
    }

    @Test
    fun `纯动效主题生成固定帧数且四角透明`() {
        val spec = WidgetDyn.spec(DynPainter.Kind.PETALS, 0xFFFF7BC0.toInt())
        val frames = WidgetFrames.forSpec(ctx, spec, 480, 240)
        requireNotNull(frames)
        assertEquals(WidgetFrames.FRAMES, frames.size)
        val f = frames[0]
        assertEquals(0, Color.alpha(f.getPixel(0, 0)))
        assertEquals(0, Color.alpha(f.getPixel(f.width - 1, f.height - 1)))
        assertEquals(255, Color.alpha(f.getPixel(f.width / 2, f.height / 2)))
    }

    /**
     * 2026-10-18 用户截图"没有做到完全圆角"：真机上左边大圆角、右边直角竖切。
     * 那是 `centerCrop` 放大后裁掉的；这里先钉住**位图本身四个角都是透明的**，
     * 再配合 XML 的 fitXY + 按视图尺寸渲染，保证 1:1 铺进去不会被裁。
     */
    @Test
    fun `四个角都必须透明_一个直角都不许有`() {
        val frames = WidgetFrames.forSpec(
            ctx, WidgetDyn.spec(DynPainter.Kind.BOKEH, 0xFFA06BFF.toInt()), 600, 300,
        )!!
        val f = frames[0]
        val pts = listOf(
            0 to 0,
            f.width - 1 to 0,
            0 to f.height - 1,
            f.width - 1 to f.height - 1,
        )
        pts.forEach { (x, y) ->
            assertEquals("角($x,$y)不是透明的，桌面上会露出直角", 0, Color.alpha(f.getPixel(x, y)))
        }
    }

    @Test
    fun `照片蒙版画在圆角里面_不会把角重新填成直角`() {
        val src = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        Canvas(src).drawColor(Color.rgb(90, 60, 120))
        val out = WidgetArt.roundedWithScrim(src, 40f, 0x66000000.toInt(), 0x99000000.toInt())
        assertEquals(0, Color.alpha(out.getPixel(0, 0)))
        assertEquals(0, Color.alpha(out.getPixel(out.width - 1, out.height - 1)))
        // 中心得压暗，但没到全黑
        val center = Color.alpha(out.getPixel(out.width / 2, out.height / 2))
        assertEquals(255, center)
        val orig = 90 * 0x10000 + 60 * 0x100 + 120
        val now = out.getPixel(out.width / 2, out.height / 2) and 0xFFFFFF
        assertTrue("蒙版没压上去：$now", now < orig)
    }

    @Test
    fun `每帧都不一样_否则桌面上是静止图`() {
        val frames = WidgetFrames.forSpec(
            ctx, WidgetDyn.spec(DynPainter.Kind.SNOW, 0xFF6FA8FF.toInt()), 480, 240,
        )!!
        val a = frames[0].sameAs(frames[frames.size / 2])
        val b = frames[0].sameAs(frames.last())
        assertTrue("第 0 帧和中/末帧不该完全相同", !a || !b)
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
        assertEquals(WidgetFrames.FRAMES, frames.size)
        // 推近 + 粒子 ⇒ 首尾帧不应该一模一样
        assertNotEquals(true, frames[0].sameAs(frames.last()))
    }

    /**
     * 圆角蒙版必须画在**新位图**上（2026-10-19 "主题显示不上"那次排查发现的真 bug）：
     * 旧实现拿 rounded() 的共享缓存位图当输出，每刷新一次再叠一层渐变 —— 越刷越黑；
     * 同尺寸的不同帧还会命中同一个缓存 key，轮播出来六帧一模一样。
     */
    @Test
    fun `蒙版不会污染源图_两次结果一致`() {
        val src = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        Canvas(src).drawColor(Color.WHITE)
        val before = src.getPixel(100, 50)
        val a = WidgetArt.roundedWithScrim(src, 20f, 0x66000000.toInt(), 0x99000000.toInt())
        val b = WidgetArt.roundedWithScrim(src, 20f, 0x66000000.toInt(), 0x99000000.toInt())
        assertEquals("源图被改写了", before, src.getPixel(100, 50))
        assertEquals("同一张源图画两次结果不同（说明在往共享位图上叠）", a.getPixel(100, 50), b.getPixel(100, 50))
        assertEquals("四个角必须透明", 0, a.getPixel(0, 0))
    }
}
