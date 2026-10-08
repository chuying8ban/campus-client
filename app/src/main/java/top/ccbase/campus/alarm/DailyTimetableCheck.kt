package top.ccbase.campus.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import java.time.*

/** 每日核对提醒，不联网，不访问教务，不改变静音状态。 */
object DailyTimetableCheck {
 const val ID=9301
 private fun sp(ctx:Context)=ctx.getSharedPreferences("daily_timetable_check",Context.MODE_PRIVATE)
 fun enabled(ctx:Context)=sp(ctx).getBoolean("enabled",false)
 fun time(ctx:Context)=sp(ctx).getString("time","20:00")!!
 fun configure(ctx:Context,on:Boolean,time:String) {
  LocalTime.parse(time)
  sp(ctx).edit().putBoolean("enabled",on).putString("time",time).apply()
  schedule(ctx)
 }
 fun next(now:LocalDateTime,time:String):LocalDateTime {
  val today=now.toLocalDate().atTime(LocalTime.parse(time))
  return if(today.isAfter(now)) today else today.plusDays(1)
 }
 fun schedule(ctx:Context) {
  val am=ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  val pi=PendingIntent.getBroadcast(ctx,ID,Intent(ctx,DailyTimetableCheckReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  am.cancel(pi)
  if(!enabled(ctx)) return
  val ms=next(LocalDateTime.now(),time(ctx)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
  am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,ms,pi)
 }
}
class DailyTimetableCheckReceiver:BroadcastReceiver() {
 override fun onReceive(ctx:Context,intent:Intent) {
  if(!DailyTimetableCheck.enabled(ctx)) return
  Notify.post(ctx,"记得核对教务课表","请查看学校官方课表是否调课；有变化时重新导入或手动修改。这是核对提醒，不是自动更新结果。")
  DiagLog.append(ctx,Attempt(DiagLog.now(),"每日课表核对",Notify.allowed(ctx),if(Notify.allowed(ctx)) "" else "通知权限未开启"))
  DailyTimetableCheck.schedule(ctx)
 }
}
