package top.ccbase.campus.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.update.ByteSink
import top.ccbase.campus.update.StreamHead
import top.ccbase.campus.update.streamHeadOf
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.cancellation.CancellationException

/** logcat 标签：诊断用的地址/堆栈只进这里，不进界面文案 */
private const val TAG = "CampusApi"

/**
 * 多人版服务端客户端（`/api/v2/…`）。
 *
 * 设计上只做两件事：**把字节搬回来** 和 **把字节送出去**。
 * 传输层是可注入的（[Transport]），所以整套请求/错误处理逻辑能在 JVM 上被测到，
 * 不联网、也不依赖真服务器 —— 这点很重要，因为服务端要到 9/21 才部署。
 *
 * 为什么不用 OkHttp/Retrofit：这个 App 一共 5 个接口，加两个依赖换来的是
 * 编译期注解处理和几 MB 体积。`HttpURLConnection` 是 JDK 自带的，够用。
 * 用户的偏好很清楚：简单系统优于巧妙系统。
 */
object Api {
    /**
     * 服务端地址：**构建时**注入（`-PapiBase=…`），不再手改常量。
     *
     * 默认值来自本机 `local.properties` 的 `apiBase`（自有域名直连 443，比隧道快得多：
     * 隧道要把示例市的流量绕到洛杉矶边缘，TTFB 能到 6 秒）。
     *
     * 但要让 App 真的能用正式站，服务端得在**同一台机器上多跑一个只挂 v2 路由的进程**：
     *   - 正式站自己的 `/api/v2/…` 被网页版的登录闸门挡着（只放行 /login /healthz
     *     /sw.js /assetlist…），App 拿 Bearer 令牌打不进去（实测 401「未登录」）；
     *   - `campus-v2test.service` 那套是**独立实例 + 数据库副本**，故意不启动抢课轮询线程，
     *     所以它只适合当预览，不能当正式用法源。
     * 在那之前，用隧道地址构建：`-PapiBase=https://xxx.trycloudflare.com`（快速隧道一重启就换地址）。
     */
    const val BASE = top.ccbase.campus.BuildConfig.API_BASE
}

@Serializable
data class CredInfo(
    @SerialName("student_id") val studentId: String = "",
    val password: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ApiUser(
    val uid: Int = 0,
    val student_id: String = "",
    val name: String? = null,
    val college: String? = null,
    @SerialName("class_name") val className: String? = null,
    @SerialName("can_grab") val canGrab: Boolean = false,
    @SerialName("has_credentials") val hasCredentials: Boolean = false,
    @SerialName("cred_updated") val credUpdated: String? = null,
    /** 作者标记（服务端 `/me` 与登录返回都带）。老服务端没有这个 key → 默认 false。
     *  2026-10-07 起 App 里没有依赖它的界面了（后台搬去网页端）；字段留着是因为服务端仍在回。 */
    @SerialName("is_author") val isAuthor: Boolean = false,
)

@Serializable
data class LoginResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String = "",
    val user: ApiUser,
    /** 仅访客引导返回；说明本机保存/卸载后无法找回，不虚构跨设备恢复。 */
    @SerialName("recovery") val recovery: String = "",
)

@Serializable
private data class MeResponse(val user: ApiUser)

@Serializable
private data class ErrorBody(val detail: String? = null)

/**
 * 一次 HTTP 响应的结果。
 *
 * `headers` 是给**登录**用的：教务系统的认证只靠 Cookie（`SESSION` + `__pstsid__`），
 * 拿不到 `Set-Cookie` 就登不上去。给默认值是为了不惊动既有的十几处
 * `HttpReply(code, text)` 构造点 —— 它们本来也不需要头。
 */
data class HttpReply(
    val code: Int,
    val text: String,
    val headers: Map<String, String> = emptyMap(),
)

/** 普通接口的读超时：这些接口都在几秒内回来，20 秒已经非常宽松 */
const val DEFAULT_READ_MS = 20_000

/**
 * 慢接口（目前只有 AI 生成建议）的读超时。
 *
 * 定 75 秒是**量出来的**，不是拍的：服务端实测一次生成 18.5 秒（模型侧最长给 60 秒
 * 超时 + 链接体检预算 6 秒），再加上手机到服务器的往返，原来的 20 秒必然被自己掐死
 * —— 用户 2026-09-20 截图那句 `SocketTimeoutException: Read timed out` 就是这么来的。
 */
const val SLOW_READ_MS = 75_000

/** 一次 HTTP 调用。测试注入假实现。 */
fun interface Transport {
    fun call(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply

    /**
     * 带指定读超时的一次请求。
     *
     * 为什么不给 [call] 直接加参数：Transport 是 SAM 接口，测试里有十几个
     * `Transport { … }` 的 lambda 实现，它们根本不关心超时 —— 改签名等于把它们全炸掉。
     * 所以新增一个**带默认实现**的方法：默认忽略 readMs（走传输层自己的默认值），
     * 只有两条真传输层重写它。
     */
    fun callFor(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        readMs: Int,
    ): HttpReply = call(method, url, headers, body)

    /**
     * 流式取二进制（更新包用它）。
     *
     * 为什么单独一个方法：`call` 把响应读成 String，安装包会被字符集转码毁掉。
     * 为什么默认抛异常：**更新包必须和业务请求走同一条链路** —— 2026-09-17 真机就栽在
     * 更新下载自己开了 `URL.openConnection()` 绕开传输层，于是自检/接口全绿、唯独更新
     * `Connection reset`。默认不支持，谁想下二进制就必须显式实现，绕不过去。
     */
    fun stream(url: String, sink: (ByteArray, Int, Long) -> Unit): Long =
        throw UnsupportedOperationException("这条传输层不支持二进制下载")

    /**
     * 分片流式取二进制（断点续传用）。
     *
     * 和 [stream] 的两点区别：
     *  1. 能带上 `Range` / `If-Range` 头（[offset] > 0 就是"从第 N 个字节开始给"）；
     *  2. **先把响应头交给调用方**（[decide]），由调用方决定要不要这一段的字节。
     *     为什么要这么别扭：决定"这一段接不接在旧半包后面"必须发生在**写第一个字节之前** ——
     *     盲接一段 206 到旧半包后面，下出来的是个装不上的坏包。
     *
     * 默认不支持，理由和 [stream] 一样：二进制下载必须走 App 那条会自己选路的链路，
     * 谁想下二进制就得显式实现，绕不过去。
     */
    fun streamRanged(
        url: String,
        offset: Long,
        ifRange: String?,
        decide: (StreamHead) -> ByteSink?,
    ): StreamHead = throw UnsupportedOperationException("这条传输层不支持断点续传")
}

class RealTransport : Transport {

    /** 直连（带 SNI）下载：校园网/普通网络都走这条。 */
    override fun stream(url: String, sink: (ByteArray, Int, Long) -> Unit): Long {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000          // 7MB，别用 20 秒的超时把自己掐死
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            var total = 0L
            c.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    total += n
                    sink(buf, n, total)
                }
            }
            return total
        } finally {
            c.disconnect()
        }
    }

    /**
     * 分片下载（断点续传）：带上 `Range`/`If-Range`，先把响应头交给 [decide]，
     * 由它决定要不要这一段的字节。
     *
     * 两处刻意的设置：
     *  - `Accept-Encoding: identity`：APK 一旦被压缩，`Content-Length` 和 Range 的字节偏移
     *    就全对不上了 —— 续传拼出来必然是坏包；
     *  - 读超时降到 20 秒（原来是 60 秒）：现在超时的代价只是"退避一下接着上次的字节再要一段"，
     *    不再是"整个包白下"。这也和引擎的停滞阈值同一个口径。
     */
    override fun streamRanged(
        url: String,
        offset: Long,
        ifRange: String?,
        decide: (StreamHead) -> ByteSink?,
    ): StreamHead {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            if (!ifRange.isNullOrBlank()) setRequestProperty("If-Range", ifRange)
        }
        try {
            val head = streamHeadOf(c.responseCode) { c.getHeaderField(it) }
            if (head.code !in 200..206) return head      // 交给引擎去说人话（它会清掉半包重试）
            val sink = decide(head) ?: return head       // 调用方说这一段不能用：一个字节都不收
            c.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    sink(buf, n)
                }
            }
            return head
        } finally {
            c.disconnect()
        }
    }

    override fun call(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply =
        callFor(method, url, headers, body, DEFAULT_READ_MS)

    /**
     * 读超时按调用方给的来。[DEFAULT_READ_MS] 是默认；AI 生成那种几十秒的接口传
     * [SLOW_READ_MS]，否则会出现"服务端还在算、客户端先超时"的假故障。
     */
    override fun callFor(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        readMs: Int,
    ): HttpReply {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            // 只有 https 才谈得上换 TLS 工厂（自检里还有明文 http 的探测）
            // 注意：这里**不能**靠自定义 SSLSocketFactory 去掉 SNI —— Android 的
            // HttpsURLConnection 会按 URL 域名自己补上 SNI（详见 NoSniTransport 的说明）
            connectTimeout = 15_000
            readTimeout = readMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            body?.let { c.outputStream.use { os -> os.write(it.toByteArray()) } }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val raw = stream?.let { s -> s.readBytes() } ?: ByteArray(0)
            // 直连这条路要**自己解压**：HttpURLConnection 只在"它自己加的"那个
            // Accept-Encoding 头下才透明解压；头是我们自己加的，它就原样交给我们。
            // 漏了这一步的表现是：好网络（走直连）下接口解析失败，坏网络（不带 SNI）反而正常。
            val bytes = if (isGzip(c.contentEncoding)) gunzip(raw) else raw
            // 头要带出去（登录靠 Set-Cookie）。同名头（Set-Cookie 常常多个）折叠成
            // 逗号分隔的一条 —— 对 Cookie 来说仍然可解析（HttpURLConnection 不给列表）。
            val hs = LinkedHashMap<String, String>()
            c.headerFields.forEach { (k, v) -> if (k != null && v != null) hs[k] = v.joinToString(", ") }
            return HttpReply(code, String(bytes, Charsets.UTF_8), hs)
        } finally {
            c.disconnect()
        }
    }
}

/** 结果类型：UI 需要区分"密码错了"和"网断了"，这两件事对用户的下一步完全不同。 */
sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val code: Int, val message: String) : ApiResult<Nothing>()
}

class CampusApi(
    private val base: String = Api.BASE,
    private val transport: Transport = Net.dial,
) {
    private val json = Json {
        ignoreUnknownKeys = true     // 服务端加字段时旧 App 不能崩
        isLenient = true
        explicitNulls = false
    }

    /**
     * 把网络异常翻成「用户看得懂 + 开发者能定位」的一句话。
     *
     * 判读表（实测踩过的都在里面）：
     *   SocketException + Permission denied → 十有八九是清单里少了 INTERNET 权限。
     *       （这个坑真发生过：App 离线起家，后来长出网络层却忘了加权限，
     *         所有请求在发出前就抛异常，界面只显示"网络异常"，白查了好几天。）
     *   UnknownHostException → 域名解析不了：没网 / DNS / 飞行模式
     *   SocketTimeoutException → 连上了但服务端不响应
     *   ConnectException → 连不上（网络或服务端没起来）
     *   SSLException → 证书问题（中间人、代理、系统时间不对）
     */
    /**
     * 网络异常 → **一句** [StudentError.TEXT]，原文进 logcat。
     *
     * 这里以前会把「异常类名 + 70 字 message」拼进界面文案（理由当时是"定位需要"），
     * 2026-09-24 定稿收敛掉：诊断信息只在 logcat / 自检页出现，学生屏幕上只有一句话。
     * 判读表（哪类异常对应什么真因）保留在注释里，改的人别把它当装饰删了：
     *   SocketException + EPERM/permission/denied → 清单少了 INTERNET 权限
     *   UnknownHostException → 没网 / DNS / 飞行模式
     *   SocketTimeoutException → 连上了但服务端不响应
     *   ConnectException → 连不上（网络或服务端没起来）
     *   SSL* → 证书问题（中间人、代理、系统时间不对）
     */
    private fun networkHint(e: Exception, url: String): String = StudentError.tech(e, "网络请求 $url")

    /**
     * 一次请求；"连接被重置"这类**瞬时**故障，幂等请求再试一次。
     *
     * 为什么值得重试：手机在移动网络上（校园网/5G 切换、NAT 表被清、运营商中间盒）
     * 常把已建立的连接 RST 掉一次，第二次通常就通 —— 一次瞬时抖动不该变成一条红色报错。
     * **只重试 GET**：POST 可能已经落到服务端（加监控、提交选课），重试有副作用风险。
     */
    private fun attempt(
        method: String,
        url: String,
        headers: Map<String, String>,
        payload: String?,
        readMs: Int = DEFAULT_READ_MS,
        retryOnTimeout: Boolean = false,
    ): HttpReply = try {
        transport.callFor(method, url, headers, payload, readMs)
    } catch (e: java.net.SocketTimeoutException) {
        // 读超时一般**不重试**（别把一个慢请求打两遍），慢接口是例外：
        // /plan/suggest 在服务端即使客户端先断开也会算完并把结果写进缓存，
        // 所以第二次几乎必然秒回。只有调用方明确说"这个接口重试无害"才做。
        if (!retryOnTimeout) throw e
        Log.w(TAG, "读超时，重试一次（慢接口的结果通常已在服务端缓存好）：$url", e)
        transport.callFor(method, url, headers, payload, readMs)
    } catch (e: java.net.SocketException) {
        if (method != "GET") throw e
        Log.w(TAG, "连接被重置，重试一次：$url", e)
        transport.callFor(method, url, headers, payload, readMs)
    }

    /**
     * 真正的网络调用**必须切到 IO 线程**。
     *
     * Android 的 HttpURLConnection 跑在主线程会直接抛 NetworkOnMainThreadException ——
     * 而假传输层的测试完全不关心线程，所以这个洞在测试里是隐形的（实际已踩过一次：
     * 界面只显示"网络异常"，直到把异常类型打出来才看见是 NetworkOnMainThreadException）。
     *
     * 防守放在这里而不是各个调用点：调用点多一处就多一次写错的机会。
     */
    private suspend fun call(method: String, path: String, token: String? = null,
                             payload: String? = null,
                             readMs: Int = DEFAULT_READ_MS,
                             retryOnTimeout: Boolean = false): ApiResult<String> {
        val headers = mutableMapOf("Accept" to "application/json")
        // 让服务端压（/plan 87KB → 21KB，移动网下差别很明显）。解压在两个传输层里做，见 Gzip.kt。
        //
        // 注意 `X-Gzip-OK: 1` 这个头是**给服务端的开关**，不是装饰：
        // 服务端只对显式带它的请求压缩。为什么不能"声明 Accept-Encoding: gzip 就压"——
        // 2026-09-17 踩过：安卓的 HttpURLConnection 会自动声明 Accept-Encoding: gzip，
        // 而 1.37 之前的版本没有解压代码，服务端一厢情愿地压就把老版本全打成
        // 「计划数据无法解析」。这个头的存在，就是为了让新老客户端能同时活着。
        headers["Accept-Encoding"] = "gzip"
        headers["X-Gzip-OK"] = "1"
        token?.let { headers["Authorization"] = "Bearer $it" }
        // 设备号/机型：服务端用它把「后台 / 抢课」这类作者功能钉在**某一台设备**上
        // （用户 2026-09-20：只有我现在用的这台设备才算我）。
        // 机型是给人看的 —— 设备号是十六进制，作者核对"批的是不是他那台小米"时必须靠机型。
        // 设备号为空就不发 —— 服务端那边"没设备号" = 不是作者，不会报错。
        if (DeviceId.value.isNotBlank()) {
            headers["X-Device-Id"] = DeviceId.value
            if (DeviceId.model.isNotBlank()) headers["X-Device-Model"] = DeviceId.model
        }
        val reply = try {
            withContext(Dispatchers.IO) { attempt(method, "$base$path", headers, payload, readMs, retryOnTimeout) }
        } catch (e: CancellationException) {
            // 取消**不是网络故障**，必须原样抛出去。
            //
            // 踩过的坑（真机截图才暴露）：`CancellationException` 是 `Exception` 的子类，
            // 被下面的 catch 一起吞了 —— 协程被取消（离开组合 / 切页 / 参数变化重新拉起）
            // 就被翻译成"网络不通"，还顺手把一条红色错误写进界面，然后**协程继续跑**，
            // 于是这条假报错常驻在页面上。用户看到的是"App 坏了"。
            throw e
        } catch (e: Exception) {
            // 网络异常统一成 code=0，UI 层据此提示"检查网络"，不要把它和 401 混在一起。
            //
            // 但**必须把真实原因带出去**。"网络异常"四个字把十几种原因压成一句
            // 没有信息量的话 —— 用户只能回来问，开发者只能猜（这个坑实际发生过好几次）。
            // 这里给出：异常类型（机器可读）+ 人话解释；目标地址进 logcat（见 networkHint）。
            return ApiResult.Err(0, networkHint(e, "$base$path"))
        }
        if (reply.code in 200..299) return ApiResult.Ok(reply.text)
        val msg = try {
            json.decodeFromString<ErrorBody>(reply.text).detail ?: ""
        } catch (e: Exception) {
            ""
        }
        return ApiResult.Err(reply.code, msg.ifBlank { StudentError.http(reply.code, "$base$path") })
    }

    /**
     * 旧学号+密码登录已退役（credential-free migration）。
     *
     * 官方学校登录只发生在客户端官方 HTTPS WebView；本方法**不发任何网络请求**，
     * 统一返回 422，给仍引用它的旧测试留一个诚实、无传输的兼容入口。
     */
    suspend fun login(studentId: String, password: String, keepPassword: Boolean = true): ApiResult<LoginResponse> =
        ApiResult.Err(422, StudentError.TEXT)

    /** 云端引导：用服务端随机生成的不透明访客身份换一条 Bearer 令牌。 */
    suspend fun guest(): ApiResult<LoginResponse> =
        when (val r = call("POST", "/api/v2/guest", payload = "")) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<LoginResponse>(r.value))
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech())
            }
            is ApiResult.Err -> r
        }

    /** 查看服务器上保存的教务密码 —— 服务端只对作者开放，普通用户会拿到 403 */
    suspend fun credentials(token: String): ApiResult<CredInfo> =
        when (val r = call("GET", "/api/v2/credentials", token = token)) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<CredInfo>(r.value))
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech())
            }
            is ApiResult.Err -> r
        }

    suspend fun plan(token: String): ApiResult<Seed> = when (val r = planBundle(token)) {
        is ApiResult.Ok -> ApiResult.Ok(r.value.seed)
        is ApiResult.Err -> r
    }

    /** `/plan` 的响应外壳：计划本体 + 课表状态（老服务端没有 `timetable` → null） */
    data class PlanBundle(val seed: Seed, val timetable: TimetableStatus?)

    /**
     * 和 [plan] 同一条请求、同一个解析，**多带一份课表状态**。
     *
     * 为什么不新开一个接口：多一次 GET 就多一次失败点、多一段等待；
     * 服务端本来就在这份响应里给了版本号（见 TimetableDto 的说明）。
     */
    suspend fun planBundle(token: String): ApiResult<PlanBundle> =
        when (val r = call("GET", "/api/v2/plan", token = token)) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(PlanBundle(
                    seed = json.decodeFromString<Seed>(r.value),
                    timetable = TimetableStatus.fromBody(json, r.value),
                ))
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech())
            }
            is ApiResult.Err -> r
        }

    /**
     * 让**服务端**按 EAMS 重读一次课表（服务端存着教务账号，App 不用碰密码）。
     *
     * 这就是「立即更新课表」该走的路 —— 以前那个按钮只是把服务端缓存再拉一遍，
     * 副标题却写着"从教务系统重新读一次"，是假的。
     *
     * 老服务端没有这条路由 → 404/405（**不是**网络故障）。调用方据此退回
     * 「从服务器同步」并如实说明，不许继续骗人。
     */
    suspend fun timetableSync(token: String): ApiResult<TimetableSyncRes> =
        when (val r = call("POST", "/api/v2/timetable/sync", token = token, payload = "{}")) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<TimetableSyncRes>(r.value))
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech())
            }
            is ApiResult.Err -> r
        }

    /**
     * 第一次进入：服务端正在用**他自己的**账号去教务读课表、排计划。
     * App 拿这个接口轮询进度 —— 进度只信服务端，客户端自己推算就会出现
     * "卡在 60% 不动"，那比转圈更让人以为坏了。
     */
    suspend fun onboard(token: String): ApiResult<OnboardState> =
        when (val r = call("GET", "/api/v2/onboard", token = token)) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<OnboardState>(r.value))
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech(e))
            }
            is ApiResult.Err -> r
        }

    /** 生成失败后的重试。force=true 会把已经生成好的规划也重做一遍（作者本人的除外）。 */
    suspend fun onboardRestart(token: String, force: Boolean = false): ApiResult<OnboardState> =
        when (val r = call("POST", "/api/v2/onboard", token = token,
                           payload = """{"force":$force}""")) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<OnboardState>(r.value))
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech(e))
            }
            is ApiResult.Err -> r
        }

    /**
     * 把**手机自己爬到**的教务课表交给服务端重建规划。**密码不出手机** ——
     * 服务端在这条路径上不碰教务、不经手密码，只做「建规划 + 落库」。
     *
     * 为什么送**原样 JSON**：服务端要的是 activities 里的精确周次（单双周、中途换机房
     * 全靠 weekIndexes 表达）。App 解析成自己的模型再拼回去就会丢字段 ——
     * 服务端有一条测试就是「精确周次原样落库」，拼一遍必挂。
     *
     * 返回只关心成败：课/节数以随后重新拉取的 `/plan` 为准（那里的数字是落库后的真相）。
     */
    suspend fun uploadActivities(token: String, activitiesJson: String): ApiResult<Boolean> =
        when (val r = call("POST", "/api/v2/plan/from-activities", token = token,
                           payload = """{"activities":$activitiesJson}""")) {
            is ApiResult.Ok -> ApiResult.Ok(true)
            is ApiResult.Err -> r
        }

    // ------------------------------------------------------------ AI 规划学习计划
    //
    // 三条路语义不同，**客户端不许把它们揉成一个**：
    //   suggest       只出建议，规划表一个字节都不写 → "生成-看看-算了"是零副作用的
    //   suggest/apply 把勾选的**追加**进任务清单（只增不改，已有的一行都不动）
    //   suggest/undo  按阶段名精确回收上一次加入的那批（只删 phase='AI 规划'）
    //
    // 模型 key 只在服务器上（App 里没有、也不需要）—— 这里全程就是普通 HTTP。

    /**
     * 专用编码器：**必须 encodeDefaults = true**。
     *
     * CampusApi 里那个共用的 `json` 没开 `encodeDefaults`（kotlinx 默认 false），
     * 于是"值等于 DTO 默认值"的字段会被**整个省掉**：用户选了「入门」/「每周 5 小时」，
     * 发出去的请求体里却没有 level / hours 这两个键 —— 服务端只能用它自己的默认值兜。
     * 现在两边默认值恰好一致（level=入门、hours=5），所以看不出问题；
     * 哪天服务端把默认值改成别的（比如 hours=10），用户明确选的 5 小时就会被悄悄篡改。
     * 这类"看着能用、改一次就错"的契约必须写死：**用户选了什么就原样发什么**。
     */
    private val planJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * 按课表 + 喜好出几条建议。**服务端不写规划表**，所以随便点不会污染任务清单。
     *
     * `raw` 原样保留服务端给的条目 JSON：apply 时按原样送回去。
     * 重新用本地 DTO 编码一遍，等于赌"服务端以后不会加字段"—— 赌输了是静默丢字段，
     * 比直接报错难查得多。
     */
    suspend fun planSuggest(token: String, prefs: PlanPrefs): ApiResult<PlanSuggest> =
        when (val r = call("POST", "/api/v2/plan/suggest", token = token,
                           payload = planJson.encodeToString(PlanPrefs.serializer(), prefs),
                           // 这是全 App 唯一一个"服务端要算几十秒"的接口：
                           // 实测生成一次 18.5 秒，手机侧还要加往返 —— 20 秒的默认超时必然把它掐死。
                           readMs = SLOW_READ_MS,
                           // 重试是安全的：这个接口**一个字节都不写规划表**（只可能写缓存），
                           // 而且服务端在客户端断开后依然会算完并写好缓存 → 第二次通常秒回。
                           retryOnTimeout = true)) {
            is ApiResult.Ok -> try {
                val env = json.decodeFromString<PlanSuggestEnvelope>(r.value)
                ApiResult.Ok(
                    PlanSuggest(
                        items = env.items.map { json.decodeFromJsonElement(PlanItem.serializer(), it) },
                        raw = env.items,
                        source = env.source,
                        reason = env.reason,
                        phase = env.phase,
                    )
                )
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech(e))
            }
            is ApiResult.Err -> r
        }

    /**
     * 把勾选的建议加进任务清单。**只增不改**，所以重试、加两次都不会弄坏已有数据
     * （代价是可能重复几条 —— 所以界面上有「撤销本次加入」）。
     *
     * 服务端会再 normalize 一遍，不信任这里传的东西：条数上限 8 条、缺标题或缺步骤的直接丢。
     * 400/413 的原话要带给用户（"一次最多加入 N 条"这种话只有服务端知道准确数字）。
     */
    suspend fun planApply(token: String, items: List<JsonElement>): ApiResult<PlanCounts> {
        val body = JsonObject(mapOf("items" to JsonArray(items))).toString()
        return when (val r = call("POST", "/api/v2/plan/suggest/apply", token = token, payload = body)) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<PlanCountsEnvelope>(r.value).counts)
            } catch (e: Exception) {
                // ⚠️ 这里是"不知道写没写进去"，不是"肯定没写"：说得含糊等于骗人
                ApiResult.Err(-1, StudentError.tech(e))
            }
            is ApiResult.Err -> r
        }
    }

    /** 撤销上一次「加入任务清单」。服务端只删 phase='AI 规划' 的那批，用户自己的任务一个字不动。 */
    suspend fun planUndo(token: String): ApiResult<PlanCounts> =
        when (val r = call("POST", "/api/v2/plan/suggest/undo", token = token)) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<PlanCountsEnvelope>(r.value).counts)
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech(e))
            }
            is ApiResult.Err -> r
        }

    suspend fun me(token: String): ApiResult<ApiUser> =
        when (val r = call("GET", "/api/v2/me", token = token)) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<MeResponse>(r.value).user)
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech())
            }
            is ApiResult.Err -> r
        }

    /**
     * 「公共学习资源目录」：所有同学探过的链接汇总（按 URL 去重）。
     *
     * 服务端已经做过隐私过滤（只给公开链接 + 课程归属 + 类型 + 通用说明），
     * App 这边连"个人语境"的字段都没有 —— DTO 里没有 why，就不可能在代码里漏带出来。
     */
    suspend fun catalog(token: String): ApiResult<Catalog> =
        when (val r = call("GET", "/api/v2/catalog", token = token)) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<Catalog>(r.value))
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech())
            }
            is ApiResult.Err -> r
        }

    /**
     * 「加入我的课程」：把学习库里的一门课加进我自己的课表。
     *
     * 只写自己那一行（服务端按令牌里的 uid 落库），所以重复点、挑别人在学的课都安全；
     * 服务端幂等，created=false = 之前就加过。
     */
    suspend fun libraryPick(token: String, name: String): ApiResult<PickCourseRes> {
        val body = JsonObject(mapOf("name" to JsonPrimitive(name))).toString()
        return when (val r = call("POST", "/api/v2/library/pick", token = token, payload = body)) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<PickCourseRes>(r.value))
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech(e))
            }
            is ApiResult.Err -> r
        }
    }

    suspend fun logout(token: String): ApiResult<String> = call("POST", "/api/v2/logout", token = token)

    suspend fun deleteCredentials(token: String): ApiResult<String> =
        call("DELETE", "/api/v2/credentials", token = token)

    // ------------------------------------------------------------ 提建议（每个用户都能用）
    //
    // 这是唯一一个**对全体用户开放**的写接口：谁都能提，不需要作者身份、不需要任何特权。
    // 服务端只做很轻的限流（1 分钟 3 条 / 24 小时 20 条，当次拒收、不封号）——
    // 用户明确反感那种会把人锁在门外的限制，所以这里也不在客户端做任何次数判断：
    // 真要拦，也由服务端给一句人话，客户端照原样显示。

    /**
     * 提一条建议。
     *
     * `appVer` 一起传上去：服务端把它存下来，我排查"这是哪一版的问题"时不用猜。
     * 长度上限由服务端管（超了会回 413 + 一句人话），客户端只做个字数提示。
     */
    suspend fun submitFeedback(
        token: String,
        text: String,
        appVer: String = "",
    ): ApiResult<FeedbackAck> {
        val body = JsonObject(
            mapOf(
                "text" to JsonPrimitive(text),
                "app_ver" to JsonPrimitive(appVer),
            ),
        ).toString()
        return when (val r = call("POST", "/api/v2/feedback", token = token, payload = body)) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<FeedbackAck>(r.value))
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech(e))
            }
            is ApiResult.Err -> r
        }
    }

    /** 我提过的建议（带处理状态）。只回自己那一份 —— 别人的一个字都不会出现。 */
    suspend fun myFeedback(token: String): ApiResult<MyFeedbackRes> =
        when (val r = call("GET", "/api/v2/feedback/mine", token = token)) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<MyFeedbackRes>(r.value))
            } catch (e: Exception) {
                ApiResult.Err(-1, StudentError.tech(e))
            }
            is ApiResult.Err -> r
        }

    // ------------------------------------------------------------ 抢课（只对作者开放）


    private fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
        append('"')
    }
}
