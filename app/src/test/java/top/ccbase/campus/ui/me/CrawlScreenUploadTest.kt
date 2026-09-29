package top.ccbase.campus.ui.me

import top.ccbase.campus.net.StudentError

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.room.Room
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
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「手机自己抓课表」走到底的三件事（**行为级**，不是纯函数）：
 *
 *  ① 上传课表这一条请求**必须带登录令牌**（服务端那条路由要合法 token，
 *     没带就是 401「缺少登录令牌」，上传等于白抓）；
 *  ② 被服务端拒了要**看得见**：401 必须给「重新登录」入口，不许只写一句"失败"
 *     （2026-09-18 真机：用户点了抓取，界面停在「正在登录教务…」不动、也不报错）；
 *  ③ 抓到了要**说得清读到了什么**（几条活动 / 几门课），不是只有"成功"两个字。
 *
 * 全程假传输层 + 真教务样本：不联网、不碰真教务、没有任何真实密码。
 * `Net.dial` 是全局的（App 里共用一个），所以这里替换它、并在 @After 换回来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class CrawlScreenUploadTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    private class Seen {
        val rows = mutableListOf<String>()
        /** 上传课表那条请求带的 Authorization */
        var uploadAuth: String? = null
    }

    /** 真教务样本（19 段课）—— 和 EamsClientTest 用同一份 */
    private val printData: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("eams/print_data.json")!!
            .bufferedReader().readText()
    }

    private fun fake(seen: Seen, uploadCode: Int, uploadBody: String) = Transport { m, u, h, _ ->
        val path = u.substringAfter("example.invalid")
        when {
            // App 自己的服务器
            path.startsWith("/api/") -> {
                seen.rows += "$m $path"
                if (path.endsWith("/plan/from-activities")) seen.uploadAuth = h["Authorization"]
                when {
                    path.endsWith("/plan/from-activities") -> HttpReply(uploadCode, uploadBody)
                    path.endsWith("/plan") -> HttpReply(200, """{"courses":[],"tasks":[]}""")
                    else -> HttpReply(404, """{"detail":"Not Found"}""")
                }
            }
            // 教务
            path.endsWith("/student/login-salt") -> HttpReply(200, "\"S9\"")
            path.endsWith("/student/login") -> HttpReply(
                200, """{"result":true}""",
                mapOf("Set-Cookie" to "SESSION=k1; Path=/, __pstsid__=k2; Path=/"),
            )
            path.contains("/print-data") -> HttpReply(200, printData, mapOf("Content-Type" to "application/json"))
            path.contains("course-table") -> HttpReply(
                200,
                "var semesters = JSON.parse('[{\"id\":1,\"abbrEn\":\"2025-2026-1\"}," +
                    "{\"id\":82,\"abbrEn\":\"2026-2027-2\"}]');\n" +
                    "var currentSemester = {\"id\":82,\"abbrEn\":\"2026-2027-2\"," +
                    "\"calendarAssoc\":[{\"id\":1}]};",
            )
            else -> HttpReply(404, "nope")
        }
    }

    /**
     * ⚠️ **必须用隔离的内存库**：`CampusDb` 在仓里是 JVM 级单例，gradle 又让多个测试类共用一个
     * JVM —— 抓课表成功会往库里落整份计划，拿 `app().db` 就等于往**后面**跑的测试里灌数据
     * （第一版把 `TodayConfirmTest` 弄红过：单独跑绿、全量跑红）。
     */
    private lateinit var db: CampusDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app(), CampusDb::class.java)
            .allowMainThreadQueries().build()
        TokenStore.save(
            ctx = app(), token = "tok-1", expiresAt = "2099-01-01T00:00:00",
            user = ApiUser(uid = 7, student_id = "2026001", name = "同学"),
        )
    }

    @After
    fun tearDown() {
        db.close()
        TokenStore.clear(app())
        Net.dial = DialTransport()          // 别把假传输层留给后面的测试
    }

    /** 走一遍真实操作：填学号密码 → 点「开始抓取」 */
    private fun crawl(seen: Seen, uploadCode: Int, uploadBody: String) {
        val t = fake(seen, uploadCode, uploadBody)
        Net.dial = DialTransport(store = MemoryRouteStore(), direct = t, noSni = t)
        rule.setContent {
            CampusTheme {
                CrawlScreen(
                    db = db,   // 隔离的内存库：见下方 setUpDb 的注释
                    api = CampusApi(base = "https://example.invalid", transport = t),
                    onClose = {},
                    // 看门狗设成永远到不了的点：不然它和抓取流程赛跑，全量跑时偶发红
                    watchdogMs = 10 * 60_000,
                )
            }
        }
        rule.onNode(hasSetTextAction() and hasText("学号", substring = true))
            .performTextInput("2026001")
        rule.onNode(hasSetTextAction() and hasText("教务系统密码", substring = true))
            .performTextInput("jw-pw-123456")
        rule.onNodeWithText("开始抓取").performClick()
    }

    private fun waitForText(text: String, ms: Long = 45_000) {
        try {
            rule.waitUntil(ms) {
                rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            // 负载下偶发红时，这棵树直接告诉我们"当时屏上是哪一句"：
            // 是没等到，还是别的文案（比如看门狗的超时）把它顶掉了
            throw AssertionError("等「$text」超时（${ms}ms）。当时的界面：\n${rule.onRoot().printToString()}", e)
        }
    }

    @Test
    fun `上传课表带登录令牌`() {
        val seen = Seen()
        crawl(seen, 200, """{"ok":true,"counts":{},"stats":{}}""")
        rule.waitUntil(20_000) { seen.uploadAuth != null }
        assertEquals(
            "上传课表这条请求必须带 App 的登录令牌 —— 不带就是 401「缺少登录令牌」",
            "Bearer tok-1", seen.uploadAuth,
        )
        assertEquals("POST", seen.rows.first { it.endsWith("/plan/from-activities") }.substringBefore(' '))
        assertTrue(
            "要打的正是服务端那条路由",
            seen.rows.any { it.endsWith("POST /api/v2/plan/from-activities") },
        )
    }

    @Test
    fun `服务端401时给重新登录入口_并且不许停在正在`() {
        val seen = Seen()
        crawl(seen, 401, """{"detail":"登录已过期，请重新登录"}""")
        waitForText("登录已过期")
        assertTrue(
            "401 必须给「重新登录」入口（用户能做的只有重新登录，别让他自己找入口）",
            rule.onAllNodesWithText("重新登录").fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "被拒之后不许再停在「正在…」（真机症状：永远停在「正在登录教务…」）",
            rule.onAllNodesWithText("正在", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `服务端还没有这条接口时_不再把"哪个接口"讲给学生`() {
        val seen = Seen()
        crawl(seen, 404, """{"detail":"Not Found"}""")
        // 2026-09-24 定稿：接口/部署状态算内部信息 → 学生只看到那一句话；
        // 但**行为**仍要和"网络不通"分开（这类失败不给"再点一次"的暗示，SyncLogic 里按 code 判）。
        waitForText(StudentError.TEXT)
        rule.waitForIdle()
        assertTrue(
            "404 也要显示那句话：$seen",
            rule.onAllNodesWithText(StudentError.TEXT, substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "不许把「哪个接口/服务端没部署」讲给学生",
            rule.onAllNodesWithText("还没有这个接口", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `抓到了要说清读到了几条活动几门课`() {
        val seen = Seen()
        crawl(seen, 200, """{"ok":true,"counts":{},"stats":{}}""")
        // 用户凭什么相信"抓到了"：把手机真读到的规模说出来
        waitForText("条上课活动")
        rule.waitForIdle()
        assertTrue(
            "要说清读到了什么（几条活动/几门课），不是只有一句「成功」",
            rule.onAllNodesWithText("门课", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
