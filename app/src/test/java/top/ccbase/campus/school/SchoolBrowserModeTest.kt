package top.ccbase.campus.school

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchoolBrowserModeTest {
    @Test fun `手机和桌面模式必须切换视口且可还原`() {
        val wv = WebView(ApplicationProvider.getApplicationContext())
        applySchoolBrowserMode(wv, false)
        val mobileUa = wv.settings.userAgentString
        assertFalse(wv.settings.useWideViewPort)
        assertFalse(wv.settings.loadWithOverviewMode)
        applySchoolBrowserMode(wv, true)
        assertTrue(wv.settings.useWideViewPort)
        assertTrue(wv.settings.loadWithOverviewMode)
        assertNotEquals(mobileUa, wv.settings.userAgentString)
        applySchoolBrowserMode(wv, false)
        assertEquals(mobileUa, wv.settings.userAgentString)
        assertFalse(wv.settings.useWideViewPort)
        assertFalse(wv.settings.loadWithOverviewMode)
        wv.destroy()
    }

    /**
     * 桌面模式还有一个"以前根本没生效"的部分：字号。
     * 用户原话「桌面模式和手机模式根本没有区别」—— UA 换了、视口标志换了，
     * 但学校的页面写死 device-width，不压缩放就等于什么都没变。这里把字号与初缩放一起钉住。
     */
    @Test fun `字号三档循环_并且真的写给 WebView`() {
        val wv = WebView(ApplicationProvider.getApplicationContext())
        applySchoolBrowserMode(wv, false, 130)
        assertEquals(130, wv.settings.textZoom)
        applySchoolBrowserMode(wv, true, 160)
        assertEquals(160, wv.settings.textZoom)
        assertEquals(160, wv.settings.textZoom)
        wv.destroy()
    }

    @Test fun `字号标签三档能环回去`() {
        assertTrue(fieldZoomLabel(100).contains("标准"))
        assertTrue(fieldZoomLabel(130).contains("大"))
        assertTrue(fieldZoomLabel(160).contains("特大"))
        // 菜单里那一行必须点得懂：每次都要说清"点一下会变成什么"，否则用户不知道该点几次
        assertTrue(fieldZoomLabel(160).contains("回标准"))
    }
}
