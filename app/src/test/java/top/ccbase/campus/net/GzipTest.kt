package top.ccbase.campus.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 服务端会对 JSON 压（nginx 4 倍），App 必须解得开 —— 而且必须在**字节层**解。
 *
 * 这一组用例钉住的都是"上线才发现"的病：
 *  · 解压做在 String 层 → 中文全毁（压缩字节当 UTF-8 读一遍就回不去了）；
 *  · 只在一条传输层上解 → 好网络走直连时接口全挂、坏网络反而正常，最难查；
 *  · 无上限解压 → 把内存交给服务端（哪怕服务端是我们自己的，也不该这么写）。
 */
class GzipTest {

    private fun gz(b: ByteArray): ByteArray =
        ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    /** 拼一个 HTTP/1.1 响应（头 + 体），和真链路一样按字节流给 parseResponse。 */
    private fun resp(body: ByteArray, extraHeaders: String = ""): HttpReply {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Connection: close\r\n" + extraHeaders + "\r\n"
        return NoSniTransport.parseResponse(head.toByteArray(Charsets.ISO_8859_1) + body)
    }

    private fun chunk(b: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("%x\r\n".format(b.size).toByteArray(Charsets.ISO_8859_1))
        o.write(b)
        o.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        o.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        return o.toByteArray()
    }

    @Test
    fun `没压缩的响应原样解析`() {
        val json = """{"ok":true,"名称":"校园助手"}"""
        assertEquals(json, resp(json.toByteArray(Charsets.UTF_8)).text)
    }

    @Test
    fun `gzip 的中文 JSON 要解开`() {
        // 中文是重点：解压若做在 String 层，这里就会变成乱码
        val json = """{"state":"done","说明":"已按你自己的课表排好了","课数":11}"""
        val r = resp(gz(json.toByteArray(Charsets.UTF_8)), "Content-Encoding: gzip\r\n")
        assertEquals(json, r.text)
    }

    @Test
    fun `nginx 常见的 chunked 加 gzip 一起也要解开`() {
        val json = """{"courses":["数学分析（I）","程序设计基础（B）"]}"""
        val raw = chunk(gz(json.toByteArray(Charsets.UTF_8)))
        val r = resp(raw, "Transfer-Encoding: chunked\r\nContent-Encoding: gzip\r\n")
        assertEquals(json, r.text)
    }

    @Test
    fun `压缩头大小写和位置都不影响`() {
        val json = """{"a":1}"""
        val r = resp(gz(json.toByteArray()), "Vary: Accept-Encoding\r\nCONTENT-ENCODING: GZIP\r\n")
        assertEquals(json, r.text)
    }

    @Test
    fun `响应头没写压缩就绝不能去解压`() {
        // 反过来的坑：没压缩却硬解 → 正常响应全挂。所以判断只看响应头，不看"我发过什么"。
        val raw = "not gzip at all".toByteArray()
        assertEquals("not gzip at all", resp(raw).text)
    }

    @Test
    fun `解压炸弹会被拒绝`() {
        // 9MB 的空格压完很小，解完超上限 → 必须拒绝，而不是把内存吃光
        val bomb = gz(ByteArray(9 * 1024 * 1024) { ' '.code.toByte() })
        var threw = false
        try {
            resp(bomb, "Content-Encoding: gzip\r\n")
        } catch (e: Exception) {
            threw = true
            assertTrue("报错要说清是解压后过大：${e.message}", e.message!!.contains("超过"))
        }
        assertTrue("解压无上限就是把内存交给服务端", threw)
    }

    @Test
    fun `业务请求声明接受压缩而探针不声明`() {
        val seen = mutableListOf<Map<String, String>>()
        val fake = Transport { _, _, h, _ ->
            seen += h
            HttpReply(200, """{"state":"none","steps":[],"percent":0}""")
        }
        // 选路探针（GET /healthz）：无副作用，所以**不应该**带压缩（它不解析体）
        DialTransport(store = MemoryRouteStore(), direct = fake, noSni = fake)
            .let { dial -> dial.call("GET", "https://example.invalid/api/v2/me", emptyMap(), null) }
        assertEquals("第一次请求是探针，必须不带任何请求头", emptyMap<String, String>(), seen.first())

        val api = CampusApi(base = "https://example.invalid", transport = fake)
        runBlocking { api.onboard("tok") }
        val last = seen.last()
        assertEquals("gzip", last["Accept-Encoding"])
        // 服务端只对显式带 X-Gzip-OK 的请求压缩：没有它，老版本 App（无解压代码）
        // 会被服务端一厢情愿的压缩打成「计划数据无法解析」（2026-09-17 真发生过）。
        assertEquals("1", last["X-Gzip-OK"])
        // 请求头就这几样：多出来的头要么是连接层自作主张，要么是有人偷偷加了东西。
        // 设备号/机型（2026-09-20）是作者身份的第二把钥匙，单独扣掉再看剩下的 ——
        // 它们平时是空串（单测里常常如此），那时根本不发这两个头，所以不能写死在全集里。
        assertEquals(
            setOf("Accept", "Accept-Encoding", "X-Gzip-OK", "Authorization"),
            last.keys - "X-Device-Id" - "X-Device-Model",
        )
    }

    @Test
    fun `设备号随请求发出去_服务端靠它把作者功能钉在这台设备上`() {
        // 用户 2026-09-20：「只有通过我现在用的这个设备登录我的账号的用户才能看到后台」。
        // App 这侧的义务就一条：把设备号报上去（真闸在服务端）。
        val seen = mutableListOf<Map<String, String>>()
        val fake = Transport { _, _, h, _ ->
            seen += h
            HttpReply(200, """{"state":"none","steps":[],"percent":0}""")
        }
        val api = CampusApi(base = "https://example.invalid", transport = fake)

        DeviceId.setForTest("dev-abc-123", "Xiaomi 17 Pro pudding")
        try {
            runBlocking { api.onboard("tok") }
            assertEquals("dev-abc-123", seen.last()["X-Device-Id"])
            // 机型必须一起报：设备号是十六进制，作者核对"批的是不是他那台小米"时只能靠机型
            // （他明确提醒过"我在别人的手机上也登过我的账号，别搞错"）。
            assertEquals("Xiaomi 17 Pro pudding", seen.last()["X-Device-Model"])
        } finally {
            DeviceId.setForTest("", "")
        }

        // 空的时候**不发**这两个头：服务端那边"没设备号" = 不是作者，不会因此报错
        runBlocking { api.onboard("tok") }
        assertFalse("空设备号不该出现在请求头里", seen.last().containsKey("X-Device-Id"))
        assertFalse("设备号为空时机型也别单独发", seen.last().containsKey("X-Device-Model"))
    }
}
