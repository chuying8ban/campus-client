package top.ccbase.campus.data.remote

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Checkin
import top.ccbase.campus.data.local.Session as StudySession
import top.ccbase.campus.data.local.TaskDone
import top.ccbase.campus.data.plan.Modules
import top.ccbase.campus.data.plan.TplLoader
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.data.seed.SeedImporter
import top.ccbase.campus.data.seed.SeedLoader
import top.ccbase.campus.net.ApiUser
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.Transport
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.StudentError

/**
 * 远端计划落地的测试。
 *
 * 这是整个多人版在**客户端**的最后一环，也是最容易出数据事故的一环：
 * 一个同学登录后拉到自己课表的瞬间，绝不能把他已有的打卡记录冲掉，
 * 也绝不能被作者本人的内置种子反过来覆盖。
 *
 * 全部用假 HTTP 传输，不联网、不依赖真服务器（服务端 9/21 才部署）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemotePlanTest {

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

    private fun planJson(courseName: String) = """
    {"courses":[{"id":501,"name":"$courseName","short":null,"credits":5,"teacher":"张老师",
      "category":null,"week_from":3,"is_focus":1,"target":null,"note":null,"sort":1}],
     "slots":[{"id":601,"course_id":501,"weekday":1,"p_start":1,"time_text":"09:30-11:05",
      "room":"C5-301","week_from":3,"sort":1}],
     "selfstudy":[],
     "tasks":[{"id":701,"phase":"本学期","phase_order":1,"title":"$courseName 作业","detail":null,
      "course_id":501,"track":null,"priority":1,"cadence":"weekly","weekday":null,
      "deliverable":"演示条目175：复盘昨天没弄懂的点","active":1,"sort":1}],
     "study_steps":[{"id":801,"task_id":701,"seq":1,"text":"看视频","minutes":30,
      "kind":"video","done_day":null}],
     "resources":[{"id":901,"task_id":701,"kind":"video","title":"讲师甲数学分析（I）","url":"https://example.com",
      "source":"B站","why":null,"http":200,"embed":1,"sort":1}],
     "checklist":[{"id":1001,"item":"挂科了吗"}],
     "milestones":[{"id":1101,"when_text":"演示条目007：复盘昨天没弄懂的点","sort":1}],
     "meta":{"semester_start":"2026-08-31"}}
    """.trimIndent()

    private fun apiReturning(reply: (String) -> HttpReply) = CampusApi(
        base = "https://example.invalid",
        transport = Transport { method, url, _, _ -> reply("$method $url") },
    )

    // ---------------------------------------------------------- 计划落地

    @Test
    fun `远端计划能写进本地库`() = runBlocking {
        val api = apiReturning { HttpReply(200, planJson("数学分析（I）")) }
        val plan = (api.plan("tok") as ApiResult.Ok).value
        PlanApplier.apply(db, plan, at = "2026-09-16T07:00:00")

        assertEquals(1, db.dao().courses().first().size)
        assertEquals("数学分析（I）", db.dao().courses().first()[0].name)
        assertEquals(1, db.dao().slots().first().size)
        assertEquals(1, db.dao().allSteps().first().size)
        assertEquals("remote", PlanApplier.source(db))
    }

    @Test
    fun `落地远端计划绝不能碰用户记录`() = runBlocking {
        // 先让用户产生真实的打卡与完成记录
        SeedImporter.import(db, SeedLoader.load(ctx))
        val taskId = db.dao().tasks().first().first().id
        db.dao().markDone(TaskDone(task_id = taskId, day = "2026-09-16", at = "10:00"))
        db.dao().saveCheckin(Checkin(day = "2026-09-16", note = "今天", updated_at = "10:00"))
        db.dao().saveSession(StudySession(started_at = "20:00", minutes = 30, day = "2026-09-16"))

        val api = apiReturning { HttpReply(200, planJson("同学自己的课")) }
        PlanApplier.apply(db, (api.plan("tok") as ApiResult.Ok).value)

        assertEquals("完成记录必须还在", 1, db.dao().doneOn("2026-09-16").first().size)
        assertNotNull(db.dao().checkin("2026-09-16"))
        assertEquals(1, db.dao().recentActiveDays(10).size)
    }

    @Test
    fun `落地后内置种子不能再覆盖同学的计划`() = runBlocking {
        val api = apiReturning { HttpReply(200, planJson("同学自己的课")) }
        PlanApplier.apply(db, (api.plan("tok") as ApiResult.Ok).value)

        val r = SeedImporter.import(db, SeedLoader.load(ctx))
        assertFalse("种子导入必须让位给远端计划", r.imported)
        assertEquals("同学自己的课", db.dao().courses().first()[0].name)
    }

    @Test
    fun `退出登录后内置种子重新接管`() = runBlocking {
        val api = apiReturning { HttpReply(200, planJson("同学自己的课")) }
        PlanApplier.apply(db, (api.plan("tok") as ApiResult.Ok).value)
        PlanApplier.releaseToSeed(db)

        assertEquals("seed", PlanApplier.source(db))
        val r = SeedImporter.import(db, SeedLoader.load(ctx))
        assertTrue(r.imported)
        assertEquals(11, db.dao().courses().first().size)
    }

    @Test
    fun `重复同步同一份计划结果一致`() = runBlocking {
        val api = apiReturning { HttpReply(200, planJson("数学分析（I）")) }
        val plan = (api.plan("tok") as ApiResult.Ok).value
        PlanApplier.apply(db, plan)
        PlanApplier.apply(db, plan)
        assertEquals(1, db.dao().courses().first().size)
        assertEquals(1, db.dao().slots().first().size)
        assertEquals(1, db.dao().tasks().first().size)
    }

    @Test
    fun `计划里的课程 id 与任务引用对得上`() = runBlocking {
        val api = apiReturning { HttpReply(200, planJson("数学分析（I）")) }
        PlanApplier.apply(db, (api.plan("tok") as ApiResult.Ok).value)
        val course = db.dao().courses().first()[0]
        val task = db.dao().tasks().first()[0]
        assertEquals(course.id, task.course_id)
    }

    @Test
    fun `同步远端计划后已挑的模块必须还在`() = runBlocking {
        // 挑一个模块
        val tpl = TplLoader.load(ctx)
        val mid = tpl.modules.first().id
        Modules.apply(db, tpl, listOf(mid))
        val moduleTasks = db.dao().tasks().first().filter { it.id >= Modules.ID_BASE }.size
        assertTrue("模块任务应该已经进库", moduleTasks > 0)

        // 再来一次完整同步 —— Content.replace 会整体替换内容，模块任务不在服务端计划里，
        // 如果同步后不重放，用户就会遇到「我挑的技能包每次更新就消失」
        val api = apiReturning { HttpReply(200, planJson("同学自己的课")) }
        val r = RemoteSync.sync(ctx, db, api, "tok", tpl)
        assertTrue(r is ApiResult.Ok)

        val after = db.dao().tasks().first().filter { it.id >= Modules.ID_BASE }.size
        assertEquals("同步后模块任务不能被清掉", moduleTasks, after)
        assertEquals(listOf(mid), Modules.picked(db))
    }

    @Test
    fun `同步失败时不破坏本地已有内容`() = runBlocking {
        val tpl = TplLoader.load(ctx)
        Modules.apply(db, tpl, listOf(tpl.modules.first().id))
        val before = db.dao().tasks().first().size

        val api = apiReturning { HttpReply(500, """{"detail":"服务器开小差"}""") }
        val r = RemoteSync.sync(ctx, db, api, "tok", tpl)

        assertTrue(r is ApiResult.Err)
        assertEquals("拉取失败必须原样保留本地内容", before, db.dao().tasks().first().size)
    }

    // ---------------------------------------------------------- API 客户端

    @Test
    fun `登录成功返回令牌与用户`() = runBlocking {
        val api = apiReturning {
            HttpReply(200, """{"token":"tok123","expires_at":"2026-10-16T00:00:00",
              "user":{"uid":2,"student_id":"2026000000","name":"同学","can_grab":false,
              "has_credentials":true}}""")
        }
        val r = api.login("2026000000", "pw")
        assertTrue(r is ApiResult.Ok)
        val v = (r as ApiResult.Ok).value
        assertEquals("tok123", v.token)
        assertEquals("同学", v.user.name)
        assertFalse(v.user.canGrab)
    }

    @Test
    fun `密码错误是 401 而不是网络错误`() = runBlocking {
        val api = apiReturning { HttpReply(401, """{"detail":"学号或教务系统密码不正确"}""") }
        val r = api.login("2026000000", "bad")
        assertTrue(r is ApiResult.Err)
        assertEquals(401, (r as ApiResult.Err).code)
        assertEquals("学号或教务系统密码不正确", r.message)
    }

    @Test
    fun `限流是 429`() = runBlocking {
        val api = apiReturning { HttpReply(429, """{"detail":"尝试过于频繁，请 10 分钟后再试"}""") }
        val r = api.login("2026000000", "pw")
        assertEquals(429, (r as ApiResult.Err).code)
    }

    @Test
    fun `网络异常归零而不是假装成 401`() = runBlocking {
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { _, _, _, _ -> throw java.io.IOException("连不上") },
        )
        val r = api.login("2026000000", "pw")
        assertEquals(0, (r as ApiResult.Err).code)
    }

    @Test
    fun `请求确实带上了令牌`() = runBlocking {
        var seen: Map<String, String> = emptyMap()
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { _, _, headers, _ -> seen = headers; HttpReply(200, planJson("x")) },
        )
        api.plan("my-token")
        assertEquals("Bearer my-token", seen["Authorization"])
    }

    @Test
    fun `密码里的引号和反斜杠不会破坏请求体`() = runBlocking {
        var body = ""
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { _, _, _, b -> body = b ?: ""; HttpReply(401, "{}") },
        )
        api.login("2026000000", "pa\"ss\\word\n")
        assertTrue("JSON 必须仍然合法", body.contains("""pa\"ss\\word\n"""))
        // 能被解析回来才算对
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(body)
        assertNotNull(parsed)
    }

    @Test
    fun `服务端多返回字段时旧 App 不崩`() = runBlocking {
        val api = apiReturning {
            HttpReply(200, planJson("数学分析（I）").replace("\"meta\"", "\"future_field\":123,\"meta\""))
        }
        assertTrue(api.plan("tok") is ApiResult.Ok)
    }

    @Test
    fun `非法 JSON 不抛异常而是返回错误`() = runBlocking {
        val api = apiReturning { HttpReply(200, "这不是 JSON") }
        assertTrue(api.plan("tok") is ApiResult.Err)
    }

    @Test
    fun `网络请求必须跑在 IO 线程而不是主线程`() {
        // 真机上 HttpURLConnection 跑主线程会直接抛 NetworkOnMainThreadException，
        // 而**假传输层完全不关心线程** —— 所以这个洞在测试里一直是隐形的（实际踩过一次）。
        // Robolectric 里有真 Looper，能断言出来。
        var ranOnMain: Boolean? = null
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { _, _, _, _ ->
                ranOnMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                throw java.io.IOException("到此为止，只为看线程")
            },
        )
        runBlocking { api.login("2026000000", "pw") }
        assertEquals(
            "网络请求跑在主线程上了 —— 真机会抛 NetworkOnMainThreadException",
            false, ranOnMain,
        )
    }

    @Test
    fun `远端空计划不许清掉本地内容`() = runBlocking {
        // 场景：新同学走完引导（本地有内置模板 + 自己挑的技能包），
        // 但服务端还没给他播种内容 → /plan 返回空。
        // 这时候整体替换 = 把同学刚挑好的东西全清掉。
        SeedImporter.import(db, SeedLoader.load(ctx))
        val before = db.dao().courses().first().size
        assertTrue("前提：本地应该有内容", before > 0)

        val api = apiReturning {
            HttpReply(200, """{"courses":[],"slots":[],"tasks":[],"resources":[],
                "study_steps":[],"milestones":[],"checklist":[],"selfstudy":[],
                "meta":{"semester_start":"2026-08-31"}}""")
        }
        val r = PlanApplier.apply(db, (api.plan("tok") as ApiResult.Ok).value)

        assertEquals("空计划不该写任何东西", 0, r.counts.size)
        assertEquals("本地课程被空计划清掉了", before, db.dao().courses().first().size)
        assertTrue("本地任务被空计划清掉了", db.dao().tasks().first().isNotEmpty())
    }

    // ---------------------------------------------- 网络失败：一句话 + logcat（2026-09-24 定稿）

    private fun errTextOf(e: Exception): String = runBlocking {
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { _, _, _, _ -> throw e },
        )
        (api.login("2026000000", "pw") as ApiResult.Err).message
    }

    /**
     * 以前这几条钉的是"把真因带出去"（缺权限要点名、域名/超时/连不上要分开、异常类名要留）。
     * 2026-09-24 定稿反过来：**学生屏幕上只允许一句话**，真因全部走 logcat。
     * 所以这里改成"分类不再上屏 + 原文必须进日志"两条。
     */
    @Test
    fun `网络失败一律只给学生一句话`() {
        for (e in listOf(
            java.net.SocketException("socket failed: EPERM (Operation not permitted)"),
            java.net.UnknownHostException("study.example"),
            java.net.ConnectException("Connection refused"),
            java.net.SocketTimeoutException("timeout"),
            java.io.IOException("boom"),
        )) {
            assertEquals("界面文案必须是那一句话（实际：$e）", StudentError.TEXT, errTextOf(e))
        }
    }

    @Test
    fun `真因只进 logcat_类名和地址都不上屏`() {
        org.robolectric.shadows.ShadowLog.clear()
        val msg = errTextOf(java.net.UnknownHostException("study.example"))
        val logs = org.robolectric.shadows.ShadowLog.getLogs()
        assertFalse("界面文案里不该出现网址：$msg", msg.contains("https://example.invalid"))
        assertFalse("界面文案里不该出现异常类名：$msg", msg.contains("UnknownHostException"))
        assertTrue("logcat 里必须有地址，不然没法定位",
            logs.any { it.tag == "StudentError" && it.msg.contains("https://example.invalid") })
        assertTrue("logcat 里必须留着真实异常类型（连堆栈）",
            logs.any { it.tag == "StudentError" && it.throwable is java.net.UnknownHostException })
    }

    @Test
    fun `报错里绝不能出现密码`() = runBlocking {
        // 登录请求体里有明文密码，异常信息里一个字符都不能带出来
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { _, _, _, body ->
                // 异常信息里故意"泄露"请求体，验证我们不会把它透出去
                throw java.io.IOException("failed while sending: ${body}")
            },
        )
        val msg = (api.login("2026000000", "SUPER-SECRET-PW") as ApiResult.Err).message
        assertTrue("报错里出现了密码明文：$msg", !msg.contains("SUPER-SECRET-PW"))
    }

    // ---------------------------------------------------------- 令牌存储

    @Test
    fun `令牌能存能读能清`() {
        val u = ApiUser(uid = 2, student_id = "2026000000", name = "同学", canGrab = false)
        TokenStore.save(ctx, "tok", "2026-10-16", u)
        assertEquals("tok", TokenStore.token(ctx))
        assertEquals("2026000000", TokenStore.studentId(ctx))
        assertEquals("同学", TokenStore.name(ctx))
        assertFalse(TokenStore.canGrab(ctx))
        TokenStore.clear(ctx)
        assertNull(TokenStore.token(ctx))
    }
}
