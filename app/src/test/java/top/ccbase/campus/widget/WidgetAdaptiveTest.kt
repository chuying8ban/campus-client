package top.ccbase.campus.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件"换台手机也放得下"的档位表。
 *
 * 用户要求「确保所有机型都能使用小组件功能」——真机尺寸无法枚举，所以把分档逻辑抽成纯函数钉死：
 *  · 原生 4×2 ≈ 170dp：三行
 *  · MIUI 4×2 ≈ 155dp：两行
 *  · 3×2 拉扁 ≈ 130dp：一行
 *  · 2×2 ≈ 110dp / 2×1 ≈ 70dp：不显示任何后续课程（只剩状态+课名），绝不留半行被裁的字
 *  · 宽度 ≤ 210dp：判为窄格子（收字号、砍日期周次）
 */
class WidgetAdaptiveTest {

    @Test
    fun `高度分档`() {
        assertEquals(3, TodayWidgetProvider.rowsFor(180f))
        assertEquals(2, TodayWidgetProvider.rowsFor(155f))
        assertEquals(1, TodayWidgetProvider.rowsFor(130f))
        assertEquals(0, TodayWidgetProvider.rowsFor(110f))
        assertEquals(0, TodayWidgetProvider.rowsFor(70f))
    }

    @Test
    fun `宽度分档`() {
        assertFalse("4×2 标准宽度不算窄", TodayWidgetProvider.isNarrow(250f))
        assertTrue("2×2 算窄", TodayWidgetProvider.isNarrow(160f))
    }

    // kind 解析放在 ArtCatalogTest（那边有 Robolectric 才能用 org.json）
}
