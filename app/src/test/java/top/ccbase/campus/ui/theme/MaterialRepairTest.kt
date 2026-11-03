package top.ccbase.campus.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 「每次更新已经下载的素材不要消失」（用户 2026-11-03 原话）。
 *
 * 素材文件本来就在 `filesDir/materials/<槽>/` —— App 升级（`adb install -r`、
 * 应用内更新覆盖安装）不清 `filesDir`，所以**文件**不会丢。真正会"消失"的是
 * 那本 `index.json`：写一半断电、改名失败、或清理工具只删了 json，
 * 列表就空了，用户看到的就是"我下的素材全没了"。
 *
 * 所以这里钉的是自愈能力，而不是"能存"：
 *  1. 索引被删光 → `repair()` 从磁盘文件把它们收回来；
 *  2. 索引被换成坏文件 → 同上（坏文件不当真）；
 *  3. 旧版单槽 `theme_media_*` → 升级后被并进素材库；
 *  4. 空文件 / 截断文件不收（收进来点开就崩，比不显示更糟）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaterialRepairTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun pngBytes(): ByteArray {
        val b = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFF5577FF.toInt())
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        b.recycle()
        return out.toByteArray()
    }

    private fun clearAll() {
        MaterialLibrary.Slot.entries.forEach { slot ->
            MaterialLibrary.list(ctx, slot).forEach { MaterialLibrary.delete(ctx, slot, it.id) }
            MaterialLibrary.select(ctx, slot, null)
            val d = File(ctx.filesDir, "materials/${slot.key}")
            d.listFiles()?.forEach { it.delete() }
        }
    }

    @Test
    fun `索引被删光，素材文件还在时 repair 能把它们收回来`() {
        clearAll()
        val a = MaterialLibrary.saveBytes(ctx, MaterialLibrary.Slot.APP, pngBytes(), "山", "image/png")
        val b = MaterialLibrary.saveBytes(ctx, MaterialLibrary.Slot.APP, pngBytes(), "海", "image/png")
        assertTrue(a != null && b != null)

        // 模拟"索引丢了"：只删 json，不删图
        val idx = File(ctx.filesDir, "materials/${MaterialLibrary.Slot.APP.key}/index.json")
        idx.delete()
        File(ctx.filesDir, "materials/${MaterialLibrary.Slot.APP.key}/index.json.bak").delete()

        MaterialLibrary.repair(ctx)

        val list = MaterialLibrary.list(ctx, MaterialLibrary.Slot.APP)
        assertEquals("索引丢了必须从磁盘文件重建，两件都要回来", 2, list.size)
        assertTrue(list.none { it.name.isBlank() || it.bytes <= 0 })
        assertTrue(MaterialLibrary.file(ctx, MaterialLibrary.Slot.APP, list.first().id)?.isFile == true)
    }

    @Test
    fun `索引被写成坏文件，repair 用备份或磁盘文件盖回来`() {
        clearAll()
        MaterialLibrary.saveBytes(ctx, MaterialLibrary.Slot.APP, pngBytes(), "云", "image/png")
        val dir = File(ctx.filesDir, "materials/${MaterialLibrary.Slot.APP.key}")
        File(dir, "index.json").writeText("{ 这不是 json")

        MaterialLibrary.repair(ctx)

        val list = MaterialLibrary.list(ctx, MaterialLibrary.Slot.APP)
        assertEquals("坏索引不许让列表变空", 1, list.size)
    }

    @Test
    fun `空文件与截断文件不会被收进库`() {
        clearAll()
        MaterialLibrary.saveBytes(ctx, MaterialLibrary.Slot.APP, pngBytes(), "真图", "image/png")
        val dir = File(ctx.filesDir, "materials/${MaterialLibrary.Slot.APP.key}")
        File(dir, "junkempty.png").writeBytes(ByteArray(0))
        File(dir, "junkbroken.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3))
        File(dir, "index.json").delete()

        MaterialLibrary.repair(ctx)

        val names = MaterialLibrary.list(ctx, MaterialLibrary.Slot.APP)
        assertEquals("只应收回那一张真图", 1, names.size)
        assertTrue(names.none { it.id == "junkempty" || it.id == "junkbroken" })
    }

    @Test
    fun `旧版单槽素材升级后被并进素材库，且原文件保留`() {
        clearAll()
        val legacyDir = File(ctx.filesDir, "appearance")
        legacyDir.mkdirs()
        val legacy = File(legacyDir, "theme_media_app.png")
        legacy.writeBytes(pngBytes())
        // 让 adoptLegacy 不因为跑过而跳过
        ctx.getSharedPreferences("campus_materials", Context.MODE_PRIVATE)
            .edit().remove("legacy_adopted").apply()

        MaterialLibrary.repair(ctx)

        val list = MaterialLibrary.list(ctx, MaterialLibrary.Slot.APP)
        assertTrue("旧版下过的素材必须出现在库里", list.any { it.name == "升级前用的素材" })
        assertTrue("原文件不许被搬走（老背景可能还在引用）", legacy.isFile)
        assertFalse(list.isEmpty())
    }
}
