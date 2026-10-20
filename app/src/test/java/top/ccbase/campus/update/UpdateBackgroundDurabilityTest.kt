package top.ccbase.campus.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「切了后台就断、又要重下」的守护测试（2026-10-20 用户原话）。
 *
 * 病根有两条，这里一条一条钉住：
 *  ① **下载期间没人保命** —— 之前的下载住在进程内线程里，App 一进后台，
 *     Android 12+ 会把进程冻住、国产 ROM 直接清掉，29MB 的包当场卡死；
 *     → 必须有前台服务（[UpdateService]），且**开始下载的那一刻**就叫起来、结束就收掉。
 *  ② **被杀之后就接不上了** —— 内存里的"我在下哪个版本"跟着进程一起没了，
 *     回来只能从头来；→ 目标要落盘，进程重启/服务重启都要能 [UpdateGate.resumeIfPending]。
 *
 * 这里用可注入的 [UpdateGate.keepAlive] 与假下载器来观察这两件事，
 * 不去驱动真的 Service（JVM 上没有真系统）。
 */
@RunWith(RobolectricTestRunner::class)
class UpdateBackgroundDurabilityTest {

    private val ctx: Context get() = getApplicationContext()

    /** 比 Robolectric 里"已安装版本"大得多的版本号：续传要判"新版本才下" */
    private val newCode = 900_001

    private fun manifest(code: Int = newCode, name: String = "9.0.1") = UpdateManifest(
        versionCode = code, versionName = name,
        url = "https://example.invalid/app-$code.apk",
        sha256 = "a".repeat(64), size = 4096,
    )

    private fun partOf(m: UpdateManifest): File = Updater.partFile(ctx, m).also { it.parentFile?.mkdirs() }

    @Before
    fun setUp() {
        UpdateGate.resetForTest()
        UpdateGate.keepAlive = { _, _ -> }        // 默认不真起服务，各用例自己接管
        UpdateGate.downloader = { _, _, _ -> Result.failure(IOException("没配下载器")) }
        // Robolectric 里没有真的 Wi-Fi 网络状态；这几条用例验的是"续不续"，
        // 别让"只允许 Wi-Fi"这道门槛把结论搅浑（它自己有单独的判断）。
        AutoUpdate.setWifiOnly(ctx, false)
        clearGatePrefs()
    }

    @After
    fun tearDown() {
        UpdateGate.resetForTest()
    }

    private fun clearGatePrefs() {
        ctx.getSharedPreferences("campus_update_gate", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitUntil(ms: Long = 3_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    // ============================================================ ① 保命

    @Test
    fun `下载一开始就叫前台服务_结束就收掉`() {
        val keep = mutableListOf<Boolean>()
        UpdateGate.keepAlive = { _, on -> keep += on }
        // 卡在闸门上，确保我们能观察到"下载期间是 true"
        val gate = CountDownLatch(1)
        UpdateGate.downloader = { c, m, _ ->
            gate.await(5, TimeUnit.SECONDS)
            File(c.cacheDir, "ok-${m.versionCode}.apk").apply { writeBytes(ByteArray(16)) }.let { Result.success(it) }
        }

        assertEquals(UpdateGate.Start.STARTED, UpdateGate.start(ctx, manifest(), quiet = true))
        assertTrue("开始下载之前就必须叫保命（晚一步用户就按了 Home 键）", keep.contains(true))

        gate.countDown()
        assertTrue("下完要收掉前台服务", waitUntil { keep.contains(false) })
        assertTrue("状态要进 Ready", UpdateGate.state is UpdateGate.S.Ready)
    }

    @Test
    fun `静默下载也要发进度通知_前台服务必须有那条常驻通知`() {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        UpdateGate.downloader = { c, m, onProgress ->
            onProgress(0.5f)
            File(c.cacheDir, "ok-${m.versionCode}.apk").apply { writeBytes(ByteArray(16)) }.let { Result.success(it) }
        }
        UpdateGate.start(ctx, manifest(), quiet = true)   // ← 静默：以前这条一个通知都不发
        assertTrue("静默下载也必须让状态栏看得见（前台服务靠它，用户靠它判断还在不在下）",
            waitUntil { shadowOf(nm).allNotifications.isNotEmpty() })
    }

    // ============================================================ ② 断点续跑

    @Test
    fun `进程被杀之后_从半包接着下_不从零重来`() {
        val m = manifest()
        val part = partOf(m)
        part.writeBytes(ByteArray(1500) { 7 })
        val expected = part.length()

        // 先起一次（把"我在下哪个版本"落盘），然后让它失败 —— 半包留在原地
        UpdateGate.downloader = { _, _, _ -> Result.failure(IOException("网络断了")) }
        UpdateGate.start(ctx, m, quiet = true)
        assertTrue(waitUntil { UpdateGate.state is UpdateGate.S.Failed })

        // 模拟"进程被系统杀掉"：内存状态清空，只有磁盘还在
        UpdateGate.resetForTest()

        val seenOffset = java.util.concurrent.atomic.AtomicLong(-1)
        UpdateGate.downloader = { c, mm, _ ->
            seenOffset.set(Updater.partFile(c, mm).length())
            part.writeBytes(ByteArray(mm.size.toInt()))
            File(c.cacheDir, "resumed-${mm.versionCode}.apk").apply { writeBytes(ByteArray(8)) }
                .let { Result.success(it) }
        }

        assertTrue("有半包就必须续上，不能等用户回来从头点", UpdateGate.resumeIfPending(ctx))
        assertTrue(waitUntil { UpdateGate.state is UpdateGate.S.Ready })
        assertEquals("续传时半包必须还在（引擎从它的长度接着要下一段）", expected, seenOffset.get())
    }

    @Test
    fun `没有半包时不瞎起任务_目标一并清掉`() {
        val m = manifest()
        UpdateGate.downloader = { _, _, _ -> Result.failure(IOException("网络断了")) }
        UpdateGate.start(ctx, m, quiet = true)
        assertTrue(waitUntil { UpdateGate.state is UpdateGate.S.Failed })
        UpdateGate.resetForTest()
        // 半包被系统清掉了（极少见，但清理类 App 会干）
        partOf(m).delete()

        val called = AtomicBoolean(false)
        UpdateGate.downloader = { _, _, _ -> called.set(true); Result.failure(IOException("不该被调用")) }
        assertFalse(UpdateGate.resumeIfPending(ctx))
        Thread.sleep(200)
        assertFalse("没东西可续就不许起下载", called.get())
    }

    @Test
    fun `已经装上了的版本不再续传_半包顺手删掉`() {
        UpdateGate.installedCodeOverride = 5                // 假装装着的是 v5
        val m = manifest(code = 1, name = "0.0.1")          // 比装着的老
        val part = partOf(m)
        part.writeBytes(ByteArray(100))
        // 直接写目标（模拟上一轮留下的记账）
        UpdateGate.downloader = { _, _, _ -> Result.failure(IOException("x")) }
        UpdateGate.start(ctx, m, quiet = true)
        assertTrue(waitUntil { UpdateGate.state is UpdateGate.S.Failed })
        UpdateGate.resetForTest()

        val called = AtomicBoolean(false)
        UpdateGate.downloader = { _, _, _ -> called.set(true); Result.failure(IOException("不该被调用")) }
        assertFalse(UpdateGate.resumeIfPending(ctx))
        Thread.sleep(150)
        assertFalse("老版本的半包不许再下", called.get())
    }

    @Test
    fun `下好之后清账_不会再自己重下`() {
        val m = manifest()
        partOf(m).writeBytes(ByteArray(64))
        UpdateGate.downloader = { c, mm, _ ->
            File(c.cacheDir, "done-${mm.versionCode}.apk").apply { writeBytes(ByteArray(8)) }
                .let { Result.success(it) }
        }
        UpdateGate.start(ctx, m, quiet = true)
        assertTrue(waitUntil { UpdateGate.state is UpdateGate.S.Ready })
        UpdateGate.resetForTest()

        val called = AtomicBoolean(false)
        UpdateGate.downloader = { _, _, _ -> called.set(true); Result.failure(IOException("不该被调用")) }
        assertFalse("下完就该清账，不许反复下同一个包", UpdateGate.resumeIfPending(ctx))
        Thread.sleep(150)
        assertFalse(called.get())
    }

    // ============================================================ 接线守卫

    @Test
    fun `清单里声明了前台服务与它的类型权限`() {
        val xml = File("src/main/AndroidManifest.xml")
        assertTrue("找不到清单（测试工作目录变了？）", xml.isFile)
        val s = xml.readText()
        assertTrue("Android 14 起必须有 FOREGROUND_SERVICE 权限", s.contains("permission.FOREGROUND_SERVICE\""))
        assertTrue("下载属于 dataSync 类型，权限也要声明", s.contains("FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue("服务本身要声明", s.contains("android:name=\".update.UpdateService\""))
        assertTrue("类型不声明，Android 14 上起前台服务会崩", s.contains("foregroundServiceType=\"dataSync\""))
    }

    @Test
    fun `进程启动时会把没下完的更新接上`() {
        val app = File("src/main/java/top/ccbase/campus/CampusApplication.kt")
        assertTrue(app.isFile)
        assertTrue(
            "Application 起来时必须有续跑入口（否则被杀之后只能等用户手动点更新）",
            app.readText().contains("UpdateGate.resumeIfPending"),
        )
    }

    @Test
    fun `失败之后不会干等六小时`() {
        val src = File("src/main/java/top/ccbase/campus/update/UpdateGate.kt").readText()
        assertTrue(
            "静默下载失败要排一条 30 分钟的重试闹钟（半包已经在了，等 6 小时等于今天别更新）",
            src.contains("AutoUpdate.scheduleIn("),
        )
    }
}
