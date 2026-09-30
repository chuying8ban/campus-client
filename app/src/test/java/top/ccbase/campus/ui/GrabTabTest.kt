package top.ccbase.campus.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiUser
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 底部 tab 栏上「监控」那一格的显隐，以及看板不再常显。
 *
 * 新口径：公开版**含监控**（只盯余量、不代抢，页面顶部有口径说明），
 * 那一格按服务端给的 can_grab 显隐 —— 拿不到权限的人连格子都不存在（不是灰着）；
 * 看板则从底部栏收进「我的」页（入口还在，只是不再占格子）。
 *
 * 为什么必须在真界面上测（源码级那半在 [TabWiringTest]）：
 * 这一格的存在与否就是用户要的功能本身，而"源码里写了 canGrab"不等于"界面上真长出来了"
 * —— 判据读错地方（比如读成 isAuthor）、或者被冻成一个只算一次的值，
 * 源码级断言全都绿，用户登录后却看不见那一格。
 *
 * 判格子用**图标的 contentDescription**（= tab 的名字），不用文字：页面正文里也可能
 * 出现同名大字，按文字找会同时命中好几处。`useUnmergedTree`：底栏图标的
 * contentDescription 只在未合并的语义树里（实测，LearningTabTest 同一个坑）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class GrabTabTest {

    @get:Rule
    val rule = createComposeRule()

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        TokenStore.clear(ctx())
    }

    /** 登录态：can_grab 由服务端下发，这里按用例给。 */
    private fun login(canGrab: Boolean) {
        TokenStore.save(
            ctx = ctx(),
            token = "tok-grab-tab",
            expiresAt = "2099-01-01",
            user = ApiUser(uid = 1, student_id = "2026000000", name = "测试用户", canGrab = canGrab),
        )
    }

    private fun renderShell() {
        rule.setContent { CampusTheme { CampusShell(version = "test", onLogin = {}) } }
        rule.waitForIdle()
    }

    /** 底栏上有几个叫这个名字的格子（0 = 那一格不存在）。 */
    private fun barItems(tab: CampusTab): Int =
        rule.onAllNodesWithContentDescription(tab.label, useUnmergedTree = true)
            .fetchSemanticsNodes().size

    /** 先证明底栏真渲染出来了 —— 否则"没有监控格"可能只是因为整条栏都没出来（假绿）。 */
    private fun assertBarRendered() {
        rule.waitUntil(20_000) { barItems(CampusTab.TODAY) == 1 }
        assertEquals("底栏必须有「今日」这一格（没有它，下面关于监控的断言都不算证据）",
            1, barItems(CampusTab.TODAY))
        assertEquals("看板不再常显：底栏不许有「看板」这一格", 0, barItems(CampusTab.BOARD))
    }

    @Test
    fun `拿到 can_grab 的登录用户_底栏出现监控那一格`() {
        login(canGrab = true)
        renderShell()
        assertBarRendered()
        assertEquals(
            "服务端给了 can_grab，底栏却没有「监控」那一格 —— 用户要的就是这一格",
            1, barItems(CampusTab.GRAB),
        )
    }

    @Test
    fun `没有 can_grab 的登录用户_底栏同样有监控那一格`() {
        // 2026-09-30 用户口径：「新版本每个用户的手机上都会显示监控，而不需要重新登录」
        // ⇒ 这一格不再按 can_grab 显隐（can_grab 是登录那一刻写进本地的缓存，老用户
        // 不会为了一个入口重新登录）。服务端已同步放开成"登录即可"（见 _grab_guard）。
        login(canGrab = false)
        renderShell()
        assertBarRendered()
        assertEquals(
            "登录了却没有「监控」格 —— 用户口径就是人人都有这一格",
            1, barItems(CampusTab.GRAB),
        )
    }
}
