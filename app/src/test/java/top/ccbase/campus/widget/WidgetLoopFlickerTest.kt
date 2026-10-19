package top.ccbase.campus.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * 动态主题"闪"的两个来源，都钉在这里：
 *
 * ① **接缝闪**：第 N-1 帧回到第 0 帧如果跳得比平时远，就是每 8 秒硬跳一次。
 *    判据用方案 01 §4 的定义：接缝差 ≤ 相邻帧平均差 × 1.5。
 * ② **叠化闪**：布局里 in/out 两个动画如果都做 alpha，中点会漏出背景 → 整块卡每 200ms 暗一次。
 *    所以 out 动画必须"保持不透明"（1→1），这条用 XML 断言钉死，防止以后有人"优化"回去。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetLoopFlickerTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun gray(f: Bitmap): IntArray {
        val n = IntArray(f.width * f.height)
        f.getPixels(n, 0, f.width, 0, 0, f.width, f.height)
        for (i in n.indices) {
            val p = n[i]
            // 亮度（BT.601）
            n[i] = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        return n
    }

    private fun diff(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i]).toLong()
        return s.toDouble() / a.size
    }

    @Test
    fun `接缝不闪_末帧回到首帧的变化不超过相邻帧的一倍半`() {
        for (kind in listOf(DynPainter.Kind.AURORA, DynPainter.Kind.SILK, DynPainter.Kind.NEBULA)) {
            val spec = WidgetDyn.spec(kind, 0xFF6C5CE7.toInt())
            val frames = requireNotNull(WidgetFrames.forSpec(ctx, spec, 320, 160)) { "$kind 没生成帧" }
            assertEquals("帧数必须是 FRAMES", WidgetFrames.FRAMES, frames.size)
            val g = frames.map { gray(it) }
            val gaps = (1 until g.size).map { diff(g[it - 1], g[it]) }
            val mean = gaps.average()
            val seam = diff(g.last(), g.first())
            assertTrue(
                "$kind 接缝跳变过大：接缝 $seam vs 相邻平均 ${"%.2f".format(mean)}" +
                    "（说明循环没闭合，每 ${WidgetFrames.FRAMES * WidgetFrames.STEP_SEC} 秒会硬跳一次）",
                seam <= mean * 1.5 + 1.5,
            )
        }
    }

    @Test
    fun `接缝不闪：最后一帧和第一帧不能是同一张静止图`() {
        // 反面：如果整段动画其实是"渐变色慢慢漂"，首尾相同也能过上面的判据。
        // 这条保证画面真的在动（相邻帧平均差 > 1 个亮度级）。
        val spec = WidgetDyn.spec(DynPainter.Kind.AURORA, 0xFF6C5CE7.toInt())
        val frames = requireNotNull(WidgetFrames.forSpec(ctx, spec, 320, 160))
        val g = frames.map { gray(it) }
        val gaps = (1 until g.size).map { diff(g[it - 1], g[it]) }
        assertTrue("画面几乎不动：相邻帧平均差 ${"%.2f".format(gaps.average())}", gaps.average() > 1.0)
    }

    @Test
    fun `叠化动画只淡入不让旧帧淡出（否则每帧暗一次 = 闪烁）`() {
        val ins = File("src/main/res/anim/widget_frame_in.xml").readText()
        val out = File("src/main/res/anim/widget_frame_out.xml").readText()
        assertTrue("入场动画应淡入", ins.contains("fromAlpha=\"0.0\"") && ins.contains("toAlpha=\"1.0\""))
        assertTrue(
            "出场动画必须保持不透明（1.0 → 1.0）；改成 1→0 会让中点亮度掉到 7 成，桌面每帧闪一次",
            out.contains("fromAlpha=\"1.0\"") && out.contains("toAlpha=\"1.0\""),
        )
        assertTrue("入场时长应 ≤ 150ms（方案：110ms）", Regex("duration=\"(\\d+)\"").find(ins)!!.groupValues[1].toInt() <= 150)
    }

    @Test
    fun `布局的翻转间隔落在 180~450ms（低于 180 会被国产桌面节流）`() {
        val base = File("src/main/res/layout/widget_today.xml").readText()
        val iv = Regex("flipInterval=\"(\\d+)\"").find(base)!!.groupValues[1].toInt()
        assertTrue("flipInterval=$iv 不在 180~450", iv in 180..450)
        assertTrue("flipInterval 应与 STEP_SEC 对齐", kotlin.math.abs(iv - WidgetFrames.STEP_SEC * 1000) <= 10)
    }

    @Test
    fun `每个字体布局都有和帧数一样多的帧位`() {
        val layouts = File("src/main/res/layout").listFiles()!!.filter { it.name.startsWith("widget_today") }
        assertTrue("没找到布局", layouts.size >= 20)
        layouts.forEach { f ->
            val n = Regex("@\\+id/f(\\d+)").findAll(f.readText()).map { it.groupValues[1].toInt() }.distinct().count()
            assertEquals("${f.name} 的帧位数和 FRAMES=${WidgetFrames.FRAMES} 不一致（多一个=多翻一帧空白，少一个=后半段看不到）",
                WidgetFrames.FRAMES, n)
        }
    }
}
