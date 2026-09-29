package top.ccbase.campus.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.local.Meta
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.data.seed.SeedImporter
import top.ccbase.campus.data.seed.SeedLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.Resource
import top.ccbase.campus.data.local.StudyStep
import top.ccbase.campus.data.local.Task
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiUser
import top.ccbase.campus.ui.tasks.PlanLogic
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 底栏那一格「学习」（原名「任务」）现在的内容。
 *
 * 用户先后提了三件事：
 *  ① 「把任务标签改为学习，把 ai 学习计划放到里面去」—— 改名 + 入口搬进来；
 *  ② 「学习里面就不要留两个标签了，合并成一个就行了」—— 把页内那层子切换去掉；
 *  ③ 2026-09-19「学习库和这里的功能重复了，把这里的删了吧」—— 页面下半那半
 *     （按课程摊开的资料列表）**整份删掉**，资源统一去「学习库」那一格看
 *     （点一门课进去看课程信息 + 资料）。
 *
 * 所以这一页现在只有任务：进去就是内容，不用点任何一层；下半那半的东西
 * （条数抬头「N 条 · 共 M 条」、类型筛选、课程分组、资源行）**一条都不许再出现**。
 *
 * 为什么要在**真界面**上测，而不是只做源码级断言（源码级在 MergeWiringTest 里）：
 *   · 改名这类事最怕"改了 label、底栏那一格却没显示出来"——只有把 CampusShell 真渲染
 *     出来、点一下，才知道点得到；
 *   · "那半删干净了"这种事，源码里没有 `learnContent(` 不等于界面上就没有残留
 *     （旧版功能常常换个函数名又长回来）；
 *   · "入口在整页内容之上"必须是坐标级的：入口的底边不许越过内容列表的顶边（它不在那条列表里）。
 *     上一轮用户抱怨的是入口藏起来/要切一层才看得见；这一轮（2026-09-19）他要求
 *     「往下翻时 ai 学习规划应该隐藏」—— 所以往下滚它**收起**、滚回顶部它**自己回来**，
 *     两条都要有证据（收起来就回不来，那是功能性回归）。
 *
 * 判底栏格子用**图标的 contentDescription**（= tab 的名字），不用文字：页面里也有
 * 「学习」这种大字，按文字找会同时命中好几处。
 *
 * 文案一律引用真来源：入口那几句走 [PlanLogic] 的常量（不往测试里编句子）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class LearningTabTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * 任务那半的页首标记。
     *
     * 原先钉的是那句说明文案（"按阶段排的路线图…"）—— 精简学习界面时把这类说明句删了，
     * 所以改成钉**页首的「整理」入口**：它属于任务那半、位置最醒目，而且真丢了就是功能性回归。
     */
    private val tasksHeadLine = top.ccbase.campus.ui.tasks.PlanLogic.MORE_ENTRY

    /**
     * 被删掉那半的条数抬头（旧 LearnScreen 的真文案，形如 "12 条 · 共 211 条"）。
     * 现在它是一条**反向**标记：这一页里再出现它，就说明删掉的那半长回来了。
     */
    private val removedHalfMarker = "条 · 共"

    /**
     * PlanScreen 没登录只会显示"还没登录"，测不到四个问题 ——
     * 所以先放一个没到期的令牌（和 PlanScreenTest 同一套写法）。
     */
    @Before
    fun loggedIn() {
        TokenStore.save(
            ctx = ApplicationProvider.getApplicationContext(),
            token = "tok-learning-tab",
            expiresAt = "2099-01-01",
            user = ApiUser(uid = 1, student_id = "s", name = "n"),
        )
        // 这一页的正文是**用户自己的**计划；App 启动不再灌作者本人的数据（怕串用户），
        // 而这条用例要验整壳的分栏/钉住行为，只能走真实 App 的库 —— 所以在这里把随包的
        // 完整种子灌进去，@After 里按 id 删干净（不留污染给后面跑的类）。
        val app = ApplicationProvider.getApplicationContext<CampusApplication>()
        runBlocking {
            app.seedJob?.join()
            SeedImporter.import(app.db, SeedLoader.load(app), force = true)
            // 界面只认 plan_source == remote 的计划（防串用户），所以这里照实标上
            app.db.dao().putMeta(listOf(Meta(PlanApplier.K_SOURCE, PlanApplier.REMOTE)))
        }
    }

    /** 直接渲染这一页那条用例自建的库，用完关掉。 */
    private var smallDb: CampusDb? = null

    @After
    fun tearDown() {
        // 把灌进 App 库的那批种子数据按 id 删干净（只动种子自己那批，不碰通用数据）
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<CampusApplication>()
            val seed = SeedLoader.load(app)
            app.db.dao().deleteTasks(seed.tasks.map { it.id })
            app.db.dao().deleteSteps(seed.study_steps.map { it.id })
            app.db.dao().deleteResources(seed.resources.map { it.id })
        }
        smallDb?.close()
    }

    private fun renderShell() {
        rule.setContent { CampusTheme { CampusShell(version = "test", onLogin = {}) } }
        rule.waitForIdle()
    }

    private fun waitFor(text: String, ms: Long = 20_000) {
        try {
            rule.waitUntil(ms) {
                rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            // 失败时把当前界面树打出来，否则只能瞎猜"到底渲染成了什么"
            println("=== 等不到「$text」，当前界面树：\n" + rule.onRoot().printToString(maxDepth = 30))
            throw e
        }
    }

    /** 有界等待，返回"最终到底有没有"（不抛：真正的判定交给下面那条 assertTrue）。 */
    private fun awaitText(text: String, ms: Long = 10_000): Boolean {
        runCatching {
            rule.waitUntil(ms) {
                rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        return rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** 某个字符串在界面上出现了几个语义节点（精确匹配）。 */
    private fun count(text: String) =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    /** 某个字符串在界面上出现了几个语义节点（子串匹配）。 */
    private fun countSubstring(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    /**
     * 底栏那一格。
     *
     * 注意 `useUnmergedTree`：底栏图标自带的 contentDescription 只在**未合并**的语义树里
     * （合并树里 Material3 的 NavigationBarItem 没把它提上来）—— 实测如此，
     * 用合并树找会"节点不存在"，看着像改名没生效，其实是找错了树。
     */
    private fun barItem(label: String) =
        rule.onAllNodesWithContentDescription(label, useUnmergedTree = true)
            .fetchSemanticsNodes().size

    /** 从底栏点进「学习」这一格（内容就在这一页里，不是藏在别处）。 */
    private fun enterLearningTab() {
        rule.onNodeWithContentDescription(CampusTab.TASKS.label, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    /** 内层那排「任务 | 学习」子切换必须整个不在（两个标签都点不到 = 一个标签都没了）。 */
    private fun assertNoSubTabs() {
        rule.onNodeWithTag("subtab-tasks").assertDoesNotExist()
        rule.onNodeWithTag("subtab-learn").assertDoesNotExist()
    }

    /**
     * 入口必须是**明处的、唯一的、可点的**，而且钉在整页内容之上：
     *   - 标题 + 说明都在场（只有个名字，用户看不出点了会发生什么）；
     *   - 整个界面里**正好一份**（0 = 入口没了；2 以上 = 重复渲染）；
     *   - 不用滚动就在屏幕上；
     *   - 坐标级证据：入口的**底边**不越过内容列表的**顶边** —— 它不在那条列表里，
     *     所以内容怎么滚都滚不走它（不是只看源码顺序）。
     */
    private fun assertEntryVisibleAndSingle() {
        val n = count(PlanLogic.TITLE)
        assertEquals(
            "入口必须正好一份，实际 $n 份（0 = 入口没了；2 以上 = 重复渲染）",
            1, n,
        )
        assertTrue(
            "入口只有个名字、没有说明 —— 用户看不出点了会发生什么",
            count(PlanLogic.ENTRY_HINT) >= 1,
        )
        rule.onNodeWithText(PlanLogic.TITLE).assertIsDisplayed()
        rule.onNodeWithText(PlanLogic.TITLE).assertHasClickAction()

        val entryBottom = rule.onNodeWithText(PlanLogic.TITLE).fetchSemanticsNode().boundsInRoot.bottom
        val listTop = rule.onNodeWithTag(MERGED_LIST_TAG).fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "入口没在整页内容之上：入口 bottom=$entryBottom，内容列表 top=$listTop",
            entryBottom <= listTop,
        )
    }

    /** 在那条列表里滚到某段文字（同一条列表 = 同一次渲染）。 */
    private fun scrollMergedTo(text: String) {
        rule.onNodeWithTag(MERGED_LIST_TAG).performScrollToNode(hasText(text, substring = true))
        rule.waitForIdle()
    }

    /** 点入口之前，PlanScreen 的特征元素（四个问题里的第一个）不该在场。 */
    private fun assertPlanNotOpenYet() = assertTrue(
        "还没点入口就已经看到了 AI 计划页的内容",
        rule.onAllNodesWithText(PlanLogic.SECTION_FOCUS).fetchSemanticsNodes().isEmpty(),
    )

    /** 点入口 → PlanScreen 真渲染出来才会有这句。 */
    private fun clickEntryAndWaitPlan() {
        rule.onNodeWithText(PlanLogic.TITLE).performClick()
        waitFor(PlanLogic.SECTION_FOCUS)
    }

    @Test
    fun `底栏那一格叫学习_不再叫任务`() {
        renderShell()
        assertEquals("底栏那一格应该已经改名", "学习", CampusTab.TASKS.label)
        assertTrue(
            "底栏看不到「${CampusTab.TASKS.label}」这一格 —— 改名没上到界面上",
            barItem(CampusTab.TASKS.label) >= 1,
        )
        assertTrue(
            "底栏还留着一个叫「任务」的格子 —— 用户要改的就是它",
            barItem("任务") == 0,
        )
        // 改名不该顺手动别的格子（少一格 = 功能没了）
        listOf(CampusTab.TODAY, CampusTab.SCHEDULE, CampusTab.ME).forEach { t ->
            assertTrue("底栏少了「${t.label}」这一格", barItem(t.label) >= 1)
        }
    }

    /**
     * 这一条是 2026-09-19 那次改动的主角：页内既没有子切换，**也没有下半那半资源列表**。
     *
     * 断言顺序是故意的：先证"进去就是任务内容"，再证两个子标签都不在，
     * 最后逐条证下半那半的东西（条数抬头 / 类型筛选 / 课程分组）一件都没留下 ——
     * 只断言"没有子切换"是不够的：那半完全可以去掉子切换之后还赖在页面下半。
     */
    @Test
    fun `学习页里只有任务_下半那半资源列表一件都不留`() {
        renderShell()
        enterLearningTab()

        // ① 进这一格就是内容：任务那半（40+ 行的路线图）在第一屏，不需要先点任何东西
        waitFor(tasksHeadLine)
        assertTrue("进「学习」后没看到任务内容", countSubstring(tasksHeadLine) >= 1)

        // ② 内层那排子切换整个不在
        assertNoSubTabs()

        // ③ 下半那半的痕迹一件都不许有：条数抬头、类型筛选、任何「共 N 条」对账
        assertTrue(
            "被删掉的那半（条数抬头「$removedHalfMarker」）又出现了 —— 页面里不该再有它",
            countSubstring(removedHalfMarker) == 0,
        )

        // ④ 往下滚一整屏还是一样（那半如果还在，只会待在下面）
        repeat(2) {
            rule.onNodeWithTag(MERGED_LIST_TAG).performTouchInput { swipeUp() }
            rule.waitForIdle()
        }
        assertTrue(
            "滚到下面又冒出那半的痕迹了（$removedHalfMarker）",
            countSubstring(removedHalfMarker) == 0,
        )
        assertNoSubTabs()
    }

    /**
     * 入口**往下翻就收起来、翻回顶部它自己回来**（用户 2026-09-19：「往下翻时 ai 学习规划应该隐藏」）。
     *
     * 它仍然不是列表里的一项（[assertEntryVisibleAndSingle] 在没滚之前用坐标证这份），
     * 所以这条要证的是两件事：
     *   ① 往下滚 → 它从树上消失（真收起，不是"挪走了还占屏"）；
     *   ② 滚回顶部 → 它又出现、还能点开 AI 计划页（收起来就回不来 = 功能性回归）。
     * 先证"这一滚真的滚动了"（任务页头滚出树），否则①可能只是因为根本没滚，那这条就什么都没证。
     */
    @Test
    fun `往下翻时入口收起来_翻回顶部它自己回来`() {
        renderShell()
        enterLearningTab()
        waitFor(tasksHeadLine)

        assertEntryVisibleAndSingle()

        // 任务有 40+ 条、比一屏高得多：滚两下足够让页头走出屏幕
        repeat(2) {
            rule.onNodeWithTag(MERGED_LIST_TAG).performTouchInput { swipeUp() }
            rule.waitForIdle()
        }
        assertTrue(
            "向下滚了两下，任务页头却还在树上 —— 滚动没生效，下面那条断言不算证据",
            count(tasksHeadLine) == 0,
        )
        assertEquals(
            "往下翻之后入口还杵在屏上 —— 用户要的就是把它收起来",
            0,
            count(PlanLogic.TITLE),
        )

        // 滚回顶部 → 它自己回来（否则入口等于"永久消失"，比杵着更糟）
        repeat(2) {
            rule.onNodeWithTag(MERGED_LIST_TAG).performTouchInput { swipeDown() }
            rule.waitForIdle()
        }
        assertTrue(
            "滚回顶部了入口却没回来（收起来就回不来了）",
            count(PlanLogic.TITLE) >= 1,
        )
        rule.onNodeWithText(PlanLogic.TITLE).assertIsDisplayed()

        assertPlanNotOpenYet()
        clickEntryAndWaitPlan()
    }

    /**
     * 任务那半的关键文字必须在同一次渲染里都在场，而那半的内容**一个都不许出现**。
     *
     * 为什么这条自己喂一份小数据、直接渲染这一页，而不是接着上面走底栏：
     * 种子里有 40+ 条任务、本身就比一屏高，LazyColumn 只组合屏幕里的 item。
     * 喂小数据以后全部内容都在一屏内，"任务页头、阶段分组、任务行都在、
     * 而资源行不在"就成了可以直接断言的形态。
     */
    @Test
    fun `任务那半的关键文字都在场_资源行一个都不许出现`() {
        val courseName = "数学分析（I）"
        val taskTitle = "数学分析（I）任务"
        val resTitle = "数学分析（I）视频"
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CampusDb::class.java,
        ).allowMainThreadQueries().build()
        smallDb = db
        runBlocking {
            db.dao().putCourses(listOf(Course(id = 1, name = courseName, teacher = "张三")))
            db.dao().putTasks(listOf(Task(id = 1, phase = "本学期", title = taskTitle, course_id = 1)))
            db.dao().putSteps(listOf(StudyStep(id = 1, task_id = 1, seq = 1, text = "看第一章")))
            db.dao().putResources(
                listOf(Resource(id = 1, task_id = 1, kind = "video", title = resTitle, url = "https://a.example/1")),
            )
            db.dao().putMeta(listOf(Meta(PlanApplier.K_SOURCE, PlanApplier.REMOTE)))
        }

        rule.setContent { CampusTheme { TasksLearnScreen(db = db, onOpenPlan = {}) } }
        rule.waitForIdle()
        assertTrue("这一页没渲染出任务内容", awaitText(tasksHeadLine))

        // 任务那半：页头 + 进度抬头 + 阶段分组 + 任务行
        assertTrue("任务那半的进度抬头不在场（\"N 项 · 步骤 a/b\"）", countSubstring("项 · 步骤") >= 1)
        assertTrue("任务那半的阶段分组不在场", countSubstring("本学期") >= 1)
        assertTrue("任务那半的任务行不在场", countSubstring(taskTitle) >= 1)

        // 那半删掉了：条数抬头、资源行都不该在（资源只在任务详情里看）
        assertTrue(
            "被删掉的那半的条数抬头又出现了（$removedHalfMarker）",
            countSubstring(removedHalfMarker) == 0,
        )
        assertTrue(
            "任务列表里冒出了资源行「$resTitle」—— 资源只该在任务详情或「学习库」里出现",
            countSubstring(resTitle) == 0,
        )

        // 还是"一页"：没有子切换
        assertNoSubTabs()
    }
}
