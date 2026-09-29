package top.ccbase.campus.ui.me

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
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
 * 「我的 → 立即更新课表」这个按钮。
 *
 * 用户原话（2026-09-18）：「现在又多了一门课，但 App 里仍然没有显示（刷新了也更新课表了还是这样）」。
 * 根因就在这个按钮上：它**只把服务器缓存的计划再拉一遍**（`GET /plan`），
 * 完全不读教务，副标题却写着「从教务系统重新读一次」—— 点了当然什么也不会变。
 *
 * 所以这里钉的是**行为**：点下去必须真的打服务端那条「重读教务」的路
 * （`POST /api/v2/timetable/sync`，服务端存着教务账号）；
 * 那条路还不存在（老服务端 404）时，必须**如实说只做了从服务器同步**，不许继续糊弄。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class MeSyncButtonTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    /**
     * ⚠️ **必须用隔离的内存库**。
     *
     * 这个仓的 `CampusDb` 有个 JVM 级单例（`inst ?: ...`），而 gradle 会让多个测试类
     * 共用一个 JVM —— 拿 `app().db` 写计划，会把数据漏给**后面**跑的测试。
     * 第一版就是这么把 `TodayConfirmTest` 弄红的（单独跑绿、全量跑红）：
     * 症状是那条"已完成的任务…取消完成"找不到任务标题。
     */
    private lateinit var db: CampusDb

    private class Seen { val rows = mutableListOf<String>() }

    /** 真形状的 /plan 响应（夹具就是服务端那份表结构，见 src/test/resources/plan_no_timetable.json） */
    private val planBody: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("plan_no_timetable.json")!!
            .bufferedReader().readText()
    }

    private fun api(seen: Seen, syncCode: Int, syncBody: String) = CampusApi(
        base = "https://example.invalid",
        transport = Transport { m, u, _, _ ->
            val path = u.substringAfter("example.invalid")
            seen.rows += "$m $path"
            when {
                path == "/api/v2/timetable/sync" -> HttpReply(syncCode, syncBody)
                path == "/api/v2/plan" -> HttpReply(200, planBody)
                else -> HttpReply(404, """{"detail":"Not Found"}""")
            }
        },
    )

    @Before
    fun setUpDb() {
        db = Room.inMemoryDatabaseBuilder(app(), CampusDb::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        TokenStore.clear(app())   // 令牌在 SharedPreferences 里，别留给后面的测试
    }

    private fun render(seen: Seen, syncCode: Int, syncBody: String) {
        TokenStore.save(
            ctx = app(), token = "tok-1", expiresAt = "2099-01-01T00:00:00",
            user = ApiUser(uid = 7, student_id = "2026001", name = "同学"),
        )
        rule.setContent {
            CampusTheme {
                MeScreen(
                    ctx = app(), db = db,
                    api = api(seen, syncCode, syncBody),
                    version = "test", onLogin = {},
                )
            }
        }
        rule.onNodeWithText("立即更新课表").performClick()
    }

    private fun has(text: String, substring: Boolean = true) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `点立即更新课表_真的让服务器按教务重读`() {
        val seen = Seen()
        render(seen, 200, """{"ok":true,"changed":2,"results":[],"no_creds":[]}""")
        rule.waitUntil(20_000) { seen.rows.any { it == "POST /api/v2/timetable/sync" } }
        assertTrue(
            "点了「立即更新课表」必须打服务端「重读教务」那条路，否则课表永远不会变：" +
                seen.rows.joinToString(),
            seen.rows.any { it == "POST /api/v2/timetable/sync" },
        )
        // 重读之后还要把新计划落到本机，不然界面还是旧的
        rule.waitUntil(20_000) { seen.rows.any { it == "GET /api/v2/plan" } }
        rule.waitUntil(20_000) { has("重读教务") }
    }

    @Test
    fun `服务端不支持重读课表时_如实说是从服务器同步`() {
        val seen = Seen()
        render(seen, 404, """{"detail":"Not Found"}""")
        // 老服务端：能力没有就没有 —— 但**不许**继续说"已更新/从教务重新读了一次"
        rule.waitUntil(20_000) { has("从服务器同步") || has("还不支持") }
        assertTrue(
            "服务端没有这条接口时，文案必须诚实（如「从服务器同步」）：" +
                rule.onAllNodesWithText("", substring = true).fetchSemanticsNodes().size,
            has("从服务器同步") || has("还不支持"),
        )
    }

    @Test
    fun `登录过期时给出重新登录入口`() {
        val seen = Seen()
        render(seen, 401, """{"detail":"登录已过期，请重新登录"}""")
        rule.waitUntil(20_000) { has("登录已过期") }
        assertTrue(
            "登录过期是**用户唯一能自己解决**的一类失败，必须给入口，不能只写一句失败",
            rule.onAllNodesWithText("重新登录").fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
