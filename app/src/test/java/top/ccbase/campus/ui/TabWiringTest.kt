package top.ccbase.campus.ui

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
        listOf("TODAY", "SCHEDULE", "TASKS", "LIBRARY", "BOARD", "ME").forEach { tab ->
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
     * 迁移后这条钉的是**新事实**。旧版声明承诺"服务端 AES-GCM 加密保管你的教务密码"，
     * 那套做法已退役 —— 现在根本不收密码，再写"我们加密存着"就是假承诺（比不写更伤信任）。
     *  ① 入口在我的页**明处**（不是只能改地址进的隐藏页，用户反复强调过讨厌那种）；
     *  ② 整页声明写清：只在官方 HTTPS 页输入 / App 不读取不保存 / 不发给我们的服务器 /
     *     导入结束清 Cookie+网页存储+缓存 / 旧版本存过的凭据可删；
     *  ③ 反向断言：不许再出现"服务端加密保管密码"这类已不成立的承诺。
     */
    @Test
    fun `安全声明要从我的页明处点进去且写清关键事实`() {
        val me = File("src/main/java/top/ccbase/campus/ui/me/MeScreen.kt").readText()
        assertTrue("我的页要有「安全与隐私」入口", me.contains("\"安全与隐私\""))
        assertTrue("入口要说明它讲什么（不能只有一个词）", me.contains("hint = \"你的教务密码"))

        val app = File("src/main/java/top/ccbase/campus/ui/CampusApp.kt").readText()
        assertTrue("入口要真的接上页面", app.contains("SafetyScreen("))

        val safety = File("src/main/java/top/ccbase/campus/ui/me/SafetyScreen.kt").readText()
        assertTrue("声明要写清密码只在官方 HTTPS 页输入",
            safety.contains("官方") && safety.contains("HTTPS"))
        assertTrue("声明要写清 App 不读取、不保存密码",
            safety.contains("不读取") && safety.contains("不保存"))
        assertTrue("声明要写清不把密码/Cookie 发给我们的服务器", safety.contains("我们的服务器"))
        assertTrue("声明要写清导入结束即清理（Cookie/网页存储/缓存）",
            safety.contains("Cookie") && safety.contains("网页存储") && safety.contains("缓存"))
        assertTrue("声明要写清旧版本凭据可一键删除", safety.contains("一键删除"))
        assertTrue(
            "声明不该再承诺「服务端加密保管你的密码」—— 已经根本不收密码了，写上就是假承诺",
            !safety.contains("AES-GCM"),
        )
        assertTrue(
            "声明不该再说教务密码存在服务器上（凭据上传路径已退役）",
            !safety.contains("服务器管理员可读"),
        )

        // 登录/导入那一页也要**自己**说清（不能只靠别处指个链接）
        val flow = File("src/main/java/top/ccbase/campus/school/SchoolImportFlow.kt").readText()
        assertTrue("导入页要写明 App 不读取密码", flow.contains("不读取"))
        assertTrue("导入页要写明课表默认不发服务器", flow.contains("不会发给任何服务器"))
    }


}
