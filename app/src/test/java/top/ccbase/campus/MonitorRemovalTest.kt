package top.ccbase.campus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.alarm.RetiredMonitorCleanup
import top.ccbase.campus.ui.CampusTab
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitorRemovalTest {
    @Test fun `监控入口接口和接收器已移除`() {
        assertFalse(CampusTab.entries.any { it.label == "监控" })
        val api = File("src/main/java/top/ccbase/campus/net/CampusApi.kt").readText()
        assertFalse(api.contains("/api/v2/grab/"))
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("GrabWatchReceiver"))
        assertFalse(File("src/main/java/top/ccbase/campus/ui/grab/GrabScreen.kt").exists())
    }
    @Test fun `升级清理关闭旧开关并取消旧闹钟`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("grab_watch", Context.MODE_PRIVATE).edit().putBoolean("on", true).commit()
        val intent = Intent("top.ccbase.campus.GRAB_WATCH").setClassName(ctx.packageName, "top.ccbase.campus.alarm.GrabWatchReceiver")
        val old = PendingIntent.getBroadcast(ctx, 9101, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC_WAKEUP, 9999999999999L, old)
        RetiredMonitorCleanup.cancel(ctx)
        assertFalse(ctx.getSharedPreferences("grab_watch", Context.MODE_PRIVATE).getBoolean("on", true))
        assertNull(PendingIntent.getBroadcast(ctx, 9101, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
    }
}
