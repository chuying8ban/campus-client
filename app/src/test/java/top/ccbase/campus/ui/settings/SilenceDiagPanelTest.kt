package top.ccbase.campus.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「我的 → 自检」里的**照着做**引导。
 *
 * 守的是"用户在手机上翻得到那个开关"：只说"缺少勿扰访问权限"没有用 ——
 * 他要在小米的设置里找到开关原文和路径。所以这里点开每一项，
 * 断言屏幕上真的出现了开关名和菜单路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class SilenceDiagPanelTest {

    @get:Rule
    val rule = createComposeRule()

    /** Robolectric 下几项权限默认都是"没给"，正好就是要引导的那条路径。 */
    private fun openPanel() {
        rule.setContent { CampusTheme { SilenceDiagPanel() } }
        rule.onNodeWithText("自检 ›").performClick()
    }

    /** 把每一项的"怎么开"都点开（点开后文案会变成"收起怎么开"，所以每次都点第一个未展开的）。 */
    private fun expandAllGuides() {
        repeat(rule.onAllNodesWithText("怎么开 ›").fetchSemanticsNodes().size) {
            rule.onAllNodesWithText("怎么开 ›")[0].performClick()
        }
    }

    /** 屏幕上出现过这段话就行（同一句可能在多项里重复，不能用 onNodeWithText 的唯一性断言）。 */
    private fun assertSeen(text: String) {
        assertTrue(
            "界面上没出现「$text」—— 那用户就照着修不了",
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `缺的权限都要摊得开_而且写明开关原名和菜单路径`() {
        openPanel()
        expandAllGuides()
        // 注意：这台环境里「修改系统设置」是已授权状态、通知也是开的，它们本来就不该有引导，
        // 所以只断言真实缺失的那几项（引导文案的完整性由 SilenceDiagTest 的纯逻辑用例钉住）
        assertSeen("模式访问权限")        // 用户那台小米上这一页的真名（截图实证）
        assertSeen("闹钟和提醒")
        assertSeen("设置 → 通知与控制中心")  // 菜单路径
    }

    @Test
    fun `电池白名单要给出小米那两处开关`() {
        openPanel()
        expandAllGuides()
        assertSeen("省电策略：无限制")
        assertSeen("自启动：允许")
        assertSeen("应用管理")      // 小米原生入口，光靠系统那页在 HyperOS 上会被拦住
        assertSeen("最近任务")      // 锁后台那一步
    }
}
