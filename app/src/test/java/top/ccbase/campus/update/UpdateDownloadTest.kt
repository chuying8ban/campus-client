package top.ccbase.campus.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「更新下载防断机制」的验收：断点续传、退避重试、停滞检测、校验不过绝不安装。
 *
 * 这里跑的全是纯 JVM 逻辑（`UpdateDownload.kt` 里没有一行 Android API），
 * 因为这台机器上没有可用的模拟器（`cc` 不在 kvm 组）——真机上"随机断流"没法复现，
 * 只能把每条分支都做成可编排的剧本，在这里钉死。
 *
 * 每条用例的名字都写成一句"用户会碰到的事"，因为这里守的是**他不会看到坏包**。
 */
class UpdateDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val apk = "campus-1.60-T1.apk"
    private val url = "https://api.example.com/updates/$apk"

    /** 512KB 的假安装包（够断两次，又不至于跑得慢） */
    private val payload = ByteArray(512 * 1024) { (it * 31 % 251).toByte() }
    private val sha = UpdateLogic.sha256Hex(payload)

    /** 分块 8KB、在第 12 块断 → 断流时磁盘上正好 98304 字节（整数，断言好读） */
    private val chunk = 8 * 1024
    private val breakAt = 12 * chunk

    private val sleeps = mutableListOf<Long>()

    private fun dir(): File = tmp.newFolder()

    private fun partOf(d: File) = File(d, "$apk.part")

    private fun metaOf(d: File) = File(d, "$apk.part.json")

    private fun destOf(d: File) = File(d, apk)

    private fun target(size: Long = payload.size.toLong(), sha256: String = sha) =
        DownloadTarget(url = url, sha256 = sha256, size = size)

    /** 退避等待全部记下来、**不真的睡**（不然一条失败用例要站 22 秒） */
    private fun tuning(
        clock: () -> Long = System::currentTimeMillis,
        maxAttempts: Int = RetryPolicy.MAX_ATTEMPTS,
        stallLimitMs: Long = STALL_LIMIT_MS,
    ) = DownloadTuning(
        maxAttempts = maxAttempts,
        stallLimitMs = stallLimitMs,
        sleeper = { sleeps += it },
        clock = clock,
    )

    private fun run(
        server: RangedFetcher,
        d: File,
        t: DownloadTarget = target(),
        tuning: DownloadTuning = tuning(),
        onProgress: (DownloadProgress) -> Unit = {},
    ): Result<File> {
        val r = DownloadEngine(server, tuning).run(t, partOf(d), destOf(d), onProgress)
        // 硬规矩：一次下载里每一次请求都必须打在 manifest 给的那**一个**地址上。
        // 换路追写 = 把两个不同文件的字节拼在一个文件里，那是个装不上的坏包。
        if (server is TestPackServer) {
            assertTrue(
                "中途换了地址：${server.reqs.map { it.url }.distinct()}",
                server.reqs.all { it.url == url },
            )
        }
        return r
    }

    // ================================================================ 一卷到底

    @Test
    fun `完整下载_校验通过才会有安装包`() {
        val d = dir()
        val s = TestPackServer(payload, chunk = chunk)
        val r = run(s, d)
        assertTrue(r.toString(), r.isSuccess)
        assertArrayEquals(payload, destOf(d).readBytes())
        assertEquals(sha, UpdateLogic.sha256HexOf(destOf(d)))
        assertFalse("半包和元数据都要清掉", partOf(d).exists())
        assertFalse(metaOf(d).exists())
        assertNull("第一次请求不该带 If-Range", s.reqs[0].ifRange)
        assertEquals(0L, s.reqs[0].offset)
    }

    @Test
    fun `三次退避重试都打在同一台服务器上_绝不静默换地址`() {
        // 换地址续写 = 把两台服务器的字节拼进一个文件 —— 那是个装不上的坏包，
        // 而且用户什么都看不到。整条链路只认 manifest 给的那一个 URL。
        val d = dir()
        val s = TestPackServer(payload, chunk = chunk, breakAfterBytes = breakAt, breakOnlyFirst = false)
        run(s, d)
        assertEquals("整条链路只许有一个 URL（绝不静默换路）：${s.reqs.map { it.url }}", 1, s.reqs.map { it.url }.distinct().size)
        assertEquals(url, s.reqs.first().url)
        assertTrue("重试次数要够多，才能证明它一直没换：${s.reqs.size}", s.reqs.size >= 3)
    }

    @Test
    fun `进度只增不退_能走到 100%`() {
        val d = dir()
        val seen = mutableListOf<DownloadProgress>()
        run(TestPackServer(payload, chunk = chunk), d, onProgress = { seen += it })
        assertTrue("回调太少：${seen.size}", seen.size >= 10)
        assertTrue("进度不许倒退", seen.zipWithNext().all { (a, b) -> b.received >= a.received })
        assertTrue("只能是 0..1：${seen.map { it.fraction }.distinct()}", seen.all { it.fraction in 0f..1f })
        assertEquals("最后一帧要满", 1f, seen.last().fraction, 0.0001f)
        assertTrue("要有一句人话：${seen.last().sizeText()}", seen.last().sizeText().contains("/"))
    }

    // ================================================================ 断点续传

    @Test
    fun `断流后自动续传_下一段请求带着已收字节的 Range 和 If-Range`() {
        val d = dir()
        val s = TestPackServer(payload, chunk = chunk, breakAfterBytes = breakAt)
        val r = run(s, d)
        assertTrue(r.toString(), r.isSuccess)
        assertEquals("断一次应该只需要两次请求", 2, s.calls)
        assertEquals("第二次必须接着已收的字节要", breakAt.toLong(), s.reqs[1].offset)
        assertEquals("还要带上 If-Range，让服务端也帮把一道关", "\"v1\"", s.reqs[1].ifRange)
        assertArrayEquals("拼起来必须和原包一模一样（错一个字节就是坏包）", payload, destOf(d).readBytes())
        assertEquals(payload.size.toLong(), destOf(d).length())
    }

    @Test
    fun `续传只跑一次请求就能下完剩下的一半_而且不会重复写前面的字节`() {
        val d = dir()
        // 第一次手动铺好一个半包 + 元数据（等价于"上次被杀进程时留下了这些"）
        val packed = payload.copyOfRange(0, breakAt)
        partOf(d).writeBytes(packed)
        PartStore(d).writeMeta(apk, PartMeta(url, "\"v1\"", "LM", sha, payload.size.toLong(), breakAt.toLong(), 0))
        val s = TestPackServer(payload, chunk = chunk)
        val r = run(s, d)
        assertTrue(r.toString(), r.isSuccess)
        assertEquals(1, s.calls)
        assertEquals("必须从半包末尾接着下", breakAt.toLong(), s.reqs[0].offset)
        assertEquals(payload.size.toLong(), destOf(d).length())
        assertArrayEquals(payload, destOf(d).readBytes())
    }

    @Test
    fun `服务端不支持 Range（回 200）_清空半包从 0 重下_绝不把整包接在半包后面`() {
        val d = dir()
        val s = TestPackServer(payload, chunk = chunk, breakAfterBytes = breakAt, ignoreRange = true)
        val r = run(s, d)
        assertTrue(r.toString(), r.isSuccess)
        assertEquals("回 200 也救得回来：整包重写一遍", payload.size.toLong(), destOf(d).length())
        assertArrayEquals(payload, destOf(d).readBytes())
        assertEquals("第一次断在 $breakAt，所以第二次拿到的整包是从 0 开始写的", breakAt.toLong(), s.reqs[1].offset)
    }

    @Test
    fun `服务器换了包（ETag 变了）_客户端自己把关_清半包从 0 重来`() {
        val d = dir()
        val s = TestPackServer(payload, chunk = chunk, breakAfterBytes = breakAt, ignoreIfRange = true)
        // 第 1 次请求**发出头之后**（吐字节期间）线上把包换了 —— 而且这台服务器不认 If-Range，
        // 照样回 206，把"识别换包"的责任完全推给客户端
        s.beforeChunk = { s.etag = "\"v2\"" }
        val r = run(s, d)
        assertTrue(r.toString(), r.isSuccess)
        assertEquals("换包 → 必须清半包、从 0 重新要", listOf(0L, breakAt.toLong(), 0L), s.reqs.map { it.offset })
        assertArrayEquals(payload, destOf(d).readBytes())
    }

    @Test
    fun `线上内容真的换了_宁可不装也不拼出坏包`() {
        val d = dir()
        // 同样大小、不同内容的另一个包（模拟"服务端把包换掉了，但 manifest 的 sha256 还是老的"）
        val other = ByteArray(payload.size) { (it * 7 + 3).toByte() }
        val s = TestPackServer(payload, chunk = chunk, breakAfterBytes = breakAt, ignoreIfRange = true)
        // 断在第 1 次请求上之后，线上的包被**换成了另一份内容**（同样大小）：这里模拟的
        // 就是最坏情况 —— 把新包的尾巴接在旧包的前半截后面，得到一个装不上的坏包
        s.beforeChunk = { s.body = other; s.etag = "\"v2\"" }
        val r = run(s, d)
        assertTrue("拿到的字节不是 manifest 说的那个 → 必须失败", r.isFailure)
        assertFalse("绝不能留下一个装不上的包", destOf(d).exists())
        assertFalse("坏掉的半包也不许留着当续传基础", partOf(d).exists())
        val msg = r.exceptionOrNull()?.message.orEmpty()
        assertTrue("文案要说清是校验没过：$msg", msg.contains("校验"))
    }

    // ================================================================ 校验闸门

    @Test
    fun `sha256 对不上_整包丢弃_绝不安装`() {
        val d = dir()
        val r = run(TestPackServer(payload, chunk = chunk), d, target(sha256 = "b".repeat(64)))
        assertTrue(r.isFailure)
        assertFalse(destOf(d).exists())
        assertFalse("半包也要丢掉（它跟 manifest 根本不是一只包）", partOf(d).exists())
        assertTrue(r.exceptionOrNull()!!.message!!.contains("校验"))
    }

    @Test
    fun `大小对不上_不安装_但要留着半包好续传`() {
        val d = dir()
        val r = run(TestPackServer(payload, chunk = chunk), d, target(size = payload.size + 4096L))
        assertTrue(r.isFailure)
        assertFalse("短一截的包绝不能装", destOf(d).exists())
        assertTrue("已经下好的部分要留着", partOf(d).length() > 0)
        val msg = r.exceptionOrNull()!!.message.orEmpty()
        assertTrue("文案要提到大小/不完整：$msg", msg.contains("大小") || msg.contains("不完整"))
    }

    @Test
    fun `半包永远不会被当成安装包_失败时留在原地等续传`() {
        val d = dir()
        val s = TestPackServer(payload, chunk = chunk, breakAfterBytes = breakAt, breakOnlyFirst = false)
        val r = run(s, d)
        assertTrue(r.isFailure)
        assertFalse("从头到尾都不该出现安装包", destOf(d).exists())
        assertTrue("半包要留着", partOf(d).isFile)
        assertNotEquals("半包的校验和当然不是 manifest 里的那个", sha, UpdateLogic.sha256HexOf(partOf(d)))
        val msg = r.exceptionOrNull()!!.message.orEmpty()
        assertTrue("必须给一句人话（含下一步怎么做）：$msg", msg.contains("重试"))
    }

    // ================================================================ 重试与退避

    @Test
    fun `断流退避三次_2秒5秒15秒_并且每次都接着已收字节`() {
        val d = dir()
        val s = TestPackServer(payload, chunk = chunk, breakAfterBytes = breakAt, breakOnlyFirst = false)
        val seen = mutableListOf<DownloadProgress>()
        val r = run(s, d, onProgress = { seen += it })

        assertTrue(r.isFailure)
        assertEquals("退避节奏必须是 2s/5s/15s", listOf(2000L, 5000L, 15000L), sleeps)
        assertEquals("一共尝试 4 次（1 次原始 + 3 次重试）", 4, s.calls)
        assertEquals(4, seen.last().attempt)
        assertTrue(
            "退避时必须明说\"正在重试\"（禁止静默）：${seen.filter { it.retryInMs > 0 }.map { it.note }}",
            seen.any { it.retryInMs > 0 && !it.note.isNullOrBlank() },
        )
        assertTrue("重试要带着已收字节续传，不许从 0 重下：${s.reqs.map { it.offset }}",
            s.reqs.drop(1).all { it.offset > 0 })
        assertEquals("整条链路只许有一个 URL（绝不静默换路）", 1, s.reqs.map { it.url }.distinct().size)
        assertTrue("文案要报出重试了几次：${r.exceptionOrNull()!!.message}",
            r.exceptionOrNull()!!.message!!.contains("已重试 3 次"))
    }

    @Test
    fun `停滞超时_20秒没有新数据就断开重试_并且接着下`() {
        val d = dir()
        var now = 0L
        val s = TestPackServer(payload, chunk = chunk)
        // 第一次请求吐了 32KB 之后"卡住"：下一块来之前，时钟已经走了 31 秒
        s.beforeChunk = { sent ->
            if (s.calls == 1 && sent >= 4 * chunk && now == 0L) {
                now = 31_000
                s.beforeChunk = {}
            }
        }
        val seen = mutableListOf<DownloadProgress>()
        val r = run(s, d, tuning = tuning(clock = { now }), onProgress = { seen += it })

        assertTrue("卡死也必须能救回来：${r.exceptionOrNull()?.message}", r.isSuccess)
        assertArrayEquals(payload, destOf(d).readBytes())
        assertEquals("第二次要从卡住前已经收下的字节接着要", 4L * chunk, s.reqs[1].offset)
        assertTrue(
            "界面上要说清是网络卡住了：${seen.map { it.note }}",
            seen.any { it.retryInMs > 0 && it.note!!.contains("卡") },
        )
    }

    @Test
    fun `停滞检测_不到阈值不误判_到了就抛`() {
        var now = 0L
        val g = StallGuard(20_000, { now })
        now = 19_999
        g.check()                       // 差 1 毫秒：不许误杀正在慢慢传的连接
        now = 20_000
        val e = assertThrows(StallTimeoutException::class.java) { g.check() }
        assertEquals(20_000L, e.idleMs)
        assertTrue("文案要给用户看得懂：${e.message}", e.message!!.contains("卡住"))
        now = 39_000
        g.noteByte()                    // 来了一块字节 → 计时重新开始
        now = 40_000
        g.check()
    }

    @Test
    fun `退避节奏表`() {
        assertEquals(2000L, RetryPolicy.delayMs(1))
        assertEquals(5000L, RetryPolicy.delayMs(2))
        assertEquals(15000L, RetryPolicy.delayMs(3))
        assertEquals("重试次数用尽就不再退避了", 15000L, RetryPolicy.delayMs(4))
        assertTrue(RetryPolicy.shouldRetry(3))
        assertFalse(RetryPolicy.shouldRetry(4))
        assertEquals(4, RetryPolicy.MAX_ATTEMPTS)
    }

    // ================================================================ 跨 App 重启

    @Test
    fun `跨 App 重启也能续_半包和元数据都在磁盘上`() {
        val d = dir()
        // 第一次：这条线路就是不通（4 次全断）→ 失败，但半包留着
        val dead = TestPackServer(payload, chunk = chunk, breakAfterBytes = breakAt, breakOnlyFirst = false)
        val r1 = run(dead, d)
        assertTrue(r1.isFailure)
        assertFalse(destOf(d).exists())
        val kept = partOf(d).length()
        assertTrue("已经下好的 $kept 字节要留着", kept in 1 until payload.size.toLong())
        assertTrue("元数据也要在，否则续不了", metaOf(d).isFile)

        // "重启 App"：全新的引擎，只认磁盘上的东西
        val alive = TestPackServer(payload, chunk = chunk)
        val r2 = run(alive, d)
        assertTrue(r2.toString(), r2.isSuccess)
        assertEquals("必须接着上次的字节要，不许从 0 重下", kept, alive.reqs[0].offset)
        assertEquals("跨重启也要带 If-Range", "\"v1\"", alive.reqs[0].ifRange)
        assertArrayEquals(payload, destOf(d).readBytes())
    }

    @Test
    fun `半包比线上包还大_判定为坏包_当场作废`() {
        val d = dir()
        partOf(d).writeBytes(ByteArray(payload.size + 10))
        PartStore(d).writeMeta(apk, PartMeta(url, "\"v1\"", "LM", sha, payload.size.toLong(), 0, 0))
        val e = DownloadEngine(TestPackServer(payload), tuning())
        assertEquals(0L, e.confirmedOffset(partOf(d), PartStore(d).readMeta(apk), target()))
        assertFalse("坏半包不许留在磁盘上", partOf(d).exists())
    }

    @Test
    fun `元数据丢了_半包一律作废_宁可重下也不猜`() {
        val d = dir()
        partOf(d).writeBytes(ByteArray(1024))
        val e = DownloadEngine(TestPackServer(payload), tuning())
        assertEquals(0L, e.confirmedOffset(partOf(d), null, target()))
        assertFalse(partOf(d).exists())
    }

    @Test
    fun `换版本之后旧半包作废_不许拿旧字节拼新包`() {
        val d = dir()
        partOf(d).writeBytes(ByteArray(1024))
        // 元数据里记的是**另一个**包的校验和（比如用户先下了 1.50 又换成 1.60）
        PartStore(d).writeMeta(apk, PartMeta(url, "\"v1\"", "LM", "a".repeat(64), payload.size.toLong(), 1024, 0))
        val e = DownloadEngine(TestPackServer(payload), tuning())
        assertEquals(0L, e.confirmedOffset(partOf(d), PartStore(d).readMeta(apk), target()))
        assertFalse(partOf(d).exists())
    }

    // ================================================================ 判定规则（纯函数）

    private fun meta(
        etag: String? = "\"v1\"",
        lm: String? = "LM",
        sha256: String = sha,
        url: String = this.url,
    ) = PartMeta(url = url, etag = etag, lastModified = lm, sha256 = sha256, total = payload.size.toLong())

    private fun decide(offset: Long, head: StreamHead, m: PartMeta? = meta()): ResumeDecision =
        UpdateResume.decide(m, offset, target(), head)

    @Test
    fun `206_区间正好接在已收字节后面_指纹一致_才追加`() {
        val d = decide(1000, StreamHead(206, "\"v1\"", "LM", rangeStart = 1000, rangeTotal = 9999))
        assertEquals(ResumeDecision.Append(1000), d)
    }

    @Test
    fun `206_但区间起点对不上_拒绝`() {
        val d = decide(1000, StreamHead(206, "\"v1\"", "LM", rangeStart = 2048, rangeTotal = 9999))
        assertTrue("不是接在已收字节后面的一律拒收：$d", d is ResumeDecision.Reject)
    }

    @Test
    fun `206_但没有 Content-Range_拒绝`() {
        val d = decide(1000, StreamHead(206, "\"v1\"", "LM"))
        assertTrue(d is ResumeDecision.Reject)
    }

    @Test
    fun `回 200 就是从 0 重下`() {
        val d = decide(1000, StreamHead(200, "\"v1\"", "LM", contentLength = 9999))
        assertTrue(d is ResumeDecision.Restart)
    }

    @Test
    fun `指纹变了_拒绝把新尾巴接在旧半包后面`() {
        val d = decide(1000, StreamHead(206, "\"v2\"", "LM", rangeStart = 1000, rangeTotal = 9999))
        assertTrue(d is ResumeDecision.Reject)
        assertTrue((d as ResumeDecision.Reject).why.contains("换了"))
    }

    @Test
    fun `上次记了标识这次服务端没给_宁可清半包重下也不猜`() {
        val d = decide(1000, StreamHead(206, null, null, rangeStart = 1000, rangeTotal = 9999))
        assertTrue("没有可比对的标识就不许追加：$d", d is ResumeDecision.Reject)
    }

    @Test
    fun `两边都没有标识可比_靠区间对齐_最终还有 sha256 兜底`() {
        val d = decide(1000, StreamHead(206, null, null, rangeStart = 1000, rangeTotal = 9999), meta(etag = null, lm = null))
        assertEquals(ResumeDecision.Append(1000), d)
    }

    @Test
    fun `本机没有半包时_206 必须从 0 开始否则拒收`() {
        assertTrue(decide(0, StreamHead(206, rangeStart = 0, rangeTotal = 9999), null) is ResumeDecision.Restart)
        assertTrue(decide(0, StreamHead(206, rangeStart = 512, rangeTotal = 9999), null) is ResumeDecision.Reject)
    }

    @Test
    fun `416 要清半包重来`() {
        val d = decide(1000, StreamHead(416, rangeTotal = 9999))
        assertTrue(d is ResumeDecision.Reject)
        assertTrue((d as ResumeDecision.Reject).why.contains("416"))
    }

    @Test
    fun `半包比目标还大_拒绝`() {
        val d = decide(payload.size.toLong() + 1, StreamHead(206, "\"v1\"", "LM", rangeStart = payload.size.toLong() + 1, rangeTotal = 9999))
        assertTrue(d is ResumeDecision.Reject)
    }

    @Test
    fun `地址变了_半包作废`() {
        val d = decide(1000, StreamHead(206, "\"v1\"", "LM", rangeStart = 1000, rangeTotal = 9999), meta(url = "https://old.example.com/$apk"))
        assertTrue(d is ResumeDecision.Reject)
    }

    @Test
    fun `解析 Content-Range`() {
        assertEquals(0L to 1000L, UpdateResume.parseContentRange("bytes 0-999/1000"))
        assertEquals(1024L to 7468868L, UpdateResume.parseContentRange("bytes 1024-7468867/7468868"))
        assertEquals("大小写和空格都容错", 0L to 1000L, UpdateResume.parseContentRange(" BYTES 0-999 / 1000 "))
        assertNull("416 那种 bytes */N 没有起点", UpdateResume.parseContentRange("bytes */1000"))
        assertNull(UpdateResume.parseContentRange("nonsense"))
        assertNull(UpdateResume.parseContentRange(null))
    }

    @Test
    fun `响应头解析_200 看 Content-Length_206 看 Content-Range`() {
        val h200 = streamHeadOf(200) { n -> if (n == "Content-Length") "1000" else null }
        assertEquals(1000L, h200.total)
        assertEquals(0L, h200.startsAt)
        val h206 = streamHeadOf(206) { n ->
            when (n) {
                "Content-Range" -> "bytes 200-999/1000"
                "Content-Length" -> "800"
                "ETag" -> "\"v1\""
                else -> null
            }
        }
        assertEquals(200L, h206.startsAt)
        assertEquals("整包大小以 Content-Range 为准", 1000L, h206.total)
        assertEquals("\"v1\"", h206.etag)
    }

    // ================================================================ 半包仓库

    @Test
    fun `PartStore_元数据能落盘再读回_也能连半包一起清掉`() {
        val d = tmp.newFolder()
        val st = PartStore(d)
        st.writeMeta(apk, PartMeta(url, "\"v1\"", "LM", sha, payload.size.toLong(), 1234, 5))
        val back = st.readMeta(apk)!!
        assertEquals("\"v1\"", back.etag)
        assertEquals(1234L, back.received)
        assertEquals("If-Range 优先用 ETag", "\"v1\"", back.validator)
        assertNull("文件不存在时读回 null，不许抛", st.readMeta("nope.apk"))
        File(d, "$apk.part").writeBytes(ByteArray(4))
        st.clear(apk)
        assertFalse(File(d, "$apk.part").exists())
        assertFalse(st.metaFile(apk).exists())
    }

    @Test
    fun `PartStore_只清别的版本留下的半包_已经下好的包不许碰`() {
        val d = tmp.newFolder()
        File(d, "$apk.part").writeBytes(ByteArray(4))
        PartStore(d).writeMeta(apk, PartMeta(url = url))
        File(d, "campus-1.50-T1.apk.part").writeBytes(ByteArray(4))
        PartStore(d).writeMeta("campus-1.50-T1.apk", PartMeta(url = url))
        File(d, apk).writeBytes(ByteArray(4))

        PartStore(d).clearOthers(keepName = "$apk.part")

        assertTrue("当前正在下的半包不许动", File(d, "$apk.part").exists())
        assertTrue(File(d, "$apk.part.json").exists())
        assertFalse(File(d, "campus-1.50-T1.apk.part").exists())
        assertFalse(File(d, "campus-1.50-T1.apk.part.json").exists())
        assertTrue("已经下好的安装包不许碰", File(d, apk).exists())
    }

    @Test
    fun `PartStore_元数据文件坏掉也不崩_当作没有半包`() {
        val d = tmp.newFolder()
        File(d, "$apk.part.json").writeText("{ 这不是 json")
        assertNull(PartStore(d).readMeta(apk))
    }

}
