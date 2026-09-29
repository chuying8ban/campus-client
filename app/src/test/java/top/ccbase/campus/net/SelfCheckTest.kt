package top.ccbase.campus.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络自检的验收。
 *
 * 它存在的唯一理由是**把"连不上"拆开**，所以每个用例都在盯同一件事：
 * 断在不同层时，结论必须不同、而且要说人话（用户在外面只有一张截图能发给我）。
 *
 * 另有「变体探测」一组：真机实测出现"TCP 连得上、HTTPS 一握手就被重置、
 * 而服务端日志里什么都没有"—— 拦截在链路上。换握手参数逐个试，才知道放行哪一种。
 */
class SelfCheckTest {

    private val base = "https://api.example.com"

    /** 真夹具：线上那条 /plan 的原样响应（87KB，含 courses/slots/tasks/milestones） */
    private val planJson: String by lazy {
        javaClass.getResourceAsStream("/live_plan_20260917.json")!!.readBytes().decodeToString()
    }

    /** 记录自检实际打过的地址 —— 用来钉死"不许再打域名根的 /healthz" */
    private val seenUrls = mutableListOf<String>()

    /** 造一个只会照剧本回答的传输层 */
    private fun transport(
        doh: String? = "203.0.113.7",
        dohCode: Int = 200,
        health: Pair<Int, String> = 200 to "{\"ok\":true}",
        me: Pair<Int, String> = 200 to "{\"name\":\"测试用户\"}",
        throwOn: String? = null,
    ) = Transport { _, url, _, _ ->
        seenUrls += url
        when {
            url.contains("/resolve") -> HttpReply(dohCode, doh?.let { "{\"Answer\":[{\"data\":\"$it\"}]}" } ?: "{}")
            // 注意这里必须精确匹配 `/api/v2/healthz`：打域名根那条是另一个服务的数字，
            // 自检显示它曾让用户以为"我的课程丢了"，所以下面有专门的断言钉住它。
            url.endsWith("/api/v2/healthz") -> {
                if (throwOn == "health") throw java.net.SocketException("Connection reset")
                HttpReply(health.first, health.second)
            }
            url.contains("/api/v2/plan") -> HttpReply(200, planJson)
            url.contains("/api/v2/me") -> HttpReply(me.first, me.second)
            else -> HttpReply(404, "{}")
        }
    }

    /**
     * 跑一次自检。**握手变体一律用假实现**：
     * 真握手会去连真服务器，单测不该依赖网络，也不该因为链路上真被拦而变红。
     */
    private suspend fun go(
        t: Transport,
        token: String? = "tok",
        resolve: (String) -> List<String> = { listOf("203.0.113.7") },
        tcp: (String, Int) -> String = { _, _ -> "" },
        handshake: (SelfCheck.Variant, String) -> String = { _, _ ->
            "握手失败：SocketException（Connection reset）"
        },
    ): List<top.ccbase.campus.net.CheckStep> {
        seenUrls.clear()          // 每轮清空：记录本轮到底打过哪些地址
        return SelfCheck.run(base, t, token, resolve = resolve, tcp = tcp, handshake = handshake)
    }

    @Test
    fun `一切正常时四层全绿`() = runBlocking {
        val steps = go(transport())
        // 解析 + TCP + 健康 + me + 计划 + 3 个变体 + 不带 SNI + 结论 = 10
        assertEquals("四层结论 + 我的计划 + 变体探测 + 结论", 10, steps.size)
        assertTrue("第一步是解析：" + steps[0].detail, steps[0].ok)
        assertTrue(steps[1].ok)
        assertTrue("健康检查：${steps[2].detail}", steps[2].ok)
        assertTrue("带令牌接口：${steps[3].detail}", steps[3].ok)
        assertTrue("空串=连上了，得显示成人话", steps[1].detail.contains("连上了"))
        assertEquals("健康检查必须打 v2 自己的", "GET /api/v2/healthz", steps[2].name)
        assertEquals("我的计划那步", "GET /api/v2/plan", steps[4].name)
        assertTrue("计划要有真实条数，不能只说 200：${steps[4].detail}", steps[4].ok)
        assertTrue("条数得对得上夹具（11 门课）：${steps[4].detail}", steps[4].detail.contains("课程 11"))
        assertTrue("时段也要对（17）：${steps[4].detail}", steps[4].detail.contains("时段 17"))
        assertTrue("结论那步要说清 ❌ 是预期：${steps.last().detail}", steps.last().name == "结论")
    }

    /**
     * 本次真实故障的形状：系统 DNS 给了一个**不是我们服务器**的 IP（运营商缓存/劫持），
     * App 连过去被重置。自检必须把这条铁证打出来，而不是只说"网络异常"。
     */
    @Test
    fun `系统解析和公共DNS不一致时要当场指出来`() = runBlocking {
        val steps = go(
            transport(doh = "203.0.113.7"),
            resolve = { listOf("127.0.0.1") }, // 假的运营商答案
            tcp = { _, _ -> "SocketException：Connection reset" },
        )
        assertFalse("两边不一致必须判失败", steps[0].ok)
        assertTrue("要同时给出两个答案：${steps[0].detail}", steps[0].detail.contains("203.0.113.7"))
        assertTrue("要说人话指出运营商 DNS：${steps[0].detail}", steps[0].detail.contains("劫持"))
        assertFalse("连假的 IP 当然连不上", steps[1].ok)
        assertTrue(steps[1].detail.contains("Connection reset"))
    }

    @Test
    fun `系统解析不了时不要把它和连不上混为一谈`() = runBlocking {
        val steps = go(
            transport(),
            resolve = { throw java.net.UnknownHostException("api.example.com") },
        )
        assertFalse(steps[0].ok)
        assertTrue("要写明是解析不了：${steps[0].detail}", steps[0].detail.contains("解析不了"))
        assertTrue("没有 IP 时要说跳过，而不是假装连过", steps[1].detail.contains("跳过"))
    }

    @Test
    fun `健康检查被重置时要说清是哪一层`() = runBlocking {
        val steps = go(transport(throwOn = "health"))
        assertTrue("TCP 层是通的", steps[1].ok)
        assertFalse("HTTP 层断了", steps[2].ok)
        assertTrue("要把异常类型带出来：${steps[2].detail}", steps[2].detail.contains("SocketException"))
    }

    /**
     * 真机实测的形状：DNS 对、TCP 也连得上，就是 HTTPS 被重置 ——
     * 这时变体探测必须能把「换一种握手就能过」试出来，否则这一页没有意义。
     */
    @Test
    fun `变体探测要能挑出哪种握手过得去`() = runBlocking {
        val steps = go(
            transport(throwOn = "health"),
            handshake = { v, _ ->
                if (v == SelfCheck.Variant.TLS12) "握手成功（TLSv1.2 / ALPN -）→ HTTP/1.1 200 OK"
                else "握手失败：SocketException（Connection reset）"
            },
        )
        val variants = steps.filter { it.name.startsWith("变体·") }
        assertEquals("三个变体 + 不带 SNI 共四个", 4, variants.size)
        val tls12 = variants.first { it.name.contains("TLS 1.2") }
        assertTrue("强制 TLS 1.2 通了就得判绿：${tls12.detail}", tls12.ok)
        assertTrue("要把协商结果写出来：${tls12.detail}", tls12.detail.contains("握手成功"))
        assertFalse(
            "默认握手失败要如实红：${variants.first { it.name.contains("默认") }.detail}",
            variants.first { it.name.contains("默认") }.ok,
        )
        assertTrue(
            "不带 SNI 那条要说明是「按 IP 握手」",
            variants.any { it.name.contains("SNI") },
        )
    }

    @Test
    fun `没登录时最后一步要说清是没令牌_不是网络问题`() = runBlocking {
        val steps = go(transport(), token = null)
        assertFalse(steps[3].ok)
        assertTrue(steps[3].detail.contains("没有登录令牌"))
    }

    @Test
    fun `令牌过期时结论是HTTP401而不是网络异常`() = runBlocking {
        val steps = go(transport(me = 401 to "{\"detail\":\"未登录\"}"), token = "过期了")
        assertFalse(steps[3].ok)
        assertTrue("要能看出是登录态问题：${steps[3].detail}", steps[3].detail.contains("401"))
    }

    @Test
    fun `公共DNS答不上来也不影响其余判断`() = runBlocking {
        val steps = go(transport(dohCode = 500))
        assertTrue("系统解析是对的，不该因为对照失败就判红：" + steps[0].detail, steps[0].ok)
        assertTrue(steps[0].detail.contains("没答上来"))
    }

    @Test
    fun `主机名要从地址里抠干净`() {
        assertEquals("api.example.com", SelfCheck.hostOf("https://api.example.com"))
        assertEquals("api.example.com", SelfCheck.hostOf("https://api.example.com/api/v2"))
        assertEquals("a.b", SelfCheck.hostOf("http://a.b:8080/x"))
    }

    @Test
    fun `打给用户看的文本要能直接复制`() = runBlocking {
        val steps = go(transport())
        val text = SelfCheck.format(base, "1.25-T1", steps)
        assertTrue(text.contains("1.25-T1"))
        assertTrue(text.contains(base))
        assertTrue("每步都要有自己的行：$text", text.lines().size >= 5)
        assertFalse("不能把令牌打进去", text.contains("tok"))
    }

    @Test
    fun `复制出去的文本半句话不能说错_断哪层要一眼能读`() = runBlocking {
        val steps = go(transport(throwOn = "health"), token = null)
        val text = SelfCheck.format(base, "1.25-T1", steps)
        assertTrue("断的那层要带异常类型：\n$text", text.contains("SocketException"))
        assertTrue("没令牌那条也要在：\n$text", text.contains("没有登录令牌"))
        assertTrue("变体结果也要进复制文本：\n$text", text.contains("变体·"))
    }

    /**
     * 钉死一个真实误会：域名根的 `/healthz` 是**另一个服务**（8034）在答，
     * 它报的课程/时段数和 App 读的这份库无关 —— 显示在自检里会让用户以为数据丢了。
     * 所以自检**永远不许**再去打那条。
     */
    @Test
    fun `自检不许再打域名根的healthz`() = runBlocking {
        val steps = go(transport())
        assertTrue("必须打 v2 自己的那条", seenUrls.any { it.endsWith("/api/v2/healthz") })
        assertFalse(
            "打过的地址里不能出现域名根的 /healthz：" + seenUrls.joinToString(),
            seenUrls.any { it == "$base/healthz" },
        )
        assertTrue("结论里要说明 ❌ 是预期，不是坏了", steps.last().detail.contains("预期"))
    }

    /** 计划那步要是真拿不到数据（比如契约坏了），必须如实报失败，不能显示成 ✅ */
    @Test
    fun `计划读不出条数时要如实报失败`() = runBlocking {
        val bad = object : Transport {
            override fun call(method: String, url: String, headers: Map<String, String>, body: String?) =
                when {
                    url.contains("/resolve") -> HttpReply(200, "{\"Answer\":[{\"data\":\"203.0.113.7\"}]}")
                    url.contains("/api/v2/plan") -> HttpReply(200, "{\"courses\":\"nope\"}")
                    else -> HttpReply(200, "{}")
                }
        }
        val steps = go(bad)
        val plan = steps.first { it.name == "GET /api/v2/plan" }
        assertFalse("读不出条数就是有问题：${plan.detail}", plan.ok)
        assertTrue("要说清是契约问题：${plan.detail}", plan.detail.contains("契约"))
    }
}
