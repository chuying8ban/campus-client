package top.ccbase.campus.net

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SNIMatcher
import javax.net.ssl.SNIServerName
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「不发 SNI 的 HTTPS 客户端」的验收 —— 本地起一个**拒绝 SNI 的 TLS 服务器**来演真事。
 *
 * 这台假服务器就是示例市移动那条链路侧的化身：凡是 ClientHello 里带了 server_name
 * 的握手一律拒（unrecognized_name），不带的照常服务。于是整个修法可以在本机被证伪/证实，
 * 不用拿手机试错。
 *
 * 用纯 JUnit（不套 Robolectric）：这条链路只碰 java.* 和本项目的数据类，不需要 Android 影子。
 */
class NoSniTransportTest {

    // ---------- 假服务器 ----------

    private class TlsTestServer(
        private val response: () -> String,
        private val keystore: String = "/tls_test.p12",
        /** 传它就原样回这些字节（验二进制下载：任何文本通道都会把这些字节转码毁掉） */
        private val rawBytes: ByteArray? = null,
    ) : AutoCloseable {
        private val server: SSLServerSocket
        val received = mutableListOf<String>()   // 收到的请求（用来验 POST 内容没被弄坏）
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        /** 一共被连了几次（用来验"该重试的会重试、不该重试的不重试"） */
        val connections = java.util.concurrent.atomic.AtomicInteger()

        init {
            val ks = KeyStore.getInstance("PKCS12").apply {
                // 注意：这里不能写 javaClass —— apply 的接收者是 KeyStore，
                // javaClass 会取到 java.security.KeyStore 的类（bootstrap 加载器），资源必找不到
                testResource(keystore).use { load(it, "changeit".toCharArray()) }
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(ks, "changeit".toCharArray()) }
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            server = ctx.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            // 别拿 SSLParameters.sniMatchers 当"带没带 SNI"的证人：实测它在这个环境里压根不触发
            // （连我手工造的、货真价实带 SNI 的 ClientHello 都记录不到），用它做证据只会得出假结论。
            // 真正的证人见 HelloCatcher —— SNI 就在**明文** ClientHello 字节里，做不了假。

            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val c = try { server.accept() } catch (e: Exception) { break }
                    connections.incrementAndGet()
                    thread(isDaemon = true) { serveOne(c) }
                }
            }
        }

        val port: Int get() = server.localPort

        private fun serveOne(c: Socket) {
            try {
                val s = c as SSLSocket
                s.startHandshake()
                // 读请求：头 + 按 Content-Length 把 body 也读全（POST 要看内容）
                val head = StringBuilder()
                val ins: InputStream = s.inputStream
                while (!head.endsWith("\r\n\r\n")) {
                    val b = ins.read()
                    if (b < 0) break
                    head.append(b.toChar())
                }
                val h = head.toString()
                val len = Regex("(?i)content-length:\\s*(\\d+)").find(h)?.groupValues?.get(1)?.toInt() ?: 0
                val bodyBytes = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = ins.read(bodyBytes, got, len - got)
                    if (n < 0) break
                    got += n
                }
                received += h + String(bodyBytes, Charsets.UTF_8)

                val rb = rawBytes
                if (rb == null) {
                    s.outputStream.write(response().toByteArray(Charsets.UTF_8))
                } else {
                    s.outputStream.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                            "Content-Length: " + rb.size + "\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.ISO_8859_1)
                    )
                    s.outputStream.write(rb)
                }
                s.outputStream.flush()
            } catch (e: Exception) {
                errors += e.toString()   // 带 SNI 的握手会走到这里；其余情况是测试自己的问题
            } finally {
                runCatching { c.close() }
            }
        }

        override fun close() { runCatching { server.close() } }
    }

    private fun ok(body: String): String =
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"

    private fun chunky(body: String): String =
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
            "${Integer.toHexString(body.length)}\r\n$body\r\n0\r\n\r\n"

    /** 只信这张自签证书的校验器（真实部署里用的是系统信任库，见下一条用例） */
    private fun trustOnlyTestCert(): X509TrustManager {
        val ks = KeyStore.getInstance("PKCS12").apply {
            testResource("/tls_test.p12").use { load(it, "changeit".toCharArray()) }
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(ks) }.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** 等一个条件成立（真实 socket 的另一端在别的线程上，别用固定 sleep 赌） */
    private fun waitFor(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    /**
     * 只收一段握手字节的裸 TCP 服务器（不解 TLS）。
     * 为什么需要它：SNI 位于**明文**的 ClientHello 里，直接看字节就不会被任何实现的怪脾气骗到。
     * JDK 的 SNIMatcher 试过，在这环境里不触发；靠"对端拒绝"更是时灵时不灵。字节不会撒谎。
     */
    private class HelloCatcher : AutoCloseable {
        private val server = java.net.ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        @Volatile var captured = ByteArray(0)
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                try {
                    server.accept().use { c ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(2048)
                        val n = c.getInputStream().read(buf)
                        if (n > 0) out.write(buf, 0, n)
                        captured = out.toByteArray()
                    }
                } catch (_: Exception) { /* 测试结束时的正常收尾 */ }
            }
        }

        fun helloText(): String = String(captured, Charsets.ISO_8859_1)
        fun looksLikeClientHello(): Boolean =
            captured.size > 5 && captured[0] == 0x16.toByte() && captured[5] == 0x01.toByte()

        override fun close() { runCatching { server.close() } }
    }

    /**
     * 手工造一个带 SNI 的 ClientHello 直接发过去。
     * 为什么不用 JDK 客户端：它"发不发 SNI"取决于解析得了解析不了等一堆条件（实测时灵时不灵），
     * 拿它当对照组会得出假结论。这里把字节写死，SNI 一定在里面。
     * 握手后面当然会失败（我们没打算完成它），但服务器在解析 ClientHello 时就会调用 SNIMatcher，
     * 那时 SNI 已经被记下来了 —— 这就是对照组要的证据。
     */
    private fun sendRawClientHelloWithSni(port: Int, name: String) {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val list = java.io.ByteArrayOutputStream().apply {
            write(0x00)                                   // name_type = host_name
            write((nameBytes.size shr 8) and 0xff); write(nameBytes.size and 0xff)
            write(nameBytes)
        }.toByteArray()
        val sniExt = java.io.ByteArrayOutputStream().apply {
            write(0x00); write(0x00)                      // extension_type = server_name
            val inner = list.size + 2
            write((inner shr 8) and 0xff); write(inner and 0xff)
            write((list.size shr 8) and 0xff); write(list.size and 0xff)
            write(list)
        }.toByteArray()
        val exts = java.io.ByteArrayOutputStream().apply {
            write((sniExt.size shr 8) and 0xff); write(sniExt.size and 0xff)
            write(sniExt)
        }.toByteArray()
        val body = java.io.ByteArrayOutputStream().apply {
            write(0x03); write(0x03)                      // TLS 1.2
            write(ByteArray(32))                          // random
            write(0x00)                                   // session_id 空
            write(0x00); write(0x02); write(0x00); write(0x2f)   // 一个 cipher suite
            write(0x01); write(0x00)                      // 压缩：null
            write((exts.size shr 8) and 0xff); write(exts.size and 0xff)
            write(exts)
        }.toByteArray()
        val hs = ByteArray(body.size + 4)
        hs[0] = 0x01
        hs[1] = ((body.size shr 16) and 0xff).toByte()
        hs[2] = ((body.size shr 8) and 0xff).toByte()
        hs[3] = (body.size and 0xff).toByte()
        body.copyInto(hs, 4)
        val rec = ByteArray(hs.size + 5)
        rec[0] = 0x16; rec[1] = 0x03; rec[2] = 0x01
        rec[3] = ((hs.size shr 8) and 0xff).toByte(); rec[4] = (hs.size and 0xff).toByte()
        hs.copyInto(rec, 5)
        java.net.Socket("127.0.0.1", port).use { sk ->
            sk.getOutputStream().write(rec)
            sk.getOutputStream().flush()
            Thread.sleep(200)                             // 让服务器把 ClientHello 读完
        }
    }

    private val hit = { _: String -> "127.0.0.1" }

    companion object {
        /** 注意：这里不能写 javaClass —— 见下面 apply 块里的注释 */
        fun testResource(name: String) =
            NoSniTransportTest::class.java.getResourceAsStream(name)!!
    }

    // ---------- 用例 ----------

    @Test
    fun `不打 SNI 就能跟"拦 SNI"的服务器通上话`() {
        TlsTestServer({ ok("""{"ok":true}""") }).use { srv ->
            val t = NoSniTransport(resolve = { listOf(InetAddress.getByName("127.0.0.1")) })
            val r = try {
                t.call("GET", "https://localhost:${srv.port}/healthz", emptyMap(), null)
            } catch (e: Exception) {
                throw AssertionError("请求失败：" + e + " / 服务器侧：" + srv.errors, e)
            }
            assertEquals(200, r.code)
            assertEquals("""{"ok":true}""", r.text)
            assertTrue("请求里必须带正确的 Host（服务器按域名认人）", srv.received.first().contains("Host: localhost"))
        }
    }

    @Test
    fun `不带 SNI_明文握手字节里就不该出现域名`() {
        HelloCatcher().use { cat ->
            val t = NoSniTransport(resolve = { listOf(InetAddress.getByName("127.0.0.1")) })
            // 对面只是裸 TCP，握不完手是必然的 —— 我们只要它收到的那段 ClientHello
            runCatching { t.call("GET", "https://localhost:${cat.port}/healthz", emptyMap(), null) }
            assertTrue("没收到握手字节，测试无效", waitFor { cat.captured.isNotEmpty() })
            val hello = cat.helloText()
            assertTrue("连 ClientHello 都不是，抓错了：" + hello.take(20), cat.looksLikeClientHello())
            assertTrue(
                "握手字节里出现了域名 —— 说明 SNI 发出去了，链路设备看得见我们访问谁：" + hello.take(120),
                !hello.contains("localhost"),
            )
            // 而且能看见"确实有扩展段"：证明我们不是在拿一个残缺的 ClientHello 自我安慰
            assertTrue("ClientHello 里该有的扩展段没找到", hello.contains("\u0000\u0000"))
        }
    }

    @Test
    fun `对照组_带 SNI 的握手字节里必须能看见域名`() {
        HelloCatcher().use { cat ->
            sendRawClientHelloWithSni(cat.port, "sni.example.com")
            assertTrue("没收到握手字节", waitFor { cat.captured.isNotEmpty() })
            assertTrue(
                "上一条用例的'没有域名'证明不了任何事 —— 这个探测器根本认不出 SNI：" + cat.helloText().take(120),
                cat.helloText().contains("sni.example.com"),
            )
        }
    }

    @Test
    fun `证书校验没打折_不在信任库里的证书必须连不上`() {
        // 用另一张同名的自签证书（测试 JVM 的信任库里没有它）
        TlsTestServer({ ok("{}") }, keystore = "/tls_evil.p12").use { srv ->
            val t = NoSniTransport(resolve = { listOf(InetAddress.getByName("127.0.0.1")) })
            var failed = false
            try { t.call("GET", "https://localhost:${srv.port}/healthz", emptyMap(), null) } catch (e: Exception) { failed = true }
            assertTrue("不被信任的证书都放过去了，说明校验被关掉了", failed)
        }
    }

    @Test
    fun `域名对不上必须失败_不能被"不发 SNI"顺手变成不校验`() {
        TlsTestServer({ ok("{}") }).use { srv ->
            val t = NoSniTransport(resolve = { listOf(InetAddress.getByName("127.0.0.1")) })
            var failed = false
            try {
                // 证书是 localhost 的，却按 127.0.0.1 去访问 → 必须抛
                t.call("GET", "https://127.0.0.1:${srv.port}/healthz", emptyMap(), null)
            } catch (e: Exception) { failed = true }
            assertTrue("拿别人的证书也能连上，等于没有域名校验", failed)
        }
    }

    @Test
    fun `POST 的请求体要原样送到_别把选课提交弄坏`() {
        TlsTestServer({ ok("""{"status":"ok"}""") }).use { srv ->
            val t = NoSniTransport(resolve = { listOf(InetAddress.getByName("127.0.0.1")) })
            val r = try {
                t.call("POST", "https://localhost:${srv.port}/api/v2/grab/targets", mapOf("X-Token" to "t1"), """{"course":"数学分析（I）"}""")
            } catch (e: Exception) {
                throw AssertionError("请求失败：" + e + " / 服务器侧：" + srv.errors, e)
            }
            assertEquals(200, r.code)
            val req = srv.received.first()
            assertTrue("请求体必须完整（含中文）", req.endsWith("""{"course":"数学分析（I）"}"""))
            assertTrue("自定义头要发出去", req.contains("X-Token: t1"))
            val bytes = """{"course":"数学分析（I）"}""".toByteArray().size
            assertTrue("必须声明 Content-Length（按字节数，中文不是 2 字节）", req.contains("Content-Length: $bytes"))
        }
    }

    @Test
    fun `握手第一次被掐掉会自动重试_不用用户手动再来一次`() {
        // 真机上那条链路的脾气就是"抖"：同一目标第一次 RST、第二次就通。
        // 这里造一个"第一次连接直接掐断、之后正常"的服务器，验证我们会自己再来一次。
        val ks = java.security.KeyStore.getInstance("PKCS12").apply {
            testResource("/tls_test.p12").use { load(it, "changeit".toCharArray()) }
        }
        val kmf = javax.net.ssl.KeyManagerFactory
            .getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(ks, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = ctx.serverSocketFactory
            .createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val conns = java.util.concurrent.atomic.AtomicInteger()
        val body = """{"ok":true}"""

        thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (e: Exception) { break }
                val n = conns.incrementAndGet()
                thread(isDaemon = true) {
                    if (n == 1) { runCatching { c.close() }; return@thread }   // 第一次：掐掉
                    runCatching {
                        val s = c as SSLSocket
                        s.startHandshake()
                        s.inputStream.bufferedReader().readLine()
                        val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                        s.outputStream.write(resp.toByteArray())
                        s.outputStream.flush()
                        s.close()
                    }
                }
            }
        }

        try {
            val t = NoSniTransport(resolve = { listOf(InetAddress.getByName("127.0.0.1")) })
            val r = t.call("GET", "https://localhost:${server.localPort}/healthz", emptyMap(), null)
            assertEquals(200, r.code)
            assertEquals("第一次被掐断后必须自己再来一次，而不是让用户手动刷新", 2, conns.get())
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun `证书不合格不重试_白连两次没有意义`() {
        TlsTestServer({ ok("{}") }, keystore = "/tls_evil.p12").use { srv ->
            val before = srv.connections.get()
            val t = NoSniTransport(resolve = { listOf(InetAddress.getByName("127.0.0.1")) })
            runCatching { t.call("GET", "https://localhost:${srv.port}/healthz", emptyMap(), null) }
            assertEquals("不被信任的证书是确定性的失败，不该重试", before + 1, srv.connections.get())
        }
    }

    // ---------- 二进制下载（更新包）----------

    @Test
    fun `二进制下载要按字节原样_不能被当文本转码`() {
        // 0..255 全跑一遍：任何 UTF-8 编解码都会毁掉其中一部分
        val blob = ByteArray(1024) { (it % 256).toByte() }
        TlsTestServer({ ok("") }, rawBytes = blob).use { srv ->
            val t = NoSniTransport(resolve = { listOf(InetAddress.getByName("127.0.0.1")) })
            val got = java.io.ByteArrayOutputStream()
            val n = t.stream("https://localhost:" + srv.port + "/updates/campus-1.31-T1.apk") { b, len, _ ->
                got.write(b, 0, len)
            }
            assertEquals(blob.size.toLong(), n)
            assertTrue("下载回来的字节和服务器上的不一致，装上去就是坏包", blob.contentEquals(got.toByteArray()))
            assertTrue("请求路径要带上", srv.received.first().contains("GET /updates/campus-1.31-T1.apk"))
        }
    }

    @Test
    fun `下载进度要递增_最后一条等于整包长度`() {
        val blob = ByteArray(300_000) { (it % 251).toByte() }
        TlsTestServer({ ok("") }, rawBytes = blob).use { srv ->
            val t = NoSniTransport(resolve = { listOf(InetAddress.getByName("127.0.0.1")) })
            val seen = mutableListOf<Long>()
            t.stream("https://localhost:" + srv.port + "/x.apk") { _, _, total -> seen += total }
            assertEquals("最后一条进度必须是整包长度", blob.size.toLong(), seen.last())
            assertEquals("进度必须严格递增：" + seen, seen.sorted(), seen)
        }
    }

    @Test
    fun `服务端用分块传输时宁可报错_也别写出一个坏安装包`() {
        TlsTestServer({ chunky("""{"a":1}""") }).use { srv ->
            val t = NoSniTransport(resolve = { listOf(InetAddress.getByName("127.0.0.1")) })
            var failed = false
            try {
                t.stream("https://localhost:" + srv.port + "/x.apk") { _, _, _ -> }
            } catch (e: Exception) { failed = true }
            assertTrue("chunked 被当成整包内容了，会写出坏安装包", failed)
        }
    }

    // ---------- 响应解析（拿字节直接测） ----------

    @Test
    fun `解析_带 Content-Length 的响应`() {
        val r = NoSniTransport.parseResponse(ok("""{"a":"中文"}""").toByteArray())
        assertEquals(200, r.code)
        assertEquals("""{"a":"中文"}""", r.text)
    }

    @Test
    fun `解析_chunked 响应要拼回原文`() {
        val r = NoSniTransport.parseResponse(chunky("""{"b":1}""").toByteArray())
        assertEquals(200, r.code)
        assertEquals("""{"b":1}""", r.text)
    }

    @Test
    fun `解析_401 的 JSON 错误体也要能读出来`() {
        val raw = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 24\r\nConnection: close\r\n\r\n".toByteArray() +
            """{"detail":"未登录"}""".toByteArray()
        val r = NoSniTransport.parseResponse(raw)
        assertEquals(401, r.code)
        assertEquals("""{"detail":"未登录"}""", r.text)
    }

    @Test
    fun `解析_请求头不完整时不许瞎猜成功`() {
        var failed = false
        try { NoSniTransport.parseResponse("GARBAGE".toByteArray()) } catch (e: Exception) { failed = true }
        assertTrue("头都不全还返回了结果，迟早骗到用户", failed)
    }

    // ---------- 域名校验（自己把的那一关） ----------

    @Test
    fun `域名全等_大小写和尾点都不该卡住`() {
        assertTrue(NoSniTransport.matchDnsName("api.example.com", "api.example.com"))
        assertTrue(NoSniTransport.matchDnsName("API.EXAMPLE.COM", "api.example.com"))
        assertTrue(NoSniTransport.matchDnsName("api.example.com.", "api.example.com"))
    }

    @Test
    fun `通配只替一个标签_多一层就不认`() {
        assertTrue(NoSniTransport.matchDnsName("*.example.com", "api.example.com"))
        assertTrue(!NoSniTransport.matchDnsName("*.example.com", "a.b.example.com"))
    }

    @Test
    fun `后缀攻击不能过_证书说 star 可不能说 star 的子孙站`() {
        assertTrue(!NoSniTransport.matchDnsName("*.example.com", "evil.example.com.attacker.com"))
        assertTrue(!NoSniTransport.matchDnsName("*.example.com", "example.com"))
        assertTrue(!NoSniTransport.matchDnsName("*.example.com", "xexample.com"))
        assertTrue(!NoSniTransport.matchDnsName("*.top", "example.com"))
        assertTrue(!NoSniTransport.matchDnsName("example.com", "api.example.com"))
    }

    @Test
    fun `证书里的 SAN 说了算_不是本机的域名就必须拒`() {
        val cert = java.security.KeyStore.getInstance("PKCS12").apply {
            testResource("/tls_test.p12").use { load(it, "changeit".toCharArray()) }
        }.getCertificate("t").let { it as X509Certificate }
        assertTrue("SAN=localhost 的证书必须认下 localhost", NoSniTransport.certNamesHost(cert, "localhost"))
        assertTrue("不能因为 IP 就连过去", !NoSniTransport.certNamesHost(cert, "127.0.0.1"))
        assertTrue(!NoSniTransport.certNamesHost(cert, "api.example.com"))
    }
}
