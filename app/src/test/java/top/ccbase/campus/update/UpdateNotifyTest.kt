package top.ccbase.campus.update

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * 「有新版本」通知栏提醒。
 *
 * 用户的原话是「若有更新用户进入 app 会收到更新通知」——
 * 所以这里要验的不是「函数返回 true」，而是**通知栏里真有那条通知、文案点得开**。
 * 假绿最典型的形态就是：逻辑判断都对，但 notify() 根本没被调用（比如权限没开时被系统丢掉了）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateNotifyTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private fun manifest(code: Int = 19, name: String = "1.39-T1") = UpdateManifest(
        versionCode = code,
        versionName = name,
        url = "/updates/campus-$name.apk",
        sha256 = "0".repeat(64),
        size = 7_337_580,
        notes = listOf("测试用"),
    )

    @Test
    fun `发出去的通知栏里真有这条_而且写明版本`() {
        val c = ctx()
        assertTrue(UpdateNotify.post(c, manifest(), "1.38-T1"))

        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(nm).allNotifications
        assertTrue("通知没进通知栏", posted.isNotEmpty())
        val text = posted.joinToString(" ") { it.extras.getString("android.title").orEmpty() + " " + it.extras.getString("android.text").orEmpty() }
        assertTrue("标题里没写版本号：$text", text.contains("1.39-T1"))
        assertTrue("没说清从哪个版本升上去：$text", text.contains("1.38-T1"))
    }

    @Test
    fun `同一个版本重复发只会覆盖同一条_不会堆一排`() {
        val c = ctx()
        UpdateNotify.post(c, manifest(), "1.38-T1")
        UpdateNotify.post(c, manifest(), "1.38-T1")
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals("通知 id 没固定死，通知栏被堆了一排", 1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun `没给通知权限时要如实返回 false_别假装提醒过了`() {
        // 关键：返回 true 会让调用方记下「已提醒」，于是这条新版本永远不再提醒 —— 静默丢掉一次升级机会
        val c = ctx()
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(nm).setNotificationsEnabled(false)

        assertFalse(UpdateNotify.post(c, manifest(), "1.38-T1"))
    }

    @Test
    fun `渠道建好了才能发_不然 Android 8 以上静默丢弃`() {
        val c = ctx()
        UpdateNotify.post(c, manifest(), "1.38-T1")
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull("没有创建通知渠道", nm.getNotificationChannel(UpdateNotify.CHANNEL))
    }
}
