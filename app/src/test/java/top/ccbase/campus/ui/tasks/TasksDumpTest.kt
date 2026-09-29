package top.ccbase.campus.ui.tasks

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.TestSeedDb
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 把「学习」页与页首「整理」那一层摊平成可读的语义树，写到 /tmp，用来核对**内容与顺序**。
 *
 * 为什么需要它：点选用例只回答"点了会不会落库"，回答不了"这一屏到底摆了多少东西、
 * 一行里堆了几块信息"。精简这类改动必须能看见"改前/改后每行剩几块"。
 *
 * ⚠️ Robolectric 在这个工程里截不了 PNG（整窗截屏要等真正的 draw 回调，2 秒必超时），
 * 所以这里只保证内容与顺序，不保证像素。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TasksDumpTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dump() {
        val app = ApplicationProvider.getApplicationContext<CampusApplication>()
        runBlocking { app.seedJob.join() }
        // 拿一份"灌了自己计划"的库来画：App 自己的库现在是空的（没同步到计划就不显示任务）
        val db = TestSeedDb.seeded(app)
        rule.setContent { CampusTheme { TasksScreen(db) } }
        rule.waitForIdle()
        File("/tmp/tasks_top.txt").writeText(rule.onRoot().printToString())

        // 页首「整理」那一层：重置/恢复都收在这里
        rule.onNodeWithText(PlanLogic.MORE_ENTRY).performClick()
        rule.waitForIdle()
        File("/tmp/tasks_more.txt").writeText(rule.onRoot().printToString())
    }
}
