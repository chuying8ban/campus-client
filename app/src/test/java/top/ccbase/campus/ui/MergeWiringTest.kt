package top.ccbase.campus.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「学习」这一格（原「任务 + 学习」合并页）的接线契约。
 *
 * 为什么用文件级断言而不是挂载界面：这类风险不在渲染，而在\"哪天有人把某一段从页面里
 * 摘掉 / 又塞回来\"——那时功能就**无声地**变了（本项目的监控 tab 就发生过一次这种事，
 * 页面类还在包里、没人调用，界面上只有一句空壳说明）。
 *
 * 历史沿革（这份文件自己也翻过几次面，每条断言都能证伪）：
 *   · 2026-09-18 用户要求「学习里面就不要留两个标签了，合并成一个就行了」→ 钉住"没有
 *     内层子切换、两半必须铺进同一条滚动页"；
 *   · 2026-09-19 用户要求「学习库和这里的功能重复了，把这里的删了吧」→ **再翻一次面**：
 *     现在钉的是"资源那半不许回来、`ui/learn` 包不许回来"，以及"资源只有一个入口（学习库）"。
 *
 * 这一版每条断言对应的错误做法：
 *   · 有人把学习内容那半又接回这一页 → 「那半不许回来」立刻红；
 *   · 有人把 `ui/learn` 目录恢复出来但没接线（最容易发生的半吊子状态）→ 目录存在那条红；
 *   · 有人顺手把 `CampusTab.LEARN` 加回枚举（标签也叫「学习」，界面上会出现两个同名格）→ 红；
 *   · 跳「学习」那一格又用回枚举序号 → 红（这条真错过：非作者用户会被跳到「学习库」）；
 *   · 入口被挪进列表里 / 挪到内容之后 → 红。
 */
class MergeWiringTest {

    private val src: String by lazy {
        File("src/main/java/top/ccbase/campus/ui/CampusApp.kt").readText()
    }

    /** 这一页那一段源码（从 `fun TasksLearnScreen(` 到文件末尾）。 */
    private val merged: String by lazy {
        val from = src.indexOf("fun TasksLearnScreen(")
        assertTrue("CampusApp 里找不到学习页 TasksLearnScreen —— 这一页没了", from >= 0)
        src.substring(from)
    }

    private val mainSrc: List<File> by lazy {
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `任务 tab 必须接真实页面而不是空壳`() {
        assertTrue(
            "学习 tab 没接到页面（点进去会看到'空壳'）",
            src.contains("CampusTab.TASKS -> TasksLearnScreen("),
        )
    }

    /**
     * 用户要的就是"资源那半别在这儿再出现一次"：它和「学习库」是同一件事的两种读法。
     * 删的是**功能的一半**，所以必须同时钉住"删干净了"和"任务那半还在"——只钉前者
     * 用一句 `!contains` 就能糊过去（把整页删了也满足）。
     */
    @Test
    fun `资源那半整份删掉_任务那半一条都不许丢`() {
        assertTrue(
            "CampusApp 里还留着「任务 | 学习」子切换 —— 用户要的就是去掉它",
            !src.contains("SubTab("),
        )
        assertTrue("子切换的测试标签还在（subtab-*）", !src.contains("subtab-"))

        assertTrue(
            "学习内容那半又接回这一页了 —— 它和「学习库」重复，用户明确要求删掉",
            !merged.contains("learnContent("),
        )
        assertTrue(
            "任务那半没有从这一页渲染出来 —— 删那半的时候把任务也删了",
            merged.contains("tasksContent("),
        )
        assertTrue(
            "页面没接上任务数据（rememberTasksSections 丢了）—— 任务会永远转圈",
            merged.contains("rememberTasksSections("),
        )
    }

    /** 半吊子状态：留着 `ui/learn` 目录但不接线。它下次一定会被谁"顺手"接回去。 */
    @Test
    fun `学习内容那半的代码整份不许留在工程里`() {
        assertFalse(
            "ui/learn 目录还在 —— 用户要求的是把这半删掉，不是让它躺在包里等复活",
            File("src/main/java/top/ccbase/campus/ui/learn").exists(),
        )
        val hits = mainSrc.flatMap { f ->
            f.readLines()
                .filter { it.contains("ui.learn") || it.contains("LearnScreen") || it.contains("LearnLogic") }
                .map { "${f.path}: ${it.trim()}" }
        }
        // TasksLearnScreen 这个名字是允许的（它是这一页自己的函数名），只挡真实引用
        val real = hits.filter { !it.contains("TasksLearnScreen") }
        assertEquals("还有代码引用已经删掉的学习内容那半：$real", 0, real.size)
    }

    /**
     * 这一页只该有**一条**滚动列表。
     *
     * 最省事的错误做法是"回退成两个页面各自带滚动容器、上下摞在一个 Column 里"：
     * 那样看着也像一页，但滚动位置、加载态、返回行为各自为政
     * （而且 LazyColumn 套 LazyColumn 会直接崩）。
     */
    @Test
    fun `这一页只有一条 LazyColumn_任务内容铺在里面`() {
        assertEquals(
            "这一页里应当只有一条 LazyColumn",
            1, Regex("LazyColumn\\(").findAll(merged).count(),
        )
        assertTrue(
            "任务内容没铺在那条列表里",
            merged.indexOf("LazyColumn(") < merged.indexOf("tasksContent("),
        )
    }

    /**
     * 底栏不许出现两个「学习」：删掉那半以后，资源只有「学习库」一个入口。
     *
     * 枚举里那个隐藏的 `LEARN("学习", …)` 就是当年"同一功能两处入口"的来源 ——
     * 它在底栏过滤里被排除，所以界面上看不见，最容易在后续改动里复活。
     */
    @Test
    fun `底栏只有一个叫学习的格_资源只有一个入口`() {
        assertEquals(
            "底栏有两个同名的「学习」格（一个是隐藏的 LEARN）—— 用户要的就是别重复",
            1, CampusTab.entries.count { it.label == "学习" },
        )
        assertTrue("CampusTab.LEARN 还在（功能已经删了，枚举项留着只会误导）", !src.contains("CampusTab.LEARN"))
        assertTrue(
            "「学习库」那一格必须还在（资源现在只有这一个入口）",
            src.contains("CampusTab.LIBRARY ->"),
        )
    }

    /**
     * 从计划页点「去「学习」清单看看」时的跳法。
     *
     * `idx` 是**过滤后**那条 tab 栏的下标，不是枚举序号：没拿到 can_grab 的人底栏里
     * 没有「监控」那一格，序号就差开一位（看板已经收进「我的」页，不进底栏）。
     * 作者本人因为监控那一格在场，序号碰巧对得上，
     * 所以这个错**只在同学的手机上**出现：点"去学习清单"会被送到「学习库」。
     * 这条钉住它，别再拿 ordinal 当 idx。
     */
    @Test
    fun `跳到学习那一格要按 tab 下标算_不能拿枚举序号当位置`() {
        assertTrue(
            "CampusApp 里没有按 tabs 下标跳「学习」的写法（tabs.indexOf）",
            src.contains("idx = tabs.indexOf(CampusTab.TASKS)"),
        )
        assertTrue(
            "又用枚举序号当 tab 下标了（CampusTab.TASKS.ordinal）—— 非作者会被跳到别的格",
            !src.contains("CampusTab.TASKS.ordinal"),
        )
    }

    /**
     * 入口必须渲染在**内容之上**（在承载内容的 LazyColumn 之外、且在它前面）。
     *
     * 这一条 2026-09-19 换了含义。以前它等于"滚到哪都看得见"；现在用户要求
     * 「往下翻时 ai 学习规划应该隐藏」，所以入口**不在列表里**这件事变成了
     * "收放由我们说了算"的成因：它不会被内容滚走，而是按滚动位置收放
     * （行为本身由 LearningTabTest 用节点数/坐标证）。
     *
     * 这里钉源码结构，防的是有人图省事把入口改成那条列表的第一项 ——
     * 那一改，卡片就真的跟着内容滚出屏幕，"往上滚回一点它自己回来"就失效了。
     */
    @Test
    fun `AI 规划入口要摆在内容之上_并按滚动收放`() {
        val entry = merged.indexOf("PlanEntry(")
        val list = merged.indexOf("LazyColumn(")
        val tasksAt = merged.indexOf("tasksContent(")
        assertTrue("CampusApp 里找不到 AI 规划入口的渲染 —— 入口没了", entry >= 0)
        assertTrue("找不到承载内容的列表", list >= 0)
        assertTrue("找不到任务内容", tasksAt >= 0)
        assertTrue(
            "入口渲染在任务内容之后了（$entry > $tasksAt）—— 往下滚就看不见它了",
            entry < tasksAt,
        )
        assertTrue(
            "入口被塞进那条滚动列表里了（entry=$entry, list=$list）—— 它会跟着内容滚走",
            entry < list,
        )

        // 收放机关：入口包在 AnimatedVisibility 里，承载内容的列表把滚动状态交出来
        val anim = merged.indexOf("AnimatedVisibility(")
        assertTrue(
            "入口外面没有收放机关（AnimatedVisibility）—— 往下翻它会一直杵着，占掉四分之一屏",
            anim in 0 until entry,
        )
        assertTrue(
            "那条列表没把滚动状态交出来（state = listState）—— 收放就无从判断",
            merged.contains("state = listState"),
        )
        assertTrue("入口没接到浮层上（onOpenPlan 断链）", src.contains("onOpenPlan = onOpenPlan"))
    }

    /**
     * 入口全工程**只许渲染一处**。
     *
     * 两个渲染处 = 用户看见两张一模一样的卡片（"这里怎么有两个"）。
     * 这条按"调用点"数（`PlanEntry(` 且不是 `fun PlanEntry(`），不数定义。
     */
    @Test
    fun `AI 规划入口全工程只渲染一处`() {
        val sites = mainSrc.flatMap { f ->
            f.readLines()
                .filter { it.contains("PlanEntry(") && !it.contains("fun PlanEntry(") }
                .map { "${f.path}: ${it.trim()}" }
        }
        assertEquals("入口只许渲染一处，现在渲染在这些地方：$sites", 1, sites.size)
        assertTrue("入口该由学习页（CampusApp）渲染，现在在：${sites.first()}", sites.first().contains("CampusApp.kt"))
    }
}
