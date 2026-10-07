package top.ccbase.campus.ui.me

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
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
 * 「App 里没有后台」—— 所有人装的是同一个包。
 *
 * 用户 2026-10-07 的口径：「**以后 App 不区分作者版和用户版，所有人的都一样**」。
 * 在那之前，后台管理是"只进作者包"的：编译期 `BuildConfig.AUTHOR_BUILD` + 运行期 `is_author`
 * 两层闸（这文件原来叫 MeAdminEntryTest，钉的是"作者看得到、别人看不到"）。
 * 现在包只有一个、后台搬去了网页端（`https://<站点>/admin/`，只有作者口令能进），
 * 所以这里改钉**反面**：
 *
 *  ① 服务端就算回 `is_author: true`，屏幕上也不许冒出任何后台入口/后台字样；
 *  ② 源码里不许再出现那些只有作者看得到的文案（"后台管理"、"查看服务器上保存的密码"）——
 *     以前它们靠 R8 才摘得干净，而本项目 **minifyEnabled false**，等于留在每个人的 dex 里；
 *  ③ 「我的」页仍然没有监控入口（监控只在底部栏那一格 —— 与后台无关，顺手一起钉住）。
 *
 * 服务端的真闸一个字没动：admin 那几条接口照样只认作者（账号 + 受信设备），
 * App 端改什么都没有用 —— 这几条只是保证界面不上不下地留个入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class MeAdminAbsentTest {

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

    /** `/me` 按传进来的 isAuthor 回 —— 与真服务端一致（服务端仍然会带这个字段） */
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

    private fun render(isAuthor: Boolean, canGrab: Boolean = false) {
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
                )
            }
        }
    }

    private fun has(text: String) =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** 等页面稳定：/me 回来、主体渲染出来（"看板"这一节是每台设备都有的） */
    private fun waitStable() = rule.waitUntil(20_000) { has("看板") }

    @Test
    fun `作者身份也看不到任何后台入口_包只有一个`() {
        render(isAuthor = true)
        waitStable()
        assertTrue("App 里不该再有后台入口（哪怕服务端说这人是作者）", !has("后台"))
        assertTrue("「后台管理」这四个字不该出现在任何人的屏幕上", !has("后台管理"))
        assertTrue("凭据回显入口也不该有（它只在网页后台里）", !has("查看服务器上保存的密码"))
    }

    @Test
    fun `普通用户的我的页同样没有后台字样`() {
        render(isAuthor = false)
        waitStable()
        assertTrue("别人不该看到「后台」", !has("后台"))
    }

    @Test
    fun `我的页不再有监控入口`() {
        // 2026-09-30 用户口径：「把我的里面的监控删了」—— 监控只在底部菜单栏那一格，
        // 同一件事不留两个入口。监控本身仍对每个登录用户开放（判据在底部栏，见 TabWiringTest）。
        render(isAuthor = true, canGrab = true)
        waitStable()
        assertTrue("我的页不该再有「监控」这一行", !has("监控"))
    }

    @Test
    fun `源码里不许再留后台专属文案`() {
        // 为什么扫源码而不是只测界面：本项目 release 没开代码压缩（minifyEnabled false），
        // 留在代码里的字符串**真的会进每个人的 dex**。以前靠 R8 摘，现在摘不掉，只能不留。
        val forbidden = listOf("后台管理", "查看服务器上保存的密码", "只有作者能进")
        val root = File("src/main")
        assertTrue("找不到 src/main（测试的工作目录变了？）", root.isDirectory)
        val bad = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, raw ->
                val code = codeOnly(raw)
                forbidden.forEach { tok ->
                    if (code.contains(tok)) bad += "${f.path}:${i + 1}  $tok  ${code.trim().take(70)}"
                }
            }
        }
        assertTrue(
            "这些是「只有作者看得到」的旧文案，现在它们会进每个人的包：\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    /** 只留代码：去掉整行 `//`、KDoc（`*` 开头）与行尾注释 —— 说明里提到这些词不算数 */
    private fun codeOnly(line: String): String {
        val t = line.trimStart()
        if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return ""
        return line.substringBefore("//")
    }
}
