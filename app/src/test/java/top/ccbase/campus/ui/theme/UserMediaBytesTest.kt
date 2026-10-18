package top.ccbase.campus.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `换新的会清掉旧的_只留一份`() {
        UserMedia.clear(ctx)
        UserMedia.saveBytes(ctx, mp4Bytes())
        UserMedia.saveBytes(ctx, mp4Bytes())
        val dir = java.io.File(ctx.filesDir, "appearance")
        val files = dir.listFiles()?.filter { it.name.startsWith("theme_media.") } ?: emptyList()
        assertEquals("旧文件没清掉，会越攒越大", 1, files.size)
    }
}
