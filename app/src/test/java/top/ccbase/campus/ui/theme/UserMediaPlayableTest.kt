package top.ccbase.campus.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 截断的动图不许被当成主题用（2026-10-19 用户："主题选了一个动图后就这样了"，整屏黑）。
 * 这里守的是最基础的一条：坏文件必须被判死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserMediaPlayableTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `空文件与垃圾字节都算不可用`() {
        val empty = File(ctx.cacheDir, "empty.mp4").apply { writeBytes(ByteArray(0)) }
        assertFalse("0 字节文件不能算能播", UserMedia.playable(empty))

        val junk = File(ctx.cacheDir, "junk.mp4").apply { writeBytes(ByteArray(4096) { 0x7F }) }
        assertFalse("随机字节不能算能播", UserMedia.playable(junk))
    }

    @Test
    fun `不存在的文件算不可用`() {
        assertFalse("不存在的文件不能算能播", UserMedia.playable(File(ctx.cacheDir, "nope.gif")))
    }
}
