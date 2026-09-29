package top.ccbase.campus.ui.me

import top.ccbase.campus.net.StudentError

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.domain.activitiesFrom
import top.ccbase.campus.net.TimetableSyncRes

/**
 * 「刷新课表」两条路共用的**纯逻辑**（普通 JUnit，不渲染、不联网）。
 *
 * 这一层钉的是文案与判断：哪一类失败该给重登入口、哪一类该说"可以再点一次"、
 * 副标题有没有在骗人。这些在渲染测试里看不出来，但正是用户实际读到的字。
 */
class SyncLogicTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------ 失败怎么说

    @Test
    fun `401 要给重登入口_并且不许说成可重试`() {
        val f = SyncLogic.fail(401, "登录已过期，请重新登录")
        assertTrue("401 必须给重登入口", f.needsRelogin)
        assertFalse("令牌都失效了，说'再点一次'等于骗用户", f.retryable)
        assertTrue("文案里要有那句标准话：${f.text}", f.text.contains("登录已过期，请重新登录"))
        assertEquals("服务端原话和我们的标准话一样时不要重复两遍", SyncLogic.RELOGIN, f.text)
    }

    @Test
    fun `服务端说缺少登录令牌_照样按登录过期处理`() {
        // 真机那次 401 服务端的原话就是这句（Authorization 没到服务端时 FastAPI 这么答）
        val f = SyncLogic.fail(401, "缺少登录令牌")
        assertTrue(f.needsRelogin)
        assertTrue("要把服务端原话带上，方便查是哪种 401：${f.text}", f.text.contains("缺少登录令牌"))
        assertTrue(f.text.contains("登录已过期"))
    }

    @Test
    fun `404 也不许把服务端没部署讲给学生`() {
        // 2026-09-24 定稿：接口/部署状态都算内部信息，学生只该看到同一句话。
        // 判断（不可重试、不用重登）照旧，只是话不往外说。
        val f = SyncLogic.fail(404, "Not Found")
        assertFalse("重试也不会出现这个接口", f.retryable)
        assertFalse(f.needsRelogin)
        assertEquals(StudentError.TEXT, f.text)
    }

    @Test
    fun `网络不通要说可以再试_服务端忙也一样`() {
        assertTrue(SyncLogic.fail(0, "SocketTimeoutException（连上了但不响应）").retryable)
        assertTrue("服务器忙是暂时状态，值得再点一次", SyncLogic.fail(429, "已有一次课表同步在进行，稍后再试").retryable)
        // 状态码不许上屏（拿不到服务端文案时也一样）：码只进 logcat
        assertEquals(StudentError.TEXT, SyncLogic.fail(500, "").text)
    }

    @Test
    fun `超时的话要点明停在哪一步_并且给做得到的事`() {
        val t = SyncLogic.timeoutText("正在登录教务…")
        assertTrue("要说清是哪一步：$t", t.contains("正在登录教务"))
        assertTrue("要告诉用户能怎么办：$t", t.contains("再点一次"))
        assertTrue("不能只说一句'超时'：$t", t.contains("45 秒"))
        // 看门狗必须比单步超时长 —— 否则它会在每一步都还没到超时的时候乱喊
        assertTrue(
            "看门狗(${SyncLogic.WATCHDOG_MS}) 必须比单步超时(${SyncLogic.STEP_TIMEOUT_MS})长",
            SyncLogic.WATCHDOG_MS > SyncLogic.STEP_TIMEOUT_MS,
        )
    }

    // ------------------------------------------------------------ 抓到了什么

    @Test
    fun `读到的规模按真教务样本数出来`() {
        val raw = javaClass.classLoader!!.getResourceAsStream("eams/print_data.json")!!
            .bufferedReader().readText()
        val acts = activitiesFrom(raw)!!
        val s = SyncLogic.stats(acts)
        assertEquals("样本里 19 段课（与 EamsClientTest 同一份真数据）", 19, s.activities)
        assertEquals("课表里 9 门课（同一门课的多次上课要算一门）", 9, s.courses)
    }

    @Test
    fun `课表形状不对时不许崩`() {
        // 教务的字段有时是字符串、有时是数字，还有 null；宁可少数一门也不许抛
        val weird = json.parseToJsonElement(
            """[{"courseName":"数学分析（I）","weekday":"1"},{"courseName":null},
                {"lessonName":"大学体育I（必修项目）"},{"courseName":123},{"courseName":"  "}]"""
        ).jsonArray
        val s = SyncLogic.stats(weird)
        assertEquals("条数照实算（包括读不出名字的那几条）", 5, s.activities)
        assertEquals("只有两门读得出名字：数字 123 也读得出，短横线那两条不算", 3, s.courses)
        assertEquals("空输入给 0，不编造", SyncLogic.Stats(0, 0), SyncLogic.stats(null))
    }

    @Test
    fun `抓完的回话要说清读到了几条几门课`() {
        val t = SyncLogic.uploadOkText(SyncLogic.Stats(19, 8), mapOf("courses" to 8, "tasks" to 42))
        assertTrue("要有手机读到的条数：$t", t.contains("19 条上课活动"))
        assertTrue("要有门数：$t", t.contains("8 门课"))
        assertTrue("还要有本机结果：$t", t.contains("42 项任务"))
    }

    // ------------------------------------------------- 「立即更新课表」的副标题

    @Test
    fun `副标题不许再假装读教务`() {
        // 老文案「从教务系统重新读一次，几秒钟」在服务端不支持时是**假话**
        val unknown = SyncLogic.syncSubtitle(null)
        assertTrue("还不知道服务端能力时不能承诺读教务：$unknown", !unknown.contains("教务课表"))
        assertTrue(unknown.contains("重新同步"))

        val no = SyncLogic.syncSubtitle("no")
        assertTrue("老服务端要明说只能从服务器同步：$no", no.contains("从服务器同步"))
        assertFalse(no.contains("重读一次你的教务课表"))

        val yes = SyncLogic.syncSubtitle("yes")
        assertTrue("支持重读时就把动作说清楚：$yes", yes.contains("重读"))
    }

    @Test
    fun `服务端重读的三种真结果不能混成一句成功`() {
        assertEquals(
            "课表真的变了要说变了",
            true, SyncLogic.serverSyncText(TimetableSyncRes(ok = true, changed = 3)).contains("有更新"),
        )
        assertTrue(
            "重读了但没变也要如实说（不然用户以为白点了）",
            SyncLogic.serverSyncText(TimetableSyncRes(ok = true, changed = 0)).contains("没有变化"),
        )
        assertTrue(
            "服务端没存教务密码时要说清是为什么",
            SyncLogic.serverSyncText(TimetableSyncRes(ok = false, noCreds = listOf(7))).contains("没存你的教务密码"),
        )
    }

    @Test
    fun `不支持重读时的兜底话要说明只做了从服务器同步`() {
        val t = SyncLogic.fallbackText(mapOf("courses" to 8, "tasks" to 40))
        assertTrue(t.contains("还不支持重读教务课表"))
        assertTrue("要说明这次做了什么：$t", t.contains("从服务器同步"))
        assertTrue(t.contains("8 门课"))
    }
}
