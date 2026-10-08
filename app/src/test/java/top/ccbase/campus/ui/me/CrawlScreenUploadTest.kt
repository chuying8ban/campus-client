package top.ccbase.campus.ui.me

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import top.ccbase.campus.net.DialTransport
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.MemoryRouteStore
import top.ccbase.campus.net.Net
import top.ccbase.campus.net.Transport
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.school.SchoolCloudSync
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「导入官方教务课表」这一页**新契约**下的安全行为。
 *
 * 旧版「填学号密码 → 点开始抓取 → 上传 /plan/from-activities」已经退役。现在：
 *  ① 打开导入页只是渲染**说明 + 一个官方登录入口**，一个自有服务器请求都不发；
 *  ② 云端同步**必须先显式同意**；同意时没有账号才新建随机访客；
 *  ③ **已有令牌失效（401）时绝不自动换成随机访客**冒充旧账号 —— 那会把数据落到
 *     另一个陌生身份下、卸载即丢，而用户以为还是原来那个账号；
 *  ④ 上传出去的只有清洗后的课表字段，密码/Cookie/HTML 一个字节都不带。
 *
 * 全程假传输层：不联网、不碰真教务、没有任何真实密码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class CrawlScreenUploadTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    private class Seen {
        val rows = mutableListOf<String>()
        var uploadAuth: String? = null
        var uploadBody: String? = null
    }

    private lateinit var db: CampusDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app(), CampusDb::class.java)
            .allowMainThreadQueries().build()
        TokenStore.clear(app())
    }

    @After
    fun tearDown() {
        db.close()
        TokenStore.clear(app())
        Net.dial = DialTransport()          // 别把假传输层留给后面的测试
    }

    private fun guestJson() =
        """{"token":"g1","expires_at":"2099-01-01T00:00:00",
            "user":{"uid":9,"student_id":"guest-9","name":"访客"},"recovery":"卸载后无法找回"}"""

    private val activities =
        """[{"courseName":"数学分析","lessonName":"数学分析（I）","courseCode":"M1","weekday":1,"startUnit":1,"endUnit":2,"weekIndexes":[1,2,3]}]"""

    /** 假服务端：guest / 上传 / plan。上传返回 [uploadCode]。 */
    private fun server(seen: Seen, uploadCode: Int) = Transport { m, u, h, b ->
        val path = u.substringAfter("example.invalid")
        when {
            path.endsWith("/api/v2/guest") -> {
                seen.rows += "$m $path"
                HttpReply(200, guestJson())
            }
            path.endsWith("/plan/from-activities") -> {
                seen.rows += "$m $path"
                seen.uploadAuth = h["Authorization"]
                seen.uploadBody = b
                HttpReply(uploadCode, """{"ok":true,"counts":{},"stats":{}}""")
            }
            path.endsWith("/plan") -> HttpReply(200, """{"courses":[],"tasks":[]}""")
            else -> HttpReply(404, """{"detail":"Not Found"}""")
        }
    }

    // --------------------------------------------------------------- UI：打开页

    @Test
    fun `打开导入页_只渲染说明不发任何请求_并写明密码不上传`() {
        val seen = Seen()
        val t = server(seen, 200)
        Net.dial = DialTransport(store = MemoryRouteStore(), direct = t, noSni = t)
        rule.setContent {
            CampusTheme {
                CrawlScreen(
                    db = db,
                    api = CampusApi(base = "https://example.invalid", transport = t),
                    onClose = {},
                    watchdogMs = 10 * 60_000,
                )
            }
        }
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("打开官方教务登录").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        assertTrue("导入页打开时不该有任何自有服务器请求：${seen.rows}", seen.rows.isEmpty())
        assertTrue(
            "页面必须写明密码不读取/不保存/不上传",
            rule.onAllNodesWithText("不读取", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "页面必须说清默认只存本机",
            rule.onAllNodesWithText("不会发给任何服务器", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    // -------------------------------------------------- 云端同步（显式同意的行为）

    @Test
    fun `没有令牌时_先建随机访客_上传请求带 Bearer 令牌`() = runBlocking {
        val seen = Seen()
        val api = CampusApi(base = "https://example.invalid", transport = server(seen, 200))
        val r = SchoolCloudSync.uploadConsented(app(), db, api, activities)

        assertTrue("云端同步应当成功：$r", r is ApiResult.Ok)
        assertTrue("没有账号要走 guest 端点", seen.rows.any { it == "POST /api/v2/guest" })
        assertEquals(
            "上传课表必须带 App 令牌（不带就是 401 缺少令牌）",
            "Bearer g1", seen.uploadAuth,
        )
        assertTrue(seen.rows.any { it == "POST /api/v2/plan/from-activities" })
    }

    @Test
    fun `已有令牌失效时_绝不自动改访客冒充旧账号`() = runBlocking {
        TokenStore.save(
            ctx = app(), token = "old-tok", expiresAt = "2099-01-01T00:00:00",
            user = ApiUser(uid = 1, student_id = "2026001", name = "同学"),
        )
        val seen = Seen()
        val api = CampusApi(base = "https://example.invalid", transport = server(seen, 401))
        val r = SchoolCloudSync.uploadConsented(app(), db, api, activities)

        assertTrue(r is ApiResult.Err)
        assertEquals(401, (r as ApiResult.Err).code)
        assertEquals("必须先拿旧令牌去试", "Bearer old-tok", seen.uploadAuth)
        assertFalse(
            "401 之后绝不允许自动新建访客身份（否则数据落在陌生账号下、用户却以为还是原来的）",
            seen.rows.any { it == "POST /api/v2/guest" },
        )
        assertTrue(
            "报错必须说清新访客与原账号不是一回事：${r.message}",
            r.message.contains("新身份") || r.message.contains("原账号"),
        )
    }

    @Test
    fun `本机已过期令牌也不自动新建访客`() = runBlocking {
        TokenStore.save(app(), "expired-old", "2000-01-01T00:00:00",
            ApiUser(uid = 1, student_id = "dummy", name = "测试"))
        val seen = Seen()
        val r = SchoolCloudSync.uploadConsented(app(), db,
            CampusApi(base = "https://example.invalid", transport = server(seen, 200)), activities)
        assertTrue(r is ApiResult.Err)
        assertEquals(401, (r as ApiResult.Err).code)
        assertTrue("本机过期不能发送任何请求", seen.rows.isEmpty())
        assertEquals("expired-old", TokenStore.token(app()))
    }

    @Test
    fun `上传出去的只有清洗后的课表_不带密码cookie`() = runBlocking {
        val seen = Seen()
        val api = CampusApi(base = "https://example.invalid", transport = server(seen, 200))
        SchoolCloudSync.uploadConsented(app(), db, api, activities)
        val body = seen.uploadBody.orEmpty()
        assertTrue("请求体里应该有课表字段", body.contains("courseName"))
        assertFalse("请求体里不许出现 password", body.contains("password", ignoreCase = true))
        assertFalse("请求体里不许出现 cookie", body.contains("cookie", ignoreCase = true))
        assertFalse("请求体里不许出现 html", body.contains("html", ignoreCase = true))
    }
}
