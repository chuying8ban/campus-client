package top.ccbase.campus.alarm
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DailyTimetableCheckTest {
 @Test fun `每日开关与开机续排独立于课前提醒`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>()
  val am=ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  DailyTimetableCheck.configure(ctx,false,"20:00")
  val before=shadowOf(am).scheduledAlarms.size
  DailyTimetableCheck.configure(ctx,true,"20:00")
  assertEquals(before+1,shadowOf(am).scheduledAlarms.size)
  DailyTimetableCheck.schedule(ctx)
  assertEquals(before+1,shadowOf(am).scheduledAlarms.size)
  BootReceiver().onReceive(ctx,Intent(Intent.ACTION_TIMEZONE_CHANGED))
  assertEquals(before+1,shadowOf(am).scheduledAlarms.size)
  DailyTimetableCheck.configure(ctx,false,"20:00")
  assertEquals(before,shadowOf(am).scheduledAlarms.size)
 }
}
