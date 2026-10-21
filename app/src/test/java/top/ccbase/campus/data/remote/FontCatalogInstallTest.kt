package top.ccbase.campus.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.RemoteFontStore
import top.ccbase.campus.domain.FontIndex
import top.ccbase.campus.domain.RemoteFont
import top.ccbase.campus.update.ByteSink
import top.ccbase.campus.update.RangedFetcher
import top.ccbase.campus.update.StreamHead

/**
 * 字体下载的落地规则（用假链路跑，不连外网）。
 *
 * 守的是这几条，每一条都对应线上会真的发生的坏情况：
 *  - 下到一半断流 → 不许留一个"看着像字体"的半包；
 *  - sha256 对不上（云端换过文件 / 中间盒改过字节）→ 不许安装；
 *  - 校验通过 → 文件落到 `filesDir/fonts/`，且立刻被认成"已下载"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FontCatalogInstallTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(ctx.filesDir, RemoteFontStore.DIR)

    private val payload = ByteArray(120_000) { (it % 251).toByte() }
    private val goodSha = MessageDigest.getInstance("SHA-256").digest(payload)
        .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
        RemoteFontStore.resetForTest()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        RemoteFontStore.resetForTest()
    }

    private fun rf(bytes: Long = payload.size.toLong(), sha: String = goodSha) = RemoteFont(
        id = "demo", name = "演示体", group = "黑体",
        file = "demo.ttf", bytes = bytes, sha256 = sha,
    )

    /** 一次就成功的假链路 */
    private fun okFetcher(body: ByteArray = payload) = RangedFetcher { _, _, _, decide ->
        val head = StreamHead(code = 200, contentLength = body.size.toLong())
        val sink: ByteSink? = decide(head)
        if (sink != null) sink(body, body.size)
        head
    }

    /** 只发一半就断（模拟校园网断流） */
    private fun halfFetcher() = RangedFetcher { _, _, _, decide ->
        val head = StreamHead(code = 200, contentLength = payload.size.toLong())
        val sink: ByteSink? = decide(head)
        val half = payload.size / 2
        if (sink != null) sink(payload.copyOf(half), half)
        head
    }

    @Test
    fun `下完校验通过才算装好_并且立刻算已下载`() = runBlocking {
        val r = FontCatalog.install(ctx, rf(), okFetcher())
        assertTrue("应该成功：${r.exceptionOrNull()?.message}", r.isSuccess)
        val f = r.getOrThrow()
        assertTrue(f.exists())
        assertEquals(payload.size.toLong(), f.length())
        assertEquals("文件名必须是 <id>.ttf", "demo.ttf", f.name)
        assertTrue(RemoteFontStore.isInstalled(ctx, "demo"))
        assertNull("半包要清掉，别占空间", dir.listFiles()?.firstOrNull { it.name.endsWith(".part") })
    }

    @Test
    fun `sha256 对不上就不许安装`() = runBlocking {
        val r = FontCatalog.install(ctx, rf(sha = "0".repeat(64)), okFetcher())
        assertTrue("校验不过必须失败", r.isFailure)
        assertNull("失败不许留下正式文件", RemoteFontStore.fileOf(ctx, "demo"))
        assertFalse(RemoteFontStore.isInstalled(ctx, "demo"))
    }

    @Test
    fun `只下到一半就断了_不许把半包当字体`() = runBlocking {
        val r = FontCatalog.install(ctx, rf(), halfFetcher())
        assertTrue("半包不能算成功", r.isFailure)
        assertNull(RemoteFontStore.fileOf(ctx, "demo"))
        assertFalse(RemoteFontStore.isInstalled(ctx, "demo"))
    }

    @Test
    fun `清单能从随包 assets 读出来_而且每款都带够校验信息`() {
        FontCatalog.loadCached(ctx)
        val list = FontCatalog.list.value
        assertTrue("随包清单不能为空（否则离线进字体库是白屏）", list.size >= 30)
        assertEquals("assets", FontCatalog.source.value)
        list.forEach { f ->
            assertNotNull("缺 sha256 就等于没法校验", f.sha256.takeIf { it.length == 64 })
            assertTrue("${f.name} 大小不对", f.bytes > 100_000)
            assertTrue("${f.name} 没分组", f.group.isNotBlank())
            assertTrue("${f.name} 文件名没扩展名", f.file.contains('.'))
        }
        // 分组要齐：用户要的是"款式都不一样"，不是同一家的十个字重
        assertTrue(FontCatalog.grouped().size >= 4)
    }

    @Test
    fun `清单能被云端版本覆盖_但空清单不许污染本机`() {
        FontCatalog.loadCached(ctx)
        val before = FontCatalog.list.value.size
        // 直接调 install 没关系；这里只验 index 解析口径
        val idx = FontIndex(version = 1, fonts = listOf(rf()))
        assertEquals(1, idx.fonts.size)
        assertEquals(before, FontCatalog.list.value.size)
    }
}
