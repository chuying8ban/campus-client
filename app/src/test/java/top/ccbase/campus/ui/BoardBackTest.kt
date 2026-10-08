package top.ccbase.campus.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.local.Meta
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiUser
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「从我的点进来的页」（看板）必须**三条路都能出去**：
 *   ① 底部 tab、② 页面上那条「‹ 返回」、③ 系统返回键。
 *
 * 这三条以前全是断的，用户的话就一句：「这个看板点进去不能退出」：
 *   - 底部 tab 只改 `idx` 不改 `extra` → 点了像没反应；
 *   - 「‹ 返回」和页面内容在同一个 Box 里、它**先画** → 点击被上层内容吃掉；
 *   - 全项目没有 `BackHandler` → 系统返回键退的是整个 App，不是看板。
 *
 * 特意用 `createAndroidComposeRule` —— 测系统返回键需要一个真实的
 * onBackPressedDispatcher（纯 createComposeRule 没有）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class BoardBackTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    @Before
    fun alreadyLoggedIn() = runBlocking {
        withContext(Dispatchers.IO) {
            app().db.dao().putMeta(listOf(Meta("onboarded", "1")))
        }
        TokenStore.save(
            ctx = ApplicationProvider.getApplicationContext(),
            token = "tok", expiresAt = "2099-01-01",
            user = ApiUser(uid = 1, student_id = "2026000000", name = "测试用户", canGrab = true),
        )
    }

    private fun waitForText(t: String) {
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun launchBoard() {
        rule.setContent { CampusTheme { top.ccbase.campus.ExitConfirmation(onExit = { rule.activity.finish() }) { CampusApp(version = "test") } } }
        rule.waitForIdle()
        rule.onNodeWithText("我的").performClick()
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithText("打卡热力 / 里程碑").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("打卡热力 / 里程碑").performScrollTo().performClick()
        waitForText("里程碑")
    }

    private fun onBoard(): Boolean =
        rule.onAllNodesWithText("里程碑").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `看板要进得去`() {
        launchBoard()
        assertTrue("看板没打开，后面的用例都会假通过", onBoard())
    }

    @Test
    fun `点底部 tab 必须能离开看板`() {
        launchBoard()
        rule.onNodeWithText("今日").performClick()
        rule.waitForIdle()
        assertFalse("点了底部 tab 还卡在看板里 —— 用户会以为整个 App 卡住了", onBoard())
    }

    @Test
    fun `页面上那条返回必须点得到_不是被页面盖住`() {
        launchBoard()
        rule.onNodeWithText("‹ 返回").performClick()
        rule.waitForIdle()
        assertFalse("「‹ 返回」点不动（多半被上层页面内容吃掉了点击）", onBoard())
    }

    // ------------------------------------------------- 主界面按返回：先提示，再按一次才退出

    private fun launchMain() {
        rule.setContent { CampusTheme { top.ccbase.campus.ExitConfirmation(onExit = { rule.activity.finish() }) { CampusApp(version = "test") } } }
        rule.waitForIdle()
    }

    @Test
    fun `主界面按返回先给提示_不能一点就退回桌面`() {
        launchMain()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue(
            "按返回没给任何提示（用户会以为按了没用，或者直接被踢出去）",
            rule.onAllNodesWithText("退出校园助手？", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertFalse("第一次按返回就把 App 关掉了", rule.activity.isFinishing)
    }

    @Test
    fun `连着按两次返回才真的退出`() {
        launchMain()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("退出").performClick()
        rule.waitForIdle()
        assertTrue("点击退出应结束 App", rule.activity.isFinishing)
    }

    @Test
    fun `在看板里按返回只退看板_不该弹退出提示`() {
        launchBoard()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertFalse("在看板里按返回把看板退掉就行，别顺手把 App 也关了", rule.activity.isFinishing)
        assertTrue(
            "子页里按返回不该出现「要退出 App」的提示",
            rule.onAllNodesWithText("退出校园助手？", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `系统返回键退的是看板_不是把整个 App 关掉`() {
        launchBoard()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertFalse("系统返回键没退出看板", onBoard())
        assertFalse("按一下返回把整个 App 关掉了", rule.activity.isFinishing)
    }
}
