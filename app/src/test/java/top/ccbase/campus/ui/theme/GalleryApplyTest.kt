package top.ccbase.campus.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「点图用它，图就该自动进本地素材库」—— 2026-10-21 用户口径：
 * 「我希望用户下载使用的主题都能够自动下载到本地主题库，而不是需要用户手动点击下载到主题库。」
 *
 * 这里钉三件事：
 *  ① 套用过的那张**一定**在库里（不然又要用户记得手动点"存到本地"）；
 *  ② 库里那条被标成"正在用"，外观页/素材库列表才能对上；
 *  ③ 背景 spec 指向库里那一条（`mat:app:<id>`），不是另外再存一份 ——
 *     否则同一个动图存两次（10MB × 2），而且素材库说"没在用"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryApplyTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun pngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.rgb(90, 60, 140))
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        MaterialLibrary.list(ctx, MaterialLibrary.Slot.APP).forEach {
            MaterialLibrary.delete(ctx, MaterialLibrary.Slot.APP, it.id)
        }
        MaterialLibrary.list(ctx, MaterialLibrary.Slot.WIDGET).forEach {
            MaterialLibrary.delete(ctx, MaterialLibrary.Slot.WIDGET, it.id)
        }
        AppearanceStore.setBackground(ctx, BG_PRESET_PREFIX + "night")
    }

    @After
    fun tearDown() = setUp()

    @Test
    fun `选一张图当 App 背景_自动进本地素材库并标成正在用`() {
        val r = GalleryApply.apply(ctx, "background", "晚霞", pngBytes(), "image/png")
        assertTrue("应该成功：${r.error}", r.ok)
        val item = r.item
        assertNotNull(item)

        val lib = MaterialLibrary.list(ctx, MaterialLibrary.Slot.APP)
        assertEquals("库里必须有这一件，不用用户再点一次", 1, lib.size)
        assertEquals(item!!.id, lib[0].id)

        val sel = MaterialLibrary.selected(ctx, MaterialLibrary.Slot.APP)
        assertEquals("要标成正在用", item.id, sel?.id)

        assertEquals(
            "背景要指向库里那一条（不然素材库和孩子对不上）",
            "mat:app:${item.id}",
            AppearanceStore.background.value,
        )
    }

    @Test
    fun `桌面的素材进的是桌面那一格_不会串到 App 背景`() {
        val r = GalleryApply.apply(ctx, "widget", "动图", pngBytes(), "image/png")
        // 桌面要抽帧，Robolectric 里抽不出来是预期内的：只要"入库+归档"这两步对了就算过
        assertEquals(1, MaterialLibrary.list(ctx, MaterialLibrary.Slot.WIDGET).size)
        assertEquals(0, MaterialLibrary.list(ctx, MaterialLibrary.Slot.APP).size)
        assertNotNull(r.item)
        assertTrue(
            "桌面那条被标成正在用",
            MaterialLibrary.selected(ctx, MaterialLibrary.Slot.WIDGET)?.id == r.item!!.id,
        )
    }

    @Test
    fun `空字节不许入库_也不许换掉现在的背景`() {
        val before = AppearanceStore.background.value
        val r = GalleryApply.apply(ctx, "background", "坏图", ByteArray(0), "image/jpeg")
        assertTrue("没有任何字节就该失败", !r.ok)
        assertTrue(MaterialLibrary.list(ctx, MaterialLibrary.Slot.APP).isEmpty())
        assertEquals("背景不能被悄悄换掉", before, AppearanceStore.background.value)
        assertNotNull(r.error)
    }

    @Test
    fun `换第二张之后_库里有两件但只有一件在用`() {
        val a = GalleryApply.apply(ctx, "background", "第一张", pngBytes(), "image/png")
        val b = GalleryApply.apply(ctx, "background", "第二张", pngBytes(), "image/png")
        assertEquals(2, MaterialLibrary.list(ctx, MaterialLibrary.Slot.APP).size)
        assertEquals("最后用的那张才算正在用", b.item!!.id, MaterialLibrary.selected(ctx, MaterialLibrary.Slot.APP)?.id)
        assertEquals("mat:app:${b.item.id}", AppearanceStore.background.value)
        assertTrue("两张都要留在库里", a.item != null && b.item != null)
    }
}
