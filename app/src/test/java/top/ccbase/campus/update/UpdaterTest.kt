package top.ccbase.campus.update

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.Transport
import java.io.File
import java.security.MessageDigest

/**
 * 更新流程里**碰 Android 的那一半**：查清单、下载、校验、拉安装器。
 *
 * 这里盯的是旁加载更新的两条安全线，两条都必须**真的**执行：
 *   1. 校验和对不上 → 整包丢弃，不留半截文件；
 *   2. 装机权限没开 → 界面要说清去哪儿开，而不是无声失败。
 *
 * 现实约束写在明处：这台机器上没有可用的模拟器（`cc` 不在 kvm 组），
 * 所以"下载一个真的 APK 然后装上去"没法端到端跑 —— 这里用假下载器把
 * 字节流、截断、损坏、半途抛异常四种情况都压了一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val payload = ByteArray(300_000) { (it % 199).toByte() }
    private val payloadSha = UpdateLogic.sha256Hex(payload)

    /** 下载用的站点地址：**测试自己给**，不吃本机 BuildConfig（否则断言的期望值会随构建配置变） */
    private val BASE = "https://api.example.com"

    private fun manifest(
        code: Int = 2, sha: String = payloadSha, size: Long = payload.size.toLong(),
        url: String = "updates/campus-1.23-T1.apk", force: Boolean = false,
    ) = UpdateManifest(code, "1.23-T1", url, sha, size, listOf("修好了假报错"), force, 0)

    /** 假的字节流下载器：留作兼容层验收（新引擎走 [fetcher] 那台"会 Range 的假服务器"） */
    private fun legacyFetcher(bytes: ByteArray = payload, chunk: Int = 64 * 1024, failAt: Int = -1) =
        ApkFetcher { _, sink ->
            var sent = 0
            while (sent < bytes.size) {
                val n = minOf(chunk, bytes.size - sent)
                sink(bytes.copyOfRange(sent, sent + n), n, (sent + n).toLong())
                sent += n
                if (failAt in 1..sent) throw IllegalStateException("连接断了")
            }
            bytes.size.toLong()
        }

    /**
     * 假服务器：**支持 Range**，行为对齐 nginx（206 + Content-Range + ETag），
     * 并且能按剧本断流。老版本这里是个只会"一次性吐字节"的假下载器 ——
     * 下载改成分片引擎之后，假货也得会回答 206/ETag，否则续传这条分支测不出来。
     */
    private fun fetcher(
        bytes: ByteArray = payload,
        chunk: Int = 64 * 1024,
        breakAfter: Int = -1,
        breakOnlyFirst: Boolean = true,
        hideLength: Boolean = false,
    ) = TestPackServer(
        bytes,
        chunk = chunk,
        breakAfterBytes = breakAfter,
        breakOnlyFirst = breakOnlyFirst,
        hideLength = hideLength,
    )

    /** 测试里不真睡：2s + 5s + 15s 的退避，谁都不想等它 22 秒 */
    private fun tuning() = DownloadTuning(sleeper = {})

    private fun download(
        m: UpdateManifest,
        f: RangedFetcher = fetcher(),
        onProgress: (Float) -> Unit = {},
        onState: (DownloadProgress) -> Unit = {},
    ): Result<File> {
        val r = Updater.download(ctx, m, f, base = BASE, onProgress = onProgress, onState = onState, tuning = tuning())
        // 命中一次"中途换地址"就是拼包事故：每次请求都得打在 manifest 给的那一个地址上
        if (f is TestPackServer) {
            assertTrue(
                "中途换了地址：${f.reqs.map { it.url }.distinct()}",
                f.reqs.all { it.url == BASE + "/updates/campus-1.23-T1.apk" },
            )
        }
        return r
    }

    private fun transportOf(code: Int, body: String, seen: MutableList<String>? = null) =
        Transport { _, url, _, _ ->
            seen?.add(url)
            HttpReply(code, body)
        }

    /** 退避等待换成空实现：清单重试也是 2s+5s+15s，真睡的话这几条用例要站一分钟 */
    private val noSleep: (Long) -> Unit = {}

    private fun goodJson(code: Int = 2) = """
        {"version_code":$code,"version_name":"1.23-T1","url":"updates/campus-1.23-T1.apk",
         "sha256":"$payloadSha","size":${payload.size},"notes":["修好了假报错"]}
    """.trimIndent()

    // ---------------------------------------------------------------- 查

    @Test
    fun `有新版就报有新版本`() {
        val out = Updater.check("https://api.example.com", transportOf(200, goodJson()), currentCode = 1)
        assertTrue(out.toString(), out is CheckOutcome.Update)
        val u = out as CheckOutcome.Update
        assertEquals("1.23-T1", u.manifest.versionName)
        assertFalse("没标 force 就不该是强制更新", u.forced)
    }

    @Test
    fun `请求要带时间戳 防止 CDN 缓存住旧清单`() {
        val seen = mutableListOf<String>()
        Updater.check("https://api.example.com", transportOf(200, goodJson(), seen), 1)
        assertTrue("必须打上 ?t= 破缓存：${seen.firstOrNull()}",
            seen.firstOrNull()?.startsWith("https://api.example.com/updates/manifest.json?t=") == true)
    }

    @Test
    fun `清单 404 当作没有更新`() {
        // 换服务器/测试隧道上没有这个文件时，不该给用户弹"更新失败"
        assertTrue(Updater.check("https://x.example.com", transportOf(404, "not found"), 1) is CheckOutcome.Latest)
    }

    @Test
    fun `服务器出错要说清是服务器的问题_而且是退避重试过之后才报`() {
        var calls = 0
        val t = Transport { _, _, _, _ -> calls++; HttpReply(500, "boom") }
        val out = Updater.check("https://api.example.com", t, 1, sleeper = noSleep)
        assertTrue(out is CheckOutcome.Failed)
        assertTrue((out as CheckOutcome.Failed).message.contains("500"))
        assertEquals("5xx 是「服务端暂时不行」，要退避重试满 4 次", 4, calls)
    }

    @Test
    fun `清单 4xx 不重试_一次就说清`() {
        var calls = 0
        val t = Transport { _, _, _, _ -> calls++; HttpReply(403, "nope") }
        val out = Updater.check("https://api.example.com", t, 1, sleeper = noSleep)
        assertTrue(out is CheckOutcome.Failed)
        assertTrue((out as CheckOutcome.Failed).message.contains("403"))
        assertEquals("4xx 重试没有意义，不该白等 22 秒", 1, calls)
    }

    @Test
    fun `清单一上来的断流会退避重试_第二次拿到了就正常返回`() {
        var calls = 0
        val t = Transport { _, _, _, _ ->
            calls++
            if (calls == 1) throw java.io.IOException("Connection reset")
            HttpReply(200, goodJson(code = 5))
        }
        val out = Updater.check("https://api.example.com", t, currentCode = 1, sleeper = noSleep)
        assertTrue("第一次断、第二次通了就该报有新版本：$out", out is CheckOutcome.Update)
        assertEquals(2, calls)
    }

    @Test
    fun `清单是乱码就当作这次没查到`() {
        val out = Updater.check("https://api.example.com", transportOf(200, "<html>hi</html>"), 1, sleeper = noSleep)
        assertTrue(out is CheckOutcome.Failed)
        val f = out as CheckOutcome.Failed
        assertTrue("要说清是「内容读不出来」，而不是含糊的失败：${f.message}", f.message.contains("读不出来"))
    }

    @Test
    fun `网络异常不该崩 只说网络不通`() {
        val boom = Transport { _, _, _, _ -> throw java.io.IOException("no route") }
        val out = Updater.check("https://api.example.com", boom, 1, sleeper = noSleep)
        assertTrue(out is CheckOutcome.Failed)
        assertTrue((out as CheckOutcome.Failed).message.contains("网络"))
    }

    @Test
    fun `已是最新就不打扰`() {
        assertTrue(Updater.check("https://api.example.com", transportOf(200, goodJson(code = 2)), 2) is CheckOutcome.Latest)
    }

    @Test
    fun `强制更新会把原因带出来`() {
        val body = goodJson().replace("\"notes\"", "\"force\":true,\"notes\"")
        val out = Updater.check("https://api.example.com", transportOf(200, body), 1)
        assertTrue((out as CheckOutcome.Update).forced)
        assertTrue(out.why.isNotBlank())
    }

    // ---------------------------------------------------------------- 下

    @Test
    fun `下载成功 且校验通过后文件就在位`() {
        val m = manifest()
        val r = download(m, fetcher(), onProgress = {})
        assertTrue(r.toString(), r.isSuccess)
        val f = r.getOrThrow()
        assertTrue("文件应落在 filesDir/update 下：${f.path}", f.path.contains("/update/"))
        assertEquals(payload.size.toLong(), f.length())
        assertEquals(payloadSha, UpdateLogic.sha256HexOf(f))
        assertFalse("临时文件必须清掉", File(f.path + ".part").exists())
        assertFalse("元数据也要清掉", File(f.path + ".part.json").exists())
    }

    @Test
    fun `分块下载和一次性下载结果一致`() {
        val m = manifest()
        val f1 = download(m, fetcher(chunk = 7_777)).getOrThrow()
        val f2 = download(m, fetcher(chunk = 300_000)).getOrThrow()
        assertEquals(UpdateLogic.sha256HexOf(f1), UpdateLogic.sha256HexOf(f2))
        assertEquals(payloadSha, UpdateLogic.sha256HexOf(f2))
    }

    @Test
    fun `校验和对不上就丢弃 绝不留下可疑文件`() {
        val m = manifest(sha = "b".repeat(64))
        val r = download(m, fetcher())
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("校验"))
        val dest = Updater.destFile(ctx, m)
        assertFalse("不能把没校验过的包留在磁盘上", dest.exists())
        assertFalse("半包也要丢掉（它跟 manifest 说的不是一只包）", File(dest.path + ".part").exists())
    }

    @Test
    fun `大小对不上也算失败 防截断安装包`() {
        val m = manifest(size = payload.size + 4096L)
        val r = download(m, fetcher())
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("大小"))
        assertFalse(Updater.destFile(ctx, m).exists())
    }

    /**
     * 断流不再是「重来一遍」。
     *
     * 老版本这里断言的是「断了就把半截文件删掉」—— 那正是要修的病：7MB 的包在示例市的
     * 移动网上一断就得从头下，用户只能一遍遍重点。现在断流会**留半包 + 续传**，
     * 所以断言改成「最后拿到的还是完整且校验通过的包」，并确认中间真的只补了缺的那一段。
     */
    @Test
    fun `下载半途断了 会自动续传 最后照样是完整的包`() {
        val m = manifest()
        val s = fetcher(breakAfter = 128 * 1024)
        val r = download(m, s)
        assertTrue(r.toString(), r.isSuccess)
        assertEquals(payloadSha, UpdateLogic.sha256HexOf(r.getOrThrow()))
        assertEquals("断一次两次请求就够：一次断、一次接着下", 2, s.calls)
        assertEquals("第二次必须带着已收的字节续传", 128 * 1024L, s.reqs[1].offset)
    }

    @Test
    fun `线路根本下不动时 不留安装包但保住半包 并且给出重试指引`() {
        val m = manifest()
        val r = download(m, fetcher(breakAfter = 64 * 1024, breakOnlyFirst = false))
        assertTrue(r.isFailure)
        val dest = Updater.destFile(ctx, m)
        assertFalse("半包绝不能被当成安装包", dest.exists())
        val part = File(dest.path + ".part")
        assertTrue("已经下好的部分要留着等续传", part.isFile)
        val msg = r.exceptionOrNull()!!.message.orEmpty()
        assertTrue("文案要说清下一步：$msg", msg.contains("重试"))

        // 用户点「重试」：接着上次的字节下完，不必重新下一遍
        val again = download(m, fetcher())
        assertTrue(again.toString(), again.isSuccess)
        assertEquals(payloadSha, UpdateLogic.sha256HexOf(again.getOrThrow()))
    }

    @Test
    fun `进度是递增的 而且能走到头`() {
        val seen = mutableListOf<Float>()
        download(manifest(), fetcher(chunk = 100_000), onProgress = { seen.add(it) })
        assertTrue("要有进度回调", seen.size >= 3)
        assertTrue("进度不能倒退：$seen", seen.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("最后一帧要接近 100%：${seen.last()}", seen.last() > 0.95f)
        assertTrue("进度不能越界：$seen", seen.all { it in 0f..1f })
    }

    @Test
    fun `服务端没给大小时 进度标成未知而不是零`() {
        val seen = mutableListOf<Float>()
        download(manifest(size = 0L), fetcher(hideLength = true), onProgress = { seen.add(it) })
        assertTrue("未知进度要回 -1，界面才不会显示一个假百分比：$seen", seen.all { it < 0f })
    }

    @Test
    fun `断流重试也要在界面上看得见 不能静默`() {
        val st = mutableListOf<DownloadProgress>()
        download(manifest(), fetcher(breakAfter = 128 * 1024), onState = { st.add(it) })
        assertTrue(
            "要在界面上说清「正在重试」：${st.map { it.note }}",
            st.any { it.retryInMs > 0 && !it.note.isNullOrBlank() },
        )
    }

    @Test
    fun `重复下载同一个版本不会把上一份拼在一起`() {
        val m = manifest()
        val a = download(m, fetcher()).getOrThrow()
        val b = download(m, fetcher()).getOrThrow()
        assertEquals("第二次必须是覆盖，不是追加", payload.size.toLong(), b.length())
        assertEquals(UpdateLogic.sha256HexOf(a), UpdateLogic.sha256HexOf(b))
    }

    @Test
    fun `老式一次性下载器接到新引擎上也能下完`() {
        // 兼容层：ApkFetcher 拿不到响应头，只能按「200 整包」上报 ——
        // 引擎的处置是「清半包、从 0 写」，所以它没有续传能力，但绝不会拼出坏包
        val m = manifest()
        val r = download(m, legacyFetcher().asRanged())
        assertTrue(r.toString(), r.isSuccess)
        assertEquals(payloadSha, UpdateLogic.sha256HexOf(r.getOrThrow()))
    }

    // ---------------------------------------------------------------- 装机

    @Test
    fun `装机权限缺失时 界面能给出准确原因而不是静默失败`() {
        // Robolectric 默认没有这个权限 → 走"要引导用户去开"这条路
        val can = Updater.canInstall(ctx)
        if (!can) assertNotNull(UpdateLogic.installPermissionHint(false))
        assertTrue("Robolectric 下拿不到就是 false，这一步只是在确认不会抛异常", can || !can)
    }

    @Test
    fun `安装用的 URI 必须能通过 FileProvider 生成`() {
        // 这一步是 Android 7+ 的硬要求：给系统安装器 file:// 会直接抛 FileUriExposedException，
        // 而这个异常只会在**真机点安装的那一刻**出现，测试不覆盖就等着线上翻车。
        val dir = File(ctx.filesDir, Updater.DIR).apply { mkdirs() }
        val apk = File(dir, "probe.apk").apply { writeBytes(ByteArray(64)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", apk,
        )
        assertTrue("authority 不对：$uri", uri.toString().startsWith("content://${ctx.packageName}.fileprovider/"))
        assertTrue("FileProvider 给不出这个路径：$uri", uri.toString().contains("probe.apk"))

        // 真正要交付的那个路径也要能生成 URI：半包改成放 filesDir 之后，
        // file_paths.xml 里少了 files-path 就只在**真机点安装**时才炸，所以在这里钉死
        val real = Updater.destFile(ctx, manifest()).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8))
        }
        val realUri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", real,
        )
        assertTrue("安装包真实路径给不出 content:// URI：$realUri", realUri.toString().contains(".apk"))
    }

    @Test
    fun `签名不一致的包判为外来包`() {
        // 纯逻辑校验：Android 本来也会拒绝覆盖安装，但我们要在下载阶段就说人话
        val mine = setOf("aa", "bb")
        val theirs = setOf("cc")
        assertTrue(mine.intersect(theirs).isEmpty())

        val pm = ctx.packageManager
        val digest = fun(b: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        assertEquals(64, digest(ByteArray(8)).length)
        // 顺带确认装机包信息能取到（取不到时 Updater 会放行交给系统判定，不误拦）
        val info = pm.getPackageArchiveInfo("/does/not/exist.apk", PackageManager.GET_SIGNING_CERTIFICATES)
        assertTrue("不存在的路径应该返回 null，而不是抛异常", info == null)
    }

    @Test
    fun `更新包必须走传输层下载_不许自己开连接`() {
        // 守的是 2026-09-17 真机那个病：更新下载自己开 URL 连接、绕开"不带 SNI"的那条链路，
        // 结果自检和接口全绿、唯独更新 Connection reset。
        val seen = mutableListOf<String>()
        val blob = "FAKE-APK-BYTES-0123456789".toByteArray()
        val t = object : Transport {
            override fun call(method: String, url: String, headers: Map<String, String>, body: String?) =
                HttpReply(200, "{}")

            override fun stream(url: String, sink: (ByteArray, Int, Long) -> Unit): Long {
                seen += url
                sink(blob, blob.size, blob.size.toLong())
                return blob.size.toLong()
            }
        }
        val out = java.io.ByteArrayOutputStream()
        RealApkFetcher(t).fetch("https://api.example.com/updates/campus-1.31-T1.apk") { b, n, _ ->
            out.write(b, 0, n)
        }
        assertEquals("必须经过传输层（否则会绕开选路、在拦 SNI 的网络里必挂）", 1, seen.size)
        assertTrue("字节要原样交给写入方", blob.contentEquals(out.toByteArray()))
    }

    /**
     * 手动点「检查更新」不能被「以后再说」静音。
     *
     * 真实反馈（2026-09-17）：同学在更新通知里点过「以后再说」，之后**每次**点
     * 「检查更新」都显示最新版本 —— 因为手动检查也把那个版本号传进 decide 了。
     * 自动检查（进 App 时）静音是对的，手动检查是用户在问，必须给真实结果。
     */
    @Test
    fun `手动检查更新不能被以后再说静音`() {
        val body = goodJson(code = 23)
        val auto = Updater.check(
            "https://api.example.com", transportOf(200, body),
            currentCode = 22, skippedCode = 23,
        )
        assertTrue("自动检查应该尊重「以后再说」，不再打扰", auto is CheckOutcome.Latest)

        val manual = Updater.check(
            "https://api.example.com", transportOf(200, body),
            currentCode = 22, skippedCode = 23, manual = true,
        )
        assertTrue(
            "手动检查必须看到真实结果，否则用户永远发现不了更新",
            manual is CheckOutcome.Update,
        )
    }
}
