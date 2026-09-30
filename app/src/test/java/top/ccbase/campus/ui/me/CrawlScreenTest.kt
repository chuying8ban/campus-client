package top.ccbase.campus.ui.me

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「手机自己抓课表」这一页。
 *
 * 它存在的唯一理由是**密码不出手机**，所以测试分两半：
 * 1. 能渲染、文案说清了"不保存、不上传"（否则用户凭什么信）；
 * 2. 源码级守卫：这个文件里密码除了送进教务登录、就只能被清空 ——
 *    不许出现在任何存储或上传调用旁边。这类"承诺"必须能对着代码验，
 *    否则就只是一句好听的界面文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class CrawlScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    @Test
    fun `页面能渲染_并且说清了密码怎么处理`() {
        rule.setContent {
            CampusTheme {
                CrawlScreen(db = app().db, api = CampusApi(), onClose = {})
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("用我的教务账号抓课表", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val all = rule.onAllNodesWithText("", substring = true)
        assertTrue("至少要看到页面标题", all.fetchSemanticsNodes().isNotEmpty())
        // 承诺必须在页面上说清楚（用户凭什么信"不上传"）
        assertTrue(
            "页面必须写明密码不保存/不上传",
            rule.onAllNodesWithText("不保存", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `密码只进登录调用_不进任何存储或上传`() {
        val f = File("src/main/java/top/ccbase/campus/ui/me/CrawlScreen.kt")
        assertTrue("找不到 CrawlScreen.kt（测试工作目录变了？）", f.isFile)
        val code = f.readText()

        assertTrue("密码必须真的送进教务登录（否则这页没法工作）",
            code.contains("client.login(sid0, pw0)"))

        // 只钉**风险**本身：密码不许和任何"落盘 / 上传 / 打日志"的调用出现在同一行。
        //
        // 为什么不搞"逐行白名单"：那样连 `pw.isBlank()`（正常校验）、`val pw0 = pw`
        // 都会被拦下来 —— 守卫一有假阳，下一个人就会把它删掉，等于没有守卫。
        // 第一版就是这么写的，果然报了 5 行全是正常代码。
        val sinks = listOf("TokenStore", "db.dao", "api.upload", "Log.", "Prefs",
                           "save", "putString", "setCredentials", "uploadActivities")
        val bad = code.lines().withIndex().filter { (_, line) ->
            val s = line.substringBefore("//")
            (s.contains("pw0") || Regex("""\bpw\b""").containsMatchIn(s)) &&
                sinks.any { s.contains(it) }
        }
        assertTrue(
            "密码不许和落盘/上传/日志调用同处一行：\n" +
                bad.joinToString("\n") { "${it.index + 1}: ${it.value.trim()}" },
            bad.isEmpty(),
        )

        // 清空这件事本身也钉住："用完即清"不能只是注释里的口号
        assertTrue("密码框必须在用完后就地清空", code.contains("pw = \"\""))
    }

    /**
     * 整屏浮层必须自带不透明底色。
     *
     * 为什么钉这条：CampusApp 里这些页是 Scaffold 的**兄弟节点**（整屏盖住，连底栏一起），
     * 底色得自己画。2026-09-18 真机踩过 —— 点「我的」→「手机自己抓课表」，
     * 抓课表页没画底色 → 「我的」页整页文字透上来，两页的字叠成一片（用户截图）。
     * 光靠"记得加"会再犯，所以把名单钉在这里；以后新加浮层，照着补一行。
     *
     * 这是**源码级守卫**，不是像素级验证：Robolectric 整窗截屏在本工程必超时
     * （见 TodayDetailShotTest 的说明），所以"真的不透明"只能真机看。这里保证的是
     * "谁都别再漏画"。
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
            // 主题底色 C.bg，或 Color(0xB3…) 这类压暗遮罩，都算"不是透明的"；
            // 具名的 `overlaySurface`（theme 里定义，语义就是"始终不透明"）同样算
            !code.contains("background(C.bg)") && !code.contains("overlaySurface") &&
                !code.contains("background(Color(0x")
        }
        assertTrue(
            "这些浮层没画不透明底色，会把底下的页面透上来（真机已出过叠字）：$missing",
            missing.isEmpty(),
        )
    }
}
