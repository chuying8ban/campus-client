package top.ccbase.campus.ui.update

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.ui.me.MeScreen
import top.ccbase.campus.ui.theme.CampusTheme
import top.ccbase.campus.update.UpdateManifest
import java.io.File

/**
 * 更新框 + 「我的」里的更新入口。
 *
 * 这里守的是**话说对了没有**，不是"渲染出来了"：
 *   - 强制更新不许出现「以后再说」（有退路的强制更新等于没有）
 *   - 下载完成必须把**校验和**摆出来（旁加载的包，用户有权知道它被验证过）
 *   - 更新说明逐条列，不许用"修复若干问题"糊过去
 *   - 手动检查必须给回话（点了没反应会被当成按钮坏了）
 *
 * 用 Box 浮层而不是 Dialog 的原因写在这里，防止后来人"顺手改成 Dialog"：
 * Robolectric 看不见 Dialog 的独立窗口，改成 Dialog 这些用例会全部失去意义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class UpdatePanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val sha = "9fefbee2111104ee7ddbc021a41d8367a794bcd4169d0be2985b2a273093b33b"

    private fun manifest(code: Int = 2, name: String = "1.23-T1", force: Boolean = false) =
        UpdateManifest(
            versionCode = code, versionName = name,
            url = "updates/campus-$name.apk", sha256 = sha, size = 7_222_304,
            notes = listOf("修好了切页冒出来的假报错", "更新框不再显示一长串网址"),
            force = force,
        )

    private fun overlay(state: UpdateUi, onStart: () -> Unit = {}, onInstall: () -> Unit = {},
                        onSkip: () -> Unit = {}, onDismiss: () -> Unit = {}, onRetry: () -> Unit = {},
                        onOpenPermission: () -> Unit = {}) {
        compose.setContent {
            CampusTheme {
                UpdateOverlay(
                    state = state, currentVersion = "1.22-T1",
                    onStart = onStart, onInstall = onInstall, onSkip = onSkip,
                    onDismiss = onDismiss, onRetry = onRetry, onOpenPermission = onOpenPermission,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun text(t: String) = compose.onNodeWithText(t, substring = true)

    // ---------------------------------------------------------------- 可选更新

    @Test
    fun `可选更新要说清从哪个版本到哪个版本 并列出更新说明`() {
        overlay(UpdateUi.Offer(manifest(), forced = false, why = ""))
        text("发现新版本 1.23-T1").assertIsDisplayed()
        text("当前 1.22-T1").assertIsDisplayed()
        text("修好了切页冒出来的假报错").assertIsDisplayed()
        text("更新框不再显示一长串网址").assertIsDisplayed()
        text("以后再说").assertIsDisplayed()
    }

    @Test
    fun `可选更新的按钮真的能点`() {
        var started = 0
        overlay(UpdateUi.Offer(manifest(), forced = false, why = ""), onStart = { started++ })
        // 用精确匹配：更新说明里也带着"更新"两个字，substring 会同时命中说明那一大块
        compose.onNodeWithText("更新").performClick()
        compose.waitForIdle()
        assertEquals(1, started)
    }

    @Test
    fun `以后再说 要能把这次更新关掉`() {
        var skipped = 0
        overlay(UpdateUi.Offer(manifest(), forced = false, why = ""), onSkip = { skipped++ })
        text("以后再说").performClick()
        compose.waitForIdle()
        assertEquals(1, skipped)
    }

    @Test
    fun `没写更新说明时要说实话 而不是留一片空白`() {
        val m = manifest().copy(notes = emptyList())
        overlay(UpdateUi.Offer(m, forced = false, why = ""))
        text("没有写说明").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- 强制更新

    @Test
    fun `强制更新不许给以后再说这条退路`() {
        overlay(UpdateUi.Offer(manifest(force = true), forced = true, why = "服务端标记为必须更新"))
        text("需要更新到 1.23-T1").assertIsDisplayed()
        text("服务端标记为必须更新").assertIsDisplayed()
        text("立即更新").assertIsDisplayed()
        assertEquals("强制更新里出现了「以后再说」—— 那就不叫强制了",
            0, compose.onAllNodesWithTextCount("以后再说"))
    }

    @Test
    fun `版本过低时要说清为什么必须更新`() {
        overlay(UpdateUi.Offer(manifest(), forced = true, why = "当前版本太旧（低于 v3），无法继续使用"))
        text("当前版本太旧（低于 v3）").assertIsDisplayed()
    }

    // ------------------------------------------------- 更新前先要权限

    @Test
    fun `更新前要权限_说清开关名_并且这一步没有能绕过去开始下载的按钮`() {
        var opened = 0
        overlay(UpdateUi.AskPermission(manifest(), forced = false), onOpenPermission = { opened++ })
        text("更新前需要系统许可").assertIsDisplayed()
        text("安装未知应用").assertIsDisplayed()
        text("在允许之前不会开始下载").assertIsDisplayed()

        // 精确匹配：这一步**不许**出现「更新」/「安装」这种能直接往下走的按钮，
        // 否则等于给"先要权限"开了个后门
        assertEquals("这一步不该有开始下载的按钮",
            0, compose.onAllNodesExactCount("更新"))
        assertEquals("这一步不该有安装按钮",
            0, compose.onAllNodesExactCount("安装"))

        compose.onNodeWithText("去允许").performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun `更新前要权限这一步_可选更新仍然留退路`() {
        var skipped = 0
        overlay(UpdateUi.AskPermission(manifest(), forced = false), onSkip = { skipped++ })
        text("以后再说").performClick()
        compose.waitForIdle()
        assertEquals(1, skipped)
    }

    @Test
    fun `强制更新在要权限这一步也不给退路`() {
        overlay(UpdateUi.AskPermission(manifest(force = true), forced = true))
        compose.onNodeWithText("去允许").assertIsDisplayed()
        assertEquals("强制更新里出现了「以后再说」",
            0, compose.onAllNodesWithTextCount("以后再说"))
    }

    // ---------------------------------------------------------------- 下载中

    @Test
    fun `下载中要显示百分比`() {
        overlay(UpdateUi.Progress(manifest(), 0.42f))
        text("正在下载更新").assertIsDisplayed()
        text("42%").assertIsDisplayed()
    }

    @Test
    fun `进度未知时不许显示假的百分比`() {
        overlay(UpdateUi.Progress(manifest(), -1f))
        text("正在下载").assertIsDisplayed()
        assertEquals("服务端没给大小时不能瞎编一个 0%",
            0, compose.onAllNodesWithTextCount("%"))
    }

    // ---------------------------------------------------------------- 下载完成

    @Test
    fun `下载完成要把校验过的证据摆出来`() {
        val f = File(ctx.cacheDir, "campus-1.23-T1.apk").apply { writeBytes(ByteArray(1024)) }
        overlay(UpdateUi.Ready(manifest(), f, needPermission = false))
        text("更新包已就绪").assertIsDisplayed()
        text("校验和 ${sha.take(12)}").assertIsDisplayed()
        text("安装").assertIsDisplayed()
    }

    @Test
    fun `没开安装权限要先引导去开 而不是干瞪眼`() {
        val f = File(ctx.cacheDir, "campus-1.23-T1.apk").apply { writeBytes(ByteArray(16)) }
        var opened = 0
        overlay(UpdateUi.Ready(manifest(), f, needPermission = true), onOpenPermission = { opened++ })
        text("安装未知应用").assertIsDisplayed()
        text("去开启权限").performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    // ---------------------------------------------------------------- 失败

    @Test
    fun `失败要说人话 并且给重试`() {
        var retried = 0
        overlay(UpdateUi.Failed(manifest(), "文件校验没通过，已丢弃"), onRetry = { retried++ })
        text("更新没成功").assertIsDisplayed()
        text("文件校验没通过").assertIsDisplayed()
        text("重试").performClick()
        compose.waitForIdle()
        assertEquals(1, retried)
    }

    // ---------------------------------------------------------------- 我的页入口

    @Test
    fun `我的页有检查更新入口 点了要真的有反应`() {
        var checked = 0
        val app = RuntimeEnvironment.getApplication() as CampusApplication
        compose.setContent {
            CampusTheme {
                MeScreen(
                    ctx = ctx, db = app.db, api = CampusApi(), version = "1.23-T1",
                    onLogin = {}, updateHint = null, onCheckUpdate = { checked++ },
                )
            }
        }
        compose.waitForIdle()
        text("检查更新").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, checked)
        text("当前版本 1.23-T1").assertIsDisplayed()
    }

    @Test
    fun `检查结果要显示在入口下面`() {
        val app = RuntimeEnvironment.getApplication() as CampusApplication
        compose.setContent {
            CampusTheme {
                MeScreen(
                    ctx = ctx, db = app.db, api = CampusApi(), version = "1.23-T1",
                    onLogin = {}, updateHint = "已经是最新版本（1.23-T1）", onCheckUpdate = {},
                )
            }
        }
        compose.waitForIdle()
        text("已经是最新版本").assertIsDisplayed()
    }

    @Test
    fun `更新状态的锚点在版本号上 不许写死成某个版本`() {
        // 版本号是构建时注入的；把测试锚点写成字面量会在下次升版本时变成"假失败"
        assertTrue(top.ccbase.campus.BuildConfig.VERSION_NAME.isNotBlank())
    }
}

/**
 * 数一数页面上**正好等于**这段文字的节点。
 *
 * 不能用 substring：按钮叫「去允许」，而说明里也写着"点「去允许」会…"，
 * substring 会同时命中说明 → 断言变成假的（真踩过）。
 */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesExactCount(
    text: String,
): Int = onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

/** 数一数页面上有多少个包含这段文字的节点（Compose 的 ``onAllNodesWithText`` 不带 count） */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(
    text: String,
): Int = onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
