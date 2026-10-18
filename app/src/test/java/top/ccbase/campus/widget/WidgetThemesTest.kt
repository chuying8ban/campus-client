package top.ccbase.campus.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetThemesTest {

    @Test
    fun `认不出的规格退回默认而不是崩`() {
        assertEquals("default", WidgetThemes.of("preset-不存在").id)
        assertEquals("default", WidgetThemes.of("").id)
    }

    @Test
    fun `预置 id 能被还原`() {
        assertEquals("light", WidgetThemes.of("light").id)
        assertEquals("sand", WidgetThemes.of("sand").id)
    }

    @Test
    fun `DIY 深色给自己亮字 浅色给深字`() {
        val dark = WidgetThemes.of("diy:210:1")
        val light = WidgetThemes.of("diy:210:0")
        assertNotEquals(dark.bgColor, light.bgColor)
        // 深色卡片的正文亮、浅色卡片的正文暗（用灰度判断，别写死具体色号）
        assertTrue(brightness(dark.headColor) > 0.6f)
        assertTrue(brightness(light.headColor) < 0.4f)
    }

    @Test
    fun `DIY 的底色带不透明遮罩 避免桌面上透成一片`() {
        val t = WidgetThemes.of("diy:300:1")
        val alpha = (t.bgColor ushr 24) and 0xFF
        assertTrue("alpha=$alpha", alpha >= 0xE0)
    }

    @Test
    fun `规格存在本机 读回来一致`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        WidgetThemes.set(ctx, "diy:120:1")
        assertEquals("diy:120:1", WidgetThemes.spec(ctx))
        WidgetThemes.set(ctx, "forest")
        assertEquals("forest", WidgetThemes.spec(ctx))
    }

    private fun brightness(argb: Int): Float {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
}
