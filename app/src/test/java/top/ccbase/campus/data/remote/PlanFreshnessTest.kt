package top.ccbase.campus.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Meta
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.TimetableStatus
import top.ccbase.campus.net.Transport

/**
 * 「App 自己发现课表变了」（用户原话：不要用户自己发现错误）。
 *
 * 这里钉三件事，每件都对应一个真实会犯的错：
 *  ① 服务端版本号变了 → **自动**重拉并落地（用户什么都不用点）；
 *  ② 版本号一样 → **一个字都不动**（否则每次开 App 就整份覆盖，用户自己改的东西会被抹掉）；
 *  ③ 服务端没有这个字段 / 字段形状不对（老服务端、新字段上线过渡期）→ **安静退回**，
 *     既不报错也不乱动 —— 新客户端必须能在老服务端上照常用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanFreshnessTest {

    private lateinit var db: CampusDb
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("测试样本缺失：src/test/resources/$name")

    private class Seen { val rows = mutableListOf<String>() }

    private fun api(body: String, code: Int = 200, seen: Seen = Seen()) = CampusApi(
        base = "https://example.invalid",
        transport = Transport { m, u, _, _ ->
            seen.rows += "$m ${u.substringAfter("example.invalid")}"
            HttpReply(code, body)
        },
    )

    private suspend fun localCourses() = db.dao().courses().first().map { it.name }

    // ---------------------------------------------------------- 判据（纯函数）

    @Test
    fun `版本号变了才重拉_老服务端安静退回`() {
        val v = TimetableStatus(version = "aaaa1111", status = "ok")
        assertTrue("本机没记过版本 → 拉一次对齐", PlanFreshness.verdict(v, null).refresh)
        assertTrue("版本号不同 → 必须重拉（这就是\"App 自己发现\"）", PlanFreshness.verdict(v, "bbbb2222").refresh)
        assertFalse("版本号一样 → 什么都不做", PlanFreshness.verdict(v, "aaaa1111").refresh)
        assertFalse("老服务端没有字段 → 不动，也不报错", PlanFreshness.verdict(null, "aaaa1111").refresh)
        assertFalse(
            "字段在但版本号是空的（服务端还没同步过）→ 也不能当成\"变了\"",
            PlanFreshness.verdict(TimetableStatus(version = "", status = "none"), "aaaa1111").refresh,
        )
        assertTrue(
            "退回的原因要写出来（界面/日志要能解释为什么不更新）",
            PlanFreshness.verdict(null, null).reason.contains("没给课表版本号"),
        )
    }

    // ---------------------------------------------------------- 端到端（假传输 + 内存库）

    @Test
    fun `版本号变了_自动重拉并落地`() = runBlocking {
        // 本机记着旧版本号，库里是旧课表
        db.dao().putMeta(listOf(Meta(PlanApplier.K_TT_VER, "old-version-0000")))
        val seen = Seen()
        val r = RemoteSync.checkAndRefresh(ctx, db, api(sample("plan_with_timetable.json"), seen = seen), "tok")
        assertTrue("必须成功：$r", r is ApiResult.Ok)
        assertTrue("结论是\"已经重拉\"：${(r as ApiResult.Ok).value}", r.value.applied)

        assertEquals("只花一次 GET /plan（不许为了比对再多打一次）", 1, seen.rows.size)
        assertEquals("GET /api/v2/plan", seen.rows.single())
        assertTrue("新课表要真的落进本地库", localCourses().contains("大学体育I（必修项目）"))
        assertEquals(
            "新版本号必须被记下来 —— 不记就等于每次开 App 都重拉一遍",
            "9f3c1a2b4d5e6f70", PlanApplier.timetableVersion(db),
        )
        assertTrue(
            "自检结果要留痕（「我的」页显示，不许静默）：${db.dao().metaGet(PlanApplier.K_CHECK_RES)}",
            db.dao().metaGet(PlanApplier.K_CHECK_RES)!!.contains("自动更新"),
        )
    }

    @Test
    fun `版本号一样_一个字都不动`() = runBlocking {
        db.dao().putMeta(listOf(Meta(PlanApplier.K_TT_VER, "9f3c1a2b4d5e6f70")))
        RemoteSync.sync(ctx, db, api(sample("plan_with_timetable.json")), "tok")
        // 用户自己动过数据：改了一门课的名字（同 id 覆盖 = 界面上的"改名/加备注"）
        val first = db.dao().courses().first().first()
        val base = db.dao().courses().first().map { it.name }
        db.dao().putCourses(listOf(first.copy(name = "我自己改的名字")))
        db.dao().putMeta(listOf(Meta("user-note", "我自己的东西")))

        val r = RemoteSync.checkAndRefresh(ctx, db, api(sample("plan_with_timetable.json")), "tok")
        assertTrue(r is ApiResult.Ok)
        assertFalse("版本号没变就不该重拉", (r as ApiResult.Ok).value.applied)
        assertEquals(
            "版本号一样时绝不许覆盖本地内容（覆盖会把同学自己改的东西抹掉）",
            "我自己改的名字", db.dao().courses().first().first { it.id == first.id }.name,
        )
        assertEquals("别的课也不许动", base.size, db.dao().courses().first().size)
        assertEquals("用户自己的 meta 也不许被碰", "我自己的东西", db.dao().metaGet("user-note"))
        assertTrue(
            "核对结果照实记下来：${db.dao().metaGet(PlanApplier.K_CHECK_RES)}",
            db.dao().metaGet(PlanApplier.K_CHECK_RES)!!.contains("最新"),
        )
    }

    @Test
    fun `老服务端没有版本号字段_安静退回_不报错也不动数据`() = runBlocking {
        RemoteSync.sync(ctx, db, api(sample("plan_no_timetable.json")), "tok")
        val before = localCourses()

        val seen = Seen()
        val r = RemoteSync.checkAndRefresh(ctx, db, api(sample("plan_no_timetable.json"), seen = seen), "tok")
        assertTrue("老服务端上必须照常工作，不许报错：$r", r is ApiResult.Ok)
        assertFalse("没有版本号可比 → 什么都不做", (r as ApiResult.Ok).value.applied)
        assertEquals("本地内容不许动", before, localCourses())
        assertNull("没有版本号就不该记版本号（记了会误判成\"已最新\"）", PlanApplier.timetableVersion(db))
        assertTrue(
            "要如实说明\"服务端没给版本号\"：${db.dao().metaGet(PlanApplier.K_CHECK_RES)}",
            db.dao().metaGet(PlanApplier.K_CHECK_RES)!!.contains("没给课表版本号"),
        )
    }

    @Test
    fun `字段形状不对_照样不崩不动数据`() = runBlocking {
        RemoteSync.sync(ctx, db, api(sample("plan_with_timetable.json")), "tok")
        db.dao().putMeta(listOf(Meta(PlanApplier.K_TT_VER, "9f3c1a2b4d5e6f70")))
        val before = localCourses()

        val r = RemoteSync.checkAndRefresh(ctx, db, api(sample("plan_timetable_bad_shape.json")), "tok")
        assertTrue("形状不对只能退化成\"没有版本号\"，绝不许抛：$r", r is ApiResult.Ok)
        assertFalse((r as ApiResult.Ok).value.applied)
        assertEquals("本地内容不许被动", before, localCourses())
    }

    @Test
    fun `核对失败要如实报出来_不许静默`() = runBlocking {
        val seen = Seen()
        val r = RemoteSync.checkAndRefresh(ctx, db, api("""{"detail":"登录已过期，请重新登录"}""", 401, seen), "tok")
        assertTrue(r is ApiResult.Err)
        assertEquals("401 的状态码要保留（UI 靠它给重登入口）", 401, (r as ApiResult.Err).code)
    }

    @Test
    fun `服务器版本变了但计划是空的_不许假装更新过`() = runBlocking {
        // 服务端课表换了版本号，但这位同学还没有计划内容 —— 空计划落地会被 PlanApplier 挡住
        db.dao().putMeta(listOf(Meta(PlanApplier.K_TT_VER, "old-version-0000")))
        val r = RemoteSync.checkAndRefresh(
            ctx, db, api("""{"courses":[],"slots":[],"tasks":[],"meta":{},
                "timetable":{"version":"9f3c1a2b4d5e6f70","status":"ok"}}"""), "tok",
        )
        assertTrue(r is ApiResult.Ok)
        assertFalse("什么都没落地就不许说\"已自动更新\"", (r as ApiResult.Ok).value.applied)
        assertEquals(
            "更不许把**新**版本号记成已同步 —— 那等于永远不再自动更新",
            "old-version-0000", PlanApplier.timetableVersion(db),
        )
    }
}
