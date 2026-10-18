package top.ccbase.campus.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 外观页的色点必须显示**真实的**颜色。
 *
 * 2026-10-18 用户截图：「默认紫」显示成土黄、「青叶」显示成橄榄绿 ——
 * 点哪个都跟真机不一样。根因是算色点时用了 `paletteWithAccent(palette, spec)`，
 * 而 `palette` 本身**已经叠过当前强调色**了，于是色相被转了两次。
 *
 * 这条守卫生效的不是"颜色对不对"（那由对比度测试管），而是**别再从 palette 起算**：
 * 必须从没有叠过强调色的底板（`DarkPalette` / `LightPalette`）算。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccentSwatchSourceTest {

    private fun themeSection(): String {
        val f = File("src/main/java/top/ccbase/campus/ui/me/ThemeSection.kt")
        assertTrue("找不到 ThemeSection.kt（测试工作目录变了？）", f.isFile)
        return f.readText()
    }

    @Test
    fun `色点不许从已叠过强调色的 palette 起算`() {
        val text = themeSection()
        val bad = text.lines().filter {
            val code = it.substringBefore("//")
            code.contains("paletteWithAccent(palette,") || code.contains("paletteWithAccent(palette ")
        }
        assertTrue(
            "色点/实时预览必须从底板起算（DarkPalette / LightPalette）。\n" +
                "用 palette 再转一次会把色相转两遍，显示出来的颜色和真机对不上：\n" +
                bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    @Test
    fun `底板起算后 十二套预置各自颜色互不相同`() {
        val dark = top.ccbase.campus.ui.theme.DarkPalette
        val colors = ACCENT_PRESETS.map { paletteWithAccent(dark, AccentSpec.preset(it.id)).violet }
        assertTrue(
            "有预置算出同一个颜色，说明色相表坏了：$colors",
            colors.toSet().size == colors.size,
        )
    }
}
