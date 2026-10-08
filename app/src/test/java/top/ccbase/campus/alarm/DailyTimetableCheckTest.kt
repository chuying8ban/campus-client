package top.ccbase.campus.alarm
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DailyTimetableCheckTest {
 @Test fun `八点边界_当日关闭_次日恢复`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>()
  val t=LocalDateTime.of(2026,10,8,8,0)
  assertFalse(DailyTimetableCheck.due(t.minusMinutes(1),null))
  assertTrue(DailyTimetableCheck.due(t,null))
  DailyTimetableCheck.suppressToday(ctx,t)
  assertFalse(DailyTimetableCheck.due(ctx,t.plusHours(12)))
  assertFalse(DailyTimetableCheck.due(ctx,t.plusDays(1).minusMinutes(1)))
  assertTrue(DailyTimetableCheck.due(ctx,t.plusDays(1)))
 }
 @Test fun `升级取消旧闹钟_不排新后台任务`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>()
  val am=ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  val pi=PendingIntent.getBroadcast(ctx,9301,Intent().setClassName(ctx.packageName,"top.ccbase.campus.alarm.DailyTimetableCheckReceiver"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val before=shadowOf(am).scheduledAlarms.size
  am.set(AlarmManager.RTC_WAKEUP,System.currentTimeMillis()+60000,pi)
  DailyTimetableCheck.schedule(ctx)
  assertEquals(before,shadowOf(am).scheduledAlarms.size)
  DailyTimetableCheck.schedule(ctx)
  assertEquals(before,shadowOf(am).scheduledAlarms.size)
 }
}
