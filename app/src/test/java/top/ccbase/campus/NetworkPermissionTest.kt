package top.ccbase.campus

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 权限清单的守卫测试。
 *
 * 为什么单独写这个类：
 * 这个 App **离线起家**，后来才长出网络层。离线测试全用假传输层，
 * 一个都不会去碰真网络 —— 于是「代码在发 HTTP，但没有 INTERNET 权限」这种洞
 * 在整条测试链上是**完全隐形**的，直到用户拿起手机点了登录，
 * 看到一句"网络异常"，而这句话对排查毫无帮助。
 *
 * 所以这里不看代码逻辑，只看**装机时到底拿到了什么权限**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkPermissionTest {

    private fun declared(): List<String> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ctx.packageManager
            .getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toList() ?: emptyList()
    }

    private fun source(rel: String) = File(rel).takeIf { it.exists() }?.readText(Charsets.UTF_8)

    @Test
    fun `声明了联网权限`() {
        assertTrue(
            "没有 INTERNET 权限，App 里所有网络请求都会直接抛异常 —— " +
                "用户看到的是「网络异常」，而这句话什么都说明不了。当前声明：${declared()}",
            "android.permission.INTERNET" in declared(),
        )
    }

    @Test
    fun `代码里只要还在发网络请求 就必须一直有这个权限`() {
        // 反向守卫：删权限的人未必知道还有代码在发请求
        val api = source("src/main/java/top/ccbase/campus/net/CampusApi.kt")
        if (api == null) return                       // 源码路径变了就跳过，别误报
        val usesNetwork = listOf("HttpURLConnection", "URL(", "transport.call").any { it in api }
        assertTrue("网络层已消失，这个测试可以删了", usesNetwork)
        assertTrue(
            "CampusApi 里有 HTTP 调用，但清单里没有 INTERNET 权限 —— 装上就是「网络异常」",
            "android.permission.INTERNET" in declared(),
        )
    }

    @Test
    fun `应用内自动更新要的装机权限也在`() {
        assertTrue(
            "没有 REQUEST_INSTALL_PACKAGES，下载完的 APK 拉不起系统安装器 —— " +
                "而且失败得很安静，用户只会看到「没能拉起安装器」。当前声明：${declared()}",
            "android.permission.REQUEST_INSTALL_PACKAGES" in declared(),
        )
    }

    @Test
    fun `FileProvider 声明齐了 否则点安装那一刻才炸`() {
        // FileUriExposedException 只在真机点"安装"时抛，纯逻辑测试永远碰不到它
        val manifest = source("src/main/AndroidManifest.xml") ?: return
        assertTrue("清单里没有 FileProvider", "androidx.core.content.FileProvider" in manifest)
        assertTrue(
            "authorities 必须用 applicationId 占位，写死字符串会和别的应用撞车",
            "applicationId}.fileprovider" in manifest,
        )
        val paths = source("src/main/res/xml/file_paths.xml")
        assertTrue("缺 file_paths.xml，FileProvider 不知道能分享哪个目录", paths != null)
        assertTrue("file_paths.xml 里要放行自动更新的下载目录", paths!!.contains("update/"))
    }

    @Test
    fun `静音功能要的权限也在`() {
        // 同样的思路覆盖另一块"系统能力"：少一个就静默失效
        val perms = declared()
        assertTrue("改铃声需要 WRITE_SETTINGS", "android.permission.WRITE_SETTINGS" in perms)
        assertTrue("精确闹钟需要 SCHEDULE_EXACT_ALARM", "android.permission.SCHEDULE_EXACT_ALARM" in perms)
        assertTrue("开机重排需要 RECEIVE_BOOT_COMPLETED", "android.permission.RECEIVE_BOOT_COMPLETED" in perms)
    }

    @Test
    fun `没有多要多余的权限`() {
        // 一个学生自用工具不该申请的权限 —— 多了反而让人不敢装
        val scary = listOf(
            "android.permission.READ_CONTACTS",
            "android.permission.READ_SMS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_EXTERNAL_STORAGE",
        )
        val bad = declared().filter { it in scary }
        assertTrue("申请了用不到的敏感权限：$bad", bad.isEmpty())
    }

    @Test
    fun `登录接口地址写的是 https`() {
        val api = source("src/main/java/top/ccbase/campus/net/CampusApi.kt") ?: return
        assertTrue("默认地址必须是 https，明文 HTTP 会把密码裸奔在网络上", "https://" in api)
        assertTrue("不允许出现明文 http 的服务地址", !api.contains("http://"))
    }
}
