package top.ccbase.campus.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
import top.ccbase.campus.ui.theme.AppFont
import top.ccbase.campus.ui.theme.FontOpt

/**
 * 下载字体的本地仓库 + 选中项的回落规则。
 *
 * 这些用例守的是用户最在意的那句：
 * 「只要下载一次用户就可以一直直接使用这个字体，哪怕更新新版本已下的字体也不能丢失」。
 * ——所以「已下载」的判据只能是**磁盘上的文件**，不能是 prefs/数据库里的一行记录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteFontStoreTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(ctx.filesDir, RemoteFontStore.DIR)

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
        RemoteFontStore.resetForTest()
        prefs().edit().clear().apply()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        prefs().edit().clear().apply()
        RemoteFontStore.resetForTest()
    }

    private fun prefs() = ctx.getSharedPreferences("campus_appearance", Context.MODE_PRIVATE)

    private fun fake(id: String, bytes: Int = 64): File =
        File(dir, "$id.ttf").apply { writeBytes(ByteArray(bytes) { 0x41 }) }

    @Test
    fun `扫盘就能认出已下载的字体_不依赖任何记录`() {
        fake("alibaba")
        fake("lxgw_wenkai")
        RemoteFontStore.refresh(ctx)
        assertEquals(setOf("alibaba", "lxgw_wenkai"), RemoteFontStore.installed.value)
        assertTrue(RemoteFontStore.isInstalled(ctx, "alibaba"))
        assertFalse(RemoteFontStore.isInstalled(ctx, "smiley"))
    }

    @Test
    fun `删除只删那一款_别的还在`() {
        fake("alibaba")
        fake("oppo_sans")
        RemoteFontStore.refresh(ctx)
        assertTrue(RemoteFontStore.delete(ctx, "alibaba"))
        assertFalse(File(dir, "alibaba.ttf").exists())
        assertEquals(setOf("oppo_sans"), RemoteFontStore.installed.value)
    }

    @Test
    fun `pref 里写着 file 但那款文件不在时_静默回落到系统字体`() {
        // 换机/清数据之后会是这样：pref 还在，文件没了。界面必须能用，不能白屏或崩
        val face = AppFont.faceOf(ctx, "file:alibaba")
        assertEquals(FontOpt.SANS, face.opt)
        assertNull(face.remoteId)
        assertEquals("sys:sans", face.key)
    }

    @Test
    fun `老版本的裸 id pref 仍然认`() {
        assertEquals(FontOpt.KUAILE, AppFont.faceOf(ctx, "kuaile").opt)
        assertEquals(FontOpt.SANS, AppFont.faceOf(ctx, null).opt)
        assertEquals(FontOpt.MONO, AppFont.faceOf(ctx, "sys:mono").opt)
    }

    @Test
    fun `选一款已下载的字体_会写进 app_font 并且立刻生效`() {
        fake("alibaba")
        RemoteFontStore.refresh(ctx)
        val ok = AppFont.setRemote(ctx, "alibaba", "阿里普惠体")
        assertTrue("文件在就该成功", ok)
        assertEquals("file:alibaba", prefs().getString("app_font", null))
        assertEquals("file:alibaba", AppFont.state.value.key)
        assertEquals("阿里普惠体", AppFont.state.value.label)
    }

    @Test
    fun `文件不在时不许假装选中`() {
        assertFalse("没有文件必须返回 false，界面据此提示", AppFont.setRemote(ctx, "nope", "不存在"))
        assertEquals(FontOpt.SANS, AppFont.state.value.opt)
    }

    @Test
    fun `删掉正在用的字体之后要能切回系统字体`() {
        fake("alibaba")
        RemoteFontStore.refresh(ctx)
        AppFont.setRemote(ctx, "alibaba", "阿里普惠体")
        assertTrue(RemoteFontStore.delete(ctx, "alibaba"))
        AppFont.resetToSystem(ctx)
        assertEquals("sys:sans", prefs().getString("app_font", null))
        assertEquals(FontOpt.SANS, AppFont.state.value.opt)
    }

    @Test
    fun `清单里没有的字体文件要被认成孤儿_别让界面显示一个查不到名字的款`() {
        fake("mystery")
        RemoteFontStore.refresh(ctx)
        val orphans = RemoteFontStore.orphans(ctx, setOf("alibaba"))
        assertEquals(listOf("mystery"), orphans)
        assertNotNull(RemoteFontStore.fileOf(ctx, "mystery"))
    }
}
