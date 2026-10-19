package top.ccbase.campus.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「动态主题画质太糊」的守卫（2026-10-19 用户）。
 *
 * 糊的原因不是"没有矢量图"，是 **RemoteViews 的位图要走 binder**：
 * 一次 updateAppWidget 的总位图超过 ~1MB，桌面会把整张卡丢掉，所以 4 帧只能共享
 * ~4 万像素/帧 —— 880×443 的格子被压到 300 多像素宽，桌面再放大 2~3 倍。
 *
 * 修法：高画质模式把帧按真实像素渲染、落成 PNG，用 `setImageViewUri` 交给桌面自己解码。
 * 这里守两件事：① 高画质帧确实接近原尺寸；② 帧文件真的落盘（否则 URI 指不到东西）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetHdFramesTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val spec = WidgetDyn.spec(DynPainter.Kind.AURORA, 0xFF6C5CE7.toInt())

    @Test
    fun `高画质帧按卡片真实分辨率渲染`() {
        val w = 960
        val h = 480
        val hd = WidgetFrames.forSpec(ctx, spec, w, h, 1f, fullRes = true)
        assertNotNull("高画质帧没生成", hd)
        // 2026-10-19：帧数抬到 24 之后，"每帧满分辨率"= 24×1.7MB ≈ 40MB 解码量，桌面会被系统回收。
        // 改成总量预算（630 万像素）按帧数摊平，每帧约 76% 宽度 —— fitXY 放大 1.3 倍肉眼无感。
        assertTrue("高画质帧宽度应达到视图宽度的 70% 以上：实际 ${hd!![0].width}", hd[0].width >= (w * 0.70f).toInt())
        assertTrue("高画质帧高度应达到视图高度的 70% 以上：实际 ${hd[0].height}", hd[0].height >= (h * 0.70f).toInt())
        val total = hd.sumOf { it.width.toLong() * it.height }
        assertTrue("整组帧像素总量 $total 超预算", total <= 6_400_000)
    }

    @Test
    fun `省内存帧明显更小（这就是以前发糊的原因）`() {
        val lo = WidgetFrames.forSpec(ctx, spec, 960, 480, 1f, fullRes = false)
        val hd = WidgetFrames.forSpec(ctx, spec, 960, 480, 1f, fullRes = true)
        assertNotNull(lo)
        assertNotNull(hd)
        assertTrue(
            "省内存帧应该明显小于高画质帧：lo=${lo!![0].width} hd=${hd!![0].width}",
            lo[0].width * 2 <= hd[0].width,
        )
    }

    @Test
    fun `帧文件真的落盘了（setImageViewUri 要能指到）`() {
        val files = WidgetFrames.frameFiles(ctx, spec, 960, 480, 1f)
        assertNotNull("frameFiles 返回 null，URI 会指空", files)
        assertEquals("帧数要和布局里的帧位一致（FRAMES=${WidgetFrames.FRAMES}，实际=${files!!.size}）", WidgetFrames.FRAMES, files.size)
        files.forEachIndexed { i, f ->
            assertTrue("第 $i 帧文件不存在", f.isFile)
            assertTrue("第 $i 帧文件是空的", f.length() > 1_000L)
            // 必须是 PNG：圆角靠透明角，JPEG 会把四角填黑
            assertTrue("第 $i 帧不是 PNG", f.name.endsWith(".png"))
        }
    }

    @Test
    fun `换了主题就不许拿旧帧`() {
        // wframes/ 只留一份帧。不核对 meta 就会拿上一个主题的帧去铺 —— 表现为"换了主题桌面不变"。
        val a = WidgetDyn.spec(DynPainter.Kind.entries.first(), 0xFF6C5CE7.toInt())
        val b = WidgetDyn.spec(DynPainter.Kind.entries.last(), 0xFF39D3C0.toInt())
        assertNotNull(WidgetFrames.frameFiles(ctx, a, 960, 480, 1f))
        assertNotNull("同主题同尺寸应当命中", WidgetFrames.existingFiles(ctx, a, 960, 480, 1f))
        assertNull("换了主题不该命中", WidgetFrames.existingFiles(ctx, b, 960, 480, 1f))
        assertNull("换了尺寸不该命中", WidgetFrames.existingFiles(ctx, a, 640, 320, 1f))
    }

    @Test
    fun `动态规格认得出来`() {
        assertTrue(WidgetFrames.isDynamic(WidgetDyn.spec(DynPainter.Kind.entries.first(), 0xFF6C5CE7.toInt())))
        assertFalse(WidgetFrames.isDynamic("solid:#FF0000"))
    }

    @Test
    fun `画质开关已删，永远走高画质`() {
        // 3.4.48：用户在界面上找不到"动态主题画质"，但我们要保证行为就是高画质那条路，
        // 不能因为"删了开关"就悄悄退回低分辨率位图。
        val files = WidgetFrames.frameFiles(ctx, spec, 960, 480, 1f)
        assertNotNull(files)
        val bmp = android.graphics.BitmapFactory.decodeFile(files!!.first().absolutePath)
        // 走的是高画质那条路（URI 读文件、每帧按总预算摊平），不是 40 万像素的位图兜底
        assertTrue("帧必须明显大于位图兜底（${bmp.width}）", bmp.width >= 600)
        bmp.recycle()
    }

}
