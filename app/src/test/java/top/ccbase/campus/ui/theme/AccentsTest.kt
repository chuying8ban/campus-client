package top.ccbase.campus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 主题（强调色）系统的守则 —— 它能让整屏变个气质，也就最容易把可读性搞坏。
 * 所以这里除了"换个色真的换了"，还按算的验一件事：
 * **12 套预置 × 深浅两套底板，每个语义色在底色上的对比度 ≥ 3.0**。
 * 低于这个数，界面上的强调字（周次、状态、按钮文字）就开始糊。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccentsTest {

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `默认规格原样返回底板`() {
        assertTrue(paletteWithAccent(DarkPalette, AccentSpec.DEFAULT) === DarkPalette)
        assertTrue(paletteWithAccent(LightPalette, "") === LightPalette)
    }

    @Test
    fun `认不出的规格退回底板而不是崩`() {
        assertTrue(paletteWithAccent(DarkPalette, "preset:没有这套") === DarkPalette)
        assertTrue(paletteWithAccent(DarkPalette, "diy:不是数字") === DarkPalette)
    }

    @Test
    fun `预置真的换了色 但没换底色`() {
        val themed = paletteWithAccent(DarkPalette, AccentSpec.preset("ocean"))
        assertNotEquals(DarkPalette.violet.toArgb(), themed.violet.toArgb())
        // 底/字的令牌必须还是原来那套（对比度是它们守着的）
        assertEquals(DarkPalette.txt.toArgb(), themed.txt.toArgb())
        assertEquals(DarkPalette.txt2.toArgb(), themed.txt2.toArgb())
    }

    @Test
    fun `转色相不改明度`() {
        val c = Color(0xFFA06BFF)
        val r = rotateHue(c, 120f)
        assertEquals(c.luminance(), r.luminance(), 0.12f)
        assertEquals(c.alpha, r.alpha, 0.001f)
    }

    @Test
    fun `十二套预置在深浅两套底板上都够对比度`() {
        val problems = mutableListOf<String>()
        for (base in listOf(DarkPalette, LightPalette)) {
            val dark = base.bg.luminance() <= 0.5f
            for (p in ACCENT_PRESETS) {
                val themed = paletteWithAccent(base, AccentSpec.preset(p.id))
                val accents = listOf(
                    "violet" to themed.violet, "magenta" to themed.magenta, "cyan" to themed.cyan,
                    "amber" to themed.amber, "red" to themed.red, "green" to themed.green,
                )
                for ((name, c) in accents) {
                    val ratio = contrast(c, themed.bg)
                    // 深色底上强调色本来就偏亮；浅色底要求更高一点
                    val floor = if (dark) 3.0 else 3.8
                    if (ratio < floor) problems.add("${p.name}/$name=${"%.2f".format(ratio)} < $floor")
                }
                // 字色对比度不许被主题影响
                check(contrast(themed.txt, themed.bg) >= 4.5) { "正文对比度被拖低了：${p.name}" }
            }
        }
        assertTrue("这些组合读不清：\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `DIY 规格能来回序列化`() {
        val spec = AccentSpec.diy(212.7f, 1f)
        assertEquals(212f, AccentSpec.diyHue(spec)!!, 0.6f)
        assertEquals(1f, AccentSpec.diyTint(spec), 0.01f)
        assertTrue(AccentSpec.isDiy(spec))
        assertEquals("ocean", AccentSpec.presetId(AccentSpec.preset("ocean")))
    }
}
