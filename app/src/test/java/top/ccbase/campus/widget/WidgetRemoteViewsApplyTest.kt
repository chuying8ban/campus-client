package top.ccbase.campus.widget

import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **所有主题规格的 RemoteViews 都必须能被真正 apply 一遍。**
 *
 * 2026-10-19 用户："小组件还是没有主题"。自检里终于露出原话：
 *   android.widget.RemoteViews$ActionException: view: android.widget.ViewFlipper
 *   can't use method with RemoteViews: setAutoStart(boolean)
 *
 * `setAutoStart` / `setFlipInterval` 不是 @RemotableViewMethod —— RemoteViews 在桌面进程里
 * 执行到这一步直接抛异常，**整份**更新都没落地（底色、文字、帧一起丢），
 * 所以表现成"换了主题没反应"。
 *
 * 这条测试遍历每一类规格都 apply 一次：谁再用只能在真机上跑的方法，这里先红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRemoteViewsApplyTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun applyOnce(spec: String?) {
        val rv = TodayWidgetProvider.themeViews(ctx, 900, 400, spec)
        rv.apply(ctx, FrameLayout(ctx))
    }

    @Test
    fun `纯色与图片主题能 apply`() {
        applyOnce("default")
        applyOnce(WidgetThemes.USER_IMAGE_SPEC)
        WidgetThemes.imageThemes.take(4).forEach { applyOnce(it.id) }
    }

    @Test
    fun `动态主题能 apply（ViewFlipper 只能用 setDisplayedChild）`() {
        val dyn = DynPainter.Kind.entries.map { WidgetDyn.spec(it, 0xFF39D3C0.toInt()) }
        assertTrue("动态规格不该为空", dyn.isNotEmpty())
        dyn.forEach { applyOnce(it) }
    }
}
