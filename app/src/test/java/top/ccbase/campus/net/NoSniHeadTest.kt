package top.ccbase.campus.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无 SNI 链路拼出来的请求头。
 *
 * 钉死的是一个**真实失效**：这条链路以前无条件塞 `Accept-Encoding: identity`，
 * 而业务请求自带 `Accept-Encoding: gzip`，于是两个同名头一起发出去 ——
 * 服务端取第一个值，永远回明文。结果就是「压缩做了、移动网下省不下」：
 * 这条链路恰恰是示例市移动下唯一能走通的（另一条 TLS 会被 SNI 拦掉），
 * 也就是说那 4 倍流量**从来没省下来过**。
 *
 * 层测：拼头是纯函数，先在本地把它钉死，再谈真机。
 */
class NoSniHeadTest {

    private val t = NoSniTransport()

    private fun head(headers: Map<String, String>) =
        t.buildHead("GET", "/api/v2/plan", "api.example.com", headers, null)

    private fun count(h: String, name: String) =
        h.lines().count { it.startsWith("$name:", ignoreCase = true) }

    @Test
    fun `调用方声明了 gzip 就不许再自己塞 identity`() {
        val h = head(mapOf("Accept-Encoding" to "gzip", "X-Gzip-OK" to "1"))
        assertEquals("两个 Accept-Encoding 会让服务端永远回明文", 1, count(h, "Accept-Encoding"))
        assertTrue(h.contains("Accept-Encoding: gzip"))
        assertFalse("不能同时出现 identity", h.contains("identity"))
    }

    @Test
    fun `调用方没声明时才补 identity_安装包下载走的就是这条`() {
        val h = head(mapOf("Accept" to "application/json"))
        assertEquals(1, count(h, "Accept-Encoding"))
        assertTrue("二进制/未声明时必须拿到不压缩的字节", h.contains("Accept-Encoding: identity"))
    }

    @Test
    fun `头名大小写不敏感_别重复补`() {
        val h = head(mapOf("accept-encoding" to "gzip"))
        assertEquals(1, count(h, "Accept-Encoding"))
        assertFalse(h.contains("identity"))
    }

    @Test
    fun `请求行和 Host 还是老样子`() {
        val h = head(mapOf("Accept" to "application/json"))
        assertTrue(h.startsWith("GET /api/v2/plan HTTP/1.1\r\n"))
        assertTrue(h.contains("Host: api.example.com\r\n"))
        assertTrue("头要有结束空行", h.endsWith("\r\n\r\n"))
    }

    // ------------------------------------------------ 断点续传的请求头（这条链路才是主战场）

    private fun dl(offset: Long, ifRange: String? = null) =
        t.buildDownloadHead("/updates/campus-1.60-T1.apk", "api.example.com", offset, ifRange)

    @Test
    fun `续传必须真的把 Range 发出去`() {
        val h = dl(offset = 1_048_576, ifRange = "\"6aacdb73-71f744\"")
        assertTrue("少了 Range 就等于从 0 重下：$h", h.contains("Range: bytes=1048576-\r\n"))
        assertTrue("If-Range 让服务端在文件换过时直接回整包，省得我们盲接", h.contains("If-Range: \"6aacdb73-71f744\"\r\n"))
        assertEquals(1, count(h, "Range"))
        assertEquals(1, count(h, "If-Range"))
    }

    @Test
    fun `整包下载不发 Range_也别发空的 If-Range`() {
        val h = dl(offset = 0)
        assertFalse("offset=0 就是整包，发 Range 反而可能触发服务端 200/416 的歧义", h.contains("Range:"))
        assertFalse(h.contains("If-Range:"))
    }

    @Test
    fun `安装包下载绝不声明 gzip_压缩会让字节偏移全对不上`() {
        val h = dl(offset = 1024)
        assertEquals(1, count(h, "Accept-Encoding"))
        assertTrue("必须显式 identity", h.contains("Accept-Encoding: identity\r\n"))
        assertTrue("装包用二进制接收", h.contains("Accept: application/octet-stream\r\n"))
    }

    @Test
    fun `下载请求头是从响应头里取值的纯函数_大小写不敏感`() {
        val raw = "HTTP/1.1 206 Partial Content\r\nETag: \"abc\"\r\nContent-Range: bytes 1024-2047/4096\r\n\r\n"
        assertEquals("\"abc\"", t.headerValue(raw, "ETag"))
        assertEquals("\"abc\"", t.headerValue(raw, "etag"))
        assertEquals("bytes 1024-2047/4096", t.headerValue(raw, "Content-Range"))
        assertEquals(null, t.headerValue(raw, "Last-Modified"))
    }
}
