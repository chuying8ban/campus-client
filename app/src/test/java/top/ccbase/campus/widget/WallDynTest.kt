package top.ccbase.campus.widget

import android.content.Context
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

/**
 * 壁纸级动态主题（换源）：spec 解析 + 没下载时不许编造画面。
 *
 * 关键契约：**图不在本地就返回 null**。这一条防的是"为了好看而在 provider 里同步联网" ——
 * 那会让广播主线程卡住（上一版 App 卡顿的来源之一）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallDynTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `spec 往返`() {
        WallDyn.items.forEach { item ->
            val spec = WallDyn.specOf(item.id)
            val back = WallDyn.parse(spec)
            assertNotNull("解析不出 $spec", back)
            assertEquals(item.id, back!!.id)
            assertTrue(WidgetFrames.isDynamic(spec))
        }
    }

    @Test
    fun `不是壁纸规格就解析成 null`() {
        assertNull(WallDyn.parse("img_aurora"))
        assertNull(WallDyn.parse("dynwall:"))
        assertNull(WallDyn.parse("dynw:aurora:ff00ff"))
    }

    @Test
    fun `图没下载时不出帧`() {
        val id = WallDyn.items.first().id
        WallDyn.file(ctx, id).delete()
        assertNull(
            "本地没图必须返回 null，交给 UI 去下载",
            WidgetFrames.forSpec(ctx, WallDyn.specOf(id), 900, 400),
        )
    }

    @Test
    fun `壁纸主题至少要有一半是明亮题材`() {
        // 用户的抱怨是"太阴间"，题材选择本身就是这个需求的实现 —— 用断言钉住方向
        val bright = setOf("sky", "sakura", "sea", "flower", "forest", "snow")
        assertTrue(WallDyn.items.map { it.id }.intersect(bright).size >= 5)
    }
}
