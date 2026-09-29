package top.ccbase.campus.ui.me

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import java.net.SocketException
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.Transport
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「我的 → 网络自检」面板。
 *
 * 守的是**点得进去、点得出东西**：
 *   - 入口必须在页面上（用户在外面连不上服务器时，得自己找得到这一页）
 *   - 点「跑一次自检」要有结论出来，而且失败的那一层要说得出原因
 *   - 结果要能复制/落盘（截图之外还有一条更准的路子）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class DiagPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val base = "https://api.example.com"

    @Test
    fun `我的页面里能找到网络自检入口_点了会回调`() {
        var opened = false
        compose.setContent {
            CampusTheme {
                MeScreen(
                    onOpenBoard = {},
                    ctx = ctx,
                    db = (ctx.applicationContext as CampusApplication).db,
                    api = CampusApi(transport = Transport { _, _, _, _ -> HttpReply(200, "{}") }),
                    version = "1.24-T1",
                    onLogin = {},
                    onOpenDiag = { opened = true },
                )
            }
        }
        compose.onNodeWithText("网络自检").performScrollTo().performClick()
        assertTrue("入口点了要真的打开自检", opened)
    }

    @Test
    fun `点一次自检要出四层结论_断的那层说得出原因`() {
        compose.setContent {
            CampusTheme {
                DiagOverlay(
                    ctx = ctx,
                    base = base,
                    version = "1.24-T1",
                    // 假的传输层：健康检查被重置，其余正常
                    transport = Transport { _, url, _, _ ->
                        when {
                            // 计划那一步要能数出条数（这才是"我的数据在不在"的答案）
                            url.contains("/api/v2/plan") -> HttpReply(
                                200,
                                "{\"courses\":[{},{}],\"slots\":[{}],\"tasks\":[{},{}],\"milestones\":[{}]}",
                            )
                            url.endsWith("/healthz") -> throw SocketException("Connection reset")
                            else -> HttpReply(200, "{\"ok\":true,\"Answer\":[{\"data\":\"203.0.113.7\"}]}")
                        }
                    },
                    token = "tok",
                    onClose = {},
                    // 握手探测注入假的：UI 测试不能真去连服务器
                    handshake = { _, _ -> "握手失败：SocketException（Connection reset）" },
                )
            }
        }
        compose.onNodeWithText("网络自检").assertIsDisplayed()
        compose.onNodeWithText("跑一次自检").performClick()
        // 自检在 IO 线程上跑（真解析、真连 443），不能只等 "有调用发生" ——
        // 那只能证明它开始了；要等结论真的写进界面（踩过：waitForIdle 不等 IO 线程）
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("❌ ", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // 健康检查必须打 v2 自己的那条（打域名根会显示另一个服务的数字，用户为此误会过）
        compose.onNodeWithText("❌ GET /api/v2/healthz").performScrollTo().assertIsDisplayed()
        // 「我的计划」那一步要把条数摆出来
        compose.onNodeWithText("课程 2", substring = true).performScrollTo().assertIsDisplayed()
        // 变体探测也要出结论（真机上就是靠这一组才知道"换哪种握手能过"）
        compose.onNodeWithText("变体·强制 TLS 1.2", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("复制结果").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `没跑之前就显示关闭_不会假装有结论`() {
        compose.setContent {
            CampusTheme {
                DiagOverlay(
                    ctx = ctx, base = base, version = "1.24-T1",
                    transport = Transport { _, _, _, _ -> HttpReply(200, "{}") },
                    token = null, onClose = {},
                )
            }
        }
        compose.onNodeWithText("关闭").assertIsDisplayed()
        compose.onNodeWithText("跑一次自检").assertIsDisplayed()
        compose.onAllNodesWithText("✅").fetchSemanticsNodes().let {
            assertTrue("还没跑就不该有结论行", it.isEmpty())
        }
    }
}
