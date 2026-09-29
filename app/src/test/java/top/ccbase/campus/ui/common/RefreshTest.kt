package top.ccbase.campus.ui.common

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.ApiUser
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 刷新功能的测试。
 *
 * 重点不是"页面画出来了"，而是：
 *  ① 状态必须能翻译成一句**带数字或带原因**的人话（不许沉默、不许假成功）
 *  ② 点一下**真的**会去刷，并把结果说出来（用注入的 sync 证明，不联网）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class RefreshTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: CampusDb

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java).allowMainThreadQueries().build()
        // 没登录时刷新条会整条隐藏（不给假入口），所以这里先存个令牌
        TokenStore.save(ctx, "tok-test", "2099-01-01T00:00:00", ApiUser(uid = 1, student_id = "2026000000"))
    }

    @After
    fun tearDown() = db.close()

    // ------------------------------------------------------------ 文案（纯逻辑）

    @Test
    fun `时间裁成能读的形状`() {
        assertEquals("2026-09-16 22:40", RefreshLogic.shortTime("2026-09-16T22:40:11"))
        assertNull(RefreshLogic.shortTime(null))
        assertNull(RefreshLogic.shortTime("   "))
    }

    @Test
    fun `没更新过要说人话_不是空白`() {
        assertEquals(RefreshLogic.NEVER, RefreshLogic.summary(null))
        assertTrue(RefreshLogic.summary("2026-09-16T22:40:11").contains("2026-09-16 22:40"))
    }

    @Test
    fun `成功文案必须带真实数字`() {
        val s = RefreshLogic.doneText(11, 47, null)
        assertTrue(s, s.contains("11 门课"))
        assertTrue(s, s.contains("47 项任务"))
        // 数字是 0 也要老实说 0，不能省略成"已更新"
        assertTrue(RefreshLogic.doneText(0, 0, null).contains("0 门课"))
    }

    @Test
    fun `失败文案必须说卡在哪_拿不到原因也不许沉默`() {
        assertTrue(RefreshLogic.failText(null).contains(RefreshLogic.NO_REASON))
        assertTrue(RefreshLogic.failText("  ").contains(RefreshLogic.NO_REASON))
        assertTrue(RefreshLogic.failText("401 登录已过期").contains("401 登录已过期"))
    }

    // ------------------------------------------------------------ 真点一下

    @Test
    fun `点刷新_真的会去刷_并把真实数字说出来`() {
        var calls = 0
        rule.setContent {
            CampusTheme {
                RefreshBar(db = db, sync = {
                    calls++
                    ApiResult.Ok(PlanApplier.Result(mapOf("courses" to 11, "tasks" to 47)))
                })
            }
        }
        rule.waitForIdle()

        // 初始态：还没更新过
        assertTrue(rule.onAllNodesWithText(RefreshLogic.NEVER).fetchSemanticsNodes().isNotEmpty())

        rule.onNodeWithText("刷新").performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("已更新：11 门课 · 47 项任务", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("刷新条没有真的调用刷新", 1, calls)
    }

    @Test
    fun `刷新失败要把原因说出来_并给出重试`() {
        var calls = 0
        rule.setContent {
            CampusTheme {
                RefreshBar(db = db, sync = {
                    calls++
                    ApiResult.Err(500, "服务器开小差了")
                })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("刷新").performClick()
        // 不再前缀"更新失败："：服务端给的就是成品文案，再套一层等于同一件事两种说法
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("服务器开小差了").fetchSemanticsNodes().isNotEmpty()
        }
        // 失败之后按钮要变成"重试"，而不是还写着"刷新"让人以为没点
        assertTrue(rule.onAllNodesWithText("重试").fetchSemanticsNodes().isNotEmpty())
        assertEquals(1, calls)

        // 再点一次 = 重试，会再刷一次
        rule.onNodeWithText("重试").performClick()
        rule.waitUntil(15_000) { calls == 2 }
        assertEquals(2, calls)
    }
}
