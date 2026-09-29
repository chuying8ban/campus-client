package top.ccbase.campus.ui.tasks

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.TestSeedDb
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「点一下就完成」→「完成必须先确认」。
 *
 * 为什么值得专门测：步骤那一行的热区是**整行**，误触的代价是"其实没做却显示做了"，
 * 而它最容易在后续改动里被悄悄改回去（无非是把 onClick 改回来，看着无害）。
 *
 * 测试里**不硬编码任务/步骤文案**：全部从库里查出真实数据再点。
 * 种子模板换文案时这条测试不会跟着假红 —— 假红比不测更浪费。
 */
@RunWith(RobolectricTestRunner::class)
// 视口给成真机尺寸：默认 320x470 太矮，列表类页面视口外的行根本不会被组合
// （表现成"找不到节点"，跟真机上"用户没滚到那儿"是两回事）
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TaskDoneConfirmTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    /**
     * 自己的内存库（灌了完整种子）。**不用 `app().db`** —— App 的库现在只灌通用数据、
     * 没有任务（刻意如此：不拿作者本人的数据冒充别人的计划），而且共享库会污染后面跑的类。
     */
    private val tdb by lazy { TestSeedDb.seeded(app()) }

    private fun waitFor(text: String, ms: Long = 15_000) =
        rule.waitUntil(ms) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun gone(text: String, ms: Long = 8_000) =
        rule.waitUntil(ms) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()
        }

    /** substring 默认开：结果里的文案是「2. 装好 WSL…」这种带序号的 */
    private fun tap(text: String, exact: Boolean = false) =
        rule.onAllNodesWithText(text, substring = !exact)[0].performClick()

    /** 按 testTag 匹配（不用 Compose 的 hasTestTag：这个版本的签名跟我们的用法不匹配） */
    private fun tagMatcher(tag: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)

    /** 任务页上有两个可滚动节点（横向分类 chips + 纵向清单）；「能按序号滚」是 LazyColumn 独有的 */
    private fun listScrollable() = hasScrollAction() and hasScrollToIndexAction()

    /** 把某个节点滚进视口；滚不到会抛异常，所以拿异常当重试信号（界面是异步重组的） */
    private fun scrollTo(matcher: SemanticsMatcher) {
        rule.waitUntil(15_000) {
            if (rule.onAllNodes(listScrollable()).fetchSemanticsNodes().size != 1) return@waitUntil false
            runCatching { rule.onNode(listScrollable()).performScrollToNode(matcher) }.isSuccess
        }
        rule.waitForIdle()
    }

    /**
     * 打开某条任务的详情 —— **按 id 的 testTag 定位，不按标题文字/列表顺序**。
     *
     * 为什么必须这样：阶段组内现在按**进度**排序（未完成在前、已完成沉底），而这条用例
     * 是先把步骤标成完成再进详情 —— 那条任务会当场沉到组尾，用「标题文字第 0 个」去找
     * 就找不着（发布闸门就是这么红的：`onAllNodesWithText(title)[0]` 越界）。
     * 点用**语义动作**、不吃坐标：滚到列表末尾的行常常只露出一半，
     * `performClick` 按中心坐标注入会打在屏外、静默无效（表现成后面 waitUntil 超时）。
     */
    private fun openTask(taskId: Int) {
        val tag = taskRowTag(taskId)
        runCatching { scrollTo(tagMatcher(tag)) }
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onAllNodesWithTag(tag)[0].assertIsDisplayed()
        rule.onAllNodesWithTag(tag)[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun doneDay(stepId: Int): String? = runBlocking {
        tdb.dao().allSteps().first().first { it.id == stepId }.done_day
    }

    private fun exists(text: String, exact: Boolean = false) =
        rule.onAllNodesWithText(text, substring = !exact).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `完成步骤要先确认_取消则什么也没发生`() {
        val db = tdb
        // 从库里挑一条真实存在、且**未完成**的步骤 —— 不猜任何文案
        val picked = runBlocking {
            app().seedJob?.join()
            val steps = db.dao().allSteps().first()
            // visibleTasks()：同一个 JVM 里各测试类共用同一个库文件，
            // 别的用例留下的墓碑会跟过来 —— 从 tasks() 挑可能挑到一条已被删掉的
            val t = db.dao().visibleTasks().first().first { tk -> steps.any { it.task_id == tk.id } }
            val s = steps.first { it.task_id == t.id && it.done_day == null }
            t.id to s
        }
        val taskId = picked.first
        val step = picked.second
        assertNull("用例前提：这条步骤应当是未完成的", doneDay(step.id))

        rule.setContent { CampusTheme { TasksScreen(db) } }
        waitFor("本学期")
        openTask(taskId)                 // 按 id 进任务详情（不按标题文字，见 openTask 的说明）
        waitFor(step.text)

        // ① 点一下 —— 必须弹确认，而且**没有**落库
        tap(step.text)
        waitFor("确认完成？")
        assertNull("点一下不能直接标记完成（用户反馈的误触就是这个）", doneDay(step.id))

        // ② 取消 —— 什么也没发生，弹层收起
        tap("取消", exact = true)
        gone("确认完成？")
        assertNull("取消 = 什么也没发生", doneDay(step.id))

        // ③ 再来一次并确认 —— 这次才落库
        tap(step.text)
        waitFor("确认完成？")
        tap("确认完成", exact = true)
        rule.waitUntil(15_000) { doneDay(step.id) != null }
        assertNotNull("点了「确认完成」之后必须落库", doneDay(step.id))
    }

    @Test
    fun `已完成的那一条_点一下直接撤销_不再追问`() {
        val db = tdb
        val step = runBlocking {
            app().seedJob?.join()
            val steps = db.dao().allSteps().first()
            val s = steps.first { it.done_day == null }
            db.dao().markStep(s.id, "2026-09-18")
            s
        }

        rule.setContent { CampusTheme { TasksScreen(db) } }
        waitFor("本学期")
        // 直接进它所属任务的详情 —— 按 id 定位：标完成之后这条会按进度沉到组尾，
        // 用标题文字找就找不到了（发布闸门红在这里）。
        openTask(step.task_id)
        waitFor(step.text)

        tap(step.text)
        assertNull("撤销是安全的，不应该多一道确认（顺手要能回退）", doneDay(step.id))
    }
}
