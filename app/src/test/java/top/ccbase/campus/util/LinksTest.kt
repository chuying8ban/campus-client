package top.ccbase.campus.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * 外链出口的**闸门**：App 里所有"把人送到站外"的地方都走 [Links.open]。
 *
 * 为什么这条必须有用例（用户 2026-09-20 问的就是这个）：
 *   · 学习库/任务/今日的资料行是**共享库**来的 —— 链接是别人收录的，不是自家常量，
 *     所以"能不能点开、点开会去哪"必须在**唯一的那个出口**上钉死；
 *   · 只放行 http/https 是这里的核心规则：`intent://`、`file://`、`javascript:`、`content://`
 *     只要有一个漏过去，App 就成了别人发跳板的工具（`intent://` 还能拉任意 App 的内部组件）。
 *   · 挡掉的时候**必须说话**（toast），不能吞掉让用户以为"点了没反应"。
 */
@RunWith(RobolectricTestRunner::class)
class LinksTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(app).clearNextStartedActivities()
    }

    @Test
    fun `只放行 http 与 https_其余 scheme 一个都不许启动`() {
        val hostile = listOf(
            "javascript:alert(1)",
            "intent://evil.example/#Intent;scheme=https;package=com.other;end",
            "file:///etc/passwd",
            "content://com.android.contacts/data/1",
            "data:text/html,<b>x</b>",
            "ftp://files.example/x.zip",
            "//evil.example/relative",
            "http:/one-slash",
            "mailto:someone@example.com",
            "",
            "   ",
        )
        hostile.forEach { u ->
            Links.open(app, u)
            assertNull("这个链接不该被启动：$u", shadowOf(app).nextStartedActivity)
        }
    }

    @Test
    fun `挡掉的时候要说一句_不能点了没反应`() {
        Links.open(app, "javascript:alert(1)")
        assertEquals(
            "非法链接必须给提示（静默是用户最反感的失败方式）",
            "这个链接不是网页，暂不支持打开",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `http 与 https 照常打开_大小写不敏感`() {
        listOf("https://www.bilibili.com/video/BV1xx", "http://old.example/course/1", "HTTPS://UPPER.example/x")
            .forEach { u ->
                shadowOf(app).clearNextStartedActivities()
                Links.open(app, u)
                assertEquals("这条应该被交给浏览器打开", u, shadowOf(app).nextStartedActivity?.data.toString())
            }
    }

    @Test
    fun `null 与空串既不启动也不提示`() {
        Links.open(app, null)
        Links.open(app, "")
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull("空链接不是「非法链接」，不该弹 toast", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `域名只留能给人看的那一段`() {
        // 资料行上要露出"要去哪"（用户 2026-09-20：共享库里的链接是别人收录的，
        // 点之前就该看得见目的地）。这条钉死显示成什么样。
        assertEquals("bilibili.com", Links.hostOf("https://www.bilibili.com/video/BV1xx?p=1"))
        assertEquals("bilibili.com", Links.hostOf("https://bilibili.com"))
        assertEquals("example.com", Links.hostOf("http://example.com:8080/a/b#c"))
        assertEquals("upper.example", Links.hostOf("HTTPS://UPPER.example/x"))
        assertEquals("bilibili.com", Links.hostOf("bilibili.com/x"))
        assertEquals("", Links.hostOf(null))
        assertEquals("", Links.hostOf("   "))
    }
}
