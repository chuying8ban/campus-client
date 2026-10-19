package top.ccbase.campus.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 字体：**App 与小组件分开**，且不许"改到不该改的东西上"。
 *
 * 三件必须成立的事：
 *  ① 写 App 字体不改小组件字体，反之亦然（两个 pref 各存各的）；
 *  ② 小组件的三份布局**只有 fontFamily 这一行不同** —— 复制 XML 是 RemoteViews 唯一可行的路子，
 *     但复制的最大风险是"改了一处忘了另外两处"，这里把它钉死；
 *  ③ App 的等宽数字（`Digits`）不受字体设置影响，否则时间/百分比会跳字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FontsTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `改 App 字体不动小组件字体`() {
        AppFont.set(ctx, FontOpt.SANS)
        WidgetFont.set(ctx, FontOpt.SANS)

        AppFont.set(ctx, FontOpt.SERIF)
        assertEquals(FontOpt.SERIF, AppFont.read(ctx))
        assertEquals("改 App 字体不许改到小组件", FontOpt.SANS, WidgetFont.read(ctx))

        WidgetFont.set(ctx, FontOpt.MONO)
        assertEquals(FontOpt.MONO, WidgetFont.read(ctx))
        assertEquals("改小组件字体不许改到 App", FontOpt.SERIF, AppFont.read(ctx))
    }

    @Test
    fun `每一款字体都有自己的布局`() {
        // 2026-10-19 用户："多加一些字体，起码提供几十个"。
        // 一款字体 = 一份布局（RemoteViews 不能运行时 setTypeface），
        // 少生成一份的后果是"选了没反应"（layoutFor 回落基础布局），所以这里钉死 1:1。
        val ids = mutableSetOf<Int>()
        FontOpt.entries.forEach {
            WidgetFont.set(ctx, it)
            val id = WidgetFont.layoutFor(ctx)
            assertNotEquals("字体 ${it.id} 没有对应布局", 0, id)
            ids += id
        }
        assertEquals("字体数必须等于布局数（不许两款字体重用一份布局）", FontOpt.entries.size, ids.size)
    }

    @Test
    fun `布局变体之间只差字体那几行`() {
        // 生成的变体只允许在 TextView 上多出 fontFamily / textFontWeight 这两类属性。
        // 手改其中一份忘了同步 —— 这个断言会立刻红。
        val base = File("src/main/res/layout/widget_today.xml")
        assertTrue("基础布局缺失（工作目录 ${File(".").absolutePath}）", base.isFile)
        val baseText = base.readText()
        assertTrue("默认布局不该写死 fontFamily", "android:fontFamily" !in baseText)
        val nText = Regex("""<TextView\b""").findAll(baseText).count()
        assertEquals("基础布局的 TextView 数变了，生成脚本要跟着改", 9, nText)

        val normalizedBase = baseText.lines().filter { "android:fontFamily" !in it }.joinToString("\n")
        FontOpt.entries.filter { it != FontOpt.SANS }.forEach { opt ->
            val f = File("src/main/res/layout/${opt.layoutName}.xml")
            assertTrue("布局缺失：${f.path}", f.isFile)
            val text = f.readText()
            assertEquals(
                "${f.name} 里每个 TextView 都要带 fontFamily=${opt.system}",
                nText, Regex("""android:fontFamily="${opt.system}"""").findAll(text).count(),
            )
            val normalized = text.lines().filter { "android:fontFamily" !in it }.joinToString("\n")
            assertEquals("${f.name} 除了字体那几行必须和基础布局一致", normalizedBase, normalized)
        }
    }

    @Test
    fun `等宽数字不受字体设置影响`() {
        assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, Digits.fontFamily)
    }

    @Test
    fun `坏值回落默认而不是崩`() {
        AppFont.set(ctx, FontOpt.SANS)
        ctx.getSharedPreferences("campus_appearance", Context.MODE_PRIVATE)
            .edit().putString("app_font", "no-such-font").commit()
        assertEquals(FontOpt.SANS, AppFont.read(ctx))
        assertNotEquals(0, AppFont.read(ctx).id.length)
    }
}
