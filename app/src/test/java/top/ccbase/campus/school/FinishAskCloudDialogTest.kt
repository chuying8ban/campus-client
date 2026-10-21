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
        assertTrue("不到 4 位不许提交，别把半截验证码发出去", js.contains("length < 4"))
        assertTrue("一次页面加载只提交一次", js.contains("fired"))
        assertTrue("绝不能绕过 encrypt() 直接 form.submit()", !js.contains("\$(\"#fm1\").submit()"))
    }

    /**
     * 上一版只挂一次性监听：注入早于表单渲染就 `return 'nocaptcha'` 彻底失效 ——
     * 用户截图里验证码框空着、页面毫无反应，正是这个状态。必须改成轮询。
     */
    @Test
    fun `自动提交是轮询等表单出现的_不是一次性监听`() {
        val js = CredentialVault.ssoCaptchaAutoSubmitJs()
        assertTrue("要自己找表单，不能 DOM 没出来就放弃", js.contains("setInterval"))
        assertTrue("每轮重新查节点，Vue 换了元素也能接上", js.contains("getElementById('captcha')"))
        assertTrue("要有停止条件，别在页面上挂一个永不退出的定时器", js.contains("clearInterval"))
    }

    /**
     * 学校模板在小屏上把页脚 fixed 到页面底部（深灰半透明），正压住「登录」按钮。
     * 用户截图里那条盖着 ICP备案 的黑带就是它 —— 手指点下去点的是页脚。
     */
    @Test
    fun `身份认证页把固定页脚放回正常流_挡住登录按钮的是页脚不是我们`() {
        val js = CredentialVault.ssoLayoutFixJs()
        assertTrue(js.contains(".footer"))
        assertTrue("必须 !important，模板里是 fixed", js.contains("position:static!important"))
        assertTrue("只说样式，不许碰输入框内容或提交逻辑",
            !js.contains("value") && !js.contains("submit"))
    }
}
