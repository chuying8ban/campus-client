package top.ccbase.campus.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-03 用户："把自己调颜色的功能取消掉"。
 *
 * 这句话指的就是外观页「主题色」下面那个写着**自己调颜色**的按钮 ——
 * 点开会在页面里长出一块 158dp 的色环（外环选色相、内圆选浓淡）。
 *
 * 这条守卫盯源码，不是盯截图：色环只要被谁"顺手加回来"，测试就先红。
 * 允许保留的是 `Accents.kt` 里的 `diy:` **解析**（老用户存下的配色不能因此失效），
 * 不许保留的是任何给用户拖色相的新入口。
 */
class DiyColorRemovedTest {

    private fun src(rel: String): String {
        val f = File("src/main/java/top/ccbase/campus/$rel")
        assertTrue("找不到 $rel（测试工作目录变了？）", f.isFile)
        return f.readText()
    }

    @Test
    fun `外观页不再有自己调颜色的入口`() {
        val theme = src("ui/me/ThemeSection.kt")
        val appearance = src("ui/me/AppearanceScreen.kt")
        // 只在注释里可以出现这四个字（说明为什么删掉），代码里不许有按钮文案
        listOf(theme, appearance).forEach { text ->
            val code = text.lines()
                .map { it.substringBefore("//").substringBefore("*") }
                .joinToString("\n")
            assertTrue(
                "界面上又出现了「自己调颜色」按钮 —— 用户 2026-11-03 明确要求去掉",
                !code.contains("自己调颜色"),
            )
        }
    }

    @Test
    fun `色环组件已被移除`() {
        val theme = src("ui/me/ThemeSection.kt")
        assertTrue("ColorWheel 不该再存在（用户要求取消自调色）", !theme.contains("fun ColorWheel("))
        assertTrue("色环的 hsv 辅助函数也不该再留着", !theme.contains("private fun hsv("))
    }

    @Test
    fun `12 套预置色点还在`() {
        val theme = src("ui/me/ThemeSection.kt")
        assertTrue("预置色点不能被一起删掉", theme.contains("ACCENT_PRESETS"))
        assertTrue(
            "预置色点仍要能落到外观里",
            theme.contains("AppearanceStore.setAccent("),
        )
    }
}
