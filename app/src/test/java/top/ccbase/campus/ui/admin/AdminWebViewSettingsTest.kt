package top.ccbase.campus.ui.admin

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 后台 WebView 的设置护栏。
 *
 * 用户 2026-09-21 反馈「后台有些卡顿，有时候翻不动」。
 * 页面那侧查过了：299 个节点、原生 body 滚动、没有 fixed 覆盖层、没有吞触摸事件的代码
 * —— 所以病症在 WebView 自己身上：
 *
 * · `useWideViewPort` 不设：Android 会按 980px 的老视口渲染，整页被缩小，
 *   **而且它得判断手指是在滚动还是在双击缩放** —— 摸上去就是划不动、划一下要等一下。
 * · 缩放功能开着会加剧这点（每次触摸都要等一个双击判定窗口）。
 *
 * 这个用例把这些设置钉住：以后谁把它删了，卡顿会回来，而测试会先红。
 */
@RunWith(RobolectricTestRunner::class)
class AdminWebViewSettingsTest {

    private fun makeWebView() = buildAdminWebView(
        ApplicationProvider.getApplicationContext<Context>(),
        token = "t0ken",
        onLoading = {},
        onError = {},
    )

    @Test
    fun 视口按设备宽度渲染_不要980px老视口() {
        val s = makeWebView().settings
        assertTrue("不设这个就会被按 980px 渲染、整页缩小", s.useWideViewPort)
        assertTrue("配合上一条才能让页面按屏幕宽度排版", s.loadWithOverviewMode)
    }

    @Test
    fun 关掉缩放_手势才能立刻变成滚动() {
        val s = makeWebView().settings
        assertFalse("开着缩放，每次触摸都要等双击判定 —— 就是「翻不动」的来源", s.supportZoom())
        assertFalse(s.builtInZoomControls)
        assertFalse(s.displayZoomControls)
        assertEquals("正文字号不该被系统字体设置放大", 100, s.textZoom)
    }

    @Test
    fun 滚动条与边缘回弹都不画() {
        val w = makeWebView()
        assertFalse(w.isVerticalScrollBarEnabled)
        assertEquals(View.OVER_SCROLL_NEVER, w.overScrollMode)
    }

    @Test
    fun 功能没被砍_JS与localStorage仍然开着() {
        val s = makeWebView().settings
        assertTrue("页面是 JS 渲染的", s.javaScriptEnabled)
        assertTrue("令牌存在 localStorage 里", s.domStorageEnabled)
    }
}
