package top.ccbase.campus.ui.tasks

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.TestSeedDb
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.local.Task
import top.ccbase.campus.net.PlanItem
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 三条"服务端数据差点意思时界面会怎样"的用例（队列里点名的测试缺口）。
 *
 * 为什么值得单独钉：这三条都不会在我方测试里自然发生 —— 我方夹具永远是"填得很齐"的漂亮数据，
 * 而线上一个服务端失误（少个字段）就能让**整页打不开**。用户看不到错误码，只看到白屏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class PlanRobustnessTest {

    @get:Rule
    val rule = createComposeRule()

    private val J = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private fun nodes(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    // ---------------------------------------------------- ① 条目缺 title
    @Test
    fun `条目缺 title 也要能解析_不能整份计划挂掉`() {
        // 一个 title 缺失的条目原来会把整个响应体的解析掀翻（PlanItem.title 是必填），
        // 结果是**整页计划打不开**，而不是"少显示一条"。
        val item = J.decodeFromString<PlanItem>("""{"why":"练手","track":"自学","steps":[]}""")
        assertEquals("缺 title 时按空串处理，别抛", "", item.title)
    }

    // ---------------------------------------------------- ② 空白的 why
    @Test
    fun `空白的为什么不要占一行`() {
        assertNull("空串不画", PlanLogic.whyLine(""))
        assertNull("纯空格也不画", PlanLogic.whyLine("   "))
        assertNull("null 不画", PlanLogic.whyLine(null))
        assertEquals("有内容就画，并去掉首尾空白", "因为期末占 40%", PlanLogic.whyLine("  因为期末占 40%  "))
    }

    // ---------------------------------------------------- ③ 空标题不占位
    @Test
    fun `空标题的条目不要在计划页占位置`() {
        val items = listOf(
            PlanItem(title = "正常条目", why = "x"),
            PlanItem(title = "", why = "服务端漏了标题"),
            PlanItem(title = "   ", why = "只有空格"),
        )
        val shown = PlanLogic.visibleItems(items)
        assertEquals("只留能显示的那条", 1, shown.size)
        assertEquals("正常条目", shown[0].value.title)
        // 关键：下标必须是**原始的**（0）—— 勾选状态按下标存，重排会让"选 A 加到 B"
        assertEquals("下标不能重排", 0, shown[0].index)
    }

    // ---------------------------------------------------- ④ 任务 tab 真看得到新加的任务
    @Test
    fun `任务 tab 真能看到刚加的任务`() {
        val app = ApplicationProvider.getApplicationContext<CampusApplication>()
        // App 启动只灌通用数据（节次/自习），任务要等用户自己的计划同步 —— 所以这里自己起库
        val tdb = TestSeedDb.seeded(app)
        val title = "刚加的任务·验收用"
        runBlocking {
            app.seedJob?.join()
            // 插到**最前面**（phase_order/sort 都给负数）：这样不用滚动就能看到，
            // 用例要证的是"界面真把它画出来了"，不是"滚动能用"（页面上有两个可滚动区，
            // performScrollToNode 会报 "found 2 nodes ... ScrollBy is defined" ✗）。
            tdb.dao().putTasks(
                listOf(
                    Task(
                        id = 900901, phase = "AI 规划", phase_order = -99, title = title,
                        active = 1, sort = -99,
                    )
                )
            )
        }

        rule.setContent { CampusTheme { TasksScreen(tdb) } }
        rule.waitForIdle()
        rule.waitUntil(15_000) { nodes("本学期") > 0 }
        rule.waitForIdle()

        assertTrue("任务 tab 里应该看得到刚加的那条", nodes(title, substring = true) > 0)
    }
}
