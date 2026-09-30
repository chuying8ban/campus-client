package top.ccbase.campus.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「外观」的存取与背景图文件生命周期（真在 Robolectric 里跑，不是纯函数推演）。
 *
 * 这几条对应的都是用户能直接撞上的行为：
 *  · 换主题/换背景之后**重启 App 还在**（存在 SharedPreferences 里，不是内存态）；
 *  · 换回"默认（纯色）"时**本机那张图要删掉**（不然用户以为删了、文件还占着几 MB）；
 *  · 选图 → 降采样 → 存盘 → 读回，这条链要真的通（相机原图 12MP 直接解码会 OOM）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearanceStoreTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    /**
     * 每个用例收尾时把主 looper 排空 —— **这一步不是装饰，是本类必须做的卫生**。
     *
     * 本类会写 `SharedPreferences`，而 `apply()` 是**异步**的：Robolectric 下它会留一条待办
     * 在主 looper 上。同一个 JVM fork 里**后面**那些 Espresso 测试（`ui.today.*`）会因为主 looper
     * 不空闲而全部空转超时 —— 2026-09-30 实见：本类**单跑全绿**、而全量跑时 11 条
     * `AppNotIdleException`；把本类移出后全量 `773/0F` 全绿，加回并排空后也是全绿。
     * 换句话说：**触发源是这个测试留下的待办，不是被测代码、也不是既有断言**。
     */
    @After
    fun drainMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `主题档存下来能读回_默认是深色`() {
        val c = ctx()
        assertEquals("默认必须是深色（和加浅色主题之前一致）", AppearanceStore.DARK, AppearanceStore.DARK)
        assertEquals("默认必须是深色", AppearanceStore.DARK, AppearanceStore.read(c))

        AppearanceStore.setTheme(c, AppearanceStore.LIGHT)
        assertEquals(AppearanceStore.LIGHT, AppearanceStore.read(c))
        assertEquals("快照状态要跟着变（界面靠它重组合）", AppearanceStore.LIGHT, AppearanceStore.theme.value)

        AppearanceStore.setTheme(c, AppearanceStore.SYSTEM)
        assertEquals(AppearanceStore.SYSTEM, AppearanceStore.read(c))
        // 三档的中文名写在一处，两个页面不该各写一份
        assertEquals("跟随系统", AppearanceStore.label(AppearanceStore.SYSTEM))
        assertEquals("浅色", AppearanceStore.label(AppearanceStore.LIGHT))
        assertEquals("深色", AppearanceStore.label(AppearanceStore.DARK))
    }

    @Test
    fun `背景三层存下来能读回_切回纯色要把本机那张图删掉`() {
        val c = ctx()
        // 造一张"已保存的背景图"，再看切换时它会不会被清掉
        val f = BackgroundPhoto.file(c)
        f.parentFile?.mkdirs()
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue("前置：文件得先存在", f.exists())

        AppearanceStore.setBackground(c, BG_PRESET_PREFIX + "ocean")
        assertEquals(BG_PRESET_PREFIX + "ocean", AppearanceStore.backgroundOf(c))
        assertFalse("换成预设之后，本机那张图没有意义了，应当删掉", f.exists())

        AppearanceStore.setBackground(c, AppearanceStore.BG_PHOTO + ":123")
        assertTrue(AppearanceStore.backgroundOf(c).startsWith(AppearanceStore.BG_PHOTO))

        AppearanceStore.setBackground(c, AppearanceStore.BG_SOLID)
        assertEquals(AppearanceStore.BG_SOLID, AppearanceStore.backgroundOf(c))
        assertEquals(AppearanceStore.BG_SOLID, AppearanceStore.background.value)
    }

    @Test
    fun `预设都是程序生成的渐变_不依赖任何图片文件`() {
        assertTrue("预设要有 4~6 个（少了像凑数，多了没人挑）", BgPresets.size in 4..6)
        assertTrue("id 不能重复（重复会让「选中哪个」分不清）", BgPresets.map { it.id }.toSet().size == BgPresets.size)
        assertTrue("每个预设都要有中文名", BgPresets.all { it.name.isNotBlank() })
        assertTrue("每个预设至少两个色标，否则不成渐变", BgPresets.all { it.colors.size >= 2 })
        assertTrue("预设颜色必须不透明（半透明叠在图上会串色）", BgPresets.all { p -> p.colors.all { it.alpha > 0.99f } })
        assertNotNull("按 id 要能查回来", presetById("ocean"))
        assertEquals("查不到的 id 要返回 null，别抛", null, presetById("不存在"))
    }

    @Test
    fun `选图_降采样_存盘_读回_这条链要通`() {
        val c = ctx()
        // 造一张 2000x1200 的"手机原图"（比长边上限 1440 大，能验证降采样真的发生）
        val src = Bitmap.createBitmap(2000, 1200, Bitmap.Config.ARGB_8888)
        src.eraseColor(android.graphics.Color.rgb(30, 60, 120))
        val tmp = File(c.cacheDir, "fake_photo.png")
        FileOutputStream(tmp).use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }
        src.recycle()

        val stamp = BackgroundPhoto.save(c, Uri.fromFile(tmp))
        assertNotNull("保存应当成功", stamp)

        val saved = BackgroundPhoto.file(c)
        assertTrue("图要落在 filesDir 下（不是 cacheDir —— 那个系统随时会清）",
            saved.absolutePath.contains(c.filesDir.absolutePath))
        assertTrue("存下来的文件不能是空的", saved.length() > 0)

        val back = BackgroundPhoto.load(c)
        assertNotNull("读回必须拿到位图", back)
        val longSide = maxOf(back!!.width, back.height)
        assertTrue("长边要降到 1440 以内，实测 $longSide", longSide <= 1440)
        back.recycle()

        BackgroundPhoto.clear(c)
        assertFalse("清掉之后文件不该还在", saved.exists())
        assertEquals("清掉之后读回应当是 null（不是崩）", null, BackgroundPhoto.load(c))
    }
}
