package top.ccbase.campus.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import top.ccbase.campus.net.StudentError

/**
 * 「App 内更新」的下载引擎：断点续传、退避重试、停滞检测、校验通过才算拿到包。
 *
 * ## 为什么要有这个文件
 * 学生大多在示例市的移动网络上：链路会按 SNI 拦握手，也会**随机断流**
 * （nginx 直发 apk 曾在 16KB 整数倍处断过）。原来的实现一断就把整个 7MB 扔掉重下，
 * 而且失败时只剩一句「下载失败」—— 用户只能一遍遍重点，运气好才下完。
 *
 * ## 五条硬规矩（改这个文件前先读一遍）
 * 1. **只有 206 + 区间正好接在已收字节后面 + 对象指纹（ETag/Last-Modified）一致，才敢追加。**
 *    服务端回 200（不支持 Range，或它手上的文件换了）→ **从 0 重下**，
 *    绝不把一整包盲接在半包后面。
 * 2. **绝不静默换路**：整条下载只认 manifest 给的那一个 URL。换域名/换镜像续写等于把
 *    两个不同文件的字节拼在一起，下出来是个装不上的坏包，而用户全程不知情。
 * 3. **大小 + sha256 都过了，才有资格被改名成安装包**。半包永远不安装，失败就留着等续传。
 * 4. 断流/超时/卡死 → 退避重试（2s / 5s / 15s），**接着上次的字节**继续，不从头再来。
 * 5. 每一步都要有可见的进度和明确的失败文案，禁止静默卡住、禁止静默失败。
 *
 * 这个文件里**没有任何 Android API** —— 全是能在 JVM 上直接测的纯函数和状态机
 * （这台机器上跑不了模拟器，见 `cc` 不在 kvm 组），所以断点续传的每条分支都必须在这里被钉死。
 */

// ---------------------------------------------------------------- HTTP 事实

/**
 * 一段响应里引擎需要知道的全部 HTTP 事实。
 *
 * 只有这些 —— 引擎刻意不认识别的头，免得哪天有人拿它去做更深的路由决策。
 */
data class StreamHead(
    val code: Int,
    /** 对象标识（强）；服务器通常会带 */
    val etag: String? = null,
    /** 对象标识（弱）：ETag 被中间盒剥掉时靠它 */
    val lastModified: String? = null,
    /** `Content-Range: bytes <start>-<end>/<total>` 里的 start */
    val rangeStart: Long? = null,
    /** 同上里的 total（整包大小） */
    val rangeTotal: Long? = null,
    /** Content-Length；没有就是 -1 */
    val contentLength: Long = -1L,
) {
    /** 整包大小：206 看 Content-Range 尾部，200 看 Content-Length，都没有就是未知 */
    val total: Long
        get() = when {
            rangeTotal != null && rangeTotal > 0 -> rangeTotal
            code == 200 && contentLength >= 0 -> contentLength
            else -> -1L
        }

    /** 这一段字节在整包里的起始偏移；200 一定从 0 开始 */
    val startsAt: Long
        get() = if (code == 206) (rangeStart ?: -1L) else 0L
}

/**
 * 分片下载器：拿到响应头后**先把头交给调用方**，由调用方决定要不要这一段的字节。
 *
 * 为什么要这个奇怪的形状（而不是"读完再返回头"）：要不要追加、要不要重下，
 * 必须在**写出第一个字节之前**决定 —— 盲接一段 206 到旧半包后面，下出来的就是坏包。
 *
 * @param offset 从第几个字节开始要（0 = 整包）
 * @param ifRange ETag / Last-Modified：服务端发现对象变了会直接回 200 整包，
 *   让服务端帮我们把"文件变了"这一关也把住
 * @param decide 收到响应头时调用；返回 sink 就收字节，返回 null 表示这一路不能用（关掉）
 */
fun interface RangedFetcher {
    fun fetch(url: String, offset: Long, ifRange: String?, decide: (StreamHead) -> ByteSink?): StreamHead
}

/** 一段响应的字节消费者。 */
typealias ByteSink = (ByteArray, Int) -> Unit

/** 把 HTTP 响应头翻成 [StreamHead]（三条链路自己解析出头之后都走这里，口径才不会漂）。 */
fun streamHeadOf(code: Int, header: (String) -> String?): StreamHead {
    val range = UpdateResume.parseContentRange(header("Content-Range"))
    return StreamHead(
        code = code,
        etag = header("ETag"),
        lastModified = header("Last-Modified"),
        rangeStart = range?.first,
        rangeTotal = range?.second,
        contentLength = header("Content-Length")?.trim()?.toLongOrNull() ?: -1L,
    )
}

// ---------------------------------------------------------------- 续传状态（落盘）

/** 一次下载的目标：地址、期望校验和、期望大小。**只有一个 URL**（见规矩 2）。 */
data class DownloadTarget(val url: String, val sha256: String, val size: Long)

/**
 * 半包的元数据，跟 `.part` 一起落在 App 私有目录里 —— 这就是"跨 App 重启也能续"的载体。
 * 进程被系统杀掉之后，下次打开只要读回它 + `.part` 的长度，就能接着下。
 */
@Serializable
data class PartMeta(
    /** 半包对应的地址。地址变了（换域名/换版本）→ 半包作废，不许追写 */
    val url: String = "",
    val etag: String? = null,
    val lastModified: String? = null,
    /** 这份半包是给哪个校验和的（manifest 换了版本 → 作废） */
    val sha256: String = "",
    /** 期望的整包大小（未知为 0） */
    val total: Long = 0L,
    /** 已经收下的字节数（只用于上报进度；续传的起点以文件实际长度为准） */
    val received: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 拿去当 `If-Range` 用的对象标识：强标识优先，没有才退回 Last-Modified */
    val validator: String? get() = etag?.takeIf { it.isNotBlank() } ?: lastModified?.takeIf { it.isNotBlank() }
}

/** `.part` + `.part.json` 的读写。放在同一个目录里，一起创建、一起清理。 */
class PartStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** @param name 安装包文件名（如 `campus-1.60-T1.apk`） */
    fun part(name: String): File = File(dir, "$name.part")

    fun metaFile(name: String): File = File(dir, "$name.part.json")

    fun readMeta(name: String): PartMeta? = runCatching {
        val f = metaFile(name)
        if (!f.isFile) null else json.decodeFromString(PartMeta.serializer(), f.readText())
    }.getOrNull()

    fun writeMeta(name: String, meta: PartMeta) {
        runCatching {
            dir.mkdirs()
            val f = metaFile(name)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(json.encodeToString(PartMeta.serializer(), meta))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }

    /** 半包 + 元数据一起删掉（校验不过、地址变了、服务器说这段不存在时都要做） */
    fun clear(name: String) {
        runCatching { part(name).delete() }
        runCatching { metaFile(name).delete() }
    }

    /**
     * 清掉**别的版本**留下的半包：那些再也续不上了，留着只是白占手机空间
     * （每个包 7MB，手机上能明显感觉出来）。当前要下载的那个不动。
     */
    fun clearOthers(keepName: String) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.name.contains(".apk.part")) continue
            if (f.name.startsWith(keepName)) continue
            runCatching { f.delete() }
        }
    }
}

// ---------------------------------------------------------------- 续传判定（纯函数）

/** 拿到响应头之后，这一段字节该怎么用。 */
sealed class ResumeDecision {
    /** 接着写：这一段从 [offset] 开始，前面 [offset] 个字节已经在半包里了 */
    data class Append(val offset: Long) : ResumeDecision()

    /** 这一路的字节是**从 0 开始的整包**，可以用（先把半包清空） */
    data class Restart(val why: String) : ResumeDecision()

    /** 这一路的字节不能用：关掉连接、清掉半包、从 0 重来 */
    data class Reject(val why: String) : ResumeDecision()
}

/**
 * 断点续传的判定规则。全部是纯函数 —— 这是整个功能里最不能出错的一段。
 */
object UpdateResume {

    /** `bytes 1024-7468867/7468868` → (1024, 7468868)。认不出来返回 null。 */
    fun parseContentRange(h: String?): Pair<Long, Long>? {
        if (h.isNullOrBlank()) return null
        val m = Regex("^\\s*bytes\\s+(\\d+)-(\\d+)\\s*/\\s*(\\d+|\\*)\\s*$", RegexOption.IGNORE_CASE)
            .find(h) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val total = m.groupValues[3].takeIf { it != "*" }?.toLongOrNull() ?: -1L
        return start to total
    }

    /** 服务器给出的对象指纹：ETag 优先，没有才退回 Last-Modified。两边都没有 → null */
    fun fingerprintOf(etag: String?, lastModified: String?): String? =
        etag?.takeIf { it.isNotBlank() } ?: lastModified?.takeIf { it.isNotBlank() }

    /**
     * 这一段字节能不能追加到已收的 [offset] 后面。
     *
     * @param meta 上次落盘的元数据（null = 本机没有可续的半包）
     * @param offset 半包里已经确认收下的字节数
     */
    fun decide(
        meta: PartMeta?,
        offset: Long,
        target: DownloadTarget,
        head: StreamHead,
    ): ResumeDecision {
        // ---- 1. 先看 HTTP 层：只有 200 / 206 是能写进文件的响应
        if (head.code == 416) {
            return ResumeDecision.Reject("服务端说这个区间不存在（HTTP 416）—— 本机半包跟线上包对不上，清掉重下")
        }
        if (head.code != 200 && head.code != 206) {
            return ResumeDecision.Reject("服务器返回 HTTP ${head.code}")
        }
        if (head.code == 206 && head.rangeStart == null) {
            return ResumeDecision.Reject("206 响应里没有 Content-Range，没法确认这一段接在哪儿")
        }

        // ---- 2. 本机有没有"同一个文件的"半包
        val usable = offset > 0 && meta != null &&
            meta.url == target.url &&
            meta.sha256.isNotBlank() &&
            UpdateLogic.shaMatches(meta.sha256, target.sha256)

        if (!usable) {
            // 没有可续的半包：只要求这一路从 0 开始
            return if (head.startsAt == 0L) ResumeDecision.Restart("从 0 开始（本机没有可续的半包）")
            else ResumeDecision.Reject("服务端从第 ${head.startsAt} 个字节开始给，但本机没有对应的半包")
        }

        // ---- 3. 半包比目标还大：一定是坏的（地址换过、或被截断过）
        if (target.size > 0 && offset > target.size) {
            return ResumeDecision.Reject("本机半包（$offset 字节）比线上包（${target.size} 字节）还大，清掉重下")
        }

        // ---- 4. 有半包，但服务端回了 200 = 它不支持 Range，或它手上的文件换了
        if (head.code == 200) {
            return ResumeDecision.Restart("服务端回了 200（不支持断点续传，或文件已变），从 0 重下")
        }

        // ---- 5. 206：区间必须正好接在已收字节的后面，多一个少一个都不行
        if (head.rangeStart != offset) {
            return ResumeDecision.Reject("服务端给的区间从第 ${head.rangeStart} 个字节开始，跟本机已收的 $offset 对不上")
        }

        // ---- 6. 206：对象指纹必须跟上次记下的一致，否则这是**另一个文件**的尾巴
        val stored = meta.validator
        val now = fingerprintOf(head.etag, head.lastModified)
        return when {
            // 两边都没有标识可比：206 + 区间精确对齐是唯一的证据，
            // 真被换了包还有最后的 sha256 兜底（校验不过就整个丢弃）
            stored == null && now == null -> ResumeDecision.Append(offset)
            stored == null || now == null ->
                ResumeDecision.Reject("这次服务端" + (if (now == null) "没给" else "才给") + "文件标识，没法确认是同一个包")
            stored == now -> ResumeDecision.Append(offset)
            else -> ResumeDecision.Reject("服务器上的安装包已经换了（ETag/Last-Modified 变了），旧半包作废")
        }
    }
}

// ---------------------------------------------------------------- 重试 / 停滞

/** 退避重试的节奏：2s / 5s / 15s。后面就是彻底失败，弹给用户看。 */
object RetryPolicy {
    val BACKOFF_MS = longArrayOf(2_000L, 5_000L, 15_000L)

    /** 最多重试几次（第 1 次是原始尝试，不算重试） */
    val MAX_RETRIES: Int get() = BACKOFF_MS.size

    val MAX_ATTEMPTS: Int get() = BACKOFF_MS.size + 1

    /** @param retry 第几次重试（从 1 开始） */
    fun delayMs(retry: Int): Long = BACKOFF_MS[(retry - 1).coerceIn(0, BACKOFF_MS.size - 1)]

    fun shouldRetry(retry: Int): Boolean = retry <= MAX_RETRIES
}

/** 连续多久没有新字节就判定"卡死"（断开重试，不能无限挂着转圈）。 */
const val STALL_LIMIT_MS = 20_000L

/** 每收下这么多字节，把进度落到元数据里（断电/被杀最多重下这一段）。 */
const val CHECKPOINT_BYTES = 1L shl 20

/** 停滞检测：连续 [limitMs] 没有收到新字节 → 判定卡死。纯状态机，时钟可注入。 */
class StallGuard(private val limitMs: Long, private val clock: () -> Long) {

    private var lastByteAt = clock()

    val idleMs: Long get() = clock() - lastByteAt

    fun noteByte() {
        lastByteAt = clock()
    }

    /** 超过阈值就抛 [StallTimeoutException]，把读操作从"无限挂"里拽出来 */
    fun check() {
        if (idleMs >= limitMs) throw StallTimeoutException(idleMs, limitMs)
    }
}

class StallTimeoutException(val idleMs: Long, val limitMs: Long) :
    IOException("下载卡住了：${limitMs / 1000} 秒没有收到新数据")

/** 大小不对（截断、或多下了） */
class SizeMismatch(val actual: Long, val expected: Long) :
    IOException("下载大小不对：拿到 ${UpdateLogic.humanSize(actual)}，应为 ${UpdateLogic.humanSize(expected)}")

/** 校验和不符 → 半包一律丢弃，绝不安装 */
class ChecksumMismatch(val expected: String, val actual: String) :
    IOException("安装包校验没通过（期望 ${expected.take(12)}…，实际 ${actual.take(12)}…）")

/** 最终失败：[userText] 是要原样显示给用户的一句话（含重试建议），不含堆栈。 */
class DownloadFailed(val userText: String, cause: Throwable? = null) : IOException(userText, cause)

// ---------------------------------------------------------------- 进度

/**
 * 一次下载的可见进度。
 *
 * [retryInMs] > 0 表示"上一次断了，正在等退避重试"—— 界面必须把这件事说出来，
 * 否则用户看到的就是一个不动的进度条（等于静默）。
 */
data class DownloadProgress(
    val received: Long,
    val total: Long,
    val attempt: Int,
    val retryInMs: Long = 0,
    val note: String? = null,
) {
    /** 进度条用的比例；总大小未知时返回 -1（界面别显示假百分比） */
    val fraction: Float
        get() = if (total > 0) (received.toDouble() / total).toFloat().coerceIn(0f, 1f) else -1f

    /** 给用户看的一行：「3.2 MB / 7.1 MB（45%）」 */
    fun sizeText(): String = when {
        total > 0 -> "${UpdateLogic.humanSize(received)} / ${UpdateLogic.humanSize(total)}" +
            "（${(fraction * 100).toInt()}%）"
        received > 0 -> "已下载 ${UpdateLogic.humanSize(received)}"
        else -> ""
    }
}

// ---------------------------------------------------------------- 引擎

/** 引擎的可调项（测试里换掉 sleeper/clock，跑起来不花真实时间）。 */
data class DownloadTuning(
    val maxAttempts: Int = RetryPolicy.MAX_ATTEMPTS,
    val stallLimitMs: Long = STALL_LIMIT_MS,
    val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    val clock: () -> Long = System::currentTimeMillis,
)

/**
 * 断点续传下载引擎。
 *
 * 一次 [run] 里可能发生多次请求（断流重试），但**始终是同一个 URL、同一份半包**，
 * 且只有"这一段字节从头写"或"精确接在已收字节后面"两种写入方式。
 */
class DownloadEngine(
    private val fetcher: RangedFetcher,
    private val tuning: DownloadTuning = DownloadTuning(),
) {

    /**
     * @param part 半包文件（`xxx.apk.part`），过程数据全写这里
     * @param dest 最终安装包位置。**只有校验通过才会出现这个文件**
     */
    fun run(
        target: DownloadTarget,
        part: File,
        dest: File,
        onProgress: (DownloadProgress) -> Unit = {},
    ): Result<File> {
        val dir = part.parentFile ?: File(".")
        val store = PartStore(dir)
        // PartStore 的命名约定是「安装包文件名」（campus-1.60-T1.apk →
        // 半包 campus-1.60-T1.apk.part、元数据 campus-1.60-T1.apk.part.json），
        // 而这里拿到的是半包路径本身，所以先把 .part 去掉 —— 去错一步的后果是
        // 元数据写到别的名字下：续传判定会以为"本机没有半包"，每次断流都从 0 重下。
        val name = part.name.removeSuffix(".part")
        dir.mkdirs()

        var attempt = 0
        var last: Exception? = null

        while (true) {
            if (attempt >= tuning.maxAttempts) {
                return Result.failure(DownloadFailed(failureText(target, part, last, attempt), last))
            }
            if (attempt > 0) {
                val wait = RetryPolicy.delayMs(attempt)
                onProgress(
                    DownloadProgress(
                        received = part.length(),
                        total = target.size,
                        attempt = attempt + 1,
                        retryInMs = wait,
                        note = retryNote(last),
                    )
                )
                tuning.sleeper(wait)
            }
            attempt++

            // ---- 本次请求从哪儿开始：半包长度就是已确认收下的字节
            val meta = store.readMeta(name)
            var offset = confirmedOffset(part, meta, target)
            onProgress(DownloadProgress(offset, totalOf(meta, target), attempt))

            var head: StreamHead? = null
            var decision: ResumeDecision? = null
            var out: FileOutputStream? = null
            var received = offset
            var checkpointed = offset
            val guard = StallGuard(tuning.stallLimitMs, tuning.clock)

            val sink: ByteSink = { buf, n ->
                guard.check()                    // 距上一块太久 = 卡死，直接抛出去断开这条连接
                guard.noteByte()
                out!!.write(buf, 0, n)
                received += n
                if (received - checkpointed >= CHECKPOINT_BYTES) {
                    flushAndSync(out!!)
                    store.writeMeta(name, metaOf(meta, head, target, received))
                    checkpointed = received
                }
                onProgress(DownloadProgress(received, head?.total?.takeIf { it > 0 } ?: target.size, attempt))
            }

            try {
                head = fetcher.fetch(target.url, offset, meta?.validator) { h ->
                    // 把这一路的响应头**留在外面**：半路断掉时（fetch 抛异常，赋值语句根本没执行完）
                    // 后面还要靠它把 ETag/Last-Modified 落盘 —— 丢了它，下次续传就变成
                    // 「服务端才给标识、本机没有」→ 半包被判无效、白下一次。
                    head = h
                    val d = UpdateResume.decide(meta, offset, target, h)
                    decision = d
                    when (d) {
                        // 这一路不能用：连字节都不收（尤其**不许**盲接到旧半包后面）
                        is ResumeDecision.Reject -> null
                        is ResumeDecision.Append -> {
                            out = FileOutputStream(part, true)
                            store.writeMeta(name, metaOf(meta, h, target, offset))
                            sink
                        }
                        is ResumeDecision.Restart -> {
                            closeQuietly(out)
                            runCatching { part.delete() }
                            out = FileOutputStream(part, false)
                            received = 0
                            offset = 0
                            checkpointed = 0
                            store.writeMeta(name, metaOf(meta, h, target, 0))
                            sink
                        }
                    }
                }
                closeQuietly(out)
                out = null
            } catch (e: Exception) {
                // 断流 / 超时 / 卡死 / 磁盘故障：**留着半包**，退避之后接着上次的字节再要一段
                closeQuietly(out)
                out = null
                runCatching { store.writeMeta(name, metaOf(meta, head, target, received)) }
                last = e
                continue
            }

            val d = decision
            if (d == null || d is ResumeDecision.Reject) {
                // 这一段字节我们一个都没要（或压根没拿到头）：半包作废，从 0 重来
                store.clear(name)
                last = IllegalStateException((d as? ResumeDecision.Reject)?.why ?: "服务器没有给出可用的响应")
                continue
            }

            // ---- 这一段收完了：把对象指纹记下来，供下次续传比对
            runCatching { store.writeMeta(name, metaOf(meta, head, target, received)) }

            // ---- 闸门一：大小
            val got = part.length()
            if (target.size > 0 && got != target.size) {
                last = SizeMismatch(got, target.size)
                continue
            }

            // ---- 闸门二：sha256。不过关就把半包全丢掉，绝不留下可疑文件
            val actual = UpdateLogic.sha256HexOf(part)
            if (!UpdateLogic.shaMatches(target.sha256, actual)) {
                store.clear(name)
                last = ChecksumMismatch(target.sha256, actual)
                continue
            }

            // ---- 到这一步它才配叫"安装包"：原子改名交出去
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                last = IOException("把下载好的包改名成安装包失败")
                continue
            }
            store.clear(name)
            // 最后一帧：大小未知时 total 保持 -1，绝不为了"好看"编一个 100% 出来
            val known = head?.total?.takeIf { it > 0 } ?: target.size
            onProgress(DownloadProgress(got, known, attempt))
            return Result.success(dest)
        }
    }

    /**
     * 半包里已经确认收下的字节数（= 续传的起点）。
     *
     * 为什么敢直接用**文件长度**：进程被系统杀掉（"跨 App 重启"就是这种）不会丢掉
     * 已经写进文件系统的字节。真正会丢字节的只有断电/内核崩溃，那时半包尾巴可能被截断 ——
     * 这个由最后的 sha256 兜住（校验不过就整个重下，绝不安装）。
     */
    internal fun confirmedOffset(part: File, meta: PartMeta?, target: DownloadTarget): Long {
        if (!part.isFile) return 0L
        val len = part.length()
        if (len <= 0L) return 0L
        val sameFile = meta != null && meta.url == target.url && meta.sha256.isNotBlank() &&
            UpdateLogic.shaMatches(meta.sha256, target.sha256)
        if (!sameFile) {
            // 半包不是这个版本的（换版本、换地址、元数据丢了）→ 作废，别拿它拼包
            runCatching { part.delete() }
            return 0L
        }
        if (target.size > 0 && len > target.size) {
            runCatching { part.delete() }
            return 0L
        }
        return len
    }

    private fun totalOf(meta: PartMeta?, target: DownloadTarget): Long =
        meta?.total?.takeIf { it > 0 } ?: target.size

    private fun metaOf(meta: PartMeta?, head: StreamHead?, target: DownloadTarget, received: Long) =
        PartMeta(
            url = target.url,
            etag = head?.etag ?: meta?.etag,
            lastModified = head?.lastModified ?: meta?.lastModified,
            sha256 = target.sha256,
            total = head?.total?.takeIf { it > 0 } ?: target.size,
            received = received,
            updatedAt = tuning.clock(),
        )

    private fun flushAndSync(out: FileOutputStream) {
        out.flush()
        runCatching { out.fd.sync() }
    }

    private fun closeQuietly(out: FileOutputStream?) {
        runCatching { out?.flush() }
        runCatching { out?.close() }
    }

    /** 失败前的一句人话：说清断在哪、还留着什么、下一步点哪儿 */
    internal fun failureText(target: DownloadTarget, part: File, last: Exception?, attempts: Int): String {
        val got = part.length()
        val kept = if (got > 0) {
            "已经下好的 ${UpdateLogic.humanSize(got)} 还在，点「重试」会从这里接着下。"
        } else {
            "没有留下任何文件。"
        }
        val head = when (last) {
            is ChecksumMismatch ->
                "安装包校验没通过（可能下载损坏，也可能被人换过），损坏的字节已全部丢弃。"
            is StallTimeoutException ->
                "网络卡死了（连续 ${last.limitMs / 1000} 秒没有新数据，已自动断开重来）。"
            is SizeMismatch ->
                "下载不完整（大小对不上），已重试 ${attempts - 1} 次。"
            is SocketTimeoutException -> "网络超时，已重试 ${attempts - 1} 次。"
            is SocketException -> "网络断了，已重试 ${attempts - 1} 次。"
            null -> "下载没成功。"
            // ⚠️ 这里原来把 `last.message`（异常原文，可能是 "Connection reset"）直接拼给用户。
            // 2026-09-24 收敛：原文只进 logcat，界面给产品话（下面那行日志就是留痕处）。
            else -> {
                StudentError.tech(last, "更新下载失败")
                "网络异常，已重试 ${attempts - 1} 次。"
            }
        }
        return "更新下载失败：$head$kept"
    }

    private fun retryNote(last: Exception?): String = when (last) {
        null -> "正在重试"
        is ChecksumMismatch -> "校验没通过，正在重新下载"
        is StallTimeoutException -> "网络卡住没数据，已断开正在重试"
        is SizeMismatch -> "下载不完整，正在续传"
        is SocketTimeoutException -> "网络超时，正在续传"
        is SocketException -> "网络断了，正在续传"
        else -> "网络异常，正在重试"
    }
}
