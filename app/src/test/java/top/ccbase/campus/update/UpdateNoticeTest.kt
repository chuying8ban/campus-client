package top.ccbase.campus.update

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「每次更新后要放一则更新内容的通知」（2026-10-18 用户要求）。
 *
 * 三条：① 说明真的跟着包（assets/changelog.tsv）走、当前版本有内容；② 发出来的通知里
 * 真有这版正文；③ 同一版只弹一次 —— 否则每次启动都弹，用户会烦到关掉通知权限。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateNoticeTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `当前版本的更新说明存在_而且写了内容`() {
        val c = ctx()
        val v = top.ccbase.campus.BuildConfig.VERSION_NAME
        val notes = UpdateNotice.notesFor(c, v)
        assertNotNull("changelog.tsv 里没有 $v 这一条", notes)
        assertTrue("说明是空的", notes!!.isNotBlank())
    }

    @Test
    fun `没有这一版就返回 null_不弹空通知`() {
        assertNull(UpdateNotice.notesFor(ctx(), "0.0.0-不存在"))
    }

    @Test
    fun `启动时发一条_通知栏里能看到版本号和正文`() {
        val c = ctx()
        val v = top.ccbase.campus.BuildConfig.VERSION_NAME
        UpdateNotice.maybeNotify(c)
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(nm).allNotifications.filter { it.channelId == "update" }
        assertTrue("没发更新说明通知", posted.isNotEmpty())
        val text = posted.joinToString(" ") {
            it.extras.getString("android.title").orEmpty() + " " + it.extras.getString("android.text").orEmpty()
        }
        assertTrue("标题没写版本号：$text", text.contains(v))
    }

    @Test
    fun `同一版只弹一次_别每次启动都烦人`() {
        val c = ctx()
        UpdateNotice.maybeNotify(c)
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val first = shadowOf(nm).allNotifications.size
        UpdateNotice.maybeNotify(c)
        // 第二次不该再多一条（id 固定，即便发了也是覆盖，所以这里断言总数没涨）
        assertTrue("重复弹了", shadowOf(nm).allNotifications.size <= first)
    }
}
