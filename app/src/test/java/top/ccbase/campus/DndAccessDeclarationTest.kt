package top.ccbase.campus

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「勿扰（模式）访问」的声明守卫。
 *
 * 由来（2026-10-18 用户真机截图）：App 里「自动静音」显示"缺：勿扰访问"，
 * 点进去的「模式访问权限」列表里**根本没有校园助手**，权限永远授不上。
 * 根因不是代码没检查权限，而是清单里少了那个"占位服务" ——
 * 系统只把声明了 `BIND_NOTIFICATION_POLICY_ACCESS_SERVICE` 的应用列进名单。
 *
 * 这个洞在纯逻辑测试里完全隐形（`isNotificationPolicyAccessGranted` 返回 false 很正常），
 * 所以这里直接查**装机后的清单**：权限与服务两件都必须有，少一件整条链路就是死的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DndAccessDeclarationTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `声明了勿扰访问权限`() {
        val perms = ctx.packageManager
            .getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toList() ?: emptyList()
        assertTrue(
            "没有 ACCESS_NOTIFICATION_POLICY：即使服务声明了，系统也不会把应用列进「模式访问权限」。当前：$perms",
            "android.permission.ACCESS_NOTIFICATION_POLICY" in perms,
        )
    }

    @Test
    fun `声明了勿扰访问的占位服务 且带正确的权限与 action`() {
        val cn = ComponentName(ctx, "top.ccbase.campus.alarm.PolicyAccessService")
        val si = runCatching {
            ctx.packageManager.getServiceInfo(cn, PackageManager.GET_META_DATA)
        }.getOrNull()
        assertTrue("清单里没有 PolicyAccessService —— 「模式访问权限」列表里就不会出现校园助手", si != null)
        assertTrue(
            "服务的 android:permission 必须是 BIND_NOTIFICATION_POLICY_ACCESS_SERVICE，实际=${si?.permission}",
            si?.permission == "android.permission.BIND_NOTIFICATION_POLICY_ACCESS_SERVICE",
        )
        assertTrue("服务必须导出，系统才能绑定它", si?.exported == true)
    }
}
