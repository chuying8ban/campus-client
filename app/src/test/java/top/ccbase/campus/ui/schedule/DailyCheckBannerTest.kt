package top.ccbase.campus.ui.schedule

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.alarm.DailyTimetableCheck
import top.ccbase.campus.ui.theme.CampusTheme
import java.time.LocalDateTime

/**
 * 顶部那条常驻提示：**在、一直在、点得动、状态会翻**。
 *
 * 2026-10-17 用户口径换成常驻细带之后，这条测试替掉了原来"弹窗有没有弹"的验收：
 * 现在的正确行为是"不弹窗、但顶上一直有一行"，而这恰恰是肉眼最容易漏掉的回归
 * （比如哪天又被谁加回对话框中，页面看起来会"正常"，只是那条提示没了）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyCheckBannerTest {

    @get:Rule
    val rule = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `没核对时提示常驻_点一下就能去更新`() {
        // 昨天更新过 = 今天还没核对；文案随是否到 08:00 有两句，这里只验"在/可点/不是已核对"
        DailyTimetableCheck.markUpdated(ctx, LocalDateTime.now().minusDays(2))
        var clicked = 0
        rule.setContent { CampusTheme { DailyCheckBanner(ctx) { clicked++ } } }

        rule.onNodeWithText("更新 ›").assertIsDisplayed().performClick()
        assertEquals("点这条提示要能直接进更新课表", 1, clicked)
        assertEquals(
            "还没核对时不该出现已核对的文案",
            0,
            rule.onAllNodesWithText("已核对课表", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `导入成功后同一位置变成已核对_仍是同一条细带`() {
        // 用"今天 08:12"而不是写死某一天：这套断言跟机器时钟无关才稳定
        val today = LocalDateTime.now().toLocalDate()
        DailyTimetableCheck.markUpdated(ctx, today.atTime(8, 12, 45))
        assertTrue(DailyTimetableCheck.updatedToday(ctx, today.atTime(20, 0)))
        assertFalse("昨天更新过不算今天", DailyTimetableCheck.updatedToday(ctx, today.minusDays(1).atTime(9, 0)))

        rule.setContent { CampusTheme { DailyCheckBanner(ctx) {} } }
        rule.onNodeWithText("已核对课表", substring = true).assertIsDisplayed()
    }
}
