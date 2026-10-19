package top.ccbase.campus.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * 本地素材库（2026-10-20 用户：App 主题与桌面小组件各一本、可上传自己的文件）。
 *
 * 钉三件事：
 * 1. 两本库**互不串台**（这是用户明确要求的"要分开"）；
 * 2. 收进来的文件立刻能用（能解析出 spec、能取到文件本体）；
 * 3. 删掉之后列表与选中态都不留空壳。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaterialLibraryTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun pngBytes(): ByteArray {
        val b = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFF3366CC.toInt())
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        b.recycle()
        return out.toByteArray()
    }

    private fun clearAll() {
        MaterialLibrary.Slot.entries.forEach { slot ->
            MaterialLibrary.list(ctx, slot).forEach { MaterialLibrary.delete(ctx, slot, it.id) }
            MaterialLibrary.select(ctx, slot, null)
        }
    }

    @Test
    fun `两本库分开存，互不串台`() {
        clearAll()
        val app = MaterialLibrary.saveBytes(ctx, MaterialLibrary.Slot.APP, pngBytes(), "app图", "image/png")
        val widget = MaterialLibrary.saveBytes(ctx, MaterialLibrary.Slot.WIDGET, pngBytes(), "桌面图", "image/png")
        assertNotNull("App 库没存进去", app)
        assertNotNull("小组件库没存进去", widget)

        val appList = MaterialLibrary.list(ctx, MaterialLibrary.Slot.APP)
        val wList = MaterialLibrary.list(ctx, MaterialLibrary.Slot.WIDGET)
        assertTrue("App 库里应只有自己的那件", appList.any { it.id == app!!.id })
        assertTrue("小组件库不该出现 App 那件", wList.none { it.id == app!!.id })
        assertTrue("App 库不该出现小组件那件", appList.none { it.id == widget!!.id })

        // 选中态也是分开的
        MaterialLibrary.select(ctx, MaterialLibrary.Slot.APP, app!!.id)
        assertNotNull("App 选中丢了", MaterialLibrary.selected(ctx, MaterialLibrary.Slot.APP))
        assertNull("小组件不该被 App 的选中影响", MaterialLibrary.selected(ctx, MaterialLibrary.Slot.WIDGET))
    }

    @Test
    fun `存进来的图片能取到文件和 spec`() {
        clearAll()
        val item = MaterialLibrary.saveBytes(ctx, MaterialLibrary.Slot.WIDGET, pngBytes(), "图", "image/png")
        assertNotNull(item)
        val f = MaterialLibrary.file(ctx, MaterialLibrary.Slot.WIDGET, item!!.id)
        assertNotNull("文件没落盘", f)
        assertTrue("文件是空的", f!!.length() > 0)

        val spec = MaterialLibrary.spec(MaterialLibrary.Slot.WIDGET, item.id)
        assertEquals("spec 应能解回槽位与 id", MaterialLibrary.Slot.WIDGET to item.id, MaterialLibrary.parseSpec(spec))
        // 静态图不是"可播放媒体"，resolve 返回 null 是对的（它走静态渲染那条路）
        assertNull(MaterialLibrary.resolve(ctx, spec))
    }

    @Test
    fun `坏文件不许收进库`() {
        clearAll()
        // 声称是 mp4 其实是随机字节：落盘后 playable() 会否掉，库里不该留
        val junk = ByteArray(2048) { (it % 251).toByte() }
        assertNull(MaterialLibrary.saveBytes(ctx, MaterialLibrary.Slot.APP, junk, "假视频", "video/mp4"))
        assertTrue("坏文件不该进列表", MaterialLibrary.list(ctx, MaterialLibrary.Slot.APP).isEmpty())
    }

    @Test
    fun `删除后列表和选中态都不留空壳`() {
        clearAll()
        val item = MaterialLibrary.saveBytes(ctx, MaterialLibrary.Slot.APP, pngBytes(), "x", "image/png")!!
        MaterialLibrary.select(ctx, MaterialLibrary.Slot.APP, item.id)
        assertTrue(MaterialLibrary.delete(ctx, MaterialLibrary.Slot.APP, item.id))
        assertTrue("列表里还留着", MaterialLibrary.list(ctx, MaterialLibrary.Slot.APP).none { it.id == item.id })
        assertNull("选中态还指着已删的件", MaterialLibrary.selected(ctx, MaterialLibrary.Slot.APP))
        assertNull("文件没删", MaterialLibrary.file(ctx, MaterialLibrary.Slot.APP, item.id))
    }

    @Test
    fun `不带 mat 前缀的 spec 不会被误认`() {
        assertNull(MaterialLibrary.parseSpec("media:gif"))
        assertNull(MaterialLibrary.parseSpec("dynw:aurora:#FF0000"))
        assertNull(MaterialLibrary.parseSpec("mat:nope:abc"))
        assertNull(MaterialLibrary.parseSpec("mat:app"))
    }
}
