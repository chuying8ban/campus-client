package top.ccbase.campus.ui.today

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.local.Resource
import top.ccbase.campus.domain.TaskNow
import top.ccbase.campus.domain.loadToday
import top.ccbase.campus.domain.pickTopRes
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 今日页第一屏要**直接看得见能看的课**（2026-09-19）。
 *
 * 用户原话：「同学使用 ai 学习计划时大多数是没有用的笔记，却很少有有用的视频课程」。
 * 视频藏在详情层 = 等于没给 —— 这一轮把「这条任务里有什么能看的」放到第一屏一行里，
 * 点它**直接开链接**（点整行仍然只是打开详情，两件事不能互相吃掉）。
 *
 * 和 TodayConfirmTest 同一套搭台方式：不硬编码文案，任务和资源都从库里查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class TodayResLineTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    /** 真正被 startActivity 出去的地址：点了资源有没有开链接，只能看这里 */
    private fun launchedUrl(): String? =
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.data?.toString()

    private fun nodes(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    /** 资源行（自带标题的那一行）里有没有这个类别标签？
     *  ⚠️ 资源行是**嵌套 clickable**，会自成语义节点、不会并进任务行 ——
     *  所以得按资源自己的标题找节点，再在它的文本里看标签（第一版按任务标题找，测出来是假的假）。 */
    private fun resLineHas(label: String, resTitle: String): Boolean =
        rule.onAllNodesWithText(resTitle, substring = true).fetchSemanticsNodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(label) } == true
        }

    /**
     * 夹具任务（[TodayFixture]）：**必然**属于"今天"，不看日历。
     *
     * 原来这里是 `assumeTrue` 跳过：种子库里查不到任务就整条不跑 —— 跳过就是没验
     * （2026-09-19 核实：种子没写 `plan_source`，所以这几条**永远**跳过）。
     * 现在查不到就断言失败：夹具坏了必须红。
     */
    private fun firstTaskRequired(): TaskNow = runBlocking {
        app().seedJob?.join()
        val db = app().db
        TodayFixture.seed(db)
        val mine = loadToday(db).let { it.tasks + it.standing }
            .firstOrNull { it.id == TodayFixture.TASK_ID }
        assertNotNull("夹具任务没进「今日」列表 —— 夹具失效了，不许拿跳过当通过", mine)
        mine!!
    }

    /** 把这个任务原有的资源先清掉：种子库里本来可能就有资源，
     *  不清的话我塞的那条排不上号，断言会飘（这条测试第一版就是这么飘的）。 */
    private fun clearRes(taskId: Int) = runBlocking {
        val ids = app().db.dao().resourcesOfTask(taskId).first().mapNotNull { it.id }
        if (ids.isNotEmpty()) app().db.dao().deleteResources(ids)
    }

    /** 往本机库塞一条资源（id 用 9xxxxx 段，避开种子/同步的数据） */
    private fun addRes(taskId: Int, kind: String?, title: String, url: String, id: Int = 900001) =
        runBlocking {
            app().db.dao().putResources(
                listOf(Resource(id = id, task_id = taskId, kind = kind, title = title, url = url, http = 200))
            )
        }

    private fun renderAndScrollTo(title: String) {
        rule.setContent { CampusTheme { TodayScreen(app().db) } }
        rule.waitUntil(15_000) { nodes("今日任务", substring = true) > 0 }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(title, substring = true))
        rule.waitForIdle()
    }

    // ------------------------------------------------------------ 第一屏看得见

    @Test
    fun `有视频的任务_第一屏就看得到那一行`() {
        val t = firstTaskRequired()
        clearRes(t.id)
        addRes(t.id, "video", "程序设计基础（B）入门 第1讲 · 复杂度与数组", "https://www.bilibili.com/video/BV1xx411c7mD")

        renderAndScrollTo(t.title)

        assertTrue("这条任务那一行没出现「视频」标签", resLineHas("视频", "复杂度与数组"))
        assertTrue("第一屏看不到视频标题", nodes("复杂度与数组", substring = true) > 0)
    }

    @Test
    fun `点视频行打开的是它的链接_不是展开详情`() {
        val t = firstTaskRequired()
        clearRes(t.id)
        val url = "https://www.bilibili.com/video/BV1xx411c7mD"
        addRes(t.id, "video", "程序设计基础（B）入门 第1讲 · 复杂度与数组", url)

        renderAndScrollTo(t.title)
        rule.onAllNodesWithText("复杂度与数组", substring = true)[0].performClick()
        rule.waitForIdle()

        assertEquals("点了视频行没打开它的链接", url, launchedUrl())
        // 嵌套点击被外层吃掉的话，这里会顺带把详情层展开 —— 那就等于「点了个假链接」
        assertEquals("点视频行把任务详情也展开了", 0, nodes("视频 / 资料", substring = true))
    }

    // ------------------------------------------------------------ 不该出现的

    @Test
    fun `链接是空的资源_一行都不画`() {
        // 库里真有可能存着 url 为空的坏数据（同步/迁移都可能）。画出来一行点了没反应，
        // 用户只会觉得界面坏了 —— 宁可没有这一行。
        val t = firstTaskRequired()
        clearRes(t.id)
        addRes(t.id, "video", "只有标题没有链接", "", id = 900002)

        renderAndScrollTo(t.title)

        assertTrue("任务行本身也不该没了", nodes(t.title, substring = true) > 0)
        assertEquals("那条没有链接的资源被画成了可点的一行", 0, nodes("只有标题没有链接", substring = true))
        assertFalse("这条任务那一行冒出了「视频」空壳", resLineHas("视频", "只有标题没有链接"))
    }

    @Test
    fun `只有文档的任务_也露一行_但不是视频`() {
        val t = firstTaskRequired()
        clearRes(t.id)
        addRes(t.id, "doc", "课程讲义 PDF", "https://example.test/notes.pdf", id = 900003)

        renderAndScrollTo(t.title)

        assertTrue("这条任务那一行没露文档", resLineHas("文档", "课程讲义 PDF"))
        assertFalse("把文档说成了视频", resLineHas("视频", "课程讲义 PDF"))
    }

    // ------------------------------------------------------------ 纯函数：挑哪一条

    @Test
    fun `第一屏优先给视频_其次课程练习文档`() {
        fun res(id: Int, kind: String, url: String = "https://x.test/a") =
            Resource(id = id, task_id = 1, kind = kind, title = "t$id", url = url)

        assertEquals("video", pickTopRes(listOf(res(1, "doc"), res(2, "video")))?.kind)
        assertEquals("course", pickTopRes(listOf(res(1, "doc"), res(2, "course")))?.kind)
        assertEquals("practice", pickTopRes(listOf(res(1, "doc"), res(2, "practice")))?.kind)
        assertEquals("认不出的种类也要能用（宁可露一条，也别空着）", "新花样",
            pickTopRes(listOf(res(1, "新花样")))?.kind)
        assertEquals(null, pickTopRes(emptyList()))
        assertEquals("url 为空的不能挑出来", null, pickTopRes(listOf(res(1, "video", url = ""))))
        assertEquals("url 是空白的也不能挑出来", null, pickTopRes(listOf(res(1, "video", url = "  "))))
    }
}
