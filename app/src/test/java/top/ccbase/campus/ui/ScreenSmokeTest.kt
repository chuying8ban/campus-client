package top.ccbase.campus.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.ui.me.MeScreen
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiUser
import top.ccbase.campus.ui.onboard.LoginScreen
import top.ccbase.campus.TestSeedDb
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.ui.board.BoardScreen
import top.ccbase.campus.ui.schedule.ScheduleScreen
import top.ccbase.campus.ui.tasks.TasksScreen
import top.ccbase.campus.ui.theme.CampusTheme
import top.ccbase.campus.ui.today.TodayScreen

/**
 * 页面渲染冒烟测试 —— 在 JVM 上**真渲染**每个页面。
 *
 * 为什么值得专门写：本机没有模拟器，我看不到界面。
 * 如果某页一进去就崩（改错一个 modifier、拿到 null、列表 key 重复…），
 * 正常情况下只有用户装上之后才会发现 —— 而那时他已经在用了。
 * 有了这个测试，**"能不能打开"这件事我在本地就能验**，
 * 不至于把"崩溃"这种最糟的失败方式留给用户去发现。
 *
 * ⚠️ 断言锚点必须**只在数据到位后**才出现。用页面标题是假阳性：
 * "正在读取课表…" 里也含"课表"两个字，加载中就能通过 —— 这种测试比没有更危险，
 * 因为它给的是虚假的安心。所以这里统一改用只有真实数据里才有的字符串
 * （"自习"/"周一"/"本学期"/"里程碑"），于是它同时验了
 * "种子导入 → 读库 → 上屏"这条完整链路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    /**
     * 每个用例都当作"全新安装"来跑。
     *
     * Robolectric 同一个测试类里的应用数据库是**共享**的：前一个用例走过引导、
     * 写过 onboarded 标志，后一个用例就会直接进主界面（实测如此）。
     * 这不是 App 的 bug（真机上数据本来就该留存），是测试隔离问题。
     */
    @Before
    fun freshInstall() = runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app().db.dao().putMeta(listOf(top.ccbase.campus.data.local.Meta("onboarded", "0")))
        }
    }

    @Test
    fun `我的页能渲染且说清了密码怎么处理`() {
        rule.setContent {
            CampusTheme {
                MeScreen(
                    ctx = ApplicationProvider.getApplicationContext(),
                    db = app().db,
                    api = CampusApi(),
                    version = "test",
                    onLogin = {},
                )
            }
        }
        // 锚点必须挑页面上真实存在的文案：未登录时显示的是"还没登录…"，
        // 不是应用名 —— 用不存在的锚点会让"页面渲染正常"被误判成"页面坏了"。
        waitFor("我的")
        // 未登录状态必须给一条出路
        waitFor("用学号登录")
        // 「我凭什么把密码给你」必须当场回答，这是这一页存在的理由
        waitFor("只用于每天自动登录读课表")
        waitFor("不以明文存放")
        // 对外自称与免责
        waitFor("非学校官方产品")
        waitFor("不对数据准确性负责")
        // 抢课是**测试功能**，按服务端给的 can_grab 显隐（2026-09-29 口径：公开版含抢课，
        // 但入口只给拿得到 can_grab 的人）。这条用例是**未登录**状态 → can_grab = false，
        // 所以「我的」页仍然一个字都不该提到它：没权限的人连这个功能存在都不必知道。
        // （这里原本断言的是"不提供"那行 —— 那行是对所有人宣布功能存在，已删掉；
        //   换成负断言：整页语义树里不许出现"抢课"二字。以后再手滑加回来，这条会立刻红。）
        val tree = rule.onRoot().printToString()
        // 先证明这棵"树"不是空的：没有这条锚点，上面那条负断言可能因为 printToString
        // 压根没抓到文字而"永远绿"（那就是白给的安全感）。
        assertTrue("语义树里连「数据来源」都没有，说明这个 dump 不可信：\n$tree",
                   tree.contains("数据来源"))
        assertTrue("「我的」页出现了「抢课」字样，等于对外泄露功能存在：\n$tree",
                   !tree.contains("抢课"))
    }


    // ---------------------------------------------- 密码相关（作者 / 同学边界）

    private fun meScreen() {
        rule.setContent {
            CampusTheme {
                MeScreen(
                    ctx = ApplicationProvider.getApplicationContext(),
                    db = app().db,
                    api = CampusApi(),
                    version = "test",
                    onLogin = {},
                )
            }
        }
    }

    @Test
    fun `非作者看不到查看密码的入口`() {
        TokenStore.clear(ApplicationProvider.getApplicationContext())
        meScreen()
        waitFor("用学号登录")
        rule.waitForIdle()
        assertTrue(
            "同学版不该出现查看密码入口 —— 密码回传一次就多一个泄露面",
            rule.onAllNodesWithText("查看服务器上保存的密码", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `作者能看到查看密码的入口`() {
        TokenStore.save(
            ctx = ApplicationProvider.getApplicationContext(),
            token = "tok",
            expiresAt = "2099-01-01",
            // 拆闸之后这一处认的是 **is_author**（不再借 can_grab）：这里故意把 can_grab
            // 留成 false，好证明"能看到回显"跟"能抢课"已经是两件事了。
            user = ApiUser(uid = 1, student_id = "2026000000", name = "测试用户", isAuthor = true),
        )
        meScreen()
        waitFor("查看服务器上保存的密码")
    }

    @Test
    fun `能抢课但不是作者_也不许看到密码回显`() {
        // 拆闸（2026-09-29）钉的就是这一格：抢课按 can_grab 显隐，
        // 而**凭据回显永远只有作者**（密码是别人自己的东西，回传一次就多一个泄露面）。
        // 只测"作者能看到"是不够的 —— 两个闸共用一个字段时那条也是绿的。
        TokenStore.save(
            ctx = ApplicationProvider.getApplicationContext(),
            token = "tok",
            expiresAt = "2099-01-01",
            user = ApiUser(uid = 1, student_id = "2026000000", name = "测试用户",
                           canGrab = true, isAuthor = false),
        )
        meScreen()
        // 正锚点：先证明这一页真渲染到位了，否则下面那条负断言可能因为"页面根本没出来"而假绿
        waitFor("只用于每天自动登录读课表")
        rule.waitForIdle()
        assertTrue(
            "能抢课的非作者看到了密码回显入口 —— 回显只该认 is_author，不该跟着 can_grab 一起放开",
            rule.onAllNodesWithText("查看服务器上保存的密码", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `登录框的眼睛开关真的能把密码露出来`() {
        rule.setContent {
            CampusTheme {
                LoginScreen(
                    ctx = ApplicationProvider.getApplicationContext(),
                    api = CampusApi(),
                    db = app().db,
                    onDone = {},
                    onBack = {},
                )
            }
        }
        waitFor("教务系统密码")
        rule.onNode(hasSetTextAction() and hasText("教务系统密码", substring = true))
            .performTextInput("pw123456")
        rule.waitForIdle()
        assertTrue(
            "密码框默认必须是挡着的",
            rule.onAllNodesWithText("pw123456", substring = true).fetchSemanticsNodes().isEmpty()
        )
        rule.onNodeWithText("显示").performClick()
        waitFor("pw123456")
    }

    private fun waitFor(text: String) {
        try {
            rule.waitUntil(timeoutMillis = 20_000) {
                rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            // 失败时把当前界面树打出来 —— 否则只能瞎猜"到底是哪一屏没渲染"
            println("=== 等不到「$text」，当前界面树：\n" +
                rule.onRoot().printToString(maxDepth = 30))
            throw e
        }
    }

    @Test
    fun `首次启动先出引导页而不是主界面`() {
        rule.setContent { CampusTheme { CampusApp(version = "test") } }
        waitFor("用学号登录")
        // 引导页绝不该出现底部导航 —— 直接进主界面等于把新用户丢进空白功能里
        listOf("今日", "看板").forEach { tab ->
            assertTrue(
                "引导页不该出现主界面导航：$tab",
                rule.onAllNodesWithText(tab).fetchSemanticsNodes().isEmpty()
            )
        }
        // 「不登录直接看内容」这条路必须不存在 —— 未登录只能看到登录页
        assertTrue(
            "不该再有「不登录看演示」的入口",
            rule.onAllNodesWithText("先看看演示内容，不登录").fetchSemanticsNodes().isEmpty()
        )
        // 对外自称与免责声明必须在场
        waitFor("中国石油大学（北京）克拉玛依校区学生自用工具")
    }

    @Test
    fun `引导走完但没登录_仍然进不了主界面`() = runBlocking {
        // 引导标记写了不算数 —— 没有会话令牌就只能看到登录/引导。
        // （原先是"跳过也能进主界面"，被明确要求改成不允许预览。）
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PlanApplier.markOnboarded(app().db) }
        rule.setContent { CampusTheme { CampusApp(version = "test") } }
        rule.waitForIdle()
        // 按底栏图标自带的 contentDescription 判（= tab 的名字），而不是按文字判：
        // 底栏那一格已从「任务」改名「学习」，写死文字会漏掉改名，写文字还可能撞上
        // 登录页里同名的普通文字。这里直接问"底栏有没有出现"。
        // useUnmergedTree：底栏图标的 contentDescription 只在未合并的语义树里（实测）。
        CampusTab.entries.map { it.label }.distinct().forEach { tab ->
            assertTrue(
                "未登录却看到了主界面底栏（$tab）—— 不允许预览内容",
                rule.onAllNodesWithContentDescription(tab, useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun `今日页能渲染且读到数据`() {
        rule.setContent { CampusTheme { TodayScreen(app().db) } }
        // "自习"只在真实数据里（页头"第 N 周"渲染得太早，不能当锚点）
        waitFor("自习")
    }

    @Test
    fun `课表页能渲染且读到数据`() {
        rule.setContent { CampusTheme { ScheduleScreen(app().db) } }
        // "门 · 第" 出自 "11 门 · 第 3~19 周" —— 必须课程数据已入库才会渲染；
        // 加载态只有 "正在读取课表…"（注意它含"课表"二字，不能拿来当锚点）
        waitFor("门 · 第")
    }

    @Test
    fun `任务页能渲染且读到数据`() {
        // App 启动只灌通用数据（节次/自习），个人任务等用户自己的计划同步 —— 这里给任务页塞个自己的库
        rule.setContent { CampusTheme { TasksScreen(TestSeedDb.seeded(app())) } }
        // "本学期"是任务分组标题
        waitFor("本学期")
    }

    @Test
    fun `看板页能渲染且读到数据`() {
        rule.setContent { CampusTheme { BoardScreen(app().db) } }
        // 统计没算完时整页只显示"正在统计…"，所以"里程碑"能出现即数据已到位
        waitFor("里程碑")
    }
}
