package top.ccbase.campus.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 接线契约测试。
 *
 * **为什么要有这个测试**：抢课页写好之后，`CampusTab.GRAB ->` 这个分支在某次改动里
 * 悄无声息地消失了 —— 文件照常编译、测试全绿，但用户装上看到的是"P0 空壳"。
 * 因为原来的测试只挂了 `GrabScreen(ctx)` 本身，**从没从 tab 入口进去看过**。
 *
 * 页面函数测对了 ≠ 用户点得到。入口接线必须单独钉住：
 *   1. 每个「不是空壳」的 tab 都要有真实分支，且分支在 `else -> ShellScreen` **之前**
 *      （Kotlin 的 when 按顺序匹配，落在 else 后面就是死代码，只有警告不报错）
 *   2. 底部 tab 栏不能把看板放进去（已移到「我的」）
 *
 * 这是源码级断言：跑得快、不需要 Robolectric，专门咬"接线被删了"这一类回归。
 */
class TabWiringTest {

    private val src = File("src/main/java/top/ccbase/campus/ui/CampusApp.kt").readText()

    /**
     * 真实的 tab 分支出现的位置（行号）。
     *
     * 必须**只在 tab 分发的 when 之后**找：同一个文件里还有一处 `CampusTab.GRAB -> C.amber`
     * （底部图标配色），不限定范围会先匹配到它 —— 这条测试第一版就栽在这儿。
     */
    private fun branchLine(tab: String): Int {
        val lines = src.lines()
        val start = lines.indexOfFirst {
            it.contains("when (shown)") || it.contains("when (val tab =")
        }
        return lines.withIndex().firstOrNull { (i, l) ->
            i > start && l.contains("CampusTab.$tab ->")
        }?.index ?: -1
    }

    private fun elseLine(): Int =
        src.lines().indexOfFirst { it.trim().startsWith("else -> ShellScreen") }

    @Test
    fun `抢课 tab 必须接真实页面而不是空壳`() {
        val i = branchLine("GRAB")
        assertTrue("CampusTab.GRAB 没有分支 —— 点进去会看到'空壳'页（这个问题真实发生过）", i >= 0)
        assertTrue("GRAB 分支里必须挂 GrabScreen", src.lines()[i].contains("GrabScreen"))
    }

    @Test
    fun `抢课不在底部 tab 栏里_只能从「我的」进`() {
        // 用户 2026-09-20：「把我的主页面的抢课隐藏一下，放到后台去」。
        // 看板当初就是这么挪的（不在底部栏、从「我的」进），抢课照同一套走。
        // 底部栏是每天都会露出来的地方，抢课是作者自己的工具，没理由占一格。
        val lines = src.lines()
        val i = lines.indexOfFirst { it.contains("CampusTab.entries.filter") }
        assertTrue("找不到底部 tab 的构造点（实现改了就把这条用例一起更新）", i >= 0)
        val block = lines.subList(i, minOf(i + 4, lines.size)).joinToString("\n")
        assertTrue("看板必须在底部栏之外", block.contains("CampusTab.BOARD"))
        assertTrue("抢课必须在底部栏之外 —— 否则又回到主页面底部了", block.contains("CampusTab.GRAB"))
    }

    @Test
    fun `抢课入口接在「我的」页上`() {
        // 入口不接上 = 作者彻底进不去抢课页（当年就是「页面写好了、入口没接线」，用户看到空壳）
        assertTrue(
            "没把「我的」的抢课入口接到抢课页上（应写成 onOpenGrab = { extra = CampusTab.GRAB }）",
            src.contains("onOpenGrab = { extra = CampusTab.GRAB }"),
        )
    }

    @Test
    fun `学习库 tab 必须接真实页面而不是空壳`() {
        // 抢课页当年就是"页面测过了、入口没接线"，用户装上看到空壳。新 tab 一律照这条钉。
        val i = branchLine("LIBRARY")
        assertTrue("CampusTab.LIBRARY 没有分支 —— 点进去会看到'空壳'页", i >= 0)
        assertTrue("LIBRARY 分支里必须挂 LibraryScreen", src.lines()[i].contains("LibraryScreen"))
    }

    @Test
    fun `所有真实 tab 的分支都要在 else 之前`() {
        val e = elseLine()
        assertTrue("找不到 else -> ShellScreen，这个测试需要跟着改", e >= 0)
        listOf("TODAY", "SCHEDULE", "TASKS", "LIBRARY", "BOARD", "ME", "GRAB").forEach { tab ->
            val i = branchLine(tab)
            assertTrue("$tab 没有分支", i >= 0)
            assertTrue("$tab 的分支落在 else 之后 → 是死代码，永远走不到（Kotlin 只给警告）", i < e)
        }
    }

    @Test
    fun `底部 tab 栏不再放看板`() {
        assertTrue(
            "看板应从底部 tab 栏移出（7 个 tab 太挤）",
            src.contains("it != CampusTab.BOARD"),
        )
    }

    @Test
    fun `看板要有另一个入口_否则等于删功能`() {
        // 移出 tab 栏可以，但必须还能进去 —— 否则就是删掉了这个功能
        val me = File("src/main/java/top/ccbase/campus/ui/me/MeScreen.kt").readText()
        assertTrue("「我的」里必须有看板入口", me.contains("onOpenBoard"))
        assertTrue("看板入口要有说明文字", me.contains("打卡热力"))
    }

    @Test
    fun `课表页要有把时间写全的列表视图`() {
        val s = File("src/main/java/top/ccbase/campus/ui/schedule/ScheduleScreen.kt").readText()
        assertTrue("课表要有「列表」视图开关", s.contains("\"列表\" to true"))
        assertTrue("列表里要写全课程名（不能截字）", s.contains("c?.name ?: \"?\""))
        assertTrue("列表里要写出教室和教师", s.contains("c?.teacher?.takeIf"))
        // 时间列一律走节次标签：线上 time_text 是「周一 1-2节」这种带星期的串，
        // 直接显示会把星期重复一遍，也不该拿它做行列的键（那正是"整页斜线"的成因）。
        assertTrue("列表的时间列要用节次标签 slotLabel", s.contains("slotLabel(s)"))
        // 网格本体已抽到 ui/schedule/WeekGrid.kt（行键是节次段起始节），所以改读那个文件 ——
        // 断言的意思没变：行的键必须是节次，绝不能是带星期的 time_text。
        val grid = File("src/main/java/top/ccbase/campus/ui/schedule/WeekGrid.kt").readText()
        // 用户要求「左边就一节一节地来」后，单节行轴搬到了 domain 层
        // （domain/WeekGrid.kt），UI 层只负责画。断言跟着指到正确的文件。
        val gridDom = File("src/main/java/top/ccbase/campus/domain/WeekGrid.kt").readText()
        assertTrue(
            "网格的课块必须按节次起画（p_start 就是行节次），不能拿 time_text 当键",
            gridDom.contains("(it.p_start ?: -1) == unit"),
        )
        assertTrue("行轴是一节一行（unitRange）", gridDom.contains("fun unitRange("))
        assertTrue("左列不能再用「N-M 节」这种把几节并成一块的标签", !grid.contains("\" 节\""))
    }

    /**
     * 用户原话：「要在app和网站里做一下安全声明，告诉用户密码隐藏等信息，让用户放心」。
     *
     * 这条钉三件事：① 入口在我的页**明处**（不是只能改地址进的隐藏页，用户反复强调过讨厌那种）；
     * ② 整页声明**真的写清了**加密方式/不回显/可删除；③ 输密码那一页也提一句。
     * 声明里的每一句都必须与实现对得上（加密走 multiuser.encrypt 的 AES-GCM）。
     */
    @Test
    fun `安全声明要从我的页明处点进去且写清关键事实`() {
        val me = File("src/main/java/top/ccbase/campus/ui/me/MeScreen.kt").readText()
        assertTrue("我的页要有「安全与隐私」入口", me.contains("\"安全与隐私\""))
        assertTrue("入口要说明它讲什么（不能只有一个词）", me.contains("hint = \"你的教务密码"))

        val app = File("src/main/java/top/ccbase/campus/ui/CampusApp.kt").readText()
        assertTrue("入口要真的接上页面", app.contains("SafetyScreen("))

        val safety = File("src/main/java/top/ccbase/campus/ui/me/SafetyScreen.kt").readText()
        assertTrue("声明要写清加密方式", safety.contains("AES-GCM"))
        assertTrue("声明要写清不回显", safety.contains("不回显"))
        assertTrue("声明要写清可一键删除", safety.contains("一键删除"))
        assertTrue("声明要写清全程 HTTPS", safety.contains("HTTPS"))
        assertTrue("声明要承认管理员理论可解密（写做不到的承诺更伤信任）",
            safety.contains("技术上可以解密"))

        val onboard = File("src/main/java/top/ccbase/campus/ui/onboard/Onboard.kt").readText()
        assertTrue("输密码那一页也要提一句并指向声明", onboard.contains("安全与隐私"))
    }

    // ---------------------------------------------------------------- 抢课页交互接线
    //
    // 抢课页最容易"编译过、测试绿、用户点不到"：交互入口藏在长列表里，
    // 或者用了 Robolectric 看不见的浮层。这几条是源码级契约，跑得快、专门咬这类回归。

    private val grabSrc = File("src/main/java/top/ccbase/campus/ui/grab/GrabScreen.kt").readText()

    @Test
    fun `抢课页必须能注入 api_否则交互根本没法测`() {
        assertTrue("GrabScreen 要有可注入的 api 参数（默认值照旧）",
            grabSrc.contains("fun GrabScreen(ctx: Context, api: CampusApi ="),
            )
    }

    @Test
    fun `确认层不许用 Dialog_必须用 Box 浮层`() {
        // Robolectric 里 Dialog 是独立窗口，测试框架看不见 —— 这个坑本项目踩过。
        assertTrue("确认层要用普通 Box 画", grabSrc.contains("Sheet.AUTO_ON ->"))
        assertFalse("不许用 AlertDialog", grabSrc.contains("AlertDialog("))
        assertFalse("不许用 Dialog(", grabSrc.contains("Dialog("))
    }

    @Test
    fun `自动抢课的开启只允许从确认按钮走_没有一次点击就开的路`() {
        // 源码级：开启动作只出现在确认按钮的 onConfirm 回调里，
        // 其它地方（列表、开关）只能弹确认层，不能直接调 setTargetAuto(..., true)。
        val onTrue = Regex("setTargetAuto\\([^)]*,\\s*true\\)|setAllAuto\\(true\\)").findAll(grabSrc).count()
        assertTrue("开启路径必须存在（否则功能是死的）", onTrue >= 1)
        assertTrue("开启必须经过确认按钮：确认开启 -> onConfirmAuto(true)",
            grabSrc.contains("\"确认开启\" to { onConfirmAuto(true) }"))
        assertTrue("还要有单门课的确认层", grabSrc.contains("Sheet.AUTO_ONE ->"))
        assertTrue("确认文案必须写清会自动提交",
            grabSrc.contains("GrabLogic.AUTO_CONFIRM_BODY"))
    }

    @Test
    fun `关闭监控和停止自动抢课都要有入口`() {
        assertTrue("要有「关闭监控」入口", grabSrc.contains("\"关闭监控\""))
        assertTrue("要有「确认关闭」的确认层", grabSrc.contains("\"确认关闭\""))
        assertTrue("开/关监控必须真调服务端（走配置接口，不是画开关）",
            grabSrc.contains("grabConfig("))
        assertTrue("要有醒目的自动提交中指示", grabSrc.contains("自动提交中"))
        assertTrue("要能一键停", grabSrc.contains("立刻停止全部自动提交"))
    }
}
