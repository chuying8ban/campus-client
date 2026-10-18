package top.ccbase.campus.widget

import android.content.Context
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.R

/**
 * 小组件布局必须能被 **RemoteViews** 充气。
 *
 * 由来（2026-10-18 真机截图）：桌面显示「载入窗口小部件时出现问题」。
 * 根因是布局里用了 `android.widget.Space` —— RemoteViews 只允许带 `@RemoteView` 注解的类，
 * Space 没有注解，launcher 充气时直接抛 InflateException。
 * 这类错误在编译、单测文案里都看不出来，只有真的 apply 一次才会炸。
 *
 * 所以这里就做那一件事：把布局塞进 RemoteViews 并 apply，任何"不在白名单里的 View"
 * 都会在这里就抛异常，而不是等到用户桌面上显示一行错误。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRemoteViewsInflateTest {

    @Test
    fun `布局能在 RemoteViews 里充气_且包含关键控件`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val rv = RemoteViews(ctx.packageName, R.layout.widget_today)
        rv.setTextViewText(R.id.widget_status, "正在上课")
        rv.setTextViewText(R.id.widget_head, "高等数学")
        rv.setTextViewText(R.id.widget_meta, "10:10–11:45 · 教A301")
        rv.setTextViewText(R.id.widget_date, "10月19日 周一")
        rv.setTextViewText(R.id.widget_week, "第8周")
        rv.setTextViewText(R.id.widget_foot, "点开看完整课表")

        val root = FrameLayout(ctx)
        val view = rv.apply(ctx, root)

        assertNotNull("apply 返回了 null，说明布局没充气成功", view)
        val content = view.findViewById<android.view.View>(R.id.widget_content)
        assertNotNull("内容容器不见了", content)
        assertNotNull(view.findViewById<android.view.View>(R.id.widget_bg_img))
        assertNotNull(view.findViewById<android.view.View>(R.id.widget_divider))
        assertNotNull(view.findViewById<android.view.View>(R.id.widget_dot))
    }
}
