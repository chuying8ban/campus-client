package top.ccbase.campus.net

import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 「不发 SNI 的握手」唯一实现。
 *
 * ## 为什么单独放一份、且所有地方都必须用它
 * 2026-09-17 真机踩的坑：链路按 SNI 拦 TLS（带域名握手 → RST，不带 → 200），
 * 于是 App 里做了"不带 SNI 的客户端"。自检里的手搓探针能通，业务请求却一直 `Connection reset`，
 * 前后换了两轮"以为的原因"（socket 工厂、SSLContext），都不对。
 *
 * 真正的原因是**连接地址**：
 * ```
 * InetSocketAddress(InetAddress.getByName("api.example.com"), 443).getHostString() == "api.example.com"      // ✗ 带域名
 * InetSocketAddress("203.0.113.7", 443).getHostString()                                    == "203.0.113.7"     // ✓ 字面量
 * ```
 * `SSLSocketFactory.getDefault().createSocket()`（不给 host）本身没错，但 Android 的 Conscrypt 会在
 * `connect(InetSocketAddress)` 时**从地址里取主机名**发 SNI —— 传 `InetAddress` 对象它自带域名，
 * 于是"不带 SNI"白做了。JDK 不会这么做（所以本地测试一直绿），只有真机会露馅。
 *
 * 结论：**连地址一律只传 IP 字面量字符串**，并且整个 App 只有这一处开 socket 的代码，
 * 让"探针能通"和"业务能通"由构造保证一致，而不是靠人对齐两段相似的代码。
 */
object RawTls {

    /** 永远是"字面量"：哪怕传进来的是域名，也先解析成 IP，再把**名字丢掉** */
    fun literal(hostOrIp: String): String = InetAddress.getByName(hostOrIp).hostAddress

    /** 同上，但输入是已经解析好的 InetAddress（它可能自带域名，必须丢掉） */
    fun literal(addr: InetAddress): String = addr.hostAddress

    /**
     * 建一个不发 SNI 的 SSLSocket 并完成握手（**不校验证书、不写请求**，那两件事由调用方负责）。
     *
     * @param hostOrIp 域名或 IP；内部一律转成 IP 字面量
     */
    fun connectNoSni(hostOrIp: String, port: Int, timeoutMs: Int): SSLSocket {
        // Kotlin 里 SSLSocketFactory.getDefault() 的静态类型是 SocketFactory，需强转
        val f = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = f.createSocket() as SSLSocket
        ssl.connect(InetSocketAddress(literal(hostOrIp), port), timeoutMs)
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        return ssl
    }
}
