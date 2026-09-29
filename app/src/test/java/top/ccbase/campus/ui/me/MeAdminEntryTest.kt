package top.ccbase.campus.ui.me

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
 * 「我的 → 后台管理」入口。
 *
 * 用户 2026-09-20 要的是「给我自己加一个后台管理系统，能看到用户的数量等信息」，
 * 并且入口要**放在明处能点到**（他反感"只能靠改地址进去"的隐藏设计）。
 *
 * 这里钉三件事：
 *  1. 作者看得到这个入口；
 *  2. **别人看不到**（别人的手机里不该出现"后台管理"这几个字）；
 *  3. 点它打开的是**自己的域名**（用户明确不要 trycloudflare 那种临时隧道）。
 *
 * 注意：这个开关只是界面层。真正的权限闸在服务端那几条 admin 接口上：
 * 非作者拿着令牌去调，服务端回 403 —— 所以就算这里被改成 true，也拿不到任何数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class MeAdminEntryTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    /** 用隔离的内存库：CampusDb 在 JVM 里是单例，共用 app().db 会把数据漏给后面的测试 */
    private lateinit var db: CampusDb

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

    /**
     * `/me` 按传进来的 isAuthor 回 —— 与真服务端一致。
     * （页面进去会补一次作者标记；如果假服务端回的跟本地不一致，结论就会被改回去。）
     */
    private fun api(isAuthor: Boolean, canGrab: Boolean = false) = CampusApi(
        base = "https://example.invalid",
        transport = Transport { _, u, _, _ ->
            when (u.substringAfter("example.invalid")) {
                "/api/v2/me" -> HttpReply(
                    200,
                    """{"user":{"uid":7,"student_id":"2026001","name":"同学","is_author":$isAuthor,"can_grab":$canGrab}}""",
                )
                else -> HttpReply(404, """{"detail":"Not Found"}""")
            }
        },
    )

    private fun render(
        isAuthor: Boolean,
        canGrab: Boolean = false,
        onOpenAdmin: () -> Unit = {},
        onOpenGrab: () -> Unit = {},
    ) {
        TokenStore.save(
            ctx = app(), token = "tok-1", expiresAt = "2099-01-01T00:00:00",
            user = ApiUser(
                uid = 7, student_id = "2026001", name = "同学",
                isAuthor = isAuthor, canGrab = canGrab,
            ),
        )
        rule.setContent {
            CampusTheme {
                MeScreen(
                    ctx = app(), db = db, api = api(isAuthor, canGrab), version = "test", onLogin = {},
                    onOpenAdmin = onOpenAdmin,
                    onOpenGrab = onOpenGrab,
                )
            }
        }
    }

    private fun has(text: String) =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** 等页面稳定：/me 回来、主体渲染出来（"看板"这一节是每台设备都有的） */
    private fun waitStable() = rule.waitUntil(20_000) { has("看板") }

    @Test
    fun `作者能看到后台管理入口`() {
        render(isAuthor = true)
        rule.waitUntil(20_000) { has("后台管理") }
        assertTrue("作者必须看得到入口（用户要求放在明处，不要隐藏设计）", has("后台管理"))
    }

    @Test
    fun `非作者看不到这个入口`() {
        render(isAuthor = false)
        waitStable()
        assertTrue("别人不该在「我的」页看到「后台管理」", !has("后台管理"))
    }

    @Test
    fun `点入口走 App 内嵌_不再丢给系统浏览器`() {
        // 用户 2026-09-20 在真机上点这个入口，弹出的却是「是否允许打开 Pure 浏览器」，
        // 点「拒绝」则完全没反应（Android 不给 startActivity 抛异常）。
        // 所以这条测试钉死：入口只切 App 内嵌页，不许再发外部浏览器 intent。
        var opened = 0
        render(isAuthor = true, onOpenAdmin = { opened++ })
        rule.waitUntil(20_000) { has("后台管理") }
        rule.onNodeWithText("后台管理").performClick()
        assertEquals("点了入口应该切到 App 内嵌的后台页", 1, opened)
        assertNull(
            "不该再发外部浏览器 intent（会弹系统框、拒绝后静默无反应）",
            shadowOf(app()).nextStartedActivity,
        )
    }

    @Test
    fun `作者能看到抢课入口_点了进抢课页`() {
        // 2026-09-29 口径：抢课是**测试功能**，底部栏那一格按 can_grab 显隐；
        // 「我的 → 后台」这一节里也留一个入口（同一个 can_grab 判据，两条路都通）。
        var opened = 0
        render(isAuthor = true, canGrab = true, onOpenGrab = { opened++ })
        rule.waitUntil(20_000) { has("抢课") }
        rule.onNodeWithText("抢课").performClick()
        assertEquals("点了抢课入口应该进抢课页", 1, opened)
    }

    @Test
    fun `非作者连抢课这两个字都看不到`() {
        // 判据是服务端的 can_grab，不是"是不是作者"：render 里 canGrab = false，
        // 所以这一节里不该长出抢课入口。（这一页当年为此删过一行「抢课功能」——
        // 那行等于对所有人宣布功能存在，跟"测试功能、按权限开放"对不上。）
        render(isAuthor = false, canGrab = false)
        waitStable()
        assertTrue("同学那边不该出现「抢课」两个字", !has("抢课"))
    }
}
