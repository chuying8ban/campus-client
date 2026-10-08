package top.ccbase.campus.net

import top.ccbase.campus.net.StudentError

import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 瞬时故障重试的验收。
 *
 * 为什么只重试 GET：手机在移动网络上连到一半被 RST 是常见的一次性抖动（校园网/5G 切换、
 * NAT 表被清、运营商中间盒），重试一次就好；但 POST 可能**已经落到服务端**
 * （加监控、提交选课），重试等于拿用户的操作赌运气。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetryTest {
    private fun api(t: Transport) = CampusApi(base = "https://api.example.com", transport = t)

    @Test
    fun `GET 被重置一次后重试要能成功`() = runBlocking {
        var calls = 0
        val r = api(
            Transport { method, _, _, _ ->
                calls++
                if (calls == 1) throw SocketException("Connection reset")
                assertEquals("GET", method)
                HttpReply(200, "{\"user\":{\"uid\":1,\"student_id\":\"guest-test\",\"name\":\"test\"}}")
            },
        ).me("tok")
        assertEquals("应该正好试了两次", 2, calls)
        assertTrue("第二次通了就该当成功，不该再报红：$r", r is ApiResult.Ok)
    }

    @Test
    fun `GET 连续两次被重置还是要如实报错`() = runBlocking {
        var calls = 0
        val r = api(
            Transport { _, _, _, _ ->
                calls++
                throw SocketException("Connection reset")
            },
        ).me("tok")
        assertEquals("只重试一次，不能无脑循环", 2, calls)
        assertTrue(r is ApiResult.Err)
        // 界面只给一句话；"Connection reset" 这种真因只进 logcat（2026-09-24 定稿）
        assertEquals(StudentError.TEXT, (r as ApiResult.Err).message)
    }

    @Test
    fun `POST 被重置不许偷偷重试`() = runBlocking {
        var calls = 0
        val r = api(
            Transport { _, _, _, _ ->
                calls++
                throw SocketException("Connection reset")
            },
        ).planUndo("tok")
        assertEquals("提交类请求一次就是一次", 1, calls)
        assertTrue(r is ApiResult.Err)
    }

    // ------------------------------------------------------------------
    // 慢接口（AI 生成建议）的读超时 + "超时重试一次"
    //
    // 事故背景（2026-09-20 用户截图）：
    //   `生成失败：网络异常：IOException ([读响应] SocketTimeoutException: Read timed out)`
    // 真因不是"网络异常"，是**客户端读超时比服务端算得还快**：实测服务端一次生成
    // 18.5 秒（模型最长 60 秒 + 链接体检 6 秒），而这里默认只有 20 秒 —— 手机侧再加
    // 一个往返，必然被自己掐死。
    //
    // 下面三条钉住两件事：慢接口要显式放宽并重试一次；**普通接口不许**跟着重试。
    // ------------------------------------------------------------------

    /** 记录"这次给了我多少毫秒"的假传输层；`failFirstTimes` 用来模拟读超时 */
    private class TimeoutProbe(
        private val payload: String,
        private val failFirstTimes: Int = 0,
    ) : Transport {
        val seenMs = mutableListOf<Int>()
        var calls = 0

        override fun call(
            method: String, url: String, headers: Map<String, String>, body: String?,
        ): HttpReply = error("这次请求必须显式带读超时，不该走不带超时的那条")

        override fun callFor(
            method: String, url: String, headers: Map<String, String>, body: String?, readMs: Int,
        ): HttpReply {
            calls++
            seenMs += readMs
            if (calls <= failFirstTimes) throw SocketTimeoutException("Read timed out")
            return HttpReply(200, payload)
        }
    }

    private val aiBody: String
        get() = javaClass.getResourceAsStream("/plan_suggest_ai.json")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("测试样本缺失：src/test/resources/plan_suggest_ai.json")

    @Test
    fun `AI 生成建议要按慢接口的超时走，不再用默认的 20 秒`() = runBlocking {
        val probe = TimeoutProbe(aiBody)
        val r = CampusApi(base = "https://example.invalid", transport = probe)
            .planSuggest("t", PlanPrefs())
        assertTrue("带真实响应应该成功：$r", r is ApiResult.Ok)
        assertEquals(
            "慢接口必须显式放宽读超时（服务端实测 18.5 秒，20 秒必被自己掐死）",
            listOf(SLOW_READ_MS), probe.seenMs,
        )
    }

    @Test
    fun `AI 生成建议读超时后会重试一次`() = runBlocking {
        val probe = TimeoutProbe(aiBody, failFirstTimes = 1)
        val r = CampusApi(base = "https://example.invalid", transport = probe)
            .planSuggest("t", PlanPrefs())
        assertEquals("第一次超时 → 再试一次", 2, probe.calls)
        assertTrue("第二次拿到了就当成功，不该报红：$r", r is ApiResult.Ok)
        assertEquals("两次都要用慢接口的超时", listOf(SLOW_READ_MS, SLOW_READ_MS), probe.seenMs)
    }

    @Test
    fun `普通接口读超时不许跟着重试`() = runBlocking {
        val probe = TimeoutProbe("{}", failFirstTimes = 9)
        val r = CampusApi(base = "https://example.invalid", transport = probe).me("t")
        assertEquals("读超时只有慢接口才重试，普通接口一次就是一次", 1, probe.calls)
        assertTrue(r is ApiResult.Err)
        assertEquals("普通接口仍用默认超时", listOf(DEFAULT_READ_MS), probe.seenMs)
    }
}
