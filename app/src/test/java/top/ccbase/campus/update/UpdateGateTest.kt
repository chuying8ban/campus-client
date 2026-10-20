package top.ccbase.campus.update

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 「静默更新 / 手动更新不许互相打架」的守护测试。
 *
 * 背景：3.4.40 之前的静默更新就是因为两个任务各下一份、抢同一个 .part 文件，
 * 用户看到的是"更新怎么都装不上" —— 功能被整块砍掉。2026-10-20 用户要求加回来，
 * 但明确说「重点是两者不要冲突导致更新失败」。这几条用例就是那句话的可执行版本。
 */
@RunWith(RobolectricTestRunner::class)
class UpdateGateTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun manifest(code: Int, name: String) = UpdateManifest(
        versionCode = code, versionName = name,
        url = "https://example.invalid/app-$code.apk",
        sha256 = "0".repeat(64), size = 10,
    )

    /** 假下载器：卡在闸门上直到测试放行，顺便把每次调用记下来。 */
    private class FakeDownloader(private val gate: CountDownLatch) {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        fun run(ctx: Context, m: UpdateManifest, onProgress: (Float) -> Unit): Result<File> {
            calls.incrementAndGet()
            onProgress(0.4f)
            gate.await(5, TimeUnit.SECONDS)
            val f = File(ctx.cacheDir, "fake-${m.versionCode}.apk")
            f.writeBytes(ByteArray(16) { 1 })
            return Result.success(f)
        }
    }

    @Test
    fun `手动点击接管静默任务_不会开第二条下载`() {
        UpdateGate.resetForTest()
        val latch = CountDownLatch(1)
        val fake = FakeDownloader(latch)
        UpdateGate.downloader = fake::run

        val m = manifest(999, "9.9.9")
        assertEquals(UpdateGate.Start.STARTED, UpdateGate.start(ctx, m, quiet = true))
        // 等后台线程真的开始（假下载器第一件事就是报 0.4）
        Thread.sleep(300)
        assertTrue(UpdateGate.state is UpdateGate.S.Working)

        // 用户这时手动点了更新：必须"接管"，而不是再下一次
        assertEquals(UpdateGate.Start.ATTACHED, UpdateGate.start(ctx, m, quiet = false))
        val s = UpdateGate.state as UpdateGate.S.Working
        assertTrue("接管后要变成前台任务（会显示进度浮层）", !s.quiet)
        assertEquals("只许有一次下载", 1, fake.calls.get())

        latch.countDown()
        Thread.sleep(400)
        assertTrue("下完应进 Ready", UpdateGate.state is UpdateGate.S.Ready)
        assertNotNull("Ready 之后「我的」页要能拿到它", UpdateGate.pending(ctx))
        assertEquals(1, fake.calls.get())
    }

    @Test
    fun `已在后台下好时_手动点更新直接进安装不再下载`() {
        UpdateGate.resetForTest()
        val latch = CountDownLatch(0)
        val fake = FakeDownloader(latch)
        UpdateGate.downloader = fake::run
        val m = manifest(1001, "10.0.1")

        UpdateGate.start(ctx, m, quiet = true)
        Thread.sleep(500)
        assertTrue(UpdateGate.state is UpdateGate.S.Ready)

        assertEquals(UpdateGate.Start.READY_ALREADY, UpdateGate.start(ctx, m, quiet = false))
        assertEquals("已经有包了就不该再下载", 1, fake.calls.get())
        val r = UpdateGate.state as UpdateGate.S.Ready
        assertTrue("点过之后这次算前台，界面要弹安装框", !r.quiet)
    }

    @Test
    fun `不同版本同时在跑时_第二次请求被拒而不是并发下载`() {
        UpdateGate.resetForTest()
        val latch = CountDownLatch(1)
        val fake = FakeDownloader(latch)
        UpdateGate.downloader = fake::run

        UpdateGate.start(ctx, manifest(2001, "20.0.1"), quiet = true)
        Thread.sleep(300)
        assertEquals(
            "另一个版本在下载时应直接拒绝，别开第二条",
            UpdateGate.Start.BUSY_OTHER,
            UpdateGate.start(ctx, manifest(2002, "20.0.2"), quiet = true),
        )
        assertEquals(1, fake.calls.get())
        latch.countDown()
        Thread.sleep(400)
    }
}
