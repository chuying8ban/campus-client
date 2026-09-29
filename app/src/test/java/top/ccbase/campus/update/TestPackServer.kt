package top.ccbase.campus.update

import java.net.SocketException

/**
 * 一台**假的静态文件服务器**，行为对齐 nginx 的 Range 语义。
 *
 * 为什么要有它：真机上"断流"是随机的（nginx 直发 apk 曾在 16KB 整数倍处断），
 * 没法靠运气复现，只能把断点写成一个可编排的剧本 ——
 * 「第 N 个字节断开」「装作不支持 Range 的服务器」「两次请求之间把线上的包换掉」。
 *
 * 它只实现 [RangedFetcher]（引擎那一侧的接口），所以整个续传逻辑还是纯 JVM 状态机。
 */
class TestPackServer(
    var body: ByteArray,
    var etag: String? = "\"v1\"",
    var lastModified: String? = "Fri, 18 Sep 2026 06:34:27 GMT",
    /** true = 装作不支持 Range：对任何请求都回 200 整包 */
    var ignoreRange: Boolean = false,
    /**
     * true = 不认 If-Range：对象换了也照样回 206（比 nginx 更坏）。
     * 用来验"客户端自己把关"这一层 —— 不能指望服务端替我们挡住换包。
     */
    var ignoreIfRange: Boolean = false,
    /** 每次请求吐到第 N 个字节就断流；-1 = 不断 */
    var breakAfterBytes: Int = -1,
    /** 只在第一次请求断（模拟"偶尔抖一下"）；false = 每次都断（模拟"这条线路就是不行"） */
    var breakOnlyFirst: Boolean = true,
    var chunk: Int = 8 * 1024,
    /** 每喂一块之前调用一次（测试里用它把时钟拨快，模拟"卡死不吐数据了"） */
    var beforeChunk: (sent: Int) -> Unit = {},
    /** 每次收到请求时调用（参数 = 这是第几次请求，从 1 开始）：用来编排"两次请求之间线上换了包" */
    var onRequest: (n: Int) -> Unit = {},
    /** true = 不报 Content-Length（服务端没给大小，界面只能标"进度未知"） */
    var hideLength: Boolean = false,
) : RangedFetcher {

    data class Req(val url: String, val offset: Long, val ifRange: String?)

    val reqs = mutableListOf<Req>()

    /** 请求次数 */
    val calls: Int get() = reqs.size

    private var breaks = 0

    val sha: String get() = UpdateLogic.sha256Hex(body)

    override fun fetch(url: String, offset: Long, ifRange: String?, decide: (StreamHead) -> ByteSink?): StreamHead {
        reqs += Req(url, offset, ifRange)
        onRequest(reqs.size)
        val bytes = body

        // ---- 对象是否换过：客户端带来的标识跟当前对象对不上
        val clientKnew = ifRange?.takeIf { it.isNotBlank() }
        val stale = clientKnew != null &&
            ifRange != etag && ifRange != lastModified &&
            !ignoreIfRange

        // ---- 区间能不能满足
        if (offset > bytes.size) {
            // 真实的 nginx 对越界的 Range 回 416
            return StreamHead(code = 416, etag = etag, lastModified = lastModified, rangeTotal = bytes.size.toLong())
        }
        val ranged = !ignoreRange && !stale && offset > 0
        val head = if (ranged) {
            StreamHead(
                code = 206,
                etag = etag,
                lastModified = lastModified,
                rangeStart = offset,
                rangeTotal = bytes.size.toLong(),
                contentLength = if (hideLength) -1L else (bytes.size - offset).toLong(),
            )
        } else {
            StreamHead(
                code = 200,
                etag = etag,
                lastModified = lastModified,
                contentLength = if (hideLength) -1L else bytes.size.toLong(),
            )
        }

        val sink = decide(head) ?: return head          // 调用方说这一段不能用：一个字节都不给

        val from = if (head.code == 206) offset else 0L
        val limit = (bytes.size - from).toInt()
        val canBreak = breakAfterBytes in 0..limit && (breaks == 0 || !breakOnlyFirst)
        var sent = 0
        while (sent < limit) {
            if (canBreak && sent >= breakAfterBytes) {
                breaks++
                // 和真机上的表现一样：读到一半被 RST 掉
                throw SocketException("Connection reset by peer")
            }
            val n = minOf(chunk, limit - sent)
            beforeChunk(sent)
            sink(bytes.copyOfRange((from + sent).toInt(), (from + sent + n).toInt()), n)
            sent += n
        }
        return head
    }
}
