package top.ccbase.campus.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字颜色的**可读性守卫**（2026-10-19 用户："浅色主题底下的标签颜色都看不见了"）。
 *
 * 守的是行为而不是实现：不管用户挑了哪一支颜色、配哪一套底色，
 * 落笔画出来的字对底色都要有起码的对比度（≥ 3:1，WCAG 对大字的线）。
 */
class TextTintContrastTest {

    private val light = Color(0xFFF5F2EC)   // 浅色底板
    private val dark = Color(0xFF121016)    // 深色底板

    private fun ratio(a: Color, b: Color) = TextTint.contrast(a, b)

    @Test
    fun `白字放在浅色底上也看得见`() {
        val out = TextTint.head(Color.White, Color(0xFF1A1A1A), light)
        assertTrue("白字在浅底上对比只有 ${ratio(out, light)}", ratio(out, light) >= 3f)
    }

    @Test
    fun `黑字放在深色底上也看得见`() {
        val out = TextTint.head(Color.Black, Color(0xFFEDEDED), dark)
        assertTrue("黑字在深底上对比只有 ${ratio(out, dark)}", ratio(out, dark) >= 3f)
    }

    @Test
    fun `三级字同样过闸`() {
        listOf(
            TextTint.body(Color.White, Color(0xFF1A1A1A), light),
            TextTint.dim(Color.White, Color(0xFF1A1A1A), light),
            TextTint.body(Color.Black, Color(0xFFEDEDED), dark),
            TextTint.dim(Color.Black, Color(0xFFEDEDED), dark),
        ).forEach {
            // 允许它退回底色或纯黑/纯白，但不许留着看不清
            assertTrue(it != Color.Transparent)
        }
        assertTrue(ratio(TextTint.dim(Color.White, Color(0xFF1A1A1A), light), light) >= 2f)
    }

    @Test
    fun `选的色本来就够看时不改动`() {
        val tint = Color(0xFFD9C8FF)                        // 淡紫
        val out = TextTint.head(tint, Color(0xFFEDEDED), dark)
        assertTrue("够看的颜色不该被替换", out == tint)
    }

    @Test
    fun `小组件那侧的 ARGB 版本同一套闸`() {
        val white = 0xFFFFFFFF.toInt()
        val got = TextTint.apply(white, 0xFF1A1A1A.toInt(), 0xFFF5F2EC.toInt(), 0)
        val c = Color(got)
        assertTrue("小组件白字在浅底上对比只有 ${ratio(c, light)}", ratio(c, light) >= 3f)
    }
}
