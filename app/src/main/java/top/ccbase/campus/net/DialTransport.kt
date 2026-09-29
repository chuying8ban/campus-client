package top.ccbase.campus.net

import android.content.Context
import top.ccbase.campus.update.ByteSink
import top.ccbase.campus.update.StreamHead
import java.net.SocketException
import javax.net.ssl.SSLException

/**
 * 会自己挑路子的传输层，并把选定的路线**记住**。
 *
 * ## 为什么需要它（真机实测，2026-09 示例市移动）
 * 同一个 IP、同一个端口、同一份 TLS 栈，**带域名的握手一律 Connection reset，不带 SNI 的握手秒通**
 * —— 拦截在链路上、按 SNI（以及握手指纹）挑客户端，跟协议版本、ALPN 都无关。
 *
 * ## 三条设计决定（都是踩出来的，别改回去）
 * 1. **先试"不带 SNI"**。看上去反直觉（正常网络下直连就好），但：
 *    - 对本站这种单站点服务，两条路完全等价（证书照常校验）；
 *    - 在被拦的网络里，它不需要先在链路设备那儿留下一次"被拦的握手"——
 *      真机上出现过"刚被 reset 之后紧接着的请求也失败、隔几秒才恢复"的现象；
 *    - 先走它，用户在坏网络上的**第一次请求就能成功**。
 * 2. **路线要持久记住**（SharedPreferences）。只探一次，之后启动直接走已定路线，
 *    既省一次往返，也避免每次启动都去撞一次被拦的握手。
 * 3. **POST 绝不自动重试**：加监控、提交选课可能已经在服务端生效，重试等于拿用户的操作赌运气。
 *    路线没定就先用 **GET /healthz** 探明（探针无副作用），而不是撞上了再补救。
 * 4. **"走不通"不只是"连接被重置"**（2026-09-26 放宽，改前先读 [isRouteBroken]）：
 *    握手被拒（`SSLException`）同样算这条路废了。旧判据只认 `SocketException`，
 *    于是正式域名的边缘一换成 Cloudflare（CF 必须靠 SNI 分流，不发 SNI 直接
 *    `ssl/tls alert handshake failure`）就**全量卡死** —— 异常既不翻转也不换路，
 *    界面永远停在「HTTPS 证书校验失败」，而服务端其实完好无损。
 */
class DialTransport(
    private val store: RouteStore = MemoryRouteStore(),
    private val direct: Transport = RealTransport(),
    private val noSni: Transport = NoSniTransport(),
) : Transport {

    /** 供界面/自检显示"现在走的是哪条路" */
    fun modeName(url: String): String = when (route(url)) {
        null -> "未探测（首次请求时自动选路）"
        true -> "不带 SNI（链路按 SNI 拦截）"
        false -> "直连（带 SNI）"
    }

    override fun call(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpReply {
        val host = hostOf(url)
        val mode = pickMode(url)
        return try {
            choose(mode).call(method, url, headers, body)
        } catch (e: Exception) {
            // 选定路线走不通：链路上的策略可能变了。翻转并记住，
            // 但**只当场重试幂等请求** —— POST 已经在路上，重试有副作用。
            if (!isRouteBroken(e)) throw e
            store.put(host, !mode)
            if (method != "GET") throw e
            try {
                choose(!mode).call(method, url, headers, body)
            } catch (e2: Exception) {
                throw swapFailed(e, e2)
            }
        }
    }

    /**
     * 二进制下载也走选定的路线 —— 否则就是你看到的那个「自检全绿、更新 Connection reset」。
     * 只有**一个字节都没写出去**时才允许换路重下；写了一半再换，下出来的是坏包。
     */
    override fun stream(url: String, sink: (ByteArray, Int, Long) -> Unit): Long {
        val host = hostOf(url)
        val mode = pickMode(url)
        var wrote = false
        val guard: (ByteArray, Int, Long) -> Unit = { b, n, t -> wrote = true; sink(b, n, t) }
        return try {
            choose(mode).stream(url, guard)
        } catch (e: Exception) {
            if (!isRouteBroken(e) || wrote) throw e
            store.put(host, !mode)
            choose(!mode).stream(url, guard)
        }
    }

    /**
     * 分片下载（断点续传）：同样是"同一台主机、同一个 URL，只是握手方式不同"的换路，
     * **不是**换包源 —— 换了包源续写下去就是两个文件的字节拼在一起。
     *
     * 规矩和 [stream] 一样、而且更要紧：只有**一个字节都没写出去**时才允许换路。
     * 已经往半包里追加过字节了还换路，等于把两条链路拿到的数据混在一个文件里；
     * 那种情况必须原样抛出去，交给上层的退避重试（它会在同一条路上重新发 Range）。
     */
    override fun streamRanged(
        url: String,
        offset: Long,
        ifRange: String?,
        decide: (StreamHead) -> ByteSink?,
    ): StreamHead {
        val host = hostOf(url)
        val mode = pickMode(url)
        var wrote = false
        val guard: (StreamHead) -> ByteSink? = { h ->
            val sink = decide(h)
            if (sink == null) null
            else { b: ByteArray, n: Int -> wrote = true; sink(b, n) }
        }
        return try {
            choose(mode).streamRanged(url, offset, ifRange, guard)
        } catch (e: Exception) {
            if (!isRouteBroken(e) || wrote) throw e
            store.put(host, !mode)
            choose(!mode).streamRanged(url, offset, ifRange, guard)
        }
    }

    /**
     * 这条路线是不是"在当前网络上走不通"。**顺着 cause 链找**：两条链路都会把底层异常包一层
     * （[NoSniTransport] 的 `withStage` 会套上阶段说明，JDK/Conscrypt 也会包装证书错误）。
     *
     * - `SocketException`：连接被重置 / 断（历来就认它）。
     * - `SSLException`：握手被拒 / 证书链不合格（含 `SSLHandshakeException`、`SSLPeerUnverifiedException`）
     *   —— **2026-09-26 那次事故补上的另一半**：正式域名的边缘一换成 Cloudflare
     *   （CF 必须靠 SNI 分流，不发 SNI 直接 `ssl/tls alert handshake failure`），全量用户当场卡死，
     *   因为旧判据只认 SocketException：异常既不翻转也不换路，界面永远停在「HTTPS 证书校验失败」，
     *   而服务端一切正常。证书真不合格时换路也会失败（照原样抛给用户），
     *   但**卡死在一条死路上、连试都不试另一条**，才是那次真正付出的代价。
     */
    private fun isRouteBroken(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is SocketException || t is SSLException) return true
            t = t.cause
        }
        return false
    }

    /**
     * 换路也不行：把**第一条**（带"断在哪一步"的）报上去，换路那条挂成原因 ——
     * 不然用户在户外只看到一句没头没尾的 reset。
     *
     * **类型要保住**：SocketException 要重建一个同类型的（上层靠这个类型决定还换不换路），
     * 其余（握手 / 证书类）**原样抛** —— 上层是按异常类型翻文案的，把
     * 「HTTPS 证书校验失败」包成 SocketException，用户看到的就只剩没信息量的「网络异常」。
     */
    private fun swapFailed(first: Exception, second: Exception): Exception =
        if (first is SocketException) {
            SocketException(first.message + "（换路后也不行：" + second.message + "）")
                .also { it.initCause(first) }
        } else {
            first.also { it.addSuppressed(second) }
        }

    private fun route(url: String): Boolean? = store.get(hostOf(url))

    private fun choose(useNoSni: Boolean): Transport = if (useNoSni) noSni else direct

    /**
     * 定路线：**先试不带 SNI**（GET /healthz，无副作用），成功了就用它；
     * 失败再试直连；两条都不通就按"不带 SNI"记（坏网络里那多半是对的，
     * 而且下次真能连上时，上面的翻转逻辑会把路线纠回来）。
     */
    private fun pickMode(url: String): Boolean {
        val host = hostOf(url)
        store.get(host)?.let { return it }
        val health = healthOf(url)
        val ok = { t: Transport -> try { t.call("GET", health, emptyMap(), null).code > 0 } catch (e: Exception) { false } }
        val mode = when {
            // IP 字面量本来就发不出 SNI（RFC 6066），"直连"在这个场景下和"不带 SNI"等价，
            // 那就先试直连 —— 自检里的 DoH（https://223.5.5.5/resolve）就靠这条保持原样
            isIpLiteral(host) -> if (ok(direct)) false else true
            ok(noSni) -> true
            ok(direct) -> false
            else -> true
        }
        store.put(host, mode)
        return mode
    }

    /** 从任意请求 URL 推出同源的 /healthz（探针必须打在同一台服务器上） */
    internal fun healthOf(url: String): String {
        val i = url.indexOf("://")
        val start = if (i >= 0) i + 3 else 0
        val slash = url.indexOf('/', start)
        val origin = if (slash >= 0) url.substring(0, slash) else url
        return "$origin/healthz"
    }

    /** IPv4/IPv6 字面量（这类主机不会发 SNI，见上文） */
    internal fun isIpLiteral(host: String): Boolean =
        host.contains(':') || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)

    internal fun hostOf(url: String): String {
        val i = url.indexOf("://")
        val start = if (i >= 0) i + 3 else 0
        val rest = url.substring(start)
        return rest.substringBefore('/').substringBefore(':')
    }
}

/** 路线记忆的落点。生产和测试各一份，传输层本身不碰 Android API。 */
interface RouteStore {
    fun get(host: String): Boolean?
    fun put(host: String, noSni: Boolean)
}

class MemoryRouteStore : RouteStore {
    private val m = mutableMapOf<String, Boolean>()
    override fun get(host: String): Boolean? = m[host]
    override fun put(host: String, noSni: Boolean) { m[host] = noSni }
}

/** 落盘：key 带 host，避免以后多域名时互相污染 */
class PrefsRouteStore(ctx: Context) : RouteStore {
    private val sp = ctx.applicationContext.getSharedPreferences("net_route", Context.MODE_PRIVATE)
    override fun get(host: String): Boolean? =
        if (sp.contains(KEY + host)) sp.getBoolean(KEY + host, true) else null
    override fun put(host: String, noSni: Boolean) { sp.edit().putBoolean(KEY + host, noSni).apply() }
    /**
     * 前缀带版本号：1.28 及以前记下的路线是**错的**（那时"不带 SNI"其实还是把域名发出去被 RST），
     * 换前缀让旧记录自然作废，免得升级后先按旧的错路线走一次（登录/加监控这种 POST 一次都不能白挨）。
     *
     * 2026-09-26 那天**特意没有**动它：那次是"边缘换了、旧路线作废"，但换路判据补上握手失败之后，
     * 客户端自己会纠回来（先撞一次、当场翻转并记住），不需要清掉全量用户的记忆 ——
     * 清记忆意味着所有人升级后都要重探一遍，代价比收益大。
     */
    private companion object { const val KEY = "no_sni:v2:" }
}

/**
 * 全 App 共用一个传输层：路线记忆必须是**同一个实例**，
 * 否则每次 `CampusApi()` 都新建一个，等于每个请求重探一次。
 * 测试里不会调 `init`，用内存版即可。
 */
object Net {
    @Volatile
    var dial: DialTransport = DialTransport()

    fun init(ctx: Context) { dial = DialTransport(PrefsRouteStore(ctx)) }
}
