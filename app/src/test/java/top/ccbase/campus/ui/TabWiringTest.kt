package top.ccbase.campus.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 接线契约测试。
 *
 * **为什么要有这个测试**：监控页写好之后，`CampusTab.GRAB ->` 这个分支在某次改动里
 * 悄无声息地消失了 —— 文件照常编译、测试全绿，但用户装上看到的是"P0 空壳"。
 * 因为原来的测试只挂了 `GrabScreen(ctx)` 本身，**从没从 tab 入口进去看过**。
 *
 * 页面函数测对了 ≠ 用户点得到。入口接线必须单独钉住：
 *   1. 每个「不是空壳」的 tab 都要有真实分支，且分支在 `else -> ShellScreen` **之前**
 *      （Kotlin 的 when 按顺序匹配，落在 else 后面就是死代码，只有警告不报错）
 *   2. 底部 tab 栏：看板**过滤掉**（入口在「我的」页）；监控那一格**人人都有**
 *      （不再按登录时缓存的 can_grab 显隐 —— 用户 2026-09-30：不重新登录也要看得见）
 *
 * 这是源码级断言：跑得快、不需要 Robolectric，专门咬"接线被删了"这一类回归。
 */
class TabWiringTest {

    private val src = File("src/main/java/top/ccbase/campus/ui/CampusApp.kt").readText()

    @Test
    fun `页顶那一块在滚动容器里_往下翻能收起`() {
        // 用户口径 2026-09-30：「往下翻的时候上面的标签可以全部收起」。
        // 做法：说明 / 状态 / 搜索框 / 筛选标签都变成 LazyColumn 的前三项（h-head / h-search /
        // h-filters），不再是钉在列表上方的固定头 —— 钉着的时候小半屏永远收不回去。
        for (k in listOf("h-head", "h-search", "h-filters")) {
            assertTrue("页顶那一块的 $k 应该是列表里的一项（钉在列表上方就收不起来）",
                grabSrc.contains("item(key = \"$k\")"))
        }
    }

    @Test
    fun `操作结果必须是弹窗_有标题也有得点`() {
        // 用户口径 2026-09-30：「里面的所有操作做完都会有弹窗提示，否则用户都不知道自己干了什么」。
        // 钉住它是**浮在页面上的卡片**（结果标题 +「点一下关掉」），不是页顶一行 11sp 小字。
        assertTrue("结果弹窗没了", grabSrc.contains("private fun NoticePopup("))
        assertTrue("弹窗要按结果分档给标题", grabSrc.contains("\"没成功\"") && grabSrc.contains("\"已完成\""))
        assertTrue("弹窗要能点掉", grabSrc.contains("\"点一下关掉\""))
    }

    @Test
    fun `加入监控不许再被开关拦住`() {
        // 2026-09-30 的死锁：这个按钮原来写成 `if (watchOn) onAdd() else onAddBlocked()`，
        // 而 watchOn = "开关在跑 且 目标非空" —— 目标为空时恒为假 ⇒ **第一门课永远加不进去**。
        // 加一门课只是"记下来"，跟开关在不在跑无关：它必须恒可点。
        assertTrue("加入监控又被开关拦住了（onAddBlocked 那套回来了）",
            !grabSrc.contains("onAddBlocked"))
    }

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
    fun `监控 tab 必须接真实页面而不是空壳`() {
        val i = branchLine("GRAB")
        assertTrue("CampusTab.GRAB 没有分支 —— 点进去会看到'空壳'页（这个问题真实发生过）", i >= 0)
        assertTrue("GRAB 分支里必须挂 GrabScreen", src.lines()[i].contains("GrabScreen"))
    }

    @Test
    fun `底部栏_过滤看板_监控那一格人人都有`() {
        // 新口径（2026-09-30 用户原话：「新版本每个用户的手机上都会显示监控，而不需要重新登录」）：
        //   · 看板从底部栏收进「我的」页 —— 底部构造处要把它过滤掉；
        //   · 监控那一格**不再按 can_grab 显隐**：can_grab 是登录那一刻写进本地的缓存，
        //     老用户装上这个版本也不会重新登录，按它显隐 = 那一格永远长不出来。
        //     服务端同时把监控那几个接口从 can_grab 放开成"登录即可"（grab/config 仍只给作者）。
        val lines = src.lines()
        val i = lines.indexOfFirst { it.contains("CampusTab.entries.filter") }
        assertTrue("找不到底部 tab 的构造点（实现改了就把这条用例一起更新）", i >= 0)
        val block = lines.subList(i, minOf(i + 4, lines.size)).joinToString("\n")
        assertTrue("看板必须从底部栏过滤掉（入口改在「我的」页）", block.contains("CampusTab.BOARD"))
        assertTrue(
            "监控那一格不许再拿本地缓存的 can_grab 当判据（用户口径：不重新登录也要看得见）：$block",
            !block.contains("TokenStore.canGrab("),
        )
        assertTrue(
            "底部栏不许把 tabs 冻成只算一次的值：$block",
            !lines[i].contains("remember") && !block.contains("remember"),
        )
    }

    @Test
    fun `监控入口只接在底部栏_「我的」页不再接一份`() {
        // 2026-09-30 用户口径：「把我的里面的监控删了」。
        // 反面教训是当年那句「页面写好了、入口没接线」—— 用户看到空壳。所以这里两头都钉：
        // 底部栏那一格必须接上（下面那条用例），「我的」里不许再接第二份。
        assertTrue(
            "「我的」里又出现了监控入口（用户口径：删掉它，只在底部栏留一格）",
            !src.contains("onOpenGrab = { extra = CampusTab.GRAB }"),
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
    fun `底部 tab 栏不再常显看板`() {
        // 口径：看板从底部栏收进「我的」页；CampusTab.BOARD 枚举仍保留为二级页入口。
        assertTrue(
            "看板没有被底部栏过滤掉 —— 现在的口径是不再常显",
            src.contains("it != CampusTab.BOARD"),
        )
    }

    @Test
    fun `看板要有另一个入口_否则等于删功能`() {
        // 看板不再占底部栏，「我的」里必须留着唯一入口，否则等于删功能。
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
     * ② 整页声明**真的写清了**加密方式/谁能看到/可删除；③ 输密码那一页也提一句。
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
        assertTrue("声明要写清密码的边界（谁能看到）", safety.contains("只对作者本人开放"))
        assertTrue("声明要写清可一键删除", safety.contains("一键删除"))
        assertTrue("声明要写清全程 HTTPS", safety.contains("HTTPS"))
        assertTrue("声明要承认管理员理论可解密（写做不到的承诺更伤信任）",
            safety.contains("技术上可以解密"))

        val onboard = File("src/main/java/top/ccbase/campus/ui/onboard/Onboard.kt").readText()
        assertTrue("输密码那一页也要提一句并指向声明", onboard.contains("安全与隐私"))
    }

    // ---------------------------------------------------------------- 监控页交互接线
    //
    // 监控页最容易"编译过、测试绿、用户点不到"：交互入口藏在长列表里，
    // 或者用了 Robolectric 看不见的浮层。这几条是源码级契约，跑得快、专门咬这类回归。

    private val grabSrc = File("src/main/java/top/ccbase/campus/ui/grab/GrabScreen.kt").readText()

    @Test
    fun `监控页必须能注入 api_否则交互根本没法测`() {
        assertTrue("GrabScreen 要有可注入的 api 参数（默认值照旧）",
            grabSrc.contains("fun GrabScreen(ctx: Context, api: CampusApi ="),
            )
    }

    @Test
    fun `确认层不许用 Dialog_必须用 Box 浮层`() {
        // Robolectric 里 Dialog 是独立窗口，测试框架看不见 —— 这个坑本项目踩过。
        assertTrue("确认层要用普通 Box 画", grabSrc.contains("Sheet.MONITOR_ON ->"))
        assertFalse("不许用 AlertDialog", grabSrc.contains("AlertDialog("))
        assertFalse("不许用 Dialog(", grabSrc.contains("Dialog("))
    }

    @Test
    fun `关闭监控要有入口`() {
        assertTrue("要有「关闭监控」入口", grabSrc.contains("\"关闭监控\""))
        assertTrue("要有「确认关闭」的确认层", grabSrc.contains("\"确认关闭\""))
        assertTrue("开/关监控必须真调服务端（走配置接口，不是画开关）",
            grabSrc.contains("grabConfig("))
        assertTrue("页面不许再出现自动提交入口", !grabSrc.contains("自动提交"))
    }
}
