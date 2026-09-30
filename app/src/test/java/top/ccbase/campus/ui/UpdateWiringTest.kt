package top.ccbase.campus.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检查的**接线**守卫（源码级）。
 *
 * 有两件事纯函数测不到，只能扫源码钉住：
 *
 * ① **回前台那条路也要查版本。** 原来只有 root 组合上的 `LaunchedEffect(Unit)`，
 *    而它**在进程常驻时只跑一次**（冷启动那一下）—— 一直在后台没被杀的用户可能好几天
 *    看不到「有新版本」。用户要的是「每次更新都要提示到人」，所以 ON_RESUME 那条路
 *    （课表自检走的就是它）必须同时补一次版本检查。
 *
 * ② **检查结果必须留痕。** 自动检查失败以前是零留痕：界面只在手动点时说话。
 *    而「服务端清单 404」「作者通道的随机段被轮换」这类失效的表现恰好是「已是最新」——
 *    不留痕就等于没有痕迹可查（用户和我们都是）。成功也要记，否则「检查根本没跑起来」
 *    这个更常见的失效仍然看不见。
 */
class UpdateWiringTest {

    private val root = File("src/main/java/top/ccbase/campus")

    private fun src(rel: String): String {
        val f = File(root, rel)
        assertTrue("找不到 $rel（测试的工作目录变了？）", f.isFile)
        return f.readText()
    }

    @Test
    fun `回前台那条路也要查一次版本`() {
        val s = src("ui/CampusApp.kt")
        val onResume = Regex("""Lifecycle\.Event\.ON_RESUME\s*\)\s*\{[^}]*\}""").find(s)?.value
        assertTrue("ON_RESUME 观察者没找到（接线被改动了？）", onResume != null)
        assertTrue(
            "ON_RESUME 分支里必须同时做课表自检与版本检查，现在只有：$onResume",
            onResume!!.contains("checkTimetable(") && onResume.contains("checkUpdate("),
        )
    }

    @Test
    fun `更新检查的结果要留痕`() {
        val s = src("ui/CampusApp.kt")
        assertTrue(
            "checkUpdate 必须把结果落下来（UpdatePrefs.markResult）：自动检查失败不许是「看不见的失败」",
            s.contains("UpdatePrefs.markResult("),
        )
        assertTrue(
            "三种结果都要给文案（含「已是最新」），否则「检查根本没跑」这条仍然看不见",
            s.contains("private fun updateTraceText"),
        )
    }

    @Test
    fun `我的页显示更新检查这一行`() {
        val s = src("ui/me/MeScreen.kt")
        assertTrue(
            "「我的」页要显示最近一次更新检查的结果与时间（读者：用户与排查的人）",
            s.contains("\"更新检查\"") && s.contains("UpdatePrefs.lastResult("),
        )
    }
}
