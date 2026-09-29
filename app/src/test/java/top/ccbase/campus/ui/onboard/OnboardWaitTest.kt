package top.ccbase.campus.ui.onboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.OnboardState
import top.ccbase.campus.net.OnboardStep
import top.ccbase.campus.net.Transport
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 第一次登录的等待页。
 *
 * 这一页存在的唯一理由：同学第一次进来时，服务端正在**用他自己的账号**去教务读课表、
 * 排计划（几秒~几十秒）。所以它必须做到三件事，三条都用例钉住：
 *   ① 说清楚正在发生什么、走到哪一步了（进度只信服务端，App 不自己推算）；
 *   ② 明说"可以退出，后台继续"，并且流程里真的兑现（重进能回这一屏）；
 *   ③ 失败给原因 + 两条出路（重试 / 先跳过用模板），绝不把人堵死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class OnboardWaitTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    private fun steps() = listOf(
        OnboardStep(k = "login", label = "登录教务系统", state = "done"),
        OnboardStep(k = "fetch", label = "读取你的已选课程", state = "doing"),
        OnboardStep(k = "plan", label = "按你的课表排学习计划", state = "wait"),
    )

    private fun running() = OnboardState(
        state = "running", step = "fetch", stepLabel = "正在读取你的课表",
        steps = steps(), percent = 40,
    )

    private fun failed() = OnboardState(
        state = "failed", step = "fetch",
        steps = listOf(
            OnboardStep(k = "login", label = "登录教务系统", state = "done"),
            OnboardStep(k = "fetch", label = "读取你的已选课程", state = "fail"),
        ),
        error = "教务系统连不上（请稍后重试）", percent = 20, tries = 1,
    )

    /** 假的 CampusApi：按调用次数依次吐状态，并记下所有请求（用来验证真的问了服务端）。 */
    private class Fake(seq: List<String>) {
        val seen = mutableListOf<String>()
        private var i = 0
        val api: CampusApi = CampusApi(
            base = "https://example.invalid",
            transport = Transport { method, url, _, _ ->
                seen += "$method $url"
                when {
                    url.contains("/api/v2/onboard") && method == "POST" ->
                        HttpReply(200, seq.last())
                    url.contains("/api/v2/onboard") ->
                        HttpReply(200, seq[minOf(i++, seq.size - 1)])
                    url.contains("/api/v2/plan") -> HttpReply(200, """{"version":1}""")
                    else -> HttpReply(404, """{"detail":"没有这个接口"}""")
                }
            },
        )
    }

    private fun waitForText(t: String) {
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    // ------------------------------------------------------------------ 渲染（无状态）

    @Test
    fun `等待页要把正在发生什么讲清楚`() {
        rule.setContent { CampusTheme { WaitingBody(st = running(), secs = 12) } }
        waitForText("正在读取你的课表")            // 当前步骤来自服务端
        waitForText("读取你的已选课程")
        waitForText("进行中")
        waitForText("已完成 40%")
        waitForText("已等 12 秒")
        waitForText("用的是你自己的学号和教务密码")   // 说清"正在发生什么"
        waitForText("这期间可以退出 App")            // 明说可以走
        rule.onNodeWithText("重试").assertDoesNotExist()   // 没失败就别给重试（会让人以为坏了）
    }

    @Test
    fun `失败时必须给原因和两条出路`() {
        rule.setContent { CampusTheme { WaitingBody(st = failed(), secs = 200) } }
        waitForText("教务系统连不上")              // 原因要说人话
        waitForText("重试")
        waitForText("先跳过，用模板进去看看")       // 不能把人堵死在这一屏
    }

    @Test
    fun `还没失败时不给跳过这个出口`() {
        rule.setContent { CampusTheme { WaitingBody(st = running(), secs = 5) } }
        waitForText("请稍等…")
        rule.onNodeWithText("先跳过，用模板进去看看").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ 轮询（真跑一遍）

    @Test
    fun `生成好了会自动拉规划然后放行`() {
        val f = Fake(listOf("""{"state":"running","steps":[],"percent":10}""",
                             """{"state":"done","steps":[],"percent":100}"""))
        var done = false
        rule.setContent {
            CampusTheme {
                WaitingScreen(api = f.api, db = app().db, token = "tok",
                    onDone = { done = true }, onSkip = {})
            }
        }
        rule.waitUntil(timeoutMillis = 8_000) { done }
        assertTrue("必须把规划拉下来再放行，否则进去是空课表",
            f.seen.any { it.contains("/api/v2/plan") })
    }

    @Test
    fun `失败后点先跳过会带着模板进去`() {
        val f = Fake(listOf("""{"state":"failed","steps":[],"error":"教务系统连不上"}"""))
        var skipped = false
        rule.setContent {
            CampusTheme {
                WaitingScreen(api = f.api, db = app().db, token = "tok",
                    onDone = {}, onSkip = { skipped = true })
            }
        }
        waitForText("先跳过，用模板进去看看")
        rule.onNodeWithText("先跳过，用模板进去看看").performClick()
        rule.waitUntil(timeoutMillis = 8_000) { skipped }
        assertTrue("跳过也要先把规划（模板）拉到本地", f.seen.any { it.contains("/api/v2/plan") })
    }

    @Test
    fun `重试要真的再去问一次服务端`() {
        val f = Fake(listOf("""{"state":"failed","steps":[],"error":"教务系统连不上"}"""))
        rule.setContent {
            CampusTheme {
                WaitingScreen(api = f.api, db = app().db, token = "tok",
                    onDone = {}, onSkip = {})
            }
        }
        waitForText("重试")
        rule.onNodeWithText("重试").performClick()
        rule.waitUntil(timeoutMillis = 8_000) {
            f.seen.any { it.startsWith("POST") && it.contains("/api/v2/onboard") }
        }
    }

    // ------------------------------------------------------------------ 纯逻辑

    @Test
    fun `老用户不能被堵在等待页上`() {
        // none = 这个人不需要生成（老用户/作者）。必须算"已结清"，
        // 否则每次打开都被拦在等待页上等一件永远不会发生的事。
        assertTrue(OnboardLogic.settled(OnboardState(state = "none")))
        assertTrue(OnboardLogic.settled(OnboardState(state = "done")))
        assertTrue(OnboardLogic.busy(OnboardState(state = "pending")))
        assertTrue(OnboardLogic.busy(OnboardState(state = "running")))
        assertFalse(OnboardLogic.settled(OnboardState(state = "running")))
        assertFalse(OnboardLogic.busy(OnboardState(state = "done")))
    }

    @Test
    fun `轮询间隔会放宽`() {
        assertEquals(1500L, OnboardLogic.pollMs(0))
        assertEquals(1500L, OnboardLogic.pollMs(58))
        assertEquals(5000L, OnboardLogic.pollMs(60))
        assertFalse(OnboardLogic.showSlowHint(89))
        assertTrue(OnboardLogic.showSlowHint(90))
    }

    @Test
    fun `服务端没给步骤就不画进度`() {
        assertEquals(emptyList<Pair<String, String>>(),
            OnboardLogic.steps(OnboardState(state = "pending")))
        assertEquals(listOf("登录教务系统" to "done"),
            OnboardLogic.steps(OnboardState(state = "running", steps = listOf(
                OnboardStep(label = "登录教务系统", state = "done")))))
    }
}
