package top.ccbase.campus.alarm

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「去授权」到底跳到哪一页 —— 顺序也是行为，得钉住。
 *
 * 用户实机暴露的问题（2026-09-17 截图）：点「去授权」后落在小米的「模式访问权限」长列表里，
 * 一眼看不到「校园」。所以首选必须是**本应用那一项**的详情页，列表页只当兜底。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagLogIntentsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun item(name: String) = DiagItem(name, false, "缺", "去授权")

    @Test
    fun `勿扰访问先试本应用详情页_再退到列表页`() {
        val l = DiagLog.intentsFor(ctx, item("勿扰访问"))
        assertEquals(2, l.size)
        assertEquals(
            "首选必须是直接落到「校园」那一项",
            "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS", l[0].action,
        )
        assertEquals(
            "详情页要带上包名，否则落不到本应用那一项",
            ctx.packageName, l[0].getStringExtra(Settings.EXTRA_APP_PACKAGE),
        )
        assertEquals(
            "退一步是应用列表页，不是最外层的应用详情",
            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, l[1].action,
        )
    }

    @Test
    fun `电池白名单先弹系统窗_再退到优化列表`() {
        val l = DiagLog.intentsFor(ctx, item("电池白名单"))
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, l[0].action)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, l[1].action)
    }

    @Test
    fun `每一项都至少有一个能去的地方`() {
        listOf("修改系统设置", "勿扰访问", "闹钟与提醒", "电池白名单", "通知", "安装更新").forEach {
            assertTrue("「$it」没有可跳转的设置页", DiagLog.intentsFor(ctx, item(it)).isNotEmpty())
        }
    }

    @Test
    fun `其余项的兜底是本应用详情页`() {
        val l = DiagLog.intentsFor(ctx, item("通知"))
        assertTrue(l.any { it.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS })
    }
}
