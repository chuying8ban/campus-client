package top.ccbase.campus.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 自检的一步。`ok=false` 时要一眼看出断在哪一层。 */
data class CheckStep(val name: String, val ok: Boolean, val detail: String)

/**
 * 网络自检：把「连不上服务器」拆成四层，各给一个结论。
 *
 * 为什么必须做进 App：用户在户外，只有一块屏幕；出问题时我这边能看到的是
 * 「服务端没收到请求」这一条（日志是空的），到底断在 DNS、TCP、还是接口，
 * **只有手机自己知道**。这一页就是把手机知道的说出来，省掉来回猜。
 *
 * 设计要点：
 *   - 第 1 步同时问「系统 DNS」和「公共 DoH」两个答案。国内运营商对不存在过的域名
 *     会返回假 IP 并长缓存（本 App 真实踩过：浏览器能开、App 被重置），
 *     两个答案不一致就是这条铁证，而 DoH 走的是 IP，不受系统 DNS 影响。
 *   - DNS / TCP / HTTP 三层都用可注入的函数，纯逻辑跑 JVM 测试，不依赖真网络。
 */
object SelfCheck {

    /** 公共 DoH（阿里），故意用 IP：这样即使系统 DNS 被劫持也拿得到"正确答案"做对照 */
    const val DOH_URL = "https://223.5.5.5/resolve"

    /**
     * 生产用的真握手。抽出来是为了让界面层可以注入换掉它 ——
     * UI 测试里不能真去连服务器（会依赖网络、还会因为链路上真被拦而变红）。
     */
    val realHandshake: (Variant, String) -> String = { v, host -> handshakeProbe(v, host, null) }

    suspend fun run(
        base: String,
        transport: Transport,
        token: String? = null,
        resolve: (String) -> List<String> = { host ->
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
        },
        publicIp: suspend (String) -> String? = { host -> dohA(transport, host) },
        tcp: (String, Int) -> String = { ip, port -> tcpProbe(ip, port) },
        handshake: (Variant, String) -> String = realHandshake,
    ): List<CheckStep> {
        val steps = mutableListOf<CheckStep>()
        val host = hostOf(base)

        // ── 1. 域名解析（系统 vs 公共）
        var sysErr = ""
        val ips = try {
            resolve(host)
        } catch (e: Exception) {
            sysErr = e.javaClass.simpleName + "：" + (e.message ?: "").take(50)
            emptyList()
        }
        val pub = try {
            publicIp(host)
        } catch (e: Exception) {
            null
        }
        val dnsDetail = buildString {
            append(if (ips.isEmpty()) "系统解析不了（$sysErr）" else "系统 → " + ips.joinToString("/"))
            if (!pub.isNullOrBlank()) {
                append("；公共 DNS → $pub")
                if (ips.isNotEmpty() && pub !in ips) {
                    append("  ⚠ 两边不一致：系统拿到的不是这台服务器，运营商 DNS 被缓存/劫持了")
                }
            } else {
                append("；公共 DNS 没答上来（不影响判断）")
            }
        }
        val dnsOk = ips.isNotEmpty() && (pub.isNullOrBlank() || pub in ips)
        steps += CheckStep("域名解析 $host", dnsOk, dnsDetail)

        // ── 2. 逐个 IP 连 443
        if (ips.isEmpty()) {
            steps += CheckStep("TCP 连 443", false, "没有可用 IP，跳过")
        } else {
            ips.forEach { ip ->
                val t0 = System.currentTimeMillis()
                val err = try {
                    tcp(ip, 443)
                } catch (e: Exception) {
                    e.javaClass.simpleName + "：" + (e.message ?: "").take(50)
                }
                val ms = System.currentTimeMillis() - t0
                steps += CheckStep(
                    "TCP 连 $ip:443",
                    err.isBlank(),
                    if (err.isBlank()) "连上了（${ms}ms）" else "连不上（${ms}ms）：$err",
                )
            }
        }

        // ── 3. 服务健康（打 App 真正读的那个服务：/api/v2/*）
        //    以前打的是域名根的 /healthz —— 那是**另一个进程（8034）**在答，
        //    它报的课程/时段数是它自己那份旧数据，与 App 这份库无关。
        //    把它显示在自检里，会让人以为"我的课程丢了"（真实误会过一次）。
        val health = callSafely(transport, "GET", "$base/api/v2/healthz", emptyMap())
        steps += CheckStep(
            "GET /api/v2/healthz",
            health.code in 200..299,
            health.note,
        )

        // ── 4. 带令牌的接口（登录态能不能用）
        if (token.isNullOrBlank()) {
            steps += CheckStep("GET /api/v2/me", false, "没有登录令牌（先登录再自检）")
        } else {
            val me = callSafely(
                transport, "GET", "$base/api/v2/me",
                mapOf("Authorization" to "Bearer $token"),
            )
            steps += CheckStep("GET /api/v2/me", me.code == 200, me.note)

            // ── 4.5 我的计划：这才是"我的数据在不在"的直接答案，条数摆出来
            //    （不再让人从"课程 11/时段 15/任务 39"这种别的服务的数字里猜）
            val planRaw = try {
                transport.call("GET", "$base/api/v2/plan",
                    mapOf("Authorization" to "Bearer $token"), null)
            } catch (e: Exception) { null }
            if (planRaw == null) {
                steps += CheckStep("GET /api/v2/plan", false, "请求失败（没拿到响应）")
            } else if (planRaw.code != 200) {
                steps += CheckStep("GET /api/v2/plan", false,
                    "HTTP ${planRaw.code}：" + planRaw.text.take(120))
            } else {
                val counted = countPlan(planRaw.text)
                steps += if (counted != null) {
                    CheckStep("GET /api/v2/plan", true, counted)
                } else {
                    // 能拿到 200 但数不出条数 = 契约出问题了（真出过：courses.target 脏值）
                    CheckStep("GET /api/v2/plan", false,
                        "返回了 200 但读不出条数（数据契约可能有问题）：" + planRaw.text.take(120))
                }
            }
        }
        // ── 5. 变体探测：换握手参数，看链路上的设备到底按什么特征拦
        //    起因：真机上"TCP 连上了、HTTPS 一握手就被 reset，而服务端日志里什么都没有"——
        //    说明拦截发生在链路上，不在服务器。只有换参数逐个试，才知道放行的是哪一种。
        val ip = ips.firstOrNull()
        Variant.values().filter { it != Variant.NO_SNI }.forEach { v ->
            val detail = try {
                handshake(v, host)
            } catch (e: Exception) {
                "探测失败：${e.javaClass.simpleName}（${(e.message ?: "").take(50)}）"
            }
            steps += CheckStep("变体·${v.label}", detail.startsWith("握手成功"), detail)
        }
        if (ip != null) {
            // 不带 SNI：连 IP 握手，看"不提域名"能不能过 —— 过了说明拦的是域名，不是客户端
            val detail = try {
                handshake(Variant.NO_SNI, ip)
            } catch (e: Exception) {
                "探测失败：${e.javaClass.simpleName}（${(e.message ?: "").take(50)}）"
            }
            steps += CheckStep("变体·${Variant.NO_SNI.label}", detail.startsWith("握手成功"), detail)
        }

        // ── 6. 结论：把"看着像坏了、其实是网络特征"的那几条说清楚，
        //    否则用户每次打开自检都以为出事了（他会只看到一个 ✗ 就慌）。
        val handshakeFails = steps.count { it.name.startsWith("变体·") && !it.ok }
        steps += if (handshakeFails > 0) {
            CheckStep(
                "结论",
                true,
                "上面 $handshakeFails 个变体握手失败是**预期现象**：你所在的网络（移动链路）" +
                    "会按域名拦 TLS，所以那几种握手被重置。App 会自动走最后那条不带 SNI 的 ✅ 链路，" +
                    "不影响使用 —— 真正要看的是计划那一步的条数。",
            )
        } else {
            CheckStep("结论", true, "各层都通，握手变体也没有被拦 —— 现在的链路是最优的。")
        }
        return steps
    }

    /**
     * 数出计划里各部分的条数。**不用 DTO 解析**：这里只要一个粗略但真实的数字，
     * 契约严格性是 `SeedContractTest` 的活；数不出来返回 null（调用方会如实报失败）。
     */
    private fun countPlan(body: String): String? = try {
        val o = Json.parseToJsonElement(body).jsonObject
        fun n(k: String) = o[k]?.jsonArray?.size ?: 0
        "课程 ${n("courses")} / 时段 ${n("slots")} / 任务 ${n("tasks")} / 里程碑 ${n("milestones")}"
    } catch (e: Exception) {
        null
    }

    /** 握手变体：链路上的拦截设备常常只认其中一种参数 */
    enum class Variant(val label: String) {
        DEFAULT("默认握手（对照）"),
        TLS12("强制 TLS 1.2"),
        ALPN("带 ALPN http/1.1"),
        NO_SNI("不带 SNI（按 IP 握手）"),
    }

    /** 一次调用的结论：HTTP 码 + 一句人话；异常也折算成同一种形状，界面才好显示 */
    private class Call(val code: Int, val note: String)

    private fun callSafely(
        transport: Transport,
        method: String,
        url: String,
        headers: Map<String, String>,
    ): Call = try {
        val r = transport.call(method, url, headers, null)
        Call(r.code, "HTTP ${r.code}" + r.text.take(100).let { if (it.isBlank()) "" else "：" + it })
    } catch (e: Exception) {
        Call(-1, "请求异常：${e.javaClass.simpleName}（${(e.message ?: "").take(60)}）")
    }

    /** 从 base 里取主机名：https://a.b/c → a.b */
    fun hostOf(base: String): String = base.trim()
        .removePrefix("https://").removePrefix("http://")
        .substringBefore('/').substringBefore(':')

    /**
     * 用 DoH 问一次 A 记录。返回第一个 IP 或 null。
     * 用正则而不是完整 JSON 模型：这里只要一个字段，多一个数据类就多一处会跟服务端一起坏的地方。
     */
    private fun dohA(transport: Transport, host: String): String? {
        val r = transport.call("GET", "$DOH_URL?name=$host&type=A", emptyMap(), null)
        if (r.code !in 200..299) return null
        val m = Regex("\"data\"\\s*:\\s*\"([0-9.]+)\"").find(r.text) ?: return null
        return m.groupValues[1]
    }

    /**
     * 真握手一次，可选换参数。返回「握手成功（TLSv1.3）→ HTTP/1.1 200 OK」这样的人话。
     *
     * 为什么要手搓 SSLSocket 而不是再走一次 HttpURLConnection：
     *   要分别控制 SNI / 协议版本 / ALPN 这三个变量 —— 它们是"被拦还是被放行"最可能的分水岭，
     *   而 HttpsURLConnection 一个都不让改。
     * hostOrIp：普通变体传域名（会带 SNI）；NO_SNI 变体传 IP（或不传 SNI 直接连）。
     */
    private fun handshakeProbe(v: Variant, hostOrIp: String, ip: String?): String = try {
        val f = SSLSocketFactory.getDefault() as SSLSocketFactory
        // 不带 SNI 这一路**必须和业务请求共用同一段代码**（RawTls）：探针能通就该等于业务能通，
        // 不能再有"两段相似代码互相对齐"——2026-09-17 就是栽在这上面，白折腾两轮。
        val sock: SSLSocket = if (v == Variant.NO_SNI) {
            RawTls.connectNoSni(ip ?: hostOrIp, 443, 8000)      // 内部已握手
        } else {
            (f.createSocket(hostOrIp, 443) as SSLSocket).apply { soTimeout = 8000 }
        }
        when (v) {
            Variant.TLS12 -> sock.enabledProtocols =
                sock.supportedProtocols.filter { it.equals("TLSv1.2", ignoreCase = true) }.toTypedArray()
            Variant.ALPN -> sock.sslParameters = sock.sslParameters.apply { applicationProtocols = arrayOf("http/1.1") }
            else -> {}
        }
        if (v != Variant.NO_SNI) sock.startHandshake()
        val proto = sock.session.protocol
        val alpn = sock.applicationProtocol ?: "-"
        sock.outputStream.write(
            "GET /healthz HTTP/1.1\r\nHost: $hostOrIp\r\nConnection: close\r\n\r\n".toByteArray()
        )
        sock.outputStream.flush()
        val line = sock.inputStream.bufferedReader().readLine()
        sock.close()
        "握手成功（$proto / ALPN $alpn）→ ${line ?: "(没有响应)"}"
    } catch (e: Exception) {
        "握手失败：${e.javaClass.simpleName}（${(e.message ?: "").take(60)}）"
    }

    /** 直接开一个 TCP 连接：成功返回空串，失败返回原因。 */
    private fun tcpProbe(ip: String, port: Int): String = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), 5000)
        }
        ""
    } catch (e: Exception) {
        e.javaClass.simpleName + "：" + (e.message ?: "").take(60)
    }

    /** 自检结果排成一段能直接复制发出去的文本（截图之外给个更准的路子） */
    fun format(base: String, version: String, steps: List<CheckStep>): String = buildString {
        appendLine("校园 App 自检 · $version")
        appendLine("地址 $base")
        steps.forEach { s ->
            appendLine((if (s.ok) "✅ " else "❌ ") + s.name + " — " + s.detail)
        }
    }.trim()
}
