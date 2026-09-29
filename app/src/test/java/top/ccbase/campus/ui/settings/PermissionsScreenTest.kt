package top.ccbase.campus.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.alarm.DiagSnapshot
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「授权与白名单」页 —— 本机没有模拟器，界面只能这样验。
 *
 * 这一页的价值全在「用户自己能把开关打开」这件事上，所以三条钉死：
 *   ① 缺项要**数得清**（"还差 N 项"，而不是让他自己找）
 *   ② 每一项都要**去得了**（按钮在）+ 说清缺了会怎样 + 怎么开
 *   ③ 全给齐了不能还报"还差"（否则用户会一直以为哪里没弄好）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class PermissionsScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun allBad() = DiagSnapshot(false, false, false, false, false, false)
    private fun allGood() = DiagSnapshot(true, true, true, true, true, true)

    private fun show(s: DiagSnapshot, onClose: () -> Unit = {}) {
        rule.setContent {
            CampusTheme { PermissionsScreen(readSnapshot = { s }, onClose = onClose) }
        }
    }

    @Test
    fun `缺项要一眼数得清`() {
        show(allBad())
        rule.onNodeWithText("授权与白名单").assertExists()
        rule.onNodeWithText(
            "还差 6 项。能弹系统窗的会直接弹窗，其余一步跳到对应的设置页；点完返回这一页会自己刷新。"
        ).assertExists()
    }

    @Test
    fun `每一项都点名_且给了去授权按钮`() {
        show(allBad())
        listOf("修改系统设置", "勿扰访问", "闹钟与提醒", "电池白名单", "通知", "安装更新").forEach {
            rule.onNodeWithText("✗ $it").assertExists()
        }
        // 至少有一个"去授权/去设置"按钮，并且带"怎么开"可以展开看路径
        assertTrue(rule.onAllNodesWithText("怎么开 ›").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `怎么开_能点出具体路径`() {
        show(allBad())
        rule.onAllNodesWithText("怎么开 ›")[0].performClick()
        // 展开后必须给出"开哪个开关/在哪一页"这种能照着做的话
        rule.onNodeWithText("要开的开关", substring = true).assertExists()
    }

    @Test
    fun `全给齐了就不许再说还差`() {
        show(allGood())
        rule.onNodeWithText("全部已授权 —— 课前静音、闹钟提醒、App 更新都能正常工作。").assertExists()
        assertTrue(
            "全给齐了还显示「还差」会让人以为哪里没弄好",
            rule.onAllNodesWithText("还差", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `厂商限制要给路径_并且能展开`() {
        show(allBad())
        rule.onNodeWithText("厂商后台限制（系统不提供查询，自己看一眼）").assertExists()
        rule.onNodeWithText("· 自启动").assertExists()
        rule.onAllNodesWithText("看路径 ›")[0].performClick()
        rule.onNodeWithText("自启动：允许", substring = true).assertExists()
    }

    @Test
    fun `关闭按钮要真的关掉`() {
        var closed = false
        show(allBad(), onClose = { closed = true })
        rule.onNodeWithText("关闭 ✕").performClick()
        rule.runOnIdle { assertTrue("点了关闭但没回调", closed) }
    }
}
