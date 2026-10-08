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
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「导入官方教务课表」这一页。
 *
 * 旧版是「在这儿填学号+密码」的页面；credential-free 迁移后**这一页不再有密码框**：
 * 登录发生在学校官方 HTTPS 页面里，App 不读取、不保存、不上传密码。
 * 所以测试分两半：
 *  1. 能渲染、文案说清了「默认只存本机 / 密码不上传」（否则用户凭什么信）；
 *  2. 源码级守卫：这个流程里没有密码输入框、没有通用 JS bridge、强制 same-origin、
 *     导入结束必须清教务登录状态。这类承诺必须能对着代码验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class CrawlScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

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
    }

    @Test
    fun `页面能渲染_并且说清了密码怎么处理`() {
        rule.setContent {
            CampusTheme {
                CrawlScreen(db = db, api = CampusApi(), onClose = {})
            }
        }
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("导入官方教务课表", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val all = rule.onAllNodesWithText("", substring = true)
        assertTrue("至少要看到页面标题", all.fetchSemanticsNodes().isNotEmpty())
        // 承诺必须在页面上说清楚（用户凭什么信「不上传」）
        assertTrue(
            "页面必须写明密码不读取/不保存",
            rule.onAllNodesWithText("不读取", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "页面必须写明课表默认不发服务器",
            rule.onAllNodesWithText("不会发给任何服务器", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        // 新契约的核心：页面上**没有**密码输入框
        assertTrue(
            "导入页不该再有密码输入框",
            rule.onAllNodesWithText("教务系统密码", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * 源码级守卫：整条导入链里不许出现「密码输入/落盘/上传」的痕迹。
     *
     * 这不是像素验证，而是把「承诺」钉在代码上 —— 只要有人把密码框加回来、或让
     * WebView 把密码 DOM/HTML 交给 App，这里就会红。
     */
    @Test
    fun `导入链里没有任何密码输入或读取`() {
        val files = listOf(
            "src/main/java/top/ccbase/campus/ui/me/CrawlScreen.kt",
            "src/main/java/top/ccbase/campus/school/SchoolImportFlow.kt",
            "src/main/java/top/ccbase/campus/school/SchoolWebLogin.kt",
        )
        val forbidden = listOf(
            "PasswordVisualTransformation",   // 密码框
            "addJavascriptInterface",          // 通用 JS bridge（会暴露原生对象）
            "getPassword",                    // 读取密码 DOM
            "OutlinedTextField",              // 任何文本输入（这一页不需要用户输入任何东西）
        )
        val bad = mutableListOf<String>()
        files.forEach { rel ->
            val f = File(rel)
            assertTrue("找不到 $rel（测试工作目录变了？）", f.isFile)
            f.readLines().forEachIndexed { i, raw ->
                val code = raw.substringBefore("//")
                forbidden.forEach { tok -> if (code.contains(tok)) bad += "$rel:${i + 1}  $tok" }
            }
        }
        assertTrue("导入链里出现了密码输入/JS bridge 的痕迹：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** 强制 same-origin + 结束清登录状态：这两条是安全边界，不能让改动悄悄丢掉。 */
    @Test
    fun `WebView 强制 same-origin 且导入结束清登录状态`() {
        val f = File("src/main/java/top/ccbase/campus/school/SchoolWebLogin.kt")
        assertTrue("找不到 SchoolWebLogin.kt", f.isFile)
        val code = f.readText()
        assertTrue("每次导航都要重新核 same-origin", code.contains("SchoolImportPolicy.isAllowedSchoolUrl"))
        assertTrue("导入结束必须清教务登录状态（含 WebStorage/缓存）", code.contains("SchoolCookie.clearAll"))
    }

    /**
     * 整屏浮层必须自带不透明底色。
     *
     * 为什么钉这条：CampusApp 里这些页是 Scaffold 的**兄弟节点**（整屏盖住，连底栏一起），
     * 底色得自己画。2026-09-18 真机踩过 —— 点「我的」→「手机自己抓课表」，抓课表页没画底色
     * → 「我的」页整页文字透上来，两页的字叠成一片（用户截图）。
     * 光靠"记得加"会再犯，所以把名单钉在这里；以后新加浮层，照着补一行。
     */
    @Test
    fun `整屏浮层都要有不透明底色_否则下层页面会透上来`() {
        val overlays = listOf(
            "ui/me/CrawlScreen.kt",
            "ui/tasks/PlanScreen.kt",
            "ui/me/SafetyScreen.kt",
            "ui/me/DiagPanel.kt",
            "ui/settings/PermissionsScreen.kt",
        )
        val missing = overlays.filter { rel ->
            val f = File("src/main/java/top/ccbase/campus/$rel")
            assertTrue("找不到 $rel（测试工作目录变了？）", f.isFile)
            val code = f.readText()
            !code.contains("background(C.bg)") && !code.contains("overlaySurface") &&
                !code.contains("background(Color(0x")
        }
        assertTrue(
            "这些浮层没画不透明底色，会把底下的页面透上来（真机已出过叠字）：$missing",
            missing.isEmpty(),
        )
    }
}
