package top.ccbase.campus.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「AI 规划学习计划」三条接口的**契约测试**（纯网络层，不挂界面）。
 *
 * 夹具 `plan_suggest_ai.json` / `plan_suggest_fallback.json` 不是手写的漂亮数据：
 * 是拿服务端那份 `study-app/ai_plan.py` **直接跑出来的**
 * （`normalize_items` / `fallback_items` + 路由的响应外壳），
 * 所以字段名、`weekday: null`、`deliverable: ""` 这些真实形状都在里面。
 *
 * 这里钉的四件事，每一条都对应一次真实会发生的静默故障：
 *  1. 入参字段名（focus/hours/style/level）—— 改错了服务端只会当默认值处理，界面看着"成了"；
 *  2. 解析真实报文（含 null 字段）；
 *  3. **把服务端给的条目原样送回去**（重新用本地 DTO 编码 = 悄悄丢字段）；
 *  4. 服务端拒绝时把**它那句话**带给用户，401 不许被当成"网络不通"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class PlanApiTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("测试样本缺失：src/test/resources/$name")

    /** 服务端 `normalize_items()` 真跑出来的响应（source=ai） */
    private val aiBody get() = sample("plan_suggest_ai.json")

    /** 模型没调通时服务端的兜底响应（source=fallback，条目按真实课程名推出来） */
    private val fbBody get() = sample("plan_suggest_fallback.json")

    private class Seen {
        val rows = mutableListOf<String>()
        var lastAuth: String? = null
        var lastBody = ""
        var lastPath = ""
        fun has(text: String) = rows.any { it.contains(text) }
    }

    private fun api(reply: (String, String) -> HttpReply, seen: Seen) = CampusApi(
        base = "https://example.invalid",
        transport = Transport { method, url, headers, payload ->
            val path = url.substringAfter("example.invalid")
            seen.rows += "$method $path ${payload.orEmpty()}"
            seen.lastAuth = headers["Authorization"]
            seen.lastBody = payload.orEmpty()
            seen.lastPath = path
            reply(path, payload.orEmpty())
        },
    )

    private val ok = { path: String, body: String -> HttpReply(200, body) }
    private val routes = mapOf(
        "/api/v2/plan/suggest" to aiBody,
        "/api/v2/plan/suggest/apply" to """{"ok":true,"counts":{"tasks":2,"steps":3},"phase":"AI 规划"}""",
        "/api/v2/plan/suggest/undo" to """{"ok":true,"counts":{"tasks":2,"steps":3,"resources":0}}""",
    )

    // ---------------------------------------------------------------- 出建议

    @Test
    fun `出建议的入参字段名必须和服务端一致`() = runBlocking {
        val seen = Seen()
        val r = api({ p, b -> HttpReply(200, routes[p] ?: "{}") }, seen)
            .planSuggest("tok-1", PlanPrefs(focus = listOf("编程"), hours = 8,
                                            style = listOf("做项目"), level = "入门"))
        assertTrue("应当成功：$r", r is ApiResult.Ok)
        assertEquals("POST", seen.rows.first().substringBefore(" "))
        assertEquals("/api/v2/plan/suggest", seen.lastPath)
        assertEquals("Bearer tok-1", seen.lastAuth)

        // 键集合**完全相等**：多一个字段服务端会忽略（无害），少一个名字写错就变成默认值 ——
        // 后者在界面上完全看不出来（"我明明选了编程，它却按没选来推"），所以必须钉死。
        val body = json.parseToJsonElement(seen.lastBody).jsonObject
        assertEquals(setOf("focus", "hours", "style", "level"), body.keys)
        assertEquals(8, body["hours"]!!.jsonPrimitive.int)
        assertEquals("入门", body["level"]!!.jsonPrimitive.content)
    }

    @Test
    fun `新加的分组用服务端字段名发出去_一个都不许改名`() = runBlocking {
        // 名字来自服务端 multiuser_api.SuggestIn：
        // goals / periods / days_per_week / subjects[{name,level}] / pace / avoid。
        // 猜一个近义词（days、weeks、subjects_level…）服务端会**静默忽略**，
        // 界面上完全看不出来（"我明明填了 4 天，它当没填"），所以键集合必须完全相等。
        val seen = Seen()
        api({ p, _ -> HttpReply(200, routes[p] ?: "{}") }, seen).planSuggest(
            "tok-1",
            PlanPrefs(
                focus = listOf("编程"), hours = 12, style = listOf("刷题"), level = "入门",
                goals = listOf("补弱"), periods = listOf("晚"), daysPerWeek = 4,
                subjects = listOf(PlanSubject("数学分析（I）", 1), PlanSubject("通用英语", 5)),
                pace = "冲刺式", avoid = "周日 别排",
            ),
        )

        val body = json.parseToJsonElement(seen.lastBody).jsonObject
        assertEquals(
            setOf(
                "focus", "hours", "style", "level",
                "goals", "periods", "days_per_week", "subjects", "pace", "avoid",
            ),
            body.keys,
        )
        assertEquals(4, body["days_per_week"]!!.jsonPrimitive.int)

        val sub = body["subjects"]!!.jsonArray
        assertEquals(2, sub.size)
        assertEquals("数学分析（I）", sub[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1, sub[0].jsonObject["level"]!!.jsonPrimitive.int)
        assertEquals("周日 别排", body["avoid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `没填的新项一个都不发_老服务端收到的还是那四项`() = runBlocking {
        // 向后兼容：老服务端（不认这些新字段）收到的报文必须与升级前**逐字节同形**。
        // 靠的是编码器 explicitNulls = false —— null 整个省掉，而不是发一个 null 过去。
        val seen = Seen()
        api({ p, _ -> HttpReply(200, routes[p] ?: "{}") }, seen).planSuggest("t", PlanPrefs())

        val body = json.parseToJsonElement(seen.lastBody).jsonObject
        assertEquals(setOf("focus", "hours", "style", "level"), body.keys)
    }

    @Test
    fun `选了默认值也照样发出去_不许被省掉`() = runBlocking {
        // 这一条是上一版真踩到的坑：kotlinx 默认 encodeDefaults=false，
        // 用户明确选了「入门」/「每周 5 小时」（正好等于 DTO 默认值）时，这两个键
        // 会被**整个省掉**，服务端只好用它自己的默认值兜 —— 现在两边恰好一致所以看不出来，
        // 服务端哪天改默认值，用户的选择就被悄悄篡改了。
        val seen = Seen()
        api({ p, _ -> HttpReply(200, routes[p] ?: "{}") }, seen).planSuggest("t", PlanPrefs())
        val body = json.parseToJsonElement(seen.lastBody).jsonObject
        assertEquals("用户选的（哪怕是默认值）必须原样发出去", setOf("focus", "hours", "style", "level"), body.keys)
        assertEquals(5, body["hours"]!!.jsonPrimitive.int)
        assertEquals("入门", body["level"]!!.jsonPrimitive.content)
    }

    @Test
    fun `真实响应能解析_周几为空也不炸`() = runBlocking {
        val seen = Seen()
        val r = api({ p, _ -> HttpReply(200, routes[p] ?: "{}") }, seen)
            .planSuggest("t", PlanPrefs())
        assertTrue(r is ApiResult.Ok)
        val s = (r as ApiResult.Ok).value
        assertEquals(2, s.items.size)
        assertEquals("ai", s.source)
        assertEquals("AI 规划", s.phase)
        assertEquals("刷算法题", s.items[0].title)
        assertEquals(3, s.items[0].weekday)
        // 真实报文里第二条第 weekday 是 null —— 这就是"契约里允许空"的那一半
        assertEquals(null, s.items[1].weekday)
        assertTrue("解析出来的条数和原样 JSON 必须一一对应", s.raw.size == s.items.size)
        assertTrue("服务端给的 key 要留着", s.items[0].key.isNotBlank())
    }

    @Test
    fun `每条建议都要带一句为什么`() = runBlocking {
        val seen = Seen()
        val r = api({ p, _ -> HttpReply(200, routes[p] ?: "{}") }, seen)
            .planSuggest("t", PlanPrefs())
        val s = (r as ApiResult.Ok).value
        // 服务端 normalize_items 里 why 允许为空 —— 这条钉的是"真报文里此刻确实都带着理由"
        s.items.forEach { assertTrue("条目「${it.title}」没有 why，界面就没法说清为什么推荐", it.why.isNotBlank()) }
    }

    // ---------------------------------------------------------------- 加入任务清单

    @Test
    fun `拿回来的条目要原样送回去_一个字段都不许丢`() = runBlocking {
        val seen = Seen()
        val a = api({ p, _ -> HttpReply(200, routes[p] ?: "{}") }, seen).planSuggest("t", PlanPrefs())
        val s = (a as ApiResult.Ok).value

        val seen2 = Seen()
        val r = api({ p, _ -> HttpReply(200, routes[p] ?: "{}") }, seen2)
            .planApply("t", s.raw)
        assertTrue("应当成功：$r", r is ApiResult.Ok)
        assertEquals("/api/v2/plan/suggest/apply", seen2.lastPath)
        assertEquals("Bearer t", seen2.lastAuth)

        val sent = json.parseToJsonElement(seen2.lastBody).jsonObject["items"]!!
        // 逐字段相等：JsonElement 完全相等，不是"看着差不多"。
        // 这条能咬住"客户端 DTO 少接一个字段 → 静默丢"的整类问题。
        assertEquals(s.raw.size, sent.jsonArray.size)
        assertEquals(s.raw, sent.jsonArray.toList())
    }

    @Test
    fun `服务端以后加字段_原样送回去也不会丢`() = runBlocking {
        // 在真夹具上塞一个客户端还不认识的字段，模拟"服务端先发版、App 后跟上"
        val root = json.parseToJsonElement(aiBody).jsonObject
        val patched = JsonObject(
            root + ("items" to kotlinx.serialization.json.JsonArray(
                root["items"]!!.jsonArray.mapIndexed { i, el ->
                    if (i == 0) JsonObject(el.jsonObject + ("future_field" to
                            kotlinx.serialization.json.JsonPrimitive("还在用"))) else el
                }
            ))
        ).toString()

        val seen = Seen()
        val s = (api({ _, _ -> HttpReply(200, patched) }, seen).planSuggest("t", PlanPrefs()) as ApiResult.Ok).value
        assertEquals("新字段不该让解析失败", 2, s.items.size)

        val seen2 = Seen()
        api({ p, _ -> HttpReply(200, routes[p] ?: "{}") }, seen2).planApply("t", s.raw)
        assertTrue("服务端新加的字段不能被客户端吃掉：${seen2.lastBody}",
                   seen2.lastBody.contains("future_field"))
    }

    @Test
    fun `加入只送勾选的那几条`() = runBlocking {
        val seen = Seen()
        val s = (api({ p, _ -> HttpReply(200, routes[p] ?: "{}") }, seen).planSuggest("t", PlanPrefs())
                 as ApiResult.Ok).value

        val seen2 = Seen()
        api({ p, _ -> HttpReply(200, routes[p] ?: "{}") }, seen2).planApply("t", listOf(s.raw[1]))
        val sent = json.parseToJsonElement(seen2.lastBody).jsonObject["items"]!!.jsonArray
        assertEquals(1, sent.size)
        assertEquals("背单词", sent[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertTrue("没勾的那条不许出现在请求体里", !seen2.lastBody.contains("刷算法题"))
    }

    @Test
    fun `加入返回的条数以服务端为准`() = runBlocking {
        val seen = Seen()
        val r = api({ p, _ -> HttpReply(200, routes[p] ?: "{}") }, seen)
            .planApply("t", emptyList())
        assertTrue(r is ApiResult.Ok)
        assertEquals(2, (r as ApiResult.Ok).value.tasks)
        assertEquals(3, r.value.steps)
    }

    // ---------------------------------------------------------------- 撤销

    @Test
    fun `撤销是独立的一条路_计数带 resources`() = runBlocking {
        val seen = Seen()
        val r = api({ p, _ -> HttpReply(200, routes[p] ?: "{}") }, seen).planUndo("tok-z")
        assertTrue("应当成功：$r", r is ApiResult.Ok)
        assertTrue(seen.has("POST /api/v2/plan/suggest/undo"))
        assertEquals("Bearer tok-z", seen.lastAuth)
        assertEquals(2, (r as ApiResult.Ok).value.tasks)
        assertEquals(3, r.value.steps)
        assertEquals(0, r.value.resources)
    }

    // ---------------------------------------------------------------- 出错

    @Test
    fun `服务端拒绝时把服务端那句话带给用户`() = runBlocking {
        val seen = Seen()
        val r = api({ _, _ ->
            HttpReply(413, """{"detail":"一次最多加入 8 条"}""")
        }, seen).planApply("t", emptyList())
        assertTrue(r is ApiResult.Err)
        val msg = (r as ApiResult.Err).message
        assertTrue("要原样带出服务端的话：$msg", msg.contains("一次最多加入 8 条"))
    }

    @Test
    fun `没令牌时服务端会401_不能被当成网络故障`() = runBlocking {
        val seen = Seen()
        val r = api({ _, _ -> HttpReply(401, """{"detail":"未登录或登录已失效"}""") }, seen)
            .planSuggest("bad", PlanPrefs())
        assertTrue(r is ApiResult.Err)
        assertEquals("401 必须保留原始状态码（UI 靠它区分「要重新登录」和「网络不通」）",
                     401, (r as ApiResult.Err).code)
    }

    @Test
    fun `兜底建议照实标成 fallback_不冒充模型给的`() = runBlocking {
        val seen = Seen()
        val r = api({ _, _ -> HttpReply(200, fbBody) }, seen).planSuggest("t", PlanPrefs())
        val s = (r as ApiResult.Ok).value
        assertEquals("fallback", s.source)
        assertTrue("服务端给的失败原因要留着，界面要照实说", s.reason.isNotBlank())
        assertTrue("兜底条目也得带 why", s.items.all { it.why.isNotBlank() })
        assertTrue("兜底也是有步骤的真任务，不是空壳", s.items.all { it.steps.isNotEmpty() })
    }
}
