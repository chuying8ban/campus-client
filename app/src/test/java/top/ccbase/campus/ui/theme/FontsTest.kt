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
    fun `三种字体给三份不同的布局`() {
        val ids = mutableSetOf<Int>()
        FontOpt.entries.forEach {
            WidgetFont.set(ctx, it)
            ids += WidgetFont.layoutFor(ctx)
        }
        assertEquals("三套字体必须落到三份不同布局", FontOpt.entries.size, ids.size)
    }

    @Test
    fun `布局变体之间只差 fontFamily 一行`() {
        val files = listOf(
            "src/main/res/layout/widget_today.xml" to null,
            "src/main/res/layout/widget_today_mono.xml" to "monospace",
            "src/main/res/layout/widget_today_serif.xml" to "serif",
        )
        val normalized = files.map { (path, fam) ->
            val f = File(path)
            assertTrue("布局缺失：$path（工作目录 ${File(".").absolutePath}）", f.isFile)
            val text = f.readText()
            if (fam != null) {
                assertEquals(
                    "$path 里应当每个 TextView 都带 fontFamily=$fam",
                    9, Regex("""android:fontFamily="$fam"""").findAll(text).count(),
                )
            } else {
                assertTrue("默认布局不该写死 fontFamily", "android:fontFamily" !in text)
            }
            text.lines().filter { "android:fontFamily" !in it }.joinToString("\n")
        }
        assertEquals("三份布局除了字体那一行必须完全一致", normalized[0], normalized[1])
        assertEquals("三份布局除了字体那一行必须完全一致", normalized[0], normalized[2])
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
