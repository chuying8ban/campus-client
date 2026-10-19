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
    fun `动态规格只留一层`() {
        // 2026-10-19 用户："圆角矩形的显示为什么有两个，只留内侧的那个"。
        // 两层同时可见 → 低分辨率底图被拉伸后圆角和帧对不上，露出第二个圆角矩形 + 一圈灰边。
        //
        // 2026-10-19 晚（12 帧 + 缓动那版）改了兜底策略：12 帧位图塞不进 binder 的 1MB，
        // 所以 HD 帧文件没就绪时**只放第 0 帧当静态底**（flip GONE、bg_img VISIBLE）；
        // 就绪之后的下一次刷新才切到 URI 轮播（flip VISIBLE、bg_img GONE）。
        // 不变量没变 —— **任何时刻只有一层可见**，所以断言改成"恰好一个可见"。
        val v = viewOf(WidgetDyn.spec(DynPainter.Kind.entries.first(), 0xFF39D3C0.toInt()))
        val flip = v.findViewById<View>(R.id.widget_flip).visibility
        val bg = v.findViewById<View>(R.id.widget_bg_img).visibility
        val visibleLayers = listOf(flip, bg).count { it == View.VISIBLE }
        assertEquals("动态规格必须恰好一层可见（flip=$flip, bg=$bg）", 1, visibleLayers)
    }
}
