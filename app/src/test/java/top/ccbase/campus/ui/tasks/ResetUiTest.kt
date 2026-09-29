package top.ccbase.campus.ui.tasks

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.TaskDone
import top.ccbase.campus.data.plan.ResetPlan
import top.ccbase.campus.data.plan.ResetStore
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.data.seed.SeedImporter
import top.ccbase.campus.data.seed.SeedLoader
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「整理」那一层（重置 / 恢复）的界面链路。
 *
 * 库一律用**内存库 + 真种子导入，绝不碰 `app().db`** —— 这个功能会把整库扫一遍再改，
 * 用共享库就等于把后面跑的用例一起改掉（第一版就是这么把 `TaskDeleteTest` 弄红的）。
 *
 * 入口从页尾搬到了页首「整理」：所以这些用例**一次都不用滚** —— 这本身就是回归测试，
 * 哪天有人把它挪回列表末尾，`assertIsDisplayed` 会立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ResetUiTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var ctx: Context
    private lateinit var db: CampusDb

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java).allowMainThreadQueries().build()
        runBlocking { SeedImporter.import(db, SeedLoader.load(ctx)) }
        ResetStore.clear(ctx)
    }

    @After
    fun tearDown() {
        // 只清自己造的文件，**不 close 内存库**：界面上的 LaunchedEffect 还在收 Flow，
        // 关了库它下一轮就会抛 `Cannot perform this operation because the connection pool has been closed`，
        // 而这个异常会漏到**后面跑的另一个测试类**里去（全套里表现为 TaskDeleteTest 莫名超时）。
        // 内存库随进程结束回收，不需要手动关。
        ResetStore.clear(ctx)
    }

    private fun visibleCount() = runBlocking { db.dao().visibleTasks().first() }.size
    private fun doneCount() = runBlocking { db.dao().doneSince("0000-00-00") }.size

    @Test
    fun `页首整理入口一眼可见_点开只弹一层_取消什么也不发生`() {
        val before = visibleCount()
        assertTrue("前提：种子里得有任务", before > 0)

        rule.setContent { CampusTheme { TasksScreen(db) } }

        // 不滚动、直接断言可见 —— 入口必须出现在第一屏
        rule.onNodeWithText(PlanLogic.MORE_ENTRY).assertIsDisplayed()
        rule.onNodeWithText(PlanLogic.MORE_ENTRY).performClick()
        rule.onNodeWithText(PlanLogic.MORE_TITLE).assertIsDisplayed()
        rule.onNodeWithText(PlanLogic.RESET_ENTRY).assertIsDisplayed()

        rule.onNodeWithText(PlanLogic.RESET_CANCEL).performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(PlanLogic.MORE_TITLE).fetchSemanticsNodes().isEmpty()
        }
        assertEquals("取消之后任务一条都不该少", before, visibleCount())
    }

    @Test
    fun `确定之后一条任务都不剩_来源标none_从同一层能一键恢复`() {
        val dao = db.dao()
        val victim = requireNotNull(
            runBlocking { dao.visibleTasks().first() }.firstOrNull { it.id < ResetPlan.SERVER_MAX },
        ) { "前提：种子里得有服务端下发的任务" }
        runBlocking { dao.markDone(TaskDone(task_id = victim.id, day = "2026-09-19", at = "2026-09-19 08:00")) }

        rule.setContent { CampusTheme { TasksScreen(db) } }

        rule.onNodeWithText(PlanLogic.MORE_ENTRY).performClick()
        rule.onNodeWithText(PlanLogic.RESET_ENTRY).performClick()
        rule.onNodeWithText(PlanLogic.RESET_CONFIRM).performClick()

        var seen = ""
        try {
            rule.waitUntil(20_000) {
                val now = runBlocking { dao.visibleTasks().first() }
                val done = doneCount()
                val src = runBlocking { dao.metaGet(PlanApplier.K_SOURCE) }
                seen = "可见=${now.size} 打勾=$done 来源=$src"
                // 2026-09-19 改口径：用户说「重置后这里就应该没有任务了」—— 重置是**清空**。
                // 这条以前断言的是「只剩内置模板任务」，方向和现在正好相反。
                now.isEmpty() && done == 0 && src == PlanApplier.NONE
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("重置没改成「清空 + 来源标 none」（等 20s）：$seen", e)
        }

        // 再开一次「整理」：重置过之后「恢复上次重置」就在同一层里。
        // ⚠️ 等它**真的显示出来**再点，别裸 assertIsDisplayed：节点可以在同一帧里已经进了
        // 语义树、却还没被摆放（那时报 "The component is not displayed!"）—— 本用例偶发红过一次，
        // 而 performClick 是按坐标注入的，未摆放 = 点在空处。
        rule.onNodeWithText(PlanLogic.MORE_ENTRY).performClick()
        try {
            rule.waitUntil(15_000) {
                runCatching {
                    rule.onNodeWithText(PlanLogic.RESET_UNDO).assertIsDisplayed()
                }.isSuccess
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val n = rule.onNodeWithText(PlanLogic.RESET_UNDO).fetchSemanticsNode()
            throw AssertionError(
                "恢复入口一直没显示出来：undo=${n.boundsInRoot} size=${n.size} " +
                    "root=${rule.onRoot().fetchSemanticsNode().boundsInRoot}",
                e,
            )
        }
        rule.onNodeWithText(PlanLogic.RESET_UNDO).performClick()

        var seen2 = ""
        try {
            rule.waitUntil(20_000) {
                val ids = runBlocking { dao.visibleTasks().first() }.map { it.id }
                val done = doneCount()
                val src = runBlocking { dao.metaGet(PlanApplier.K_SOURCE) }
                seen2 = "victim在不在=${victim.id in ids} 打勾=$done 来源=$src"
                // 来源也要一起还原：数据回来了但来源还是 none，界面会照样显示空态 = 白恢复
                victim.id in ids && done > 0 && src == PlanApplier.REMOTE
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("恢复没生效（等 20s）：$seen2", e)
        }
        assertTrue("恢复后原来那条任务要回来", victim.id in runBlocking { dao.visibleTasks().first() }.map { it.id })
    }
}
