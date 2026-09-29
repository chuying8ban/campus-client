package top.ccbase.campus.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 钉住"连地址里不许带域名"这条不变量。
 *
 * 这是 2026-09-17 真机拉锯战的真正原因：`SSLSocketFactory.getDefault().createSocket()`（不给 host）
 * 看着已经没 SNI 了，可 Android 的 Conscrypt 会**从 connect 的地址里取主机名**再发 SNI，
 * 而 `InetAddress` 对象自带域名。JDK 不这么干 —— 所以本机怎么测都测不出来，只有真机会露馅。
 * 因此这里不测"行为"（行为在本机复现不了），只钉"我们传出去的一定是字面量"这条不变量。
 */
class RawTlsTest {

    @Test
    fun `连接地址必须是字面量_自带域名的 InetAddress 要把名字丢掉`() {
        val named = InetAddress.getByAddress("api.example.com", byteArrayOf(127, 0, 0, 1))

        // 前提成立：这个对象确实带着域名（否则这条用例就没在测东西）
        assertEquals("api.example.com", named.hostName)

        val lit = RawTls.literal(named)
        assertEquals("127.0.0.1", lit)
        assertFalse("名字必须丢掉，否则 Conscrypt 会拿它当 SNI 发出去：" + lit, lit.contains("ccbase"))
    }

    @Test
    fun `传域名进来也要先转成字面量`() {
        assertEquals("127.0.0.1", RawTls.literal("127.0.0.1"))
    }
}
