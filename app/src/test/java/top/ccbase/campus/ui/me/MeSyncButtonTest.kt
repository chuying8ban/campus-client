package top.ccbase.campus.ui.me

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiUser
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.Transport
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「我的 → 更新教务课表」这个按钮（credential-free 新契约）。
 *
 * 旧版这里叫「立即更新课表」，点下去会打服务端 `POST /api/v2/timetable/sync`
 * —— 那条路靠**服务端存着你的教务密码**代读。现在密码不再上传，这条路退役了。
 *
 * 所以这里钉的是新行为：
 *  ① 点「更新教务课表」**不发任何自有服务器请求**，而是打开学校官方登录页（WebView）；
 *  ② 靠服务端凭据重读教务的旧入口不再出现（不许有两个"更新课表"让人犹豫点哪个）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class MeSyncButtonTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    private lateinit var db: CampusDb

    private class Seen { val rows = mutableListOf<String>() }

    private fun api(seen: Seen) = CampusApi(
        base = "https://example.invalid",
        transport = Transport { m, u, _, _ ->
            seen.rows += "$m ${u.substringAfter("example.invalid")}"
            HttpReply(404, """{"detail":"Not Found"}""")
        },
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app(), CampusDb::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        TokenStore.clear(app())
    }

    private fun has(text: String, substring: Boolean = true) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun render(seen: Seen, onOpenCrawl: () -> Unit) {
        TokenStore.save(
            ctx = app(), token = "tok-1", expiresAt = "2099-01-01T00:00:00",
            user = ApiUser(uid = 7, student_id = "2026001", name = "同学"),
        )
        rule.setContent {
            CampusTheme {
                MeScreen(
                    ctx = app(), db = db, api = api(seen), version = "test",
                    onLogin = {}, onOpenCrawl = onOpenCrawl,
                )
            }
        }
        rule.waitUntil(20_000) { has("更新教务课表") }
    }

    @Test
    fun `点更新教务课表_打开官方登录页_不打任何服务端接口`() {
        val seen = Seen()
        var opened = false
        render(seen) { opened = true }

        rule.onNodeWithText("更新教务课表").performClick()
        rule.waitForIdle()

        assertTrue("「更新教务课表」必须打开官方登录页读一次", opened)
        assertEquals(
            "读教务不许再经过自有服务器（密码不上传）：${seen.rows}",
            emptyList<String>(), seen.rows,
        )
    }

    @Test
    fun `靠服务端凭据重读教务的旧入口不再出现`() {
        val seen = Seen()
        render(seen) {}
        rule.waitForIdle()
        assertTrue("旧的「立即更新课表」不该还在", !has("立即更新课表"))
        assertTrue(
            "副标题要说清密码不出手机",
            has("密码不出手机", substring = true),
        )
    }
}
