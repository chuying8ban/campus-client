package top.ccbase.campus.net

import top.ccbase.campus.update.ByteSink
import top.ccbase.campus.update.StreamHead
import top.ccbase.campus.update.streamHeadOf
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.SocketException
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 不发 SNI 的 HTTPS 客户端：自己建 SSLSocket 握手、自己写 HTTP/1.1、自己校验证书。
 *
 * ## 为什么不能用 HttpsURLConnection + 自定义 SSLSocketFactory（试过，没用）
 * Android 的 HttpsURLConnection（OkHttp 内核）在握手前会**按 URL 的域名显式设 SNI**
 * （`SSLParameters.setServerNames(SNIHostName(host))`），所以在工厂里把 host 换成 IP
 * 根本拦不住它 —— 真机自检里业务请求依旧 `Connection reset` 就是被这一手打回来的。
 * 只有自己建 SSLSocket（不给 host）才完全由我们决定 ClientHello 里有什么。
 *
 * ## 安全没打折
 * 1) 证书链：握手后立刻用平台信任库（测试可注入）校验一遍，不合格就不发请求；
 * 2) 域名：握手后自己比对证书的 SAN（见 [certNamesHost]）。
 * 我们只是"不说出要访问谁"，不是"不检查对面是谁"。
 */
class NoSniTransport(
    private val trust: X509TrustManager = platformTrustManager(),
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) : Transport {

    override fun call(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply =
        callFor(method, url, headers, body, readTimeoutMs)

    /**
     * 无 SNI 链路同样要能接受更长的读超时 —— 这台手机在移动网下**只有这条路走得通**，
     * 而 AI 生成恰恰是最慢的接口。不改这里的话，修了直连、坏在移动网，等于没修。
     */
    override fun callFor(
        method: String, url: String, headers: Map<String, String>, body: String?, readMs: Int,
    ): HttpReply {
        val u = URL(url)
        // 只有 https 才谈得上 SNI；其余（自检里可能出现的 http）交回常规实现
        if (u.protocol != "https") return RealTransport().callFor(method, url, headers, body, readMs)

        val host = u.host
        val port = if (u.port > 0) u.port else 443
        val path = u.file.ifEmpty { "/" }
        val addr = resolve(host).firstOrNull() ?: throw java.net.UnknownHostException(host)

        // 握手（含重试）：地址一律用**字面量** —— 传 InetAddress 对象时它自带域名，
        // Android 的 Conscrypt 会从 connect 地址里取出域名发 SNI，"不带 SNI"就白做了（真机实测）。
        val ssl = openWithRetry(RawTls.literal(addr), port, host, readMs = readMs)
        var stage = "写请求"
        try {
            val head = buildHead(method, path, host, headers, body)
            val out = ssl.outputStream
            out.write(head.toByteArray(Charsets.UTF_8))
            if (body != null) out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()

            stage = "读响应"
            return parseResponse(readAll(ssl.inputStream))
        } catch (e: Exception) {
            throw withStage(stage, e)
        } finally {
            runCatching { ssl.close() }
        }
    }

    /**
     * 拼请求头。抽成纯函数是为了能被单测直接验（不需要真网络）。
     *
     * `Accept-Encoding` 只在调用方**没自己声明**时才补 identity。以前是无条件补，
     * 于是业务请求（自带 `Accept-Encoding: gzip`）在这条链路上发出去的是
     * 「identity + gzip」两个同名头 —— 服务端按第一个值判断，永远回明文，
     * 结果 1.37 起做的那套压缩**在无 SNI 链路上从来没生效过**（2026-09-17 实测确认：
     * 带两个头拿到的就是 87KB 明文，而这条链路正是这台手机在移动网下唯一能走通的）。
     * APK 下载不声明这个头，所以它照旧拿 identity —— 二进制绝不经过压缩层。
     */
    internal fun buildHead(
        method: String, path: String, host: String,
        headers: Map<String, String>, body: String?,
    ): String = buildString {
        append("$method $path HTTP/1.1\r\n")
        append("Host: $host\r\n")
        append("Connection: close\r\n")   // 收完即断，下面就能靠 EOF 判断读完了
        if (headers.keys.none { it.equals("Accept-Encoding", ignoreCase = true) }) {
            append("Accept-Encoding: identity\r\n")
        }
        headers.forEach { (k, v) -> append("$k: $v\r\n") }
        if (body != null) {
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        }
        append("\r\n")
    }

    /**
     * 握手 + 校验证书，失败自动重试（默认 3 次，间隔 [HANDSHAKE_RETRY_DELAY_MS]）。
     *
     * 为什么**连 POST 也能重试**：走到这里一个字节的请求都还没发出去，重试的语义与"第一次尝试"
     * 完全相同，不存在"提交了两次"的风险。真机上那条链路会抖：同一条路第一次 RST、第二次就通。
     * 证书不合格（不是"没连上"）不重试 —— 那是确定性的，重试只是白白多连两次。
     */
    private fun openWithRetry(ip: String, port: Int, host: String, readMs: Int = readTimeoutMs): SSLSocket {
        var last: Exception? = null
        repeat(HANDSHAKE_ATTEMPTS) { i ->
            try {
                val ssl = RawTls.connectNoSni(ip, port, connectTimeoutMs)
                ssl.soTimeout = readMs
                try {
                    val chain = ssl.session.peerCertificates.map { it as X509Certificate }.toTypedArray()
                    val auth = if (chain[0].publicKey.algorithm.startsWith("EC")) "EC" else "RSA"
                    trust.checkServerTrusted(chain, auth)                  // 链（握手已验一次，这里是明账）
                    if (!certNamesHost(chain[0], host)) {                  // 域名校验自己把关
                        throw SSLPeerUnverifiedException("证书不是 $host 的（SAN 对不上）")
                    }
                } catch (e: Exception) {
                    runCatching { ssl.close() }
                    throw e
                }
                return ssl
            } catch (e: SSLPeerUnverifiedException) {
                throw e                                            // 域名对不上：确定性失败
            } catch (e: Exception) {
                if (isCertFailure(e)) throw e                       // 证书链不合格：确定性失败，重试没意义
                last = e
                if (i < HANDSHAKE_ATTEMPTS - 1) Thread.sleep(HANDSHAKE_RETRY_DELAY_MS)
            }
        }
        throw withStage("握手", last ?: java.io.IOException("连不上 $host"))
    }

    /**
     * 把"断在哪一步"贴进异常里。
     * SocketException 要保住**类型**（DialTransport 靠它决定换不换路线），所以重建一个同类型的。
     * 其余类型换成 IOException 带阶段说明 —— 用户在户外只有一块屏幕，异常里没阶段就只能靠猜。
     */
    private fun withStage(stage: String, e: Exception): Exception = when {
        e is SSLPeerUnverifiedException || e is SSLHandshakeException -> e
        e is SocketException && e !is SSLException ->
            SocketException("$stage：${e.message}").also { it.initCause(e) }
        e is SocketException -> e
        else -> java.io.IOException("[$stage] ${e.javaClass.simpleName}：${e.message}", e)
    }

    /**
     * 是不是"证书不合格"。注意握手阶段的证书错误会被包成 SSLHandshakeException，
     * 光看外层类型认不出来 —— 得顺着 cause 链找（JDK/Conscrypt 都会把 ValidatorException 塞在里面）。
     */
    private fun isCertFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is java.security.cert.CertificateException) return true
            if (t is java.security.cert.CertPathValidatorException) return true
            t = t.cause
        }
        return false
    }

    /**
     * 流式下载二进制（更新包）。**必须**和业务请求共用同一条链路 —— 这里同样走 RawTls + 字面量地址。
     *
     * 与 [call] 的区别：不解码成文本、不缓存整包，边读边交给 sink（7MB 要报进度，也要能中断）。
     * 只认 `Content-Length` 或读到 EOF（我们发了 Connection: close）；遇到 chunked 直接报错，
     * 宁可失败也别写出一个坏安装包。
     */
    override fun stream(url: String, sink: (ByteArray, Int, Long) -> Unit): Long {
        val u = URL(url)
        if (u.protocol != "https") return RealTransport().stream(url, sink)
        val host = u.host
        val port = if (u.port > 0) u.port else 443
        val path = u.file.ifEmpty { "/" }
        val addr = resolve(host).firstOrNull() ?: throw java.net.UnknownHostException(host)

        val ssl = openWithRetry(RawTls.literal(addr), port, host, readMs = 60_000)
        try {
            val head = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n" +
                "Accept: application/octet-stream\r\n\r\n"
            ssl.outputStream.write(head.toByteArray(Charsets.UTF_8))
            ssl.outputStream.flush()

            val ins = ssl.inputStream
            val headerText = String(readHeaderBlock(ins), Charsets.ISO_8859_1)
            val statusLine = headerText.lineSequence().firstOrNull().orEmpty()
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw java.io.IOException("响应头读不出来：$statusLine")
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            if (headerText.contains("chunked", ignoreCase = true)) {
                throw java.io.IOException("服务端用了分块传输，这条链路不支持（拒绝写出可能损坏的安装包）")
            }
            val len = Regex("(?i)content-length:\\s*(\\d+)").find(headerText)
                ?.groupValues?.get(1)?.toLongOrNull()

            val buf = ByteArray(64 * 1024)
            var total = 0L
            var remain = len ?: -1L
            while (true) {
                if (remain == 0L) break
                val want = if (remain > 0) minOf(buf.size.toLong(), remain).toInt() else buf.size
                val n = ins.read(buf, 0, want)
                if (n <= 0) break
                total += n
                if (remain > 0) remain -= n
                sink(buf, n, total)
                if (total > MAX_DOWNLOAD) throw java.io.IOException("下载超过上限（$MAX_DOWNLOAD 字节）")
            }
            if (len != null && total != len) throw java.io.IOException("下载不完整：$total / $len 字节")
            return total
        } finally {
            runCatching { ssl.close() }
        }
    }

    /**
     * 分片下载（断点续传）。这条链路是示例市移动下**唯一能走通**的那条，
     * 也就意味着防断机制真正生效的地方就是这里。
     *
     * 读超时用 20 秒（不是 [stream] 的 60 秒）：现在超时的代价只是"退避一下、接着上次的
     * 字节再要一段"，跟引擎的停滞阈值同一个口径 —— 卡住了就早点断开重来，别让用户干等。
     */
    override fun streamRanged(
        url: String,
        offset: Long,
        ifRange: String?,
        decide: (StreamHead) -> ByteSink?,
    ): StreamHead {
        val u = URL(url)
        if (u.protocol != "https") return RealTransport().streamRanged(url, offset, ifRange, decide)
        val host = u.host
        val port = if (u.port > 0) u.port else 443
        val path = u.file.ifEmpty { "/" }
        val addr = resolve(host).firstOrNull() ?: throw java.net.UnknownHostException(host)

        val ssl = openWithRetry(RawTls.literal(addr), port, host, readMs = 20_000)
        try {
            ssl.outputStream.write(
                buildDownloadHead(path, host, offset, ifRange).toByteArray(Charsets.UTF_8)
            )
            ssl.outputStream.flush()

            val ins = ssl.inputStream
            val headerText = String(readHeaderBlock(ins), Charsets.ISO_8859_1)
            val statusLine = headerText.lineSequence().firstOrNull().orEmpty()
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw java.io.IOException("响应头读不出来：$statusLine")
            val head = streamHeadOf(code) { name -> headerValue(headerText, name) }
            if (head.code !in 200..206) return head          // 交给引擎去说人话
            if (headerText.contains("chunked", ignoreCase = true)) {
                throw java.io.IOException("服务端用了分块传输，这条链路不支持（拒绝写出可能损坏的安装包）")
            }
            val sink = decide(head) ?: return head           // 这一段不能用：一个字节都不收

            val buf = ByteArray(64 * 1024)
            var total = 0L
            var remain = head.contentLength.takeIf { it >= 0 } ?: -1L
            while (true) {
                if (remain == 0L) break
                val want = if (remain > 0) minOf(buf.size.toLong(), remain).toInt() else buf.size
                val n = ins.read(buf, 0, want)
                if (n <= 0) break
                total += n
                if (remain > 0) remain -= n
                sink(buf, n)
                if (total > MAX_DOWNLOAD) throw java.io.IOException("下载超过上限（$MAX_DOWNLOAD 字节）")
            }
            return head
        } finally {
            runCatching { ssl.close() }
        }
    }

    /**
     * 分片下载的请求头。抽成纯函数是为了能在单测里直接钉死
     * （尤其是 `Range` 有没有发出去、`Accept-Encoding` 有没有被压成两个）。
     */
    internal fun buildDownloadHead(path: String, host: String, offset: Long, ifRange: String?): String =
        buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Connection: close\r\n")
            append("Accept: application/octet-stream\r\n")
            // 绝不声明 gzip：装了包的字节偏移会跟 Range 对不上，续传拼出来必是坏包
            append("Accept-Encoding: identity\r\n")
            if (offset > 0) append("Range: bytes=$offset-\r\n")
            if (!ifRange.isNullOrBlank()) append("If-Range: $ifRange\r\n")
            append("\r\n")
        }

    /** 从已读出的响应头文本里取一个头的值（头名大小写不敏感）。 */
    internal fun headerValue(headerText: String, name: String): String? =
        Regex("(?im)^" + Regex.escape(name) + ":\\s*(.+?)\\s*$")
            .find(headerText)?.groupValues?.get(1)

    /** 一直读到空行（\r\n\r\n）为止，返回含结束空行的原始字节。 */
    private fun readHeaderBlock(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var state = 0
        while (out.size() < 32 * 1024) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return out.toByteArray()
                else -> if (b == '\r'.code) 1 else 0
            }
        }
        return out.toByteArray()
    }

    private fun readAll(input: InputStream): ByteArray {
        // 我们发了 Connection: close，正常靠 EOF 结束；加上限防被喂爆内存。
        // （读超时会抛 SocketTimeoutException —— 那是错误，不能当空响应）
        val buf = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream()
        while (out.size() < MAX_BODY) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_BODY = 8 * 1024 * 1024
        private const val MAX_DOWNLOAD = 64L * 1024 * 1024

        /** 握手重试次数与间隔：链路上的拦截会抖，第一次被 RST 第二次往往就通 */
        private const val HANDSHAKE_ATTEMPTS = 3
        private const val HANDSHAKE_RETRY_DELAY_MS = 400L

        fun platformTrustManager(): X509TrustManager {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as java.security.KeyStore?)
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        fun isIpLiteral(host: String): Boolean = IPV4.matches(host) || host.contains(':')

        /**
         * 证书是不是"这台主机"的证书。
         *
         * 为什么要自己写：平台那个域名校验器**在 JDK 上对任何域名都返回 false**
         * （实测：同一个会话、证书 SAN 就是 localhost，`verify("localhost")` 依然 false ——
         * 因为 JDK 的 HttpsURLConnection 是在握手阶段完成域名校验的，这个 default 实现只
         * 用来表示"不覆盖"）。而我们的 socket 没有 host，握手阶段自然不校验，所以这一关
         * 必须自己把。Android 上那个实现是能用的，但不能只靠它。
         */
        fun certNamesHost(cert: X509Certificate, host: String): Boolean {
            val sans = cert.subjectAlternativeNames
            val wantIp = isIpLiteral(host)
            val dns = mutableListOf<String>()
            val ips = mutableListOf<String>()
            sans?.forEach { e ->
                val type = (e.getOrNull(0) as? Number)?.toInt() ?: return@forEach
                val v = e.getOrNull(1) as? String ?: return@forEach
                when (type) {
                    2 -> dns += v          // dNSName
                    7 -> ips += v          // iPAddress
                }
            }
            if (wantIp) return ips.any { it == host }
            // 有 SAN 就只看 SAN（RFC 6125）；一个 SAN 都没有的老证书才回退 CN
            val names = if (dns.isNotEmpty()) dns else listOfNotNull(commonNameOf(cert))
            return names.any { matchDnsName(it, host) }
        }

        /** RFC 6125 的核心：全等，或者 `*.` 只替**一个**标签 */
        fun matchDnsName(pattern: String, host: String): Boolean {
            val p = pattern.lowercase().trimEnd('.')
            val h = host.lowercase().trimEnd('.')
            if (p == h) return true
            if (!p.startsWith("*.")) return false
            val suffix = p.substring(1)                  // ".example.com"
            if (suffix.count { it == '.' } < 2) return false   // "*.com" 这种别认
            if (!h.endsWith(suffix)) return false
            val left = h.substring(0, h.length - suffix.length)
            return left.isNotEmpty() && !left.contains('.')
        }

        private fun commonNameOf(cert: X509Certificate): String? =
            Regex("(?:^|,)CN=([^,]+)", RegexOption.IGNORE_CASE)
                .find(cert.subjectX500Principal.name)?.groupValues?.get(1)?.trim()

        /** 解析 HTTP/1.1 响应（含 chunked）。抽成纯函数，方便拿字节直接测 */
        fun parseResponse(raw: ByteArray): HttpReply {
            val headEnd = indexOfHeaderEnd(raw)
            require(headEnd > 0) { "响应头不完整" }
            val head = String(raw, 0, headEnd, Charsets.ISO_8859_1)
            val bodyBytes = raw.copyOfRange(headEnd + 4, raw.size)

            val lines = head.split("\r\n")
            val code = lines.first().split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw IllegalStateException("状态行看不懂：" + lines.first())
            val h = HashMap<String, String>()
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val plain = if (h["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                dechunk(bodyBytes)
            } else bodyBytes
            // 我们自己声明了 Accept-Encoding: gzip，所以响应可能是压缩的。
            // 解压在**字节层**做（见 gunzip 的注释：转成 String 再解就已经毁了）。
            val body = if (isGzip(h["content-encoding"])) gunzip(plain) else plain
            return HttpReply(code, String(body, Charsets.UTF_8))
        }

        private fun indexOfHeaderEnd(b: ByteArray): Int {
            for (i in 0 until b.size - 3) {
                if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() && b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()) return i
            }
            return -1
        }

        /** 去掉 chunked 分块和长度行；尾部 trailer 忽略 */
        fun dechunk(b: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < b.size) {
                var j = i
                while (j < b.size && j < i + 16 && b[j] != 13.toByte()) j++
                val sizeStr = String(b, i, j - i, Charsets.ISO_8859_1).trim().substringBefore(';')
                val size = sizeStr.toIntOrNull(16) ?: break
                if (size == 0) break
                val start = j + 2
                if (start + size > b.size) break
                out.write(b, start, size)
                i = start + size + 2   // 跳过数据后的 CRLF
            }
            return out.toByteArray()
        }
    }
}
