package top.ccbase.campus.ui.me

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiUser
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.Transport
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「提建议」页。
 *
 * 用户 2026-09-21：「加一个提建议功能，每个用户都可以在 app 内给这个 app 提供建议」。
 *
 * 这是一个**给全体用户看的写入口**，所以两件事必须钉死：
 *  1. 空内容不发请求（别用空请求去污染作者那边）；
 *  2. 发出去的请求体里带上了写的内容和版本号（版本号是为了我能定位"哪一版的问题"）。
 * 另外「我提过的」要能显示出服务端翻好的中文状态 —— 提建议的人得知道自己的话被看到了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class FeedbackScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    /** 发出去的请求体（断言用）；假服务端把它记下来 */
    private val sent = mutableListOf<String>()

    /** 「我提过的」的假响应。带回复时状态也就成了「已看过」——跟服务端 reply_feedback 的行为一致 */
    private fun mineJson(reply: String) = if (reply.isEmpty())
        """{"items":[{"id":1,"text":"想要课表导出","created_at":"2026-09-21T02:00:00+00:00",
            |"status":"new","status_cn":"已收到"}]}""".trimMargin()
    else
        """{"items":[{"id":1,"text":"想要课表导出","created_at":"2026-09-21T02:00:00+00:00",
            |"status":"read","status_cn":"已看过","replied":true,
            |"reply":"$reply","replied_at":"2026-09-21T03:00:00+00:00"}]}""".trimMargin()

    private fun api(reply: String = "") = CampusApi(
        base = "https://example.invalid",
        transport = Transport { _, u, _, body ->
            when (u.substringAfter("example.invalid")) {
                "/api/v2/feedback/mine" -> HttpReply(200, mineJson(reply))
                "/api/v2/feedback" -> {
                    sent += body ?: ""
                    HttpReply(200, """{"ok":true,"note":"收到了，谢谢 —— 我会看"}""")
                }
                else -> HttpReply(404, """{"detail":"Not Found"}""")
            }
        },
    )

    @After
    fun tearDown() {
        TokenStore.clear(app())
    }

    private fun render(reply: String = "") {
        TokenStore.save(
            ctx = app(), token = "tok-1", expiresAt = "2099-01-01T00:00:00",
            user = ApiUser(uid = 7, student_id = "2026001", name = "同学"),
        )
        rule.setContent {
            CampusTheme {
                FeedbackScreen(ctx = app(), version = "1.80", api = api(reply), onClose = {})
            }
        }
    }

    @Test
    fun 作者回复了_我提过的里会显示出来() {
        render(reply = "下版加上，导出成 CSV")
        rule.waitForIdle()
        rule.onNodeWithTag(FB_REPLY).assertExists()
        rule.onNodeWithText("回复：下版加上，导出成 CSV").assertExists()
    }

    @Test
    fun 没回复就不画回复行() {
        render()
        rule.waitForIdle()
        rule.onNodeWithTag(FB_REPLY).assertDoesNotExist()
    }

    @Test
    fun 回复的文案里不出现_作者_后台_字样() {
        // 保密红线（用户 2026-09-17 / 09-20）：普通用户屏幕上不该出现这些词
        render(reply = "下版加上")
        rule.waitForIdle()
        rule.onNodeWithText("回复：下版加上").assertExists()
        for (w in listOf("作者", "后台", "抢课")) {
            assertTrue("回复行里不该出现「$w」", !rule.onNodeWithText("回复：下版加上").toString().contains(w))
        }
    }

    @Test
    fun 空内容点提交_一个请求都不发() {
        render()
        rule.onNodeWithTag(FB_SUBMIT).performClick()
        rule.waitForIdle()
        assertTrue("空内容不该发请求", sent.isEmpty())
    }

    @Test
    fun 写了内容点提交_请求体里带着文本和版本号() {
        render()
        rule.onNodeWithTag(FB_INPUT).performTextInput("加个课表导出吧")
        rule.onNodeWithTag(FB_SUBMIT).performClick()
        rule.waitForIdle()
        assertEquals(1, sent.size)
        assertTrue("内容要发上去", sent[0].contains("加个课表导出吧"))
        assertTrue("版本号要发上去（服务端存下来，方便定位是哪一版的问题）", sent[0].contains("1.80"))
    }

    @Test
    fun 提交成功后清空输入框_并显示服务端的回话() {
        render()
        rule.onNodeWithTag(FB_INPUT).performTextInput("加个课表导出吧")
        rule.onNodeWithTag(FB_SUBMIT).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("收到了，谢谢 —— 我会看").assertExists()
        // 清空了：占位符又露出来了
        rule.onNodeWithText("写在这里…").assertExists()
    }

    @Test
    fun 我提过的会渲染出来_状态是中文() {
        render()
        rule.waitForIdle()
        rule.onNodeWithText("想要课表导出").assertExists()
        rule.onNodeWithText("已收到 · 2026-09-21").assertExists()
    }
}
