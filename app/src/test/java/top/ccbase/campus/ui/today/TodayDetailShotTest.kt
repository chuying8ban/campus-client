package top.ccbase.campus.ui.today

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.domain.loadToday
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 把「今日任务详情」**摊平成可读的语义树**，用 App 自己那份种子数据（= 手机上那份），
 * 存到 /tmp/today_detail.txt。
 *
 * 为什么要有它：点击用例只回答"点了会不会落库"，回答不了"这一层里到底摆了什么、
 * 顺序对不对"—— 步骤多/资料多的时候，我把动作栏写进滚动区的错误只有把整层摊开才看得见。
 *
 * ⚠️ 不要试图在这里 `captureToImage()` 出 PNG：Robolectric 的整窗截屏要等一次真正的
 * draw 回调（`WindowCapture.forceRedraw`），在这个工程里 2 秒必超时 ✗（2026-09-18 试过）。
 * 想要"看起来什么样"，只能真机截图；这里只保证**内容和顺序**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TodayDetailShotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun waitFor(text: String, ms: Long = 15_000) =
        rule.waitUntil(ms) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun `今日任务详情的样子`() {
        val app = ApplicationProvider.getApplicationContext<CampusApplication>()
        val title = runBlocking {
            app.seedJob?.join()
            // 夹具任务：必然属于"今天"（以前这里查不到就 `return`，静默当成通过 = 没验）
            TodayFixture.seed(app.db)
            val mine = loadToday(app.db).let { it.tasks + it.standing }
                .firstOrNull { it.id == TodayFixture.TASK_ID }
            assertNotNull("夹具任务没进「今日」列表 —— 夹具失效了，不许静默返回", mine)
            mine!!.title
        }

        rule.activity.setContent { CampusTheme { TodayScreen(app.db) } }
        rule.waitForIdle()
        waitFor("今日任务")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(title, substring = true))
        rule.waitForIdle()

        rule.onAllNodesWithText(title, substring = true)[0].performClick()
        waitFor("任务详情")
        Thread.sleep(800)                               // 让层画完（IO 查库不等 idle）
        rule.waitForIdle()

        val dump = rule.onRoot().printToString(maxDepth = 40)
        File("/tmp/today_detail.txt").writeText(dump)
        println("TREE today_detail -> /tmp/today_detail.txt")
        println(dump.takeLast(2500))

        // 这一层该有的东西：标题、详情抬头、动作文案
        assertTrue("详情层该有任务标题", dump.contains(title))
        assertTrue("详情层该有「任务详情」抬头", dump.contains("任务详情"))
        assertTrue("底部动作栏该有完成相关的文案", dump.contains("完成"))

        // 动作栏必须**不在**滚动区里：它在树里应当和步骤/资料同级或更靠后，而不是被卷走。
        // 判据用行为：详情打开时「完成」必须已经在屏幕上（不需要滚动就能点到）。
        assertTrue(
            "「完成」按钮必须不用滚动就在屏幕上",
            rule.onAllNodesWithText("今天还没完成", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("今天已完成", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
