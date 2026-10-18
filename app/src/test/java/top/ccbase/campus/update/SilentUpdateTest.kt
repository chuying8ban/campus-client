package top.ccbase.campus.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 静默下载（用户 2026-10-19："另外加一个静默下载功能"）。
 *
 * 这里只钉"不会骗人"的几条：默认开、能关、没下好就说没下好、
 * 包被系统清掉后不能还宣称"已就绪"（否则点安装按钮会毫无反应）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SilentUpdateTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun reset() {
        ctx.getSharedPreferences("campus_update", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `默认是开的_关掉之后记得住`() {
        reset()
        assertTrue("默认应该自动下载", SilentUpdate.enabled(ctx))
        SilentUpdate.setEnabled(ctx, false)
        assertFalse(SilentUpdate.enabled(ctx))
        assertFalse("重建后仍然记得", SilentUpdate.enabled(ctx))
    }

    @Test
    fun `没有下好的包时_不宣称已就绪`() {
        reset()
        assertNull(SilentUpdate.readyApk(ctx))
        assertNull(SilentUpdate.readyVersion(ctx))
        assertFalse(SilentUpdate.installReady(ctx))
    }

    @Test
    fun `包被系统删掉后_不能还挂着已就绪`() {
        reset()
        val f = java.io.File(ctx.filesDir, "gone.apk")
        ctx.getSharedPreferences("campus_update", Context.MODE_PRIVATE).edit()
            .putString("ready_path", f.absolutePath)
            .putInt("ready_code", 999)
            .putString("ready_name", "9.9.9")
            .commit()
        assertNull("文件已经不在了，必须返回 null", SilentUpdate.readyApk(ctx))
        assertNull("并且要把就绪标记清掉", SilentUpdate.readyVersion(ctx))
    }
}
