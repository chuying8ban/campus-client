package top.ccbase.campus.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「App 自己发现课表变了」用的两块接口契约。
 *
 * 夹具 `plan_*.json` 的 `timetable` 对象是拿服务端 `timetable_sync.py` 的
 * `status()` **真跑出来的**（不是手写的漂亮数据）：
 *   - `plan_with_timetable.json`    同步过：status=ok、version=16 位指纹
 *   - `plan_timetable_none.json`    新用户还没同步过：status=none、version="" ← 必须当"没有版本号"
 *   - `plan_no_timetable.json`      老服务端：整个键都没有 ← 必须安静退回，不许报错
 *   - `plan_timetable_bad_shape.json` 形状被改坏（version 是数字、courses 是字符串、
 *     timetable 是数组）← 一个可选字段不许把 `/plan` 整条路打挂
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class TimetableApiTest {

    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("测试样本缺失：src/test/resources/$name")

    private class Seen {
        val rows = mutableListOf<String>()
        var auth: String? = null
        var body = ""
    }

    private fun api(body: String, code: Int = 200, seen: Seen) = CampusApi(
        base = "https://example.invalid",
        transport = Transport { m, u, h, b ->
            seen.rows += "$m ${u.substringAfter("example.invalid")}"
            seen.auth = h["Authorization"]
            seen.body = b.orEmpty()
            HttpReply(code, body)
        },
    )

    @Test
    fun `plan 里能读出课表版本号`() = runBlocking {
        val seen = Seen()
        val r = api(sample("plan_with_timetable.json"), seen = seen).planBundle("tok-1")
        assertTrue("应当解析成功：$r", r is ApiResult.Ok)
        val b = (r as ApiResult.Ok).value
        assertEquals("Bearer tok-1", seen.auth)
        assertEquals("GET /api/v2/plan", seen.rows.single())

        val tt = b.timetable
        assertNotNull("服务端给了 timetable 就必须读出来（读不出来=App 永远不知道课表变了）", tt)
        assertEquals("9f3c1a2b4d5e6f70", tt!!.version)
        assertTrue("版本号可用", tt.hasVersion)
        assertEquals("ok", tt.status)
        assertEquals("2026-09-18T06:21:03", tt.updatedAt)
        assertEquals(11, tt.courses)
        // 计划本体照旧解析（这条不能因为多读了字段而退化）
        assertTrue("计划本体也要在：${b.seed.courses.size} 门课", b.seed.courses.isNotEmpty())
    }

    @Test
    fun `新用户还没同步过课表_版本号是空串_不该被当成有效版本`() = runBlocking {
        val r = api(sample("plan_timetable_none.json"), seen = Seen()).planBundle("t") as ApiResult.Ok
        val tt = r.value.timetable
        assertNotNull(tt)
        assertEquals("status=none", "none", tt!!.status)
        assertEquals("", tt.version)
        assertTrue("空版本号 = 不能拿去比对（否则每次开 App 都会白拉一遍或永远不拉）", !tt.hasVersion)
        assertTrue("也不是\"上次核对失败\"", !tt.failed)
    }

    @Test
    fun `老服务端没有这个键_不许报错`() = runBlocking {
        val r = api(sample("plan_no_timetable.json"), seen = Seen()).planBundle("tok")
        assertTrue("老服务端必须照常能用（新客户端不能把老服务端打挂）：$r", r is ApiResult.Ok)
        val b = (r as ApiResult.Ok).value
        assertNull("没有这个键 → null，调用方安静退回旧行为", b.timetable)
        assertTrue(b.seed.courses.isNotEmpty())
    }

    @Test
    fun `字段类型被改坏_也不许把 plan 打挂`() = runBlocking {
        val r = api(sample("plan_timetable_bad_shape.json"), seen = Seen()).planBundle("tok")
        assertTrue("一个新增的可选字段形状不对，不能让所有人开 App 就报「计划数据无法解析」：$r",
            r is ApiResult.Ok)
        // 形状读不出来 → 当"没有版本号"处理，绝不崩
        val tt = (r as ApiResult.Ok).value.timetable
        assertTrue("读不出干净的形状就该是 null 或空版本号：$tt", tt == null || !tt.hasVersion)
        assertTrue("计划本体照样要能读出来", r.value.seed.courses.isNotEmpty())
    }

    @Test
    fun `让服务器重读课表_打的是服务端那条路_并且带令牌`() = runBlocking {
        val seen = Seen()
        val r = api("""{"ok":true,"results":[{"uid":7,"changed":2}],"changed":2,"no_creds":[]}""",
            seen = seen).timetableSync("tok-9")
        assertTrue("应当成功：$r", r is ApiResult.Ok)
        assertEquals("POST /api/v2/timetable/sync", seen.rows.single())
        assertEquals("重读课表也要令牌（服务端按 token 判断刷谁）", "Bearer tok-9", seen.auth)
        assertEquals("请求体是空对象：普通用户只能刷自己，uid 由服务端从 token 取", "{}", seen.body)

        val res = (r as ApiResult.Ok).value
        assertTrue(res.ok)
        assertEquals(2, res.changed)
        assertEquals("no_creds 是下划线命名，解析错了就会误判成\"没有密码\"", emptyList<Int>(), res.noCreds)
    }

    @Test
    fun `服务端没存密码时_要能读出 no_creds`() = runBlocking {
        val r = api("""{"ok":false,"results":[],"changed":0,"no_creds":[7]}""",
            seen = Seen()).timetableSync("t") as ApiResult.Ok
        assertEquals(listOf(7), r.value.noCreds)
        assertTrue("ok=false 要能读出来（界面据此说清原因）", !r.value.ok)
    }

    @Test
    fun `老服务端没有重读接口_404 要保留状态码`() = runBlocking {
        val seen = Seen()
        val r = api("""{"detail":"Not Found"}""", code = 404, seen = seen).timetableSync("t")
        assertTrue(r is ApiResult.Err)
        assertEquals("UI 靠 404 区分「服务器不支持重读」和「网络不通」，不能丢状态码",
            404, (r as ApiResult.Err).code)
    }

    @Test
    fun `重读接口 401 也要保留状态码`() = runBlocking {
        val r = api("""{"detail":"登录已过期，请重新登录"}""", code = 401, seen = Seen()).timetableSync("t")
        assertEquals(401, (r as ApiResult.Err).code)
        assertTrue("服务端原话要带出来", r.message.contains("登录已过期"))
    }
}
