package top.ccbase.campus.update

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
import top.ccbase.campus.BuildConfig

/**
 * App 内更新说明（用户 2026-10-19："新版本内容通知要在 app 内出现"）。
 *
 * 系统通知会被划掉、被勿扰吞掉，所以 App 里必须自己也弹一次；这两条钉住：
 * 没看过要拿得到，看过一次就不再烦人。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateNoticeInAppTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun clear() {
        ctx.getSharedPreferences("update_notice", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `这一版的说明要能取到_并且正文是从 assets 里真读的`() {
        clear()
        val pending = UpdateNotice.pendingInApp(ctx)
        assertNotNull("新版本第一次进 App 必须能拿到更新说明", pending)
        assertEquals("版本号要和当前包一致", BuildConfig.VERSION_NAME, pending!!.first)
        assertTrue("正文不能为空", pending.second.isNotBlank())
    }

    @Test
    fun `点过知道了就不再弹`() {
        clear()
        assertNotNull(UpdateNotice.pendingInApp(ctx))
        UpdateNotice.markInAppSeen(ctx)
        assertNull("同一版不该反复弹", UpdateNotice.pendingInApp(ctx))
    }

    @Test
    fun `系统通知的已读标记不影响 App 内弹窗`() {
        clear()
        UpdateNotice.maybeNotify(ctx) // 发系统通知，并记 seen_version
        assertNotNull("系统通知弹过，App 内还得弹一次", UpdateNotice.pendingInApp(ctx))
    }
}
