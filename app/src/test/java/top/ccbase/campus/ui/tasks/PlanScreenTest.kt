package top.ccbase.campus.ui.tasks

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.StudyStep
import top.ccbase.campus.data.local.Task
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.data.seed.SeedLoader
import top.ccbase.campus.net.ApiUser
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.Transport
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「AI 规划学习计划」这一页。
 *
 * 喂给它的全是**服务端真形状**的数据：
 *  - 建议响应 `plan_suggest_*.json`：拿服务端自己的 `ai_plan.py` 跑出来的（含 `weekday: null`）；
 *  - 课表响应 `live_plan_sample_1.json`：生产库导出的真实 `/api/v2/plan` 报文（65 项任务、68 个步骤）。
 * 不用手写的漂亮 JSON —— 手写会把契约错误一起写进去，测出来的绿是假的。
 *
 * 这里钉住的是"用户点得到、点了会怎样"：
 *  1. 只问四项，选项就是那几件事；
 *  2. 生成后**每条都写着为什么推荐它**（服务端 why 逐条对应）；
 *  3. 加入只送勾选的那几条，一条不勾就发不出去；
 *  4. 加入成功后**本机任务清单真的被刷新**（本地库里真出现那条任务，不是只弹一句话）；
 *  5. 撤销要二次确认，确认后本地那条真的没了；
 *  6. 模型没调通时照实说是兜底版；
 *  7. 文案不吹（整棵树里不许出现效果承诺词）、弹层不用 Dialog（Robolectric 看不见）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class PlanScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private lateinit var db: CampusDb

    /** 与建议夹具里第一条同名：加入后本机应该看到它 */
    private val aiTitle = "刷算法题"

    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("测试样本缺失：src/test/resources/$name")

    private val aiBody get() = sample("plan_suggest_ai.json")
    private val fbBody get() = sample("plan_suggest_fallback.json")

    /**
     * 带资源的建议报文（服务端 `ai_plan.py` 那一侧新出的 `resources`）：
     * 第一条挂 video + course，第二条挂一个**认不出的 kind**（podcast）——
     * 服务端以后加新类型时，界面不许露英文、也不许把整条建议解析崩。
     */
    private val resBody get() = sample("plan_suggest_res.json")

    /** 服务端真报文（65 项任务）+ 一条「AI 规划」任务 = 用户点完「加入任务清单」之后服务端的样子 */
    private fun planBody(withAi: Boolean): String {
        val base = SeedLoader.json.decodeFromString<Seed>(sample("live_plan_sample_1.json"))
        val seed = if (!withAi) base else base.copy(
            tasks = base.tasks + Task(
                id = 900001, phase = PlanLogic.PHASE_AI, title = aiTitle,
                detail = "你想加强编程", track = "自学", priority = 1, cadence = "weekly",
                weekday = 3, deliverable = "20 题记录", active = 1, sort = 900,
            ),
            study_steps = base.study_steps + StudyStep(
                id = 900001, task_id = 900001, seq = 1, text = "每天一题",
            ),
        )
        return SeedLoader.json.encodeToString(Seed.serializer(), seed)
    }

    /** 记下真正发出去的请求：界面上的"点一下"到底有没有变成网络调用，只能看这里 */
    private val seen = mutableListOf<String>()

    private fun see(method: String, url: String, payload: String?) {
        seen += "$method ${url.substringAfter("example.invalid")} ${payload.orEmpty()}"
    }

    private fun has(text: String) = seen.any { it.contains(text) }

    private fun lastBody(path: String): String =
        seen.lastOrNull { it.startsWith("POST $path ") }?.substringAfter("POST $path ") ?: ""

    /**
     * 假传输层，按路径回真样本。
     * `withAi` 模拟"加入之后服务端有了那条任务"；撤销请求一到就翻回 false（服务端也不会再有了）。
     */
    private fun fakeApi(
        applyReply: String = """{"ok":true,"counts":{"tasks":1,"steps":1},"phase":"AI 规划"}""",
        suggestReply: String = aiBody,
        withAiInitially: Boolean = false,
    ): CampusApi {
        var withAi = withAiInitially
        return CampusApi(
            base = "https://example.invalid",
            transport = Transport { method, url, _, payload ->
                see(method, url, payload)
                val path = url.substringAfter("example.invalid")
                when (path) {
                    "/api/v2/plan/suggest" -> HttpReply(200, suggestReply)
                    "/api/v2/plan/suggest/apply" -> {
                        withAi = true                       // 服务端落库了
                        HttpReply(200, applyReply)
                    }
                    "/api/v2/plan/suggest/undo" -> {
                        withAi = false                      // 服务端把「AI 规划」那批删了
                        HttpReply(200, """{"ok":true,"counts":{"tasks":1,"steps":1,"resources":0}}""")
                    }
                    "/api/v2/plan" -> HttpReply(200, planBody(withAi))
                    else -> HttpReply(404, """{"detail":"没有这个接口"}""")
                }
            },
        )
    }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java)
            .allowMainThreadQueries()
            .build()
        // 没有令牌这一页会走"还没登录"分支，测不到真流程
        TokenStore.save(ctx, "tok-plan", "2099-01-01 00:00:00",
            ApiUser(uid = 1, student_id = "s", name = "n"))
    }

    @After
    fun tearDown() = db.close()

    private fun render(api: CampusApi, onGoTasks: () -> Unit = {}) {
        compose.setContent {
            CampusTheme { PlanScreen(db = db, api = api, onClose = {}, onGoTasks = onGoTasks) }
        }
    }

    private fun onPage(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes()

    private fun descOnPage(desc: String) =
        compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes()

    /** 本地课表的真形状 —— 逐门课自评只能从这儿拿课名，不许写死 */
    private fun putCourses(vararg names: String) = runBlocking {
        withContext(Dispatchers.IO) {
            db.dao().putCourses(names.mapIndexed { i, n -> Course(id = i + 1, name = n, sort = i) })
        }
    }

    /** 等「生成建议」这一发真的打到服务端（界面上的动作有没有变成网络调用，只能看这里） */
    private fun waitSent() = compose.waitUntil(20_000) {
        seen.any { it.startsWith("POST /api/v2/plan/suggest ") }
    }

    private fun waitFor(text: String, substring: Boolean = false, ms: Long = 20_000) {
        compose.waitUntil(ms) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun aiItems() = json.parseToJsonElement(aiBody).jsonObject["items"]!!.jsonArray
        .map { it.jsonObject }

    /**
     * 夹具里全部资源（顺序即渲染顺序）。
     * 断言一律拿夹具里的真字符串（标题 / 链接），不抄界面文案 —— 抄了就等于把实现再抄一遍。
     */
    private fun resRows(): List<JsonObject> =
        json.parseToJsonElement(resBody).jsonObject["items"]!!.jsonArray
            .flatMap { it.jsonObject["resources"]?.jsonArray.orEmpty() }
            .map { it.jsonObject }

    /** 真正被 startActivity 出去的地址：点了资源有没有开链接，只能看这里 */
    private fun launchedUrl(): String? =
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.data?.toString()

    private fun localTasks(): List<Task> = runBlocking {
        withContext(Dispatchers.IO) { db.dao().tasks().first() }
    }

    // ---------------------------------------------------------------- 页面本身

    @Test
    fun `表单把该问的都问出来_不再有笼统的基础自评`() {
        putCourses("数学分析（I）", "通用英语")
        render(fakeApi())
        // 用户原话：「我希望尽量详细，让用户自由选择自己想要的计划」——
        // 少一组就等于少一项选择，所以一组一组钉死。
        listOf(
            PlanLogic.SECTION_GOALS, PlanLogic.SECTION_FOCUS, PlanLogic.SECTION_WHEN,
            PlanLogic.SECTION_HOURS, PlanLogic.SECTION_SUBJECTS, PlanLogic.SECTION_STYLE,
            PlanLogic.SECTION_PACE, PlanLogic.SECTION_AVOID,
        ).forEach { sec ->
            assertTrue("少了一项「${sec}」—— 用户要的是尽量详细、自由选择", onPage(sec).isNotEmpty())
        }
        (PlanLogic.FOCUS + PlanLogic.STYLE + PlanLogic.GOALS + PlanLogic.PERIODS +
            PlanLogic.PACES + PlanLogic.DAYS.map { PlanLogic.daysLabel(it) }).forEach { o ->
            assertTrue("选项「${o}」不在页面上", onPage(o).isNotEmpty())
        }
        PlanLogic.HOURS.forEach { h ->
            assertTrue("少了一档时间：${PlanLogic.hoursLabel(h)}",
                       onPage(PlanLogic.hoursLabel(h)).isNotEmpty())
        }
        // 用户 14:4x 截图原话：「这个基础自评指代不清，应该给每个课程分开自评」
        assertTrue("笼统的「基础自评」不该再露出", onPage("基础自评").isEmpty())
        listOf("入门", "会用", "熟练").forEach {
            assertTrue("旧档位「$it」不该再露出", onPage(it).isEmpty())
        }
        // 页头必须说清它做什么（用户凭什么点进来）
        assertTrue(onPage(PlanLogic.NOTE).isNotEmpty())
    }

    @Test
    fun `逐门课自评跟着本地课表走_一门一行五个档`() {
        putCourses("数学分析（I）", "通用英语")
        render(fakeApi())
        waitFor("数学分析（I）")
        assertTrue("课表里的课要一门一行", onPage("通用英语").isNotEmpty())
        assertTrue("得说清 1~5 是什么意思", onPage(PlanLogic.SUBJECT_HINT).isNotEmpty())
        assertTrue("每门课都要有档位可点",
                   descOnPage(PlanLogic.scoreDesc("数学分析（I）", 1)).isNotEmpty())
        assertTrue(descOnPage(PlanLogic.scoreDesc("通用英语", 5)).isNotEmpty())
    }

    @Test
    fun `课表还没抓时这一组说明为什么是空的_而不是藏起来`() {
        render(fakeApi())
        assertTrue("这一组不许藏：藏了他永远不知道有这项",
                   onPage(PlanLogic.SECTION_SUBJECTS).isNotEmpty())
        assertTrue(onPage(PlanLogic.COURSES_EMPTY).isNotEmpty())
    }

    @Test
    fun `只评在意的课_没评的课和没填的分组都不进请求`() {
        putCourses("数学分析（I）", "通用英语")
        render(fakeApi())
        waitFor("数学分析（I）")
        compose.onNodeWithContentDescription(PlanLogic.scoreDesc("数学分析（I）", 1)).performClick()
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitSent()

        val body = json.parseToJsonElement(lastBody("/api/v2/plan/suggest")).jsonObject
        assertEquals("没填的分组一个都不该发过去",
                     setOf("focus", "hours", "style", "level", "subjects"), body.keys)
        val sub = body["subjects"]!!.jsonArray
        assertEquals("只评了一门就只该带一门（没评的不参与）", 1, sub.size)
        assertEquals("数学分析（I）", sub[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1, sub[0].jsonObject["level"]!!.jsonPrimitive.int)
    }

    @Test
    fun `排除项超长按服务端口径截断_发出去的和界面上说的一致`() {
        render(fakeApi())
        compose.onNodeWithContentDescription(PlanLogic.AVOID_FIELD_DESC)
            .performTextInput("啊".repeat(80))
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitSent()

        val body = json.parseToJsonElement(lastBody("/api/v2/plan/suggest")).jsonObject
        assertEquals(PlanLogic.AVOID_LIMIT, body["avoid"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `整棵树里不许出现效果承诺词`() {
        render(fakeApi())
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)
        // 用户反复强调过"别吹"。这条是**行为级**的：页面渲染出来的所有文字里都不许有这几类词。
        listOf("提分", "见效", "包过", "稳过", "必过", "保证").forEach { w ->
            val n = compose.onAllNodes(hasText(w, substring = true)).fetchSemanticsNodes().size
            assertTrue("页面上出现了「${w}」—— 不许承诺效果（第 $n 处）", n == 0)
        }
    }

    // ---------------------------------------------------------------- 生成

    @Test
    fun `生成建议后每条都写着为什么推荐它`() {
        render(fakeApi())
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)

        // 逐条对着**服务端真报文**核：标题在、why 也在，而且拼法就是界面上那句话
        val items = aiItems()
        assertTrue("夹具本身得有内容", items.isNotEmpty())
        items.forEach { it0 ->
            val title = it0["title"]!!.jsonPrimitive.content
            val why = it0["why"]!!.jsonPrimitive.content
            assertTrue("这条建议没显示出来：$title",
                       compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
            assertTrue("「${title}」没有写出为什么推荐它 —— 用户没法判断该不该信",
                       compose.onAllNodesWithText(PlanLogic.WHY_PREFIX + why)
                           .fetchSemanticsNodes().isNotEmpty())
        }
        // 生成只是"看看"，一个字节都不许写库
        assertTrue("生成阶段不许有写库请求：$seen", !has("/suggest/apply") && !has("/suggest/undo"))
        assertEquals("生成阶段也不许动本机库", 0, localTasks().size)
    }

    @Test
    fun `模型没调通时照实说是服务端推的保守版`() {
        render(fakeApi(suggestReply = fbBody))
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        val src = json.parseToJsonElement(fbBody).jsonObject
        val note = PlanLogic.sourceNote(
            src["source"]!!.jsonPrimitive.content, src["reason"]!!.jsonPrimitive.content)
        assertTrue("兜底建议必须有说明", note.isNotBlank())
        waitFor(note)
        // 兜底条目的标题也得显示出来（用真课程名推的：复习 XXX）
        val first = src["items"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content
        assertTrue(compose.onAllNodesWithText(first).fetchSemanticsNodes().isNotEmpty())
    }

    // ---------------------------------------------------------------- 建议里的资源（视频/课程）

    @Test
    fun `建议条目里就能看到视频课程_不用先加入清单`() {
        val res = resRows()
        assertEquals("夹具本身得有资源（video + course + 一个认不出的 kind）", 3, res.size)
        render(fakeApi(suggestReply = resBody))
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)

        // 用户原话：「我同学使用 ai 学习计划时大多数是没有用的笔记，却很少有有用的视频课程」——
        // 有用的视频必须**在挑的时候**就看得见，等到加入清单之后再去学习页里翻就已经晚了。
        res.forEach { r ->
            val t = r["title"]!!.jsonPrimitive.content
            assertTrue("建议条目里看不到资源「$t」—— 视频课程得在选的时候就露出来", onPage(t).isNotEmpty())
        }
        // 每一行都得说清它是什么：认得的按视频/课程写，认不出的写「链接」，不能把内部值（podcast）露出去
        listOf("视频", "课程", "链接").forEach { lb ->
            assertTrue("资源行少了「$lb」这类前缀", onPage(lb).isNotEmpty())
        }
    }

    @Test
    fun `点资源行会打开它的链接_且不会顺手改勾选`() {
        val first = resRows().first()
        val title = first["title"]!!.jsonPrimitive.content
        val url = first["url"]!!.jsonPrimitive.content
        render(fakeApi(suggestReply = resBody))
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)
        assertTrue("资源行没渲染出来，点不到它：$title", onPage(title).isNotEmpty())
        val pickedBefore = onPage(PlanLogic.PICKED).size

        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()

        // 点资源是「我现在就去看这个视频」，不是改这条建议的取舍 ——
        // 整行才是勾选，资源行必须是它自己那一下（嵌套点击如果被外层吃掉，用户会觉得点了个假链接）
        assertEquals("点资源行把这条建议取消勾选了", pickedBefore, onPage(PlanLogic.PICKED).size)
        assertEquals("点了资源没打开它的链接", url, launchedUrl())
    }

    @Test
    fun `老服务端不带 resources 时不出现任何资源行`() {
        // 老缓存里根本没有 resources 字段（新加的字段对旧数据必须是"没有就是没有"），
        // 界面上不能冒出「无资源」「打开」这类占位 —— 那是廉价感的主要来源。
        render(fakeApi())
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)
        listOf("打开 ↗", "链接", "视频", "课程").forEach { t ->
            assertEquals("老报文里没有资源，界面上不该出现「$t」", 0, onPage(t).size)
        }
        assertTrue("没有资源时这一页本身要照常可用", onPage(aiTitle).isNotEmpty())
    }

    @Test
    fun `资源类型前缀都是中文_认不出的写链接`() {
        // 服务端词表随时会长（book 就是后加的）；把内部值露在界面上这件事本项目被用户抓过一次
        // （步骤里的 produce 一直显示英文，直到摊开真机数据才发现），所以每种 kind 都钉死。
        assertEquals("视频", PlanLogic.resLabel("video"))
        assertEquals("课程", PlanLogic.resLabel("course"))
        assertEquals("练习", PlanLogic.resLabel("practice"))
        assertEquals("文档", PlanLogic.resLabel("doc"))
        assertEquals("书", PlanLogic.resLabel("book"))
        // 认不出的（含空值）写「链接」：说不清是什么，至少说明这是个能点开的网页
        assertEquals("链接", PlanLogic.resLabel("podcast"))
        assertEquals("链接", PlanLogic.resLabel(""))
    }

    // ---------------------------------------------------------------- 加入

    @Test
    fun `加入只送勾选的那几条_取消勾选的不进请求体`() {
        val api = fakeApi()
        render(api)
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)

        val items = aiItems()
        val keep = items[0]["title"]!!.jsonPrimitive.content
        val drop = items[1]["title"]!!.jsonPrimitive.content
        assertEquals("默认应该全勾上", items.size,
                     compose.onAllNodesWithText(PlanLogic.PICKED).fetchSemanticsNodes().size)

        compose.onNodeWithText(drop).performClick()      // 点一下整行 = 取消勾选
        assertEquals("取消一条后只剩 ${items.size - 1} 条选中", items.size - 1,
                     compose.onAllNodesWithText(PlanLogic.PICKED).fetchSemanticsNodes().size)

        compose.onNodeWithText(PlanLogic.APPLY).performClick()
        waitFor("本机清单现在共", substring = true)

        val sent = json.parseToJsonElement(lastBody("/api/v2/plan/suggest/apply"))
            .jsonObject["items"]!!.jsonArray
        assertEquals("只该送勾选的那一条", 1, sent.size)
        assertEquals(keep, sent[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertTrue("没勾的那条不许出现在请求体里：${lastBody("/api/v2/plan/suggest/apply")}",
                   !lastBody("/api/v2/plan/suggest/apply").contains(drop))
        // 原样送回去：服务端给的那批字段一个不少（这里抽查 steps 与 key 这类关键字段）
        assertTrue("items 里必须带着 steps（服务端没步骤的条目会被丢掉）",
                   sent[0].jsonObject["steps"]!!.jsonArray.isNotEmpty())
        assertTrue("items 里必须带着服务端算的 key", sent[0].jsonObject["key"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `一条都没勾就点加入_发不出请求`() {
        render(fakeApi())
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)
        aiItems().forEach { compose.onNodeWithText(it["title"]!!.jsonPrimitive.content).performClick() }
        assertEquals("此刻应该一条都没勾", 0,
                     compose.onAllNodesWithText(PlanLogic.PICKED).fetchSemanticsNodes().size)

        compose.onNodeWithText(PlanLogic.APPLY).performClick()
        compose.waitForIdle()
        assertTrue("一条没勾还能发请求 = 服务端 400，白跑一趟：$seen", !has("/suggest/apply"))
        assertEquals("也不该动本机库", 0, localTasks().size)
    }

    @Test
    fun `加入成功后本机任务清单真的被刷新了`() {
        render(fakeApi())
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)
        assertEquals("加入之前本机没有任务", 0, localTasks().size)

        compose.onNodeWithText(PlanLogic.APPLY).performClick()
        waitFor("本机清单现在共", substring = true)

        // 关键一条：不是"弹了一句成功"，而是**本机库里真出现了那条任务**
        val tasks = localTasks()
        val ai = tasks.filter { it.phase == PlanLogic.PHASE_AI }
        assertEquals("「AI 规划」那一批应该只剩刚加进来的这条", 1, ai.size)
        assertEquals(aiTitle, ai[0].title)
        assertTrue("课表原来的任务不该被这次加入弄丢（只增不改）", tasks.size > 1)
        assertTrue("点完必须真拉过一次 /plan 才能刷新本机清单：$seen", has("GET /api/v2/plan"))
    }

    @Test
    fun `加入成功后每一条都标着已加入_按钮也换样子`() {
        val items = json.parseToJsonElement(aiBody).jsonObject["items"]!!.jsonArray.size
        assertTrue("夹具得有几条建议可勾（服务端一次给 4~8 条，样本是 2 条）", items >= 2)
        render(fakeApi())
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)
        compose.onNodeWithText(PlanLogic.APPLY).performClick()
        waitFor("本机清单现在共", substring = true)

        // 逐条的可见状态：不是只弹一句「成功」，而是每条都换样子给你看（用户要的「交互感」）
        val badges = compose.onAllNodesWithText(PlanLogic.APPLIED_BADGE, substring = true)
            .fetchSemanticsNodes().size
        assertEquals("加入了几条就该有几条标着「${PlanLogic.APPLIED_BADGE}」", items, badges)
        // 还写着「加入任务清单」用户就不知道到底成没成
        assertEquals(
            "加入成功后按钮必须换样子",
            0, compose.onAllNodesWithText(PlanLogic.APPLY).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `加入成功后有个入口跳去学习清单_点了会切过去`() {
        var went = false
        render(fakeApi(), onGoTasks = { went = true })
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)
        compose.onNodeWithText(PlanLogic.APPLY).performClick()
        waitFor("本机清单现在共", substring = true)

        compose.onNodeWithText(PlanLogic.GO_TASKS, substring = true).performClick()
        assertTrue("点了「${PlanLogic.GO_TASKS}」要真的切到学习清单（回调没被调）", went)
    }

    @Test
    fun `服务端拒绝时把它的原话显示出来`() {
        // 服务端 plan_suggest_apply 的真实拒绝之一（multiuser_api.py 里那条 413）
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { m, url, _, payload ->
                see(m, url, payload)
                when (url.substringAfter("example.invalid")) {
                    "/api/v2/plan/suggest" -> HttpReply(200, aiBody)
                    "/api/v2/plan/suggest/apply" -> HttpReply(413, """{"detail":"一次最多加入 8 条"}""")
                    else -> HttpReply(404, """{"detail":"no"}""")
                }
            },
        )
        render(api)
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)
        compose.onNodeWithText(PlanLogic.APPLY).performClick()
        waitFor("一次最多加入 8 条", substring = true)
        assertTrue("被拒绝就不该去刷新本机清单：$seen", !has("GET /api/v2/plan"))
        assertEquals("被拒绝时本机库必须原样不动", 0, localTasks().size)
    }

    // ---------------------------------------------------------------- 加入成功但本机刷新失败

    @Test
    fun `加入成功但本机刷新失败_不许谎报成功并给重试入口`() {
        // 真实会发生：服务端已经落库，拉 /plan 那一步断了（弱网）。
        // 这时候说一句"加入成功"就是骗人 —— 手机上还是旧清单。
        var planBroken = true
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { m, url, _, payload ->
                see(m, url, payload)
                when (url.substringAfter("example.invalid")) {
                    "/api/v2/plan/suggest" -> HttpReply(200, aiBody)
                    "/api/v2/plan/suggest/apply" ->
                        HttpReply(200, """{"ok":true,"counts":{"tasks":1,"steps":1},"phase":"AI 规划"}""")
                    "/api/v2/plan" -> if (planBroken) HttpReply(500, """{"detail":"服务端内部错误"}""")
                                     else HttpReply(200, planBody(true))
                    else -> HttpReply(404, """{"detail":"no"}""")
                }
            },
        )
        render(api)
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)
        compose.onNodeWithText(PlanLogic.APPLY).performClick()
        waitFor("本机清单没刷新成功", substring = true)

        assertEquals("本机这一半确实没成，库里就该还是空的", 0, localTasks().size)
        assertTrue("必须给重试入口，否则用户只能干等",
                   compose.onAllNodesWithText(PlanLogic.RETRY_REFRESH).fetchSemanticsNodes().isNotEmpty())

        // 点重试：这次拉通了，本机清单补上
        planBroken = false
        compose.onNodeWithText(PlanLogic.RETRY_REFRESH).performClick()
        waitFor("本机清单现在共", substring = true)
        assertEquals("重试之后本机要真的有那条 AI 任务", 1,
                     localTasks().count { it.phase == PlanLogic.PHASE_AI })
    }

    // ---------------------------------------------------------------- 撤销

    @Test
    fun `撤销要二次确认_确认后本机那条真没了`() {
        render(fakeApi())
        compose.onNodeWithText(PlanLogic.GENERATE).performClick()
        waitFor(aiTitle)
        compose.onNodeWithText(PlanLogic.APPLY).performClick()
        waitFor("本机清单现在共", substring = true)
        assertEquals(1, localTasks().count { it.phase == PlanLogic.PHASE_AI })

        compose.onNodeWithText(PlanLogic.UNDO).performClick()
        // 点了"撤销"只弹确认层，**还不能**发请求 —— 删任务是不可逆的
        waitFor(PlanLogic.UNDO_ASK)
        assertTrue("没确认就把任务删了：$seen", !has("/suggest/undo"))
        assertTrue("确认层要讲清会删掉什么（只有「AI 规划」那批）",
                   compose.onAllNodesWithText(PlanLogic.UNDO_BODY).fetchSemanticsNodes().isNotEmpty())

        compose.onNodeWithText(PlanLogic.UNDO_YES).performClick()
        waitFor("已撤销", substring = true)
        assertTrue("确认后才该真的调撤销", has("POST /api/v2/plan/suggest/undo"))
        assertEquals("撤销后本机那条 AI 任务必须没了", 0,
                     localTasks().count { it.phase == PlanLogic.PHASE_AI })
    }

    // ---------------------------------------------------------------- 接线与禁区（源码级）

    @Test
    fun `入口在「学习」页明处_并且「我的」里不再留一份`() {
        // 2026-09-18 用户要求：「把任务标签改为学习，把 ai 学习计划放到里面去」。
        // 这一条跟着搬：入口从「我的」**搬到**「学习」标签页（任务页顶部那个卡片），
        // 断言从"我的页里有没有"改成"学习页里有没有 + 我的页里绝不能再留一份"。
        // 2026-09-18 追加①：入口又往上提了一层 —— 从"任务页顶部"提到**整页内容之上**；
        // 追加②：那一格里的内层子切换「任务 | 学习」被用户要求去掉，两半合并成一页，
        //          入口仍然是**最上面那个区块**（在承载两半的那条 LazyColumn 之外，
        //          所以往下滚到学习内容时它也不会滚走）。卡片仍住在 TasksScreen.kt，
        //          所以下面三条断言照旧；但**渲染**只剩 CampusApp 那一处
        //          （见 MergeWiringTest 的"只渲染一处"与"入口在两半内容之上"）。
        val tasks = File("src/main/java/top/ccbase/campus/ui/tasks/TasksScreen.kt").readText()
        assertTrue("「学习」页要有 AI 规划入口（用户明确说过讨厌隐藏入口）",
                   tasks.contains("Text(PlanLogic.TITLE"))
        assertTrue("入口要有说明文字，不能只有一个词", tasks.contains("PlanLogic.ENTRY_HINT"))
        assertTrue("入口要真的接上回调", tasks.contains("onOpenPlan"))
        assertTrue("入口的渲染已经不在任务页里了（两处渲染 = 页面上出现两份）",
                   !tasks.contains("ai-plan-entry"))

        val me = File("src/main/java/top/ccbase/campus/ui/me/MeScreen.kt").readText()
        assertTrue("入口是搬走的，「我的」页不许再留一份（两处入口迟早各自漂）",
                   !me.contains("onOpenPlan"))
        assertTrue("「我的」页不该再出现 AI 规划入口的文案",
                   !me.contains("AI 规划学习计划"))

        val app = File("src/main/java/top/ccbase/campus/ui/CampusApp.kt").readText()
        assertTrue("CampusApp 要把入口接到浮层状态上", app.contains("onOpenPlan = { showPlan = true }"))
        assertTrue("CampusApp 要真的渲染 PlanScreen", app.contains("PlanScreen("))
        assertTrue("入口要由合并页渲染出来（摆在两半内容之上）", app.contains("PlanEntry("))

        // 合并成一页以后，入口必须仍是**最上面的那个区块**：
        // 源码顺序上它在任务半、学习半两段内容之前，也在那条滚动列表之前
        // （合页最容易出的错就是把入口顺手塞进列表里 —— 那样往下滚就没了）。
        val entryAt = app.indexOf("PlanEntry(")
        assertTrue("CampusApp 里找不到入口渲染", entryAt >= 0)
        listOf("tasksContent(", "LazyColumn(").forEach { block ->
            val at = app.indexOf(block)
            assertTrue("CampusApp 里找不到 $block（合并页的哪一段丢了？）", at >= 0)
            assertTrue(
                "入口跑到 $block 之后了（entry=$entryAt, $block=$at）—— 往下滚就看不见它",
                entryAt < at,
            )
        }
    }

    @Test
    fun `确认层不许用 Dialog_必须用普通浮层`() {
        // Robolectric 里 Dialog 是独立窗口，测试框架看不见 —— 本项目踩过这个坑
        val src = File("src/main/java/top/ccbase/campus/ui/tasks/PlanScreen.kt").readText()
        assertTrue("不许用 AlertDialog", !src.contains("AlertDialog("))
        assertTrue("不许用 Dialog(", !src.contains("Dialog("))
    }
}
