package top.ccbase.campus.ui.library

import android.app.Application
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import top.ccbase.campus.data.library.Library
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.Catalog
import top.ccbase.campus.net.CatalogCourse
import top.ccbase.campus.net.CatalogItem
import top.ccbase.campus.net.CatalogKind
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「学习库」那一格的真界面：挑 → 加到我的清单。
 *
 * 为什么在真界面上测（而不是只测 [Library] 的纯函数）：
 *   · 这一页的价值全在"挑得中、加得对"这条链路上 —— 纯函数测不出
 *     "整行点按到底有没有接上勾选""底部那颗按钮点得到吗"；
 *   · 底部动作条 + 列表内容区是两层，最容易出的错是**最后一条被固定条盖住**
 *     （点了个空气、不报错）—— 只有真渲染 + assertIsDisplayed 才看得出来。
 *
 * 规则：**自己建内存库**，不碰生产单例（`app().db`）。渲染 + 写库的用例必须这样隔离，
 * 否则数据会漏给同一个 JVM 里后面跑的类（技能里记着这个坑）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class LibraryScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: CampusDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, CampusDb::class.java).allowMainThreadQueries().build()
        runBlocking {
            db.dao().putCourses(
                listOf(
                    Course(id = 7, name = "数学分析（I）", teacher = "张三", credits = 3.0),
                    // 全班还一条资料都没攒的课 —— 它也必须能在学习库里点开（看到课程信息）
                    Course(id = 8, name = "军事理论导论", teacher = "李四", credits = 2.0),
                ),
            )
            db.dao().putSlots(
                listOf(Slot(id = 1, weekday = 1, course_id = 7, p_start = 1, p_end = 2, room = "II区308", weeks = "3,4,5")),
            )
        }
    }

    @After
    fun tearDown() {
        TokenStore.clear(app)
    }

    private fun item(url: String, title: String, course: String, kind: String = "video") = CatalogItem(
        url = url, title = title, kind = kind,
        kindLabel = if (kind == "video") "视频" else "文档",
        source = "B站", course = course, note = "视频 · 来源 B站",
    )

    /** 夹具逐字照真接口的形状（courses/kinds 由服务端汇总，App 不自己统计）。 */
    private fun catalog() = Catalog(
        items = listOf(
            item("https://a/1", "函数与极限", "数学分析（I）"),
            item("https://a/2", "导数与微分", "数学分析（I）", kind = "doc"),
            item("https://c/1", "指针入门", "程序设计基础（B）"),
        ),
        courses = listOf(CatalogCourse("数学分析（I）", 2), CatalogCourse("程序设计基础（B）", 1)),
        kinds = listOf(
            CatalogKind("video", "视频", 2),
            CatalogKind("doc", "文档", 1),
            CatalogKind("course", "慕课", 0),
            CatalogKind("practice", "练习", 0),
        ),
        total = 3,
        generatedAt = "2026-09-19T16:00:00+08:00",
    )

    private fun show(load: suspend () -> ApiResult<Catalog>) {
        rule.setContent { CampusTheme { LibraryScreen(db = db, load = load) } }
    }

    private fun tap(tag: String, unmerged: Boolean = false) {
        waitTag(tag, unmerged)
        val n = rule.onNodeWithTag(tag, useUnmergedTree = unmerged)
        // 先证明"用户够得着"：节点在视口外时 performClick 是按坐标注入的，落在空处不报错
        n.assertIsDisplayed()
        n.performClick()
        rule.waitForIdle()
    }

    private fun waitTag(tag: String, unmerged: Boolean = false) {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 等一段文字出现。
     *
     * 详情页的时间段/教室是 `LaunchedEffect` **异步**读进来的，"打开详情 → 立刻断言"必然偶发红
     * （库里有 ≠ 界面上已经有）。凡是等界面上的东西一律走这里，别裸断言。
     */
    private fun waitText(text: String) {
        try {
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("等了 10 秒界面上还是没有「$text」", e)
        }
    }

    private fun tasks() = runBlocking { db.dao().visibleTasks().first() }
    private fun resources() = runBlocking { db.dao().visibleResources().first() }
    private fun steps() = runBlocking { db.dao().allSteps().first() }

    @Test
    fun 别人在学的课_详情页给公开信息与加入按钮_不编上课时间() {
        val base = catalog()
        val c = base.copy(
            items = base.items + item("https://ai/1", "机器学习入门", "人工智能基础", kind = "course"),
            courses = base.courses +
                CatalogCourse("人工智能基础", 1, learners = 1, teacher = "艾尔西丁", credits = 2.0),
        )
        show { ApiResult.Ok(c) }

        waitText("学习库")
        tap(libCourseTag("人工智能基础"))
        waitTag(LIB_PICK)
        rule.onNodeWithTag(LIB_PICK).assertIsDisplayed()
        rule.onNodeWithText("1 人在学", substring = true).assertIsDisplayed()
        // 上课时间是"我的课表"才有的事实：别人的课表长什么样我不知道，绝不编一个出来
        rule.onNodeWithText("上课时间").assertDoesNotExist()
    }

    @Test
    fun 自己课表里的课_照旧显示上课时间_不出现加入按钮() {
        show { ApiResult.Ok(catalog()) }

        waitText("学习库")
        tap(libCourseTag("数学分析（I）"))

        rule.onNodeWithText("上课时间").assertIsDisplayed()
        rule.onNodeWithTag(LIB_PICK).assertDoesNotExist()
    }

    @Test
    fun 按课程进去勾两条加到清单_学习页出现一条任务两条资料() {
        show { ApiResult.Ok(catalog()) }

        tap(libCourseTag("数学分析（I）"))
        tap(libCheckTag("https://a/1"))
        tap(libCheckTag("https://a/2"))

        // 没点底部那颗按钮之前**一个字节都不许落库**（点选不是写操作）
        assertEquals("勾选阶段不该写库", 0, tasks().size)

        tap(LIB_ADD)

        rule.waitUntil(10_000) { tasks().isNotEmpty() }
        val t = tasks().single()
        assertEquals(Library.taskIdFor(7), t.id)
        assertEquals("标题要跟课程和条数走", Library.taskTitle("数学分析（I）", 2), t.title)
        assertEquals(2, resources().count { it.task_id == t.id })
        assertEquals(2, steps().count { it.task_id == t.id })
        // 资料带着能打开它的链接 —— 用户点进详情要能真的打开
        assertTrue(resources().any { it.url == "https://a/1" && it.task_id == t.id })
        // 加完之后选中清空、底部那条动作条收起（否则用户会以为还可以再点一次）
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(LIB_ADD).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("已加 2 条到「学习」页的「学习库」").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun 换分类看的时候勾选不丢() {
        show { ApiResult.Ok(catalog()) }

        tap(libCourseTag("数学分析（I）"))            // 默认是"按课程"：先进这门课
        tap(libCheckTag("https://a/1"))
        picked(1)

        tap(LIB_MODE_KIND)                       // 切到"按类型"（会自动退出课程）
        picked(1)                                // 换个看法不该把勾选清掉
        tap(libCheckTag("https://a/2"))           // 在类型视图里再挑一条
        picked(2)

        tap(LIB_MODE_COURSE)
        tap(libCourseTag("数学分析（I）"))
        picked(2)                                // 切回来勾选还在
        tap(LIB_BACK)
        picked(2)                                // 退回课程列表勾选也还在
    }

    private fun picked(n: Int) {
        // 底部那条是界面上唯一能看到"选了几条"的地方，直接断言它 ——
        // 比去翻语义树内部字段稳（那些 API 也不是给用例读的）
        assertEquals(
            "底部动作条上的选中数不对：看不到「已选 ${n} 条」这一行",
            1,
            rule.onAllNodesWithText("已选 ${n} 条").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun 拉不到目录时照实说_并给重试() {
        var tries = 0
        show {
            tries++
            if (tries == 1) ApiResult.Err(0, "网络连不上，请检查网络") else ApiResult.Ok(catalog())
        }

        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("网络连不上，请检查网络").fetchSemanticsNodes().isNotEmpty()
        }
        // 拉不到就说拉不到 —— 显示成"暂时没有资料"等于谎报，用户会以为库里是空的
        assertEquals(
            "不该在拉取失败时显示空库文案",
            0,
            rule.onAllNodesWithText("这里还没有资料").fetchSemanticsNodes().size,
        )

        tap(LIB_RETRY)
        waitTag(libCourseTag("数学分析（I）"))
    }

    @Test
    fun 按类型筛选只看这一类() {
        show { ApiResult.Ok(catalog()) }

        tap(LIB_MODE_KIND)
        tap(libKindTag("doc"))
        waitTag(libItemTag("https://a/2"))
        assertEquals(
            "筛了文档就不该还露着视频",
            0,
            rule.onAllNodesWithTag(libItemTag("https://a/1")).fetchSemanticsNodes().size,
        )
    }

    // ------------------------------------------------------------ 课程详情页
    // 用户原话：「我希望用户可以在学习库中查看每个课程的详细内容」。
    // 所以点一门课不是"行内筛一下"，而是进这门课的详情：课程信息 + 上课时间地点 + 资料分组。

    @Test
    fun 点进一门课看到课程信息与上课时间地点_资料按类型分组() {
        show { ApiResult.Ok(catalog()) }

        tap(libCourseTag("数学分析（I）"))
        waitTag(libDetailTag("数学分析（I）"))

        // 课程信息（教师 · 学分）—— 这些字只可能来自本机课表数据
        assertTrue(
            "详情页没显示教师与学分",
            rule.onAllNodesWithText("张三 · 3 学分").fetchSemanticsNodes().size >= 1,
        )
        // 上课时间地点（异步读进来的 —— 等它到再断言，别裸断言）
        waitText("周一")
        waitText("II区308")

        // 资料按类型分组：视频/文档各自成组、标题里带条数
        waitTag(libItemTag("https://a/1"))
        assertTrue("没有「视频 N」这一组", rule.onAllNodesWithText("视频 1").fetchSemanticsNodes().size >= 1)
        assertTrue("没有「文档 N」这一组", rule.onAllNodesWithText("文档 1").fetchSemanticsNodes().size >= 1)
        // 分组里点方框仍然能勾（勾方框 = 加清单；点整行 = 打开资料，两件事）
        tap(libCheckTag("https://a/1"))
        picked(1)
    }

    @Test
    fun 全班还没资料的课也能点进去_照实说没有资料() {
        show { ApiResult.Ok(catalog()) }

        // 课表里的课都在表上，没资料的标"暂无资料"（藏起来用户会以为漏了自己的课）
        assertTrue(
            "没资料的课没列在学习库里",
            rule.onAllNodesWithTag(libCourseTag("军事理论导论")).fetchSemanticsNodes().size >= 1,
        )

        tap(libCourseTag("军事理论导论"))
        waitTag(libDetailTag("军事理论导论"))

        // 没资料也要看到课程本身的信息 —— 这正是"查看每个课程的详细内容"的一部分
        assertTrue(
            "没资料的课进去看不到教师学分",
            rule.onAllNodesWithText("李四 · 2 学分").fetchSemanticsNodes().size >= 1,
        )
        assertTrue(
            "没资料时没有照实说明",
            rule.onAllNodesWithText("这门课还没有资料。").fetchSemanticsNodes().size >= 1,
        )
        // 不能拿"这里还没有资料"那种空库文案糊过去（那是在说整个库是空的）
        assertEquals(
            "没资料的课不该显示成'整个库都没资料'",
            0,
            rule.onAllNodesWithText("这里还没有资料").fetchSemanticsNodes().size,
        )

        // 返回课程列表（标签在，别让用户进得去出不来）
        tap(LIB_BACK)
        waitTag(libCourseTag("数学分析（I）"))
    }

    // ------------------------------------------------------------ 已加入标记 / 选错了能去掉

    @Test
    fun 加过的条目打上已加入标记_整行点按是打开不是移出_点明写的移出才真的走() {
        show { ApiResult.Ok(catalog()) }

        tap(libCourseTag("数学分析（I）"))
        tap(libCheckTag("https://a/1"))
        tap(LIB_ADD)
        waited("加进清单", { tasks().size }) { tasks().isNotEmpty() }

        // ① 加过的打标记（不打的话用户会重复勾、重复点"加入"，被去重吃掉才发现白折腾）
        waited("「已加入」标记出现在那一条上", { 快照() }) {
            rule.onAllNodesWithTag(libAddedTag("https://a/1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            "没加过的那条不许打标记",
            0,
            rule.onAllNodesWithTag(libAddedTag("https://a/2"), useUnmergedTree = true).fetchSemanticsNodes().size,
        )

        // ② 整行点按 = **打开这条资料**（预览）：跟加不加清单无关，更不该把已加入的弄走
        shadowOf(app).clearNextStartedActivities()
        tap(libItemTag("https://a/1"))
        assertEquals(
            "点已加入的资料行要打开它（不是移出）",
            "https://a/1", shadowOf(app).nextStartedActivity?.data.toString(),
        )
        assertTrue("只是想看看这是什么，结果资料被移出了", tasks().isNotEmpty())
        assertTrue("资料也不该消失", resources().any { it.url == "https://a/1" })

        // ③ 选错了能去掉：点那个明写的「移出」，一点即回（不给确认框 —— 撤销类不挡）
        tap(libRemoveTag("https://a/1"), unmerged = true)
        waited("移出后清单里没有它了", { 快照() }) { tasks().isEmpty() }
        assertEquals("资料行要跟着清掉", 0, resources().count { it.url == "https://a/1" })
        waited("「已加入」标记跟着消失", { 快照() }) {
            rule.onAllNodesWithTag(libAddedTag("https://a/1"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        // 操作结果照实说 —— 悄悄没了用户会以为界面抽风
        waited("状态行说已移出", { 快照() }) {
            rule.onAllNodesWithText("已从「学习库」移出").fetchSemanticsNodes().isNotEmpty()
        }
        // 移出之后还能重新挑回来（别把条目变成"再也加不了"）
        tap(libCheckTag("https://a/1"))
        tap(LIB_ADD)
        waited("重新加回来", { 快照() }) { tasks().isNotEmpty() }
    }

    // ------------------------------------------------------------ 直接看（不必先加清单）

    /**
     * 用户 2026-09-20：*现在学习库里还是不能预览课程，我希望用户可以直接在学习库里学习课程，
     * 而不是非要加进清单*。
     *
     * 钉死两件事：① 没加进清单也能**打开**这条资料（整行点按 → Links.open 走 Custom Tabs）；
     * ② 打开**不等于**收藏 —— 一按就落库那种"预览"是拿预览当幌子骗用户收藏。
     */
    @Test
    fun 没加进清单也能直接打开资料_打开不等于加清单() {
        show { ApiResult.Ok(catalog()) }

        tap(libCourseTag("数学分析（I）"))
        waitTag(libItemTag("https://a/1"))

        shadowOf(app).clearNextStartedActivities()
        tap(libItemTag("https://a/1"))

        assertEquals(
            "点资料行要打开它本身（Custom Tabs 在 App 内滑出）—— 这才是「直接学」",
            "https://a/1", shadowOf(app).nextStartedActivity?.data.toString(),
        )
        // 底部那条动作条只在勾了东西之后出现：没勾就没有它
        assertEquals(
            "看一眼不该等于是加进清单",
            0, rule.onAllNodesWithTag(LIB_ADD).fetchSemanticsNodes().size,
        )
        assertTrue("资料被顺手塞进了清单", tasks().isEmpty())

        // 想收藏才点方框 —— 这才出现底部「加到我的清单」
        tap(libCheckTag("https://a/1"))
        assertEquals(
            "勾方框之后底部动作条要出现（不然用户加不进东西）",
            1, rule.onAllNodesWithTag(LIB_ADD).fetchSemanticsNodes().size,
        )
    }

    // ------------------------------------------------------------ 搜索 / 刷新 / 课程标签
    // 2026-09-19 加。一条资料一条资料地翻是这一页原来最笨的地方（91 条摊在一个平面上）。

    /**
     * 搜索：**本地过滤**，不发请求。钉两件事：
     *   ① 按课程名搜能筛掉别的课；② 清空搜索词之后课程都回来
     * （筛掉之后回不来的搜索框是最招人烦的毛病）。
     */
    @Test
    fun 搜索框按课程名过滤_清空后课程都回来() {
        show { ApiResult.Ok(catalog()) }
        waitTag(libCourseTag("数学分析（I）"))
        waitTag(libCourseTag("程序设计基础（B）"))

        rule.onNodeWithTag(LIB_SEARCH).performTextInput("数学")
        waited("搜「数学」只剩数学分析（I）", { 搜索现场() }) {
            rule.onAllNodesWithTag(libCourseTag("数学分析（I）")).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithTag(libCourseTag("程序设计基础（B）")).fetchSemanticsNodes().isEmpty()
        }

        rule.onNodeWithTag(LIB_SEARCH).performTextClearance()
        waited("清空之后 程序设计基础（B） 那门课回来了", { 搜索现场() }) {
            rule.onAllNodesWithTag(libCourseTag("程序设计基础（B）")).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithTag(libCourseTag("数学分析（I）")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 搜不到 ≠ 库里没有：两句话必须分开，否则用户以为资料被删了 */
    @Test
    fun 搜不到时照实说没搜到_不说成这里还没有资料() {
        show { ApiResult.Ok(catalog()) }
        waitTag(libCourseTag("数学分析（I）"))

        rule.onNodeWithTag(LIB_SEARCH).performTextInput("量子力学")
        waitText("没搜到「量子力学」相关的课程")
        assertEquals(
            "搜不到的时候不能说成「这里还没有资料」——那是两件事",
            0,
            rule.onAllNodesWithText("这里还没有资料").fetchSemanticsNodes().size,
        )
    }

    /** 「按类型」里按资料名搜：只留命中的那条（课程标签也跟着走，不留空壳行） */
    @Test
    fun 按类型里搜资料名只留那一条() {
        show { ApiResult.Ok(catalog()) }
        waitTag(libCourseTag("数学分析（I）"))
        tap(LIB_MODE_KIND)
        waitTag(libItemTag("https://c/1"))

        rule.onNodeWithTag(LIB_SEARCH).performTextInput("指针")
        waited("搜「指针」只剩 程序设计基础（B） 那条", { 搜索现场() }) {
            rule.onAllNodesWithTag(libItemTag("https://c/1")).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithTag(libItemTag("https://a/1")).fetchSemanticsNodes().isEmpty()
        }
    }

    /** 刷新：按一下就重拉一次目录 —— 用户不该为了看一眼新链接去重启 App */
    @Test
    fun 刷新按钮会重新拉一次目录() {
        var calls = 0
        show { calls += 1; ApiResult.Ok(catalog()) }
        waitTag(libCourseTag("数学分析（I）"))
        assertEquals("进页面就该拉一次", 1, calls)

        tap(LIB_REFRESH)
        waited("点刷新后再拉一次（共 2 次）", { "拉取次数=$calls" }) { calls >= 2 }
        waitTag(libCourseTag("数学分析（I）"))
    }

    /**
     * 「按类型」那一列的课程标签：点它进那门课的详情。
     *
     * 为什么要有它：按类型摊开后，同一门课的资料散落在各处。用户看到一条好链接、想"这门课
     * 还有别的什么"时，原来只能自己返回 → 切回按课程 → 再找那门课。
     */
    @Test
    fun 按类型里点课程标签进那门课的详情() {
        show { ApiResult.Ok(catalog()) }
        tap(LIB_MODE_KIND)
        waitTag(libCourseChipTag("数学分析（I）"), unmerged = true)

        // 数学分析（I）有两条资料 → 这个标签会出现两次；"第几个同名节点"不是契约，取第一个点就行
        val chip = rule.onAllNodesWithTag(libCourseChipTag("数学分析（I）"), useUnmergedTree = true)[0]
        chip.assertIsDisplayed()
        chip.performClick()
        rule.waitForIdle()

        waitTag(libDetailTag("数学分析（I）"))
        waitText("张三 · 3 学分")     // 详情页的课程信息（只可能来自本机课表数据）

        // 进得去也要出得来：返回回到"按类型"那一支（用户原来的位置与搜索词都不该被翻掉）
        tap(LIB_BACK)
        waitTag(libItemTag("https://c/1"))
    }

    /** 搜索现场：超时的时候把当时列表里有什么打出来，省得下一轮靠猜 */
    private fun 搜索现场(): String {
        fun n(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
        return "课程[数学分析（I）]=${n(libCourseTag("数学分析（I）"))} 课程[程序设计基础（B）]=${n(libCourseTag("程序设计基础（B）"))} " +
            "资料[a/1]=${n(libItemTag("https://a/1"))} 资料[c/1]=${n(libItemTag("https://c/1"))} " +
            "搜索框=${rule.onAllNodesWithTag(LIB_SEARCH).fetchSemanticsNodes().size}"
    }

    /** 当时界面上是什么样 —— 超时的时候把现场打出来，省得下一轮靠猜（技能里的规矩） */
    private fun 快照(): String = "任务=${tasks().size} 资料=${resources().size} " +
        "已加入标记=${rule.onAllNodesWithTag(libAddedTag("https://a/1"), useUnmergedTree = true).fetchSemanticsNodes().size} " +
        "移出按钮=${rule.onAllNodesWithTag(libRemoveTag("https://a/1"), useUnmergedTree = true).fetchSemanticsNodes().size}"

    /** waitUntil 超时只会给一句 "Condition still not satisfied"，查不出卡在哪 → 把现场一起抛出来 */
    private fun waited(what: String, seen: () -> Any, cond: () -> Boolean) {
        try {
            rule.waitUntil(10_000, cond)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$what 超时；当时看到：${seen()}", e)
        }
    }
}
