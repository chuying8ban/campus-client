package top.ccbase.campus.net

import java.net.SocketException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.update.ByteSink
import top.ccbase.campus.update.StreamHead

private const val BASE = "https://api.example.com"

/**
 * 链路按 SNI 拦截时"换一条路"的验收（示例市移动实测）。
 *
 * 规则来自真机：带 SNI 的握手一律被 reset，不带 SNI 的秒通 —— 所以路线**先试不带 SNI**
 * 并记住，别让用户在坏网络上的第一次请求就去撞一次被拦的握手。
 */
class DialTransportTest {

    private val base = BASE

    /** 记录调用并照剧本回答的假传输层 */
    private class Fake(
        val name: String,
        /** 公开给用例看：验"下载走的是哪条路" */
        val log: MutableList<String>,
    ) : Transport {
        var calls = 0
        private var reply: (String) -> HttpReply = { HttpReply(200, "{}") }
        fun failWith(e: Exception) { reply = { throw e } }
        fun ok(code: Int = 200) { reply = { HttpReply(code, "{}") } }
        fun down() { reply = { throw SocketException("Connection reset") } }
        override fun call(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply {
            calls++
            log += "$name $method ${url.removePrefix(BASE)}"
            return reply(url)
        }

        // ---- 二进制下载（更新包）----
        var bytes = ByteArray(0)
        /** 写一半再断：用来验"不许中途换路重下" */
        var breakAfterBytes = false
        override fun stream(url: String, sink: (ByteArray, Int, Long) -> Unit): Long {
            calls++
            log += "$name stream ${url.removePrefix(BASE)}"
            if (reply(url).code >= 400) throw SocketException("Connection reset")
            if (breakAfterBytes) {
                sink(bytes, bytes.size / 2, (bytes.size / 2).toLong())
                throw SocketException("下载到一半断了")
            }
            sink(bytes, bytes.size, bytes.size.toLong())
            return bytes.size.toLong()
        }

        /** 两个 fake 共用同一个日志列表，所以只数**自己**的（前缀带上名字） */
        fun streamCalls() = log.count { it.startsWith("$name stream") }
    }

    private fun setup(noSniOk: Boolean = true, directOk: Boolean = true): Triple<DialTransport, Fake, Fake> {
        val log = mutableListOf<String>()
        val noSni = Fake("无SNI", log).apply { if (noSniOk) ok() else down() }
        val direct = Fake("直连", log).apply { if (directOk) ok() else down() }
        return Triple(DialTransport(MemoryRouteStore(), direct, noSni), direct, noSni)
    }

    // ---------- 选路 ----------

    @Test
    fun `不带 SNI 能通就走它_而且不再去撞直连`() {
        val (dial, direct, noSni) = setup(noSniOk = true, directOk = true)
        val r = dial.call("GET", "$base/api/v2/plan", emptyMap(), null)
        assertEquals(200, r.code)
        assertTrue("路线应该是不带 SNI：" + dial.modeName(base), dial.modeName(base).contains("不带 SNI"))
        assertEquals("探针 + 业务请求各一次", 2, noSni.calls)
        assertEquals("既然不带 SNI 通了，就不该去撞直连（撞了就等于告诉链路设备我们是 App）", 0, direct.calls)
    }

    @Test
    fun `不带 SNI 不通才退到直连`() {
        val (dial, direct, noSni) = setup(noSniOk = false, directOk = true)
        val r = dial.call("GET", "$base/api/v2/plan", emptyMap(), null)
        assertEquals(200, r.code)
        assertTrue("路线应该是直连：" + dial.modeName(base), dial.modeName(base).contains("直连"))
        assertEquals("探针一次 + 业务请求一次", 2, direct.calls)
        assertEquals("探针在不带 SNI 上试过一次", 1, noSni.calls)
    }

    @Test
    fun `两条都不通时按不带 SNI 记_异常原样抛给用户`() {
        val (dial, _, _) = setup(noSniOk = false, directOk = false)
        var thrown = false
        try { dial.call("GET", "$base/api/v2/plan", emptyMap(), null) } catch (e: SocketException) { thrown = true }
        assertTrue("两条都不通时必须报错，不能假装成功", thrown)
        assertTrue("出过事之后路线要有个明确的记录，别停在未探测：" + dial.modeName(base),
            !dial.modeName(base).contains("未探测"))
    }

    @Test
    fun `路线记下来就不重复探测`() {
        val (dial, direct, noSni) = setup()
        dial.call("GET", "$base/api/v2/plan", emptyMap(), null)
        dial.call("GET", "$base/api/v2/plan", emptyMap(), null)
        assertEquals("三次请求只该探一次", 3, noSni.calls)
        assertEquals(0, direct.calls)
    }

    // ---------- 记忆 ----------

    @Test
    fun `记忆要能落盘_下次启动直接用`() {
        val store = MemoryRouteStore()
        val log = mutableListOf<String>()
        val noSni1 = Fake("无SNI", log)
        DialTransport(store, Fake("直连", log), noSni1).call("GET", "$base/healthz", emptyMap(), null)
        // 新实例（模拟重启）拿同一个 store → 不该再探测
        val noSni2 = Fake("无SNI2", log)
        val dial2 = DialTransport(store, Fake("直连2", log), noSni2)
        dial2.call("GET", "$base/api/v2/plan", emptyMap(), null)
        assertEquals("重启后不该再探测（探针不重复打）", 1, noSni2.calls)
    }

    /** 记忆按主机分开：自检里还要访问 DoH（223.5.5.5），别互相带跑 */
    @Test
    fun `一条主机的路线不影响另一台主机`() {
        val log = mutableListOf<String>()
        val noSni = Fake("无SNI", log).apply { down() }
        val direct = Fake("直连", log).apply { down() }
        val dial = DialTransport(MemoryRouteStore(), direct, noSni)
        try { dial.call("GET", "$base/api/v2/plan", emptyMap(), null) } catch (_: Exception) {}
        assertTrue("study 这台要留下路线记录：" + dial.modeName(base), !dial.modeName(base).contains("未探测"))

        // 换一台主机，而且这次"直连"才是通的那条 → 它该自己记成直连，不受 study 影响
        val log2 = mutableListOf<String>()
        val noSni2 = Fake("无SNI", log2).apply { down() }
        val direct2 = Fake("直连", log2).apply { ok() }
        val dial2 = DialTransport(MemoryRouteStore(), direct2, noSni2)
        val r = dial2.call("GET", "https://223.5.5.5/resolve?name=x&type=A", emptyMap(), null)
        assertEquals(200, r.code)
        assertTrue(
            "DoH 这台该记成直连：" + dial2.modeName("https://223.5.5.5/resolve"),
            dial2.modeName("https://223.5.5.5/resolve").contains("直连"),
        )
        assertEquals("探针只该打 223.5.5.5", true, log2.all { it.contains("223.5.5.5") })
    }

    // ---------- 2026-09-26 事故：握手被拒也算"这条路走不通" ----------
    // 那天 api.example.com 的边缘换成 Cloudflare，不带 SNI 的握手被 CF 直接拒（SSL alert 40），
    // 抛出来的是 SSLHandshakeException —— 旧判据只认 SocketException，于是既不翻转也不换路，
    // 全量用户停在同一句「HTTPS 证书校验失败」上。下面三条钉住：这种事以后客户端自己会纠回来。

    @Test
    fun `握手被拒_GET 当场换路_而不是把用户卡死在同一句报错上`() {
        val log = mutableListOf<String>()
        val noSni = Fake("无SNI", log).apply { ok() }
        val direct = Fake("直连", log).apply { ok() }
        val dial = DialTransport(MemoryRouteStore(), direct, noSni)
        dial.call("GET", "$base/api/v2/plan", emptyMap(), null)              // 先选定：不带 SNI
        assertTrue("先选定不带 SNI：" + dial.modeName(base), dial.modeName(base).contains("不带 SNI"))

        // 真实症状：不带 SNI 的握手被边缘直接拒掉（alert 40），一个字节都没发出去
        noSni.failWith(SSLHandshakeException("Read error: ssl=0xb400006ffd4146d8: Failure in SSL library"))

        val r = dial.call("GET", "$base/api/v2/plan", emptyMap(), null)
        assertEquals("握手被拒后必须自己换到另一条路，不能一直撞同一面墙", 200, r.code)
        assertTrue("路线该翻到直连：" + dial.modeName(base), dial.modeName(base).contains("直连"))
        assertTrue("这一次是直连答的", log.last().startsWith("直连"))
    }

    @Test
    fun `握手被拒的 POST_翻转记忆但绝不偷偷重发`() {
        val log = mutableListOf<String>()
        val noSni = Fake("无SNI", log).apply { ok() }
        val direct = Fake("直连", log).apply { ok() }
        val dial = DialTransport(MemoryRouteStore(), direct, noSni)
        dial.call("GET", "$base/api/v2/plan", emptyMap(), null)              // 探针 + 业务请求 = 2 次
        noSni.failWith(SSLHandshakeException("alert handshake failure"))

        var thrown = false
        try { dial.call("POST", "$base/api/v2/logout", emptyMap(), null) } catch (e: SSLHandshakeException) { thrown = true }
        assertTrue("POST 必须原样抛错，让用户自己决定（不许悄悄换路重发）", thrown)
        assertEquals("POST 只准在已选定那条路上发一次", 3, noSni.calls)
        assertTrue("但记忆要翻过来，下一个请求别再撞同一面墙：" + dial.modeName(base),
            dial.modeName(base).contains("直连"))
    }

    @Test
    fun `两条路都拒握手_报出来的还得是握手异常`() {
        val log = mutableListOf<String>()
        val noSni = Fake("无SNI", log).apply { ok() }
        val direct = Fake("直连", log).apply { ok() }
        val dial = DialTransport(MemoryRouteStore(), direct, noSni)
        dial.call("GET", "$base/api/v2/plan", emptyMap(), null)
        noSni.failWith(SSLHandshakeException("换路前的握手失败"))
        direct.failWith(SSLHandshakeException("换路后的握手失败"))

        var kind: String? = null
        try { dial.call("GET", "$base/api/v2/plan", emptyMap(), null) }
        catch (e: Exception) { kind = e.javaClass.simpleName }
        assertEquals(
            "别把握手失败包成 SocketException —— 上层按异常类型翻文案，包错就只剩没信息量的「网络异常」",
            "SSLHandshakeException", kind,
        )
    }

    // ---------- 选错了之后的自救 ----------

    @Test
    fun `选定的路线又被重置_GET 当场换路_POST 原样报错不许偷偷重发`() {
        val log = mutableListOf<String>()
        val noSni = Fake("无SNI", log).apply { ok() }
        val direct = Fake("直连", log).apply { ok() }
        val dial = DialTransport(MemoryRouteStore(), direct, noSni)
        dial.call("GET", "$base/api/v2/plan", emptyMap(), null)     // 探明：不带 SNI
        noSni.down()                                               // 这条这会儿被掐了
        var thrown = false
        try { dial.call("POST", "$base/api/v2/logout", emptyMap(), null) } catch (e: SocketException) { thrown = true }
        assertTrue("POST 失败必须原样抛错，让用户自己决定", thrown)
        assertEquals("POST 只准在已选定那条路上发一次，不许偷偷重发", 3, noSni.calls)
        assertEquals("被重置后路线应该翻到直连：" + dial.modeName(base), true, dial.modeName(base).contains("直连"))
        val r = dial.call("GET", "$base/api/v2/plan", emptyMap(), null)
        assertEquals(200, r.code)
        assertTrue("翻转后 GET 该走直连", log.last().startsWith("直连"))
    }

    @Test
    fun `IP 主机不会发 SNI_所以对它先试直连`() {
        val log = mutableListOf<String>()
        val noSni = Fake("无SNI", log).apply { ok() }
        val direct = Fake("直连", log).apply { ok() }
        val dial = DialTransport(MemoryRouteStore(), direct, noSni)
        dial.call("GET", "https://223.5.5.5/resolve?name=x&type=A", emptyMap(), null)
        assertTrue("IP 主机该走直连：" + dial.modeName("https://223.5.5.5/resolve"),
            dial.modeName("https://223.5.5.5/resolve").contains("直连"))
        assertEquals("先试的就是直连，不带 SNI 不该被碰", 0, noSni.calls)
        assertTrue("223.5.5.5 要认成 IP 字面量", dial.isIpLiteral("223.5.5.5"))
        assertTrue("域名不是 IP 字面量", !dial.isIpLiteral("api.example.com"))
    }

    @Test
    fun `探针 URL 要从请求 URL 推同源 healthz`() {
        val d = DialTransport()
        assertEquals("https://api.example.com/healthz", d.healthOf("https://api.example.com/api/v2/plan"))
        assertEquals("https://api.example.com/healthz", d.healthOf("https://api.example.com"))
        assertEquals("api.example.com", d.hostOf("https://api.example.com:8443/x"))
    }

    // ------------------------------------------------ 下载（更新包）也要走这条路

    @Test
    fun `下载也走选定路线_不许绕开传输层`() {
        val (dial, direct, noSni) = setup()
        dial.call("GET", "$BASE/healthz", emptyMap(), null)      // 先把路线定下来
        noSni.bytes = byteArrayOf(1, 2, 3, 4)
        val out = java.io.ByteArrayOutputStream()
        val n = dial.stream("$BASE/updates/campus.apk") { b, len, _ -> out.write(b, 0, len) }
        assertEquals(4L, n)
        assertEquals("下载应该走选定的路线（不带 SNI）", 1, noSni.streamCalls())
        assertEquals("下载不该改走直连（那是被拦的那条）", 0, direct.streamCalls())
    }

    @Test
    fun `下载写到一半断了不许换路重下_那会下出一个坏包`() {
        val (dial, direct, noSni) = setup()
        dial.call("GET", "$BASE/healthz", emptyMap(), null)
        noSni.bytes = ByteArray(100) { 7 }
        noSni.breakAfterBytes = true
        var failed = false
        try {
            dial.stream("$BASE/updates/campus.apk") { _, _, _ -> }
        } catch (e: Exception) { failed = true }
        assertTrue("写了一半还换路重下，装出来的包是坏的", failed)
        assertEquals("写了一半还换路，装出来的包就是坏的", 0, direct.streamCalls())
    }

    // ------------------------------------------------ 断点续传（分片下载）也要守同一套规矩

    /** 会分片回答的假传输层：能选"断在写字节之前"还是"断在写字节之后" */
    private class RangedFake(
        val name: String,
        val log: MutableList<String>,
    ) : Transport {
        var calls = 0
        var rangedCalls = 0
        var failEarly = false          // 这条链路根本连不上（该换路）
        var failAfterBytes = false     // 写了字节才断（线路抖了，不许换路）
        val served = ByteArray(64) { 9 }

        override fun call(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply {
            calls++
            log += "$name call"
            if (failEarly) throw SocketException("Connection reset")
            return HttpReply(200, "{}")
        }

        override fun streamRanged(
            url: String,
            offset: Long,
            ifRange: String?,
            decide: (StreamHead) -> ByteSink?,
        ): StreamHead {
            rangedCalls++
            log += "$name ranged offset=$offset"
            if (failEarly) throw SocketException("Connection reset")
            val head = StreamHead(
                code = 206,
                etag = "\"v1\"",
                lastModified = "LM",
                rangeStart = offset,
                rangeTotal = served.size.toLong(),
                contentLength = (served.size - offset).toLong(),
            )
            val sink = decide(head) ?: return head
            val from = offset.toInt()
            sink(served.copyOfRange(from, served.size), served.size - from)
            if (failAfterBytes) throw SocketException("下载到一半断了")
            return head
        }
    }

    private fun rangedSetup(noSniOk: Boolean = true, directOk: Boolean = true): Triple<DialTransport, RangedFake, RangedFake> {
        val log = mutableListOf<String>()
        val noSni = RangedFake("无SNI", log).apply { failEarly = !noSniOk }
        val direct = RangedFake("直连", log).apply { failEarly = !directOk }
        return Triple(DialTransport(MemoryRouteStore(), direct, noSni), direct, noSni)
    }

    @Test
    fun `断点续传也走选定路线_不会绕开传输层`() {
        val (dial, direct, noSni) = rangedSetup()
        dial.call("GET", "$BASE/healthz", emptyMap(), null)          // 先把路线定下来（不带 SNI）
        val out = java.io.ByteArrayOutputStream()
        val head = dial.streamRanged("$BASE/updates/campus.apk", 0L, null) { h ->
            { b, n -> out.write(b, 0, n) }
        }
        assertEquals(206, head.code)
        assertEquals(64, out.size())
        assertEquals("续传要走选定的那条路（不带 SNI）", 1, noSni.rangedCalls)
        assertEquals("不该去撞被拦的直连", 0, direct.rangedCalls)
    }

    @Test
    fun `一个字节都没写出去时_换路是可以的（还没脏数据）`() {
        val (dial, direct, noSni) = rangedSetup(noSniOk = false, directOk = true)
        val r = dial.call("GET", "$BASE/healthz", emptyMap(), null)
        assertEquals(200, r.code)
        assertEquals("探针确认不带 SNI 不通，就该记成直连", true, dial.modeName(BASE).contains("直连"))
    }

    @Test
    fun `分片下载写到一半断了_绝不换路续写_原样抛给上层退避重试`() {
        val (dial, direct, noSni) = rangedSetup()
        dial.call("GET", "$BASE/healthz", emptyMap(), null)
        noSni.failAfterBytes = true
        var thrown = false
        try {
            dial.streamRanged("$BASE/updates/campus.apk", 0L, "\"v1\"") { h -> { _, _ -> } }
        } catch (e: SocketException) { thrown = true }
        assertTrue("写了一半必须原样抛出，让上层在**同一条路**上重发 Range", thrown)
        assertEquals("换路续写就是把两条链路的数据拼在一个文件里", 0, direct.rangedCalls)
    }
}
