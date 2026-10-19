package top.ccbase.campus.widget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import top.ccbase.campus.R
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 静态主题必须真的把底图塞进 RemoteViews（2026-10-19 用户："静止时总是不显示，点微动就有了"）。
 *
 * 真因：`applyTheme` 里写的是 `if (dyn == null)`，而 `dyn` 是 Boolean —— `== null` 恒为 false，
 * 静态分支一次都没跑过。纯色 / 随包图片 / 我的图 全都没有底图，只剩文字。
 *
 * 盯的是"静态规格一定显示 bg_img、隐藏 flip"，不是图好不好看（那归 WidgetDarkImageTest）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetStaticBranchTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun viewOf(spec: String?): View {
        val rv = TodayWidgetProvider.themeViews(ctx, 900, 400, spec)
        return rv.apply(ctx, FrameLayout(ctx))
    }

    @Test
    fun `纯色主题必须真的画上底图`() {
        val v = viewOf(WidgetThemes.presets.first().id)
        // 断言 drawable 非空，而不是 visibility：布局里 bg_img 默认就 VISIBLE，
        // 只看可见性的话，`if (dyn == null)` 那个 bug 下这条测试照样绿 —— 等于没测。
        assertNotNull("纯色主题没生成底图 —— 静态分支没走到",
            v.findViewById<ImageView>(R.id.widget_bg_img).drawable)
        assertEquals("静态主题不该用帧轮播", View.GONE, v.findViewById<View>(R.id.widget_flip).visibility)
    }

    @Test
    fun `我的图也走静态分支`() {
        val v = viewOf(WidgetThemes.USER_IMAGE_SPEC)
        assertNotNull("我的图没生成底图", v.findViewById<ImageView>(R.id.widget_bg_img).drawable)
        assertEquals(View.GONE, v.findViewById<View>(R.id.widget_flip).visibility)
    }

    @Test
    fun `根容器必须透明，否则圆角会被填成黑色方块`() {
        // 2026-10-19 用户："没有实现圆角，外边有黑色的矩形"。
        // 圆角是靠位图的透明角落做出来的，根容器垫一层不透明底色就全毁了。
        listOf(WidgetThemes.presets.first().id, WidgetThemes.USER_IMAGE_SPEC).forEach { spec ->
            val bg = viewOf(spec).background as? android.graphics.drawable.ColorDrawable
            val color = bg?.color ?: 0
            assertEquals("$spec 的根容器不透明 —— 圆角会变成黑角", 0, color ushr 24)
        }
    }

    @Test
    fun `动态规格只留一层（底图必须藏起来）`() {
        // 2026-10-19 用户："圆角矩形的显示为什么有两个，只留内侧的那个"。
        // 兜底底图是低分辨率渲染再拉伸，圆角弧度和帧对不上，就露出第二个圆角矩形 + 一圈灰边。
        // URI 在真机上已验证能读到（他截图里那张清晰的二次元照片就是帧本身），兜底撤掉。
        val v = viewOf(WidgetDyn.spec(DynPainter.Kind.entries.first(), 0xFF39D3C0.toInt()))
        assertEquals(View.VISIBLE, v.findViewById<View>(R.id.widget_flip).visibility)
        assertEquals(
            "底图必须藏起来，否则会有两层圆角",
            View.GONE, v.findViewById<View>(R.id.widget_bg_img).visibility,
        )
    }
}
