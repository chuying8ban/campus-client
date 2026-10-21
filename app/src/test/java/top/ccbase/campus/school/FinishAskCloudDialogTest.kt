package top.ccbase.campus.school

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「导好了但没同步云端就点完成」的确认框（2026-10-21 用户口径）。
 *
 * 用户原话：「给用户两个选项，一个是不同步，一个是同步到云端，点哪个就按哪个方式导课表」。
 * 所以这里守的**不是"弹没弹窗"**，而是：
 * · 两个选项都必须真的存在（少一个，用户就没得选）；
 * · 点「不同步」就走收尾，点「同步」就走同意流程 —— 不能两边都收尾（会把没上传的成功说成上传了）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xhdpi")
class FinishAskCloudDialogTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var localOnly = 0
    private var syncCloud = 0
    private var cancelled = 0

    private fun show() {
        rule.setContent {
            FinishAskCloudDialog(
                onLocalOnly = { localOnly++ },
                onSyncCloud = { syncCloud++ },
                onCancel = { cancelled++ },
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `两个选项都在_不会只有一个`() {
        show()
        assertEquals(1, rule.onAllNodesWithText("还没同步到云端").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText("不同步，仅本机用").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText("同步到云端").fetchSemanticsNodes().size)
    }

    @Test
    fun `选不同步才收尾_不许顺手同步`() {
        show()
        rule.onNodeWithText("不同步，仅本机用").performClick()
        rule.waitForIdle()
        assertEquals(1, localOnly)
        assertEquals("选了不同步就不该触发同步", 0, syncCloud)
    }

    @Test
    fun `选同步就走同意流程_不立刻收尾`() {
        show()
        rule.onNodeWithText("同步到云端").performClick()
        rule.waitForIdle()
        assertEquals(1, syncCloud)
        assertEquals("同步是异步的：得等上传成功才收尾，不能先把人送走", 0, localOnly)
    }

    /**
     * 验证码自动提交的脚本：必须"替用户按按钮"，不能直接 `form.submit()`。
     * CAS 的提交流程里有 RSA 加密密码和 `_eventId`，绕过按钮就必然登录失败。
     */
    @Test
    fun `验证码填满就自动按登录按钮_不绕过页面的加密逻辑`() {
        val js = CredentialVault.ssoCaptchaAutoSubmitJs()
        assertTrue("要点的就是页面自己的登录按钮", js.contains(".userButton"))
        assertTrue(js.contains("captcha"))
        assertTrue("至少 4 位才提交，别把半截验证码发出去", js.contains(">= 4"))
        assertTrue("一次页面加载只提交一次", js.contains("fired"))
        assertTrue("绝不能绕过 encrypt() 直接 form.submit()", !js.contains("\$(\"#fm1\").submit()"))
    }
}
