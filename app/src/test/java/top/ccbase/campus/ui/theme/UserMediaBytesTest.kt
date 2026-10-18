package top.ccbase.campus.ui.theme

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

/**
 * 图库里的「动态」内容落地（2026-10-18 用户：图库要和静态合并、动态要标注）。
 *
 * 服务端把 safebooru 的 mp4/webm 混进搜索结果并打 `animated: true`；App 这边点下去
 * **必须是存原始字节**（解码成 Bitmap 就只剩一帧，用户会以为"动态没做成"）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserMediaBytesTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun mp4Bytes(): ByteArray {
        // 前 12 字节：4 字节长度 + "ftyp" + 品牌 —— 和真 mp4 的头一致
        val head = byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte())
        return head + ByteArray(2048)
    }

    @Test
    fun `mp4 字节能直接落盘_并且被认成视频`() {
        UserMedia.clear(ctx)
        val kind = UserMedia.saveBytes(ctx, mp4Bytes())
        assertNotNull("mp4 没被认出来", kind)
        assertEquals(UserMedia.Kind.VIDEO, kind)
        val cur = UserMedia.current(ctx)
        assertNotNull(cur)
        assertEquals(UserMedia.Kind.VIDEO, cur!!.kind)
    }

    @Test
    fun `不是视频的字节一律拒绝_别把一张图存成主题`() {
        UserMedia.clear(ctx)
        assertNull(UserMedia.saveBytes(ctx, ByteArray(64) { 0x42 }))
        assertNull("空字节", UserMedia.saveBytes(ctx, ByteArray(0)))
    }

    /** 2026-10-18 用户："小部件的视频变成主题的了" —— 两套媒体必须互不覆盖 */
    @Test
    fun `App 和小组件各存各的_互不覆盖`() {
        UserMedia.clear(ctx)
        val a = ByteArray(4096) { 1 }
        val b = ByteArray(4096) { 2 }
        a[4] = 'f'.code.toByte(); a[5] = 't'.code.toByte(); a[6] = 'y'.code.toByte(); a[7] = 'p'.code.toByte()
        b[4] = 'f'.code.toByte(); b[5] = 't'.code.toByte(); b[6] = 'y'.code.toByte(); b[7] = 'p'.code.toByte()
        assertNotNull(UserMedia.saveBytes(ctx, a, UserMedia.Slot.APP))
        assertNotNull(UserMedia.saveBytes(ctx, b, UserMedia.Slot.WIDGET))
        val app = UserMedia.current(ctx, UserMedia.Slot.APP)
        val wid = UserMedia.current(ctx, UserMedia.Slot.WIDGET)
        assertNotNull(app); assertNotNull(wid)
        assertTrue("两个槽指到了同一个文件", app!!.file.absolutePath != wid!!.file.absolutePath)
        // 只清 App 的，小组件那份要留着
        UserMedia.clear(ctx, UserMedia.Slot.APP)
        assertNull(UserMedia.current(ctx, UserMedia.Slot.APP))
        assertNotNull(UserMedia.current(ctx, UserMedia.Slot.WIDGET))
    }

    @Test
    fun `换新的会清掉旧的_只留一份`() {
        UserMedia.clear(ctx)
        UserMedia.saveBytes(ctx, mp4Bytes())
        UserMedia.saveBytes(ctx, mp4Bytes())
        val dir = java.io.File(ctx.filesDir, "appearance")
        val files = dir.listFiles()?.filter { it.name.startsWith("theme_media") } ?: emptyList()
        assertEquals("旧文件没清掉，会越攒越大", 1, files.size)
    }
}
