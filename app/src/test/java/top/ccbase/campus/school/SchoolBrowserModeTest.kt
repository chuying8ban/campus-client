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
}
