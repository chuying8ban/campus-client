package top.ccbase.campus.ui.tasks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.TestSeedDb
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「从我的清单里删掉」整条链路：确认 → 打墓碑 → 页尾能捞回来 → 服务端再同步不复活。
 *
 * 为什么要写成 Robolectric 用例而不是"改完手点一下"：这条链路的坑全在**边界**上 ——
 * 取消到底动没动库、同步会不会把删掉的写回来、删完还停不留在详情里。
 * 手点一遍根本覆盖不到"再同步一次"这种时序。
 */
@RunWith(RobolectricTestRunner::class)
// 视口给成真机尺寸：默认的 320x470 太矮，列表类页面在视口外的东西根本不会 compose，
// 表现成"点了没反应 / 找不到节点"（跟真机上"用户没滚到那儿"是两回事）
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TaskDeleteTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    /**
     * 自己的内存库（灌了完整种子）。**不用 `app().db`** —— App 的库现在只灌通用数据、
     * 没有任务（刻意如此：不拿作者本人的数据冒充别人的计划），而且共享库会污染后面跑的类。
     */
    private val tdb by lazy { TestSeedDb.seeded(app()) }

    private fun waitFor(text: String) = rule.waitUntil(15_000) {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String) = rule.waitUntil(15_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    /** 名字别叫 hasText/hasTag：成员函数会遮蔽 Compose 同名的匹配器（踩过，报"期望 SemanticsMatcher 实参是 Boolean"） */
    private fun showsText(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun showsTag(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun tapTag(tag: String) {
        // ① 尽量滚进视口（列表里滑出屏幕的项会被回收）；滚不到不算错，交给 ② 报错
        runCatching { scrollTo(tagMatcher(tag)) }
        // ② 等它真的出现在**界面**里（界面是异步重组的），再用语义动作点
        //    —— 不吃坐标，所以"只露出一半"也不会打空
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onAllNodesWithTag(tag)[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    /** 点第 [index] 个精确匹配的文字节点（页面里可能有多处同词，按下标取） */
    private fun tap(text: String, index: Int = 0) =
        rule.onAllNodesWithText(text, substring = false)[index].performClick()

    /**
     * 任务页上有**两个**可滚动节点（横向的分类 chips + 纵向的任务清单），
     * 所以滚动时必须指名纵向那个（"能按序号滚"是 LazyColumn 独有的能力）。
     */
    private fun listScrollable() = hasScrollAction() and hasScrollToIndexAction()

    /**
     * 把某个节点滚进视口再操作。
     *
     * 为什么必须滚：LazyColumn 和 verticalScroll 都只 compose 视口内的内容 ——
     * "已删除"那段在清单最底部、详情页的删除行在详情最底部，不滚过去语义树里根本没有它们
     * （第一版用例就是在这儿红的：`There are no existing nodes for that selector`），
     * 更阴的是详情页那处：节点存在但在视口外，click 落在空处、什么也没发生 → 表现为 15s 超时。
     */
    private fun scrollTo(matcher: SemanticsMatcher) {
        // 两道等待，都是拿"真实发生的失败"当条件，而不是猜时间：
        // ① 先等纵向列表"稳定地只有 1 个"——删/恢复触发的重组过程中它会短暂变成 0 个或 2 个；
        // ② 再等"真的滚到"——界面是异步重组的，"库里有"不等于"界面上有"，
        //    界面还没把目标放回来时 performScrollToNode 会一路滚到底都找不到节点
        //    （报 `No node found that matches TestTag = '…'`，本用例 50% 偶发就是它）。
        // 滚不到会抛异常，就把异常当重试信号。修在这里，所有调用点一起受益。
        rule.waitUntil(15_000) {
            if (rule.onAllNodes(listScrollable()).fetchSemanticsNodes().size != 1) return@waitUntil false
            runCatching { rule.onNode(listScrollable()).performScrollToNode(matcher) }.isSuccess
        }
        rule.waitForIdle()
    }

    /**
     * 「等某个东西消失」而不是"立刻断言它不存在"。
     *
     * 删/恢复之后界面重排是异步的（库改了 → LaunchedEffect 重读 → 重组），
     * 裸断言会抢在重组之前跑，偶发变红（真踩过：库里已经删了，界面上那一行还没走）。
     */
    private fun waitGoneText(text: String) = rule.waitUntil(15_000) { !showsText(text) }

    private fun waitGoneTag(tag: String) = rule.waitUntil(15_000) { !showsTag(tag) }

    /** 按 testTag 匹配（不用 Compose 的 hasTestTag：这个版本的签名跟我们的用法不匹配） */
    private fun tagMatcher(tag: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)

    /**
     * 挑一条**可见**的真实任务，返回它的 id 与标题（标题不为空的那类更稳）。
     *
     * 为什么必须从 `visibleTasks()` 挑而不是 `tasks()`：同一个 JVM 里 `CampusDb.get()` 是
     * 进程级单例 —— Robolectric 的多个测试类**共用同一个库文件**，别的用例留下的墓碑
     * 会跟到这里来（真踩过：这条用例从 tasks() 挑的第一条正好被上一个用例删掉了，
     * 于是"删除入口找不到"，连带把 TaskDoneConfirmTest 也带红）。
     */
    private fun pickTask(): Pair<Int, String> = runBlocking {
        app().seedJob?.join()
        val t = tdb.dao().visibleTasks().first().first { it.title.isNotBlank() }
        t.id to t.title
    }

    private fun visibleIds(): Set<Int> = runBlocking {
        tdb.dao().visibleTasks().first().map { it.id }.toSet()
    }

    private fun tombstoneIds(): Set<Int> = runBlocking {
        tdb.dao().deletedTaskIds().first().toSet()
    }

    /**
     * 用例跑完必须把墓碑清干净 —— 库是整个 JVM 共用的，留着会毒到后面跑的其它用例
     * （症状是"某个毫不相干的用例突然找不到任务"，非常难查）。
     */
    @After
    fun cleanTombstones() {
        runBlocking { tdb.dao().undeleteAllTasks() }
    }

    @Test
    fun `删除要先确认_取消则什么也没发生`() {
        val (id, _) = pickTask()
        val db = tdb
        assertTrue("用例前提：这条任务是可见的", visibleIds().contains(id))

        rule.setContent { CampusTheme { TasksScreen(db) } }
        waitFor("本学期")
        assertTrue("列表里应该有这一条的删除入口", showsTag(deleteTag(id)))

        tapTag(deleteTag(id))
        waitFor(DELETE_CONFIRM_TITLE)
        // ① 还没确认 —— 库里一个字不许变
        assertTrue("点开确认层不能就把任务删了", visibleIds().contains(id))
        assertTrue("没确认就还没有墓碑", tombstoneIds().isEmpty())

        // ② 取消 —— 弹层收起、任务还在、入口还在、底部也不该冒出"已删除"那段
        tap("取消")
        waitGoneText(DELETE_CONFIRM_TITLE)
        assertTrue("取消 = 什么也没发生", visibleIds().contains(id))
        assertTrue(showsTag(deleteTag(id)))
        assertTrue("取消不该留下墓碑", tombstoneIds().isEmpty())
        assertFalse("取消后不该出现已删除段", showsTag(RESTORE_TOGGLE_TAG))
    }

    @Test
    fun `删除之后_服务端再同步一次也不能让它复活`() {
        val (id, _) = pickTask()
        val db = tdb
        val row = runBlocking { db.dao().visibleTasks().first().first { it.id == id } }

        rule.setContent { CampusTheme { TasksScreen(db) } }
        waitFor("本学期")

        tapTag(deleteTag(id))
        waitFor(DELETE_CONFIRM_TITLE)
        tap("删除")
        rule.waitUntil(15_000) { !visibleIds().contains(id) }
        waitGoneTag(deleteTag(id))

        // 页尾那段要能捞回来（它在清单最底部，先滚过去）
        scrollTo(tagMatcher(RESTORE_TOGGLE_TAG))
        assertTrue("已删除的那一条要能在底部捞回来", showsText("已删除 1 项"))

        // ★ 关键：模拟服务端再同步同一批任务（Content.kt 走的就是 putTasks → upsert）
        runBlocking { db.dao().putTasks(listOf(row)) }
        rule.waitForIdle()
        assertFalse("同步把行写回来了，但它必须仍然不出现在清单里", visibleIds().contains(id))
        assertTrue("墓碑得留着，否则下次同步又活了", tombstoneIds().contains(id))
    }

    @Test
    fun `删错了能一条条恢复_也能全部恢复`() {
        val all = runBlocking {
            app().seedJob?.join()
            tdb.dao().visibleTasks().first().filter { it.title.isNotBlank() }.take(2)
        }
        assertEquals("用例前提：库里至少要有两条任务", 2, all.size)
        val db = tdb

        rule.setContent { CampusTheme { TasksScreen(db) } }
        waitFor("本学期")
        all.forEach { t ->
            tapTag(deleteTag(t.id))
            waitFor(DELETE_CONFIRM_TITLE)
            tap("删除")
            rule.waitUntil(15_000) { !visibleIds().contains(t.id) }
            waitGoneTag(deleteTag(t.id))   // 库层没了≠界面没了：等界面也把它收走，再点下一条
        }
        assertEquals("两条都该有墓碑", 2, tombstoneIds().count { it == all[0].id || it == all[1].id })

        // ① 展开「已删除」，一条条恢复
        scrollTo(tagMatcher(RESTORE_TOGGLE_TAG))
        tapTag(RESTORE_TOGGLE_TAG)
        // 按 id 点"这一条"的恢复（页面上有好几个同名的「恢复」，按下标取第一个等于赌顺序）
        val restoreNode = rule.onNode(tagMatcher(restoreTag(all[0].id)))
        restoreNode.performScrollTo()          // 先滚到看得见：顺带验证"用户够得着"
        rule.waitForIdle()
        restoreNode.assertIsDisplayed()
        // 用**语义动作**点，不用触摸点：节点只露出一半时 performClick 会打在屏外、静默无效
        // （这正是本用例以前偶发超时 15s 的根因）。语义点击直接触发 OnClick 回调，不吃坐标。
        restoreNode.performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(15_000) { visibleIds().contains(all[0].id) }   // 库层：恢复确实写回去了
        // 但「库里有」≠「界面上有」—— 界面是异步重组的（库改动 → LaunchedEffect 重读 → 重组）。
        // 这里以前直接滚，界面还没把这一条放回来，就会一路滚到底都找不到节点，
        // 偶发报 `No node found that matches TestTag = 'task-delete-N'`（本用例 4 次里红 2 次就是它）。
        // 所以滚到"真的滚到"为止：滚不到会抛异常，拿它当重试条件。
        rule.waitUntil(15_000) {
            runCatching { scrollTo(tagMatcher(deleteTag(all[0].id))) }.isSuccess
        }
        assertTrue("恢复后这一条要回到清单里", showsTag(deleteTag(all[0].id)))

        // ② 剩下那条走"全部恢复"（同样用语义动作点，理由同上）
        // 先把「已删除」那段滚回视口：LazyColumn 会回收滑出屏幕的项 —— 找不到节点
        // 不是按钮没了，而是它压根没被组合（1.69 发布闸门踩过这条）。
        scrollTo(tagMatcher(RESTORE_TOGGLE_TAG))
        val allNode = rule.onNodeWithText("全部恢复")
        allNode.performScrollTo()
        rule.waitForIdle()
        allNode.performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(15_000) { visibleIds().contains(all[1].id) }
        scrollTo(tagMatcher(deleteTag(all[1].id)))
        assertTrue("全部恢复后这条也要回来", showsTag(deleteTag(all[1].id)))
        assertTrue("都恢复完了墓碑就该清空", tombstoneIds().isEmpty())
        assertFalse("都恢复完了，底部那段就该收掉", showsTag(RESTORE_TOGGLE_TAG))
    }

    @Test
    fun `任务详情里也能删_删完退回列表`() {
        val (id, title) = pickTask()
        val db = tdb

        rule.setContent { CampusTheme { TasksScreen(db) } }
        waitFor("本学期")
        tap(title)                     // 进详情
        waitForTag(DETAIL_ROOT_TAG)
        // 删除行在详情最底部，得先滚过去（视口外点击会落在空处，表现为静默失败）
        rule.onAllNodesWithTag(DETAIL_DELETE_TAG)[0].performScrollTo()
        assertTrue("详情里要有删除入口", showsTag(DETAIL_DELETE_TAG))

        tapTag(DETAIL_DELETE_TAG)
        waitFor(DELETE_CONFIRM_TITLE)
        tap("删除")
        rule.waitUntil(15_000) { !visibleIds().contains(id) }
        // 删掉自己之后不能停在详情里（那条已经不在清单上了）
        waitGoneTag(DETAIL_ROOT_TAG)
        assertFalse("删完不该还停在任务详情里", showsTag(DETAIL_ROOT_TAG))
        assertTrue("删掉的那条应该躺在页尾的已删除里", tombstoneIds().contains(id))
    }
}
