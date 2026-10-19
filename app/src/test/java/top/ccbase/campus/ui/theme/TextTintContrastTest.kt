package top.ccbase.campus.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字颜色：**用户的选择必须赢**（2026-10-19 用户："为什么我改成黑色里面的文字还是白色"）。
 *
 * 上一版的错在于"对比度不够就静默改回白色"。现在的约定变了：
 *   - 挑了什么就画什么（head/body/dim 原样给）；
 *   - 可读性靠背景幕补（scrimArgbFor：深字压白幕、浅字压黑幕，同向时更厚）。
 */
class TextTintContrastTest {

    private val lightBg = 0xFFF5F2EC.toInt()
    private val darkBg = 0xFF121016.toInt()

    @Test
    fun `挑黑就是黑_不许被换成白`() {
        val black = Color.Black
        assertEquals(black, TextTint.head(black, Color.White, Color(darkBg)))
        assertEquals(black, TextTint.head(black, Color.White, Color(lightBg)))
    }

    @Test
    fun `挑白就是白_不许被换成黑`() {
        assertEquals(Color.White, TextTint.head(Color.White, Color.Black, Color(lightBg)))
    }

    @Test
    fun `没挑时用色板原本的字色`() {
        assertEquals(Color.Red, TextTint.head(null, Color.Red, Color(lightBg)))
    }

    @Test
    fun `深字配白幕_浅字配黑幕`() {
        val onDark = TextTint.scrimArgbFor("#000000", darkBg)
        val onLight = TextTint.scrimArgbFor("#FFFFFF", lightBg)
        assertTrue("黑字深底该压白幕", Color(onDark).red > 0.9f)
        assertTrue("黑字深底幕要够厚", Color(onDark).alpha >= 0.5f)
        assertTrue("白字浅底该压黑幕", Color(onLight).red < 0.1f)
        assertTrue("白字浅底幕要够厚", Color(onLight).alpha >= 0.5f)
        assertTrue(Color(TextTint.scrimArgbFor("#000000", lightBg)).alpha < 0.5f)
        assertNotEquals(0, TextTint.scrimArgbFor("#000000", lightBg))
    }

    @Test
    fun `默认档不加幕`() {
        assertEquals(0, TextTint.scrimArgbFor(TextTint.AUTO, lightBg))
        assertEquals(0, TextTint.scrimArgbFor(TextTint.AUTO, darkBg))
    }

    @Test
    fun `小组件的 ARGB 版本同样不改用户的颜色`() {
        assertEquals(0xFF000000.toInt(), TextTint.apply(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), darkBg, 0))
    }
}
