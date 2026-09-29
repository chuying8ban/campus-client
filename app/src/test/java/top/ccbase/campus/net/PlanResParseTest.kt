package top.ccbase.campus.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建议条目新挂的 `resources`（视频 / 课程 / 练习 / 文档 / 书）。
 *
 * 为什么单开一条：这是**服务端先改、App 后跟**的字段，两边不同步是常态 ——
 *   - 服务端已经给了 resources，App 得解析出来并显示（否则用户还是只看得到"没用的笔记"）；
 *   - 老服务端 / 老缓存里根本没有这个字段，App 必须当成"就是没有"，不许崩、也不许显示占位。
 * 这两个方向都得钉住，所以夹具用服务端跑出来的真报文（`src/test/resources/plan_suggest_*.json`），
 * 不手写"漂亮数据"。
 */
class PlanResParseTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("测试样本缺失：src/test/resources/$name")

    /** 与生产同一条解析路径：外层取 items，再逐条 decode 成 PlanItem */
    private fun items(body: String): List<PlanItem> =
        json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray
            .map { json.decodeFromJsonElement(PlanItem.serializer(), it) }

    private fun item(body: String, index: Int): PlanItem = items(body)[index]

    /** 服务端 ai_plan.py 新形状：第一条挂 video + course，第二条挂一个认不出的 kind */
    private val resBody get() = sample("plan_suggest_res.json")

    // ---------------------------------------------------------------- 有资源

    @Test
    fun `带 resources 的条目要解析出 kind title url`() {
        val res = item(resBody, 0).resources
        assertEquals("第一条挂了 2 个资源", 2, res.size)

        assertEquals("video", res[0].kind)
        assertEquals("算法入门 第 1 讲 复杂度与数组", res[0].title)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", res[0].url)
        // why / source / http / embed 都要跟着过来：界面上要靠 why 说清"为什么给这个"，
        // 探测状态丢了的话以后再想按存活情况排序就没数据可用
        assertEquals("你偏好看视频，这一讲正好从零讲复杂度", res[0].why)
        assertEquals("bilibili", res[0].source)
        assertEquals(200, res[0].http)
        assertEquals(0, res[0].embed)

        assertEquals("course", res[1].kind)
        assertEquals("数据结构与算法 公开课", res[1].title)
        assertEquals("https://www.icourse163.org/course/XYZ-1001", res[1].url)
    }

    @Test
    fun `缺省的探测字段不影响解析`() {
        // 第二条资源没给 http：老形状里就是没有这个字段，必须当 null，而不是解析失败
        val res = item(resBody, 1).resources.single()
        assertEquals("podcast", res.kind)
        assertEquals("英语听力电台", res.title)
        assertNull("没探过就是 null（不是 0，0 会被当成「探测失败」）", res.http)
        assertEquals(0, res.embed)
        assertEquals("", res.why)
    }

    @Test
    fun `认不出的 kind 原样留着_由界面决定怎么写`() {
        // 服务端以后加新类型（book 就是这么加进来的）不能让整条建议解析失败：
        // 这里只负责把原始值带过来，落地成哪个中文词是界面的事。
        val res = item(resBody, 1).resources.single()
        assertEquals("podcast", res.kind)
        assertTrue("url 一定是 http(s)：界面上要直接能点开", res.url.startsWith("https://"))
    }

    @Test
    fun `资源里多出来的字段不会把整条建议解析崩`() {
        // 服务端加字段是常态（ignoreUnknownKeys 就是为这个），资源对象也一样
        val body = """
            {"ok":true,"source":"ai","items":[{"title":"读文档","steps":["看一节"],
             "resources":[{"kind":"doc","title":"官方文档","url":"https://developer.android.com/",
                           "probe_ms":12,"alive":true}]}]}
        """.trimIndent()
        val res = item(body, 0).resources.single()
        assertEquals("doc", res.kind)
        assertEquals("官方文档", res.title)
        assertEquals("https://developer.android.com/", res.url)
    }

    // ---------------------------------------------------------------- 没资源（老服务端 / 老缓存）

    @Test
    fun `不带 resources 的老报文解析成空列表_不报错`() {
        // plan_suggest_ai.json 是服务端**加 resources 之前**跑出来的真形状
        val its = items(sample("plan_suggest_ai.json"))
        assertEquals(2, its.size)
        its.forEach {
            assertTrue("老报文没有 resources，就该是空列表：${it.title}", it.resources.isEmpty())
            assertTrue("老报文其余字段照旧", it.title.isNotBlank() && it.steps.isNotEmpty())
        }
    }

    @Test
    fun `resources 是空数组也当没有`() {
        val body = """{"ok":true,"items":[{"title":"背单词","resources":[],"steps":["每天 20 个"]}]}"""
        val it = item(body, 0)
        assertEquals("空数组和缺字段对界面是一回事：不显示这一段", emptyList<PlanRes>(), it.resources)
    }

    // ---------------------------------------------------------------- 走接口那条路

    @Test
    fun `走接口的解析路径也拿得到资源`() = runBlocking {
        // 直接从 JSON decode 只能证明类写得对；用户看到的资源是从接口这条路过来的，
        // 所以这里把 CampusApi 也跑一遍（transport 换成假的，不出网）。
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { _, _, _, _ -> HttpReply(200, resBody) },
        )
        val r = api.planSuggest("tok-plan", PlanPrefs())
        assertTrue("接口层应该解析成功：$r", r is ApiResult.Ok)
        val s = (r as ApiResult.Ok).value
        assertEquals(2, s.items.size)
        assertEquals(2, s.items[0].resources.size)
        assertEquals("算法入门 第 1 讲 复杂度与数组", s.items[0].resources[0].title)

        // 回写服务端送的是 raw 里那份原样 JSON：raw 必须也还带着 resources，
        // 否则"加入清单"会把资源丢掉（服务端 apply 时按它落库）
        val rawRes: JsonElement = s.raw[0].jsonObject["resources"]!!
        assertEquals(2, rawRes.jsonArray.size)
        assertEquals("video", rawRes.jsonArray[0].jsonObject["kind"]!!.jsonPrimitive.content)
    }

    // ---------------------------------------------------------------- 坏数据不许带走整页

    @Test
    fun `资源缺 title 和 url 也必须能解析_不能把整页带崩`() {
        // 服务端是另一条线在改，字段哪天少了谁也说不准。这里钉的是最坏情况：
        // 一条资源缺字段 → 整份响应解析失败 → 同学看到"建议内容无法解析"，
        // 连本来能用的条目都没了。所以这两个字段必须有默认值，空的行由界面自己跳过。
        val body = """
            {"items":[{"title":"复习数学分析（I）","steps":["看课件"],
              "resources":[{"kind":"video"},
                           {"kind":"doc","title":"讲义","url":"https://example.org/a"}]}]}
        """.trimIndent()
        val it = item(body, 0)
        assertEquals("条目本身必须照样解析出来", "复习数学分析（I）", it.title)
        assertEquals("两个资源都收下（空的由界面跳过）", 2, it.resources.size)
        assertEquals("", it.resources[0].title)
        assertEquals("", it.resources[0].url)
        assertEquals("video", it.resources[0].kind)
        assertEquals("https://example.org/a", it.resources[1].url)
    }
}
