package top.ccbase.campus.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime

/** 固定08:00起、仅前台入口提示。schedule只取消升级前的旧闹钟。 */
object DailyTimetableCheck {
 private fun sp(ctx:Context)=ctx.getSharedPreferences("daily_timetable_check",Context.MODE_PRIVATE)
 fun due(now:LocalDateTime,suppressed:String?):Boolean = now.hour>=8 && suppressed!=now.toLocalDate().toString()
 fun due(ctx:Context,now:LocalDateTime=LocalDateTime.now())=due(now,sp(ctx).getString("suppressed_day",null))
 fun suppressToday(ctx:Context,now:LocalDateTime=LocalDateTime.now()) {sp(ctx).edit().putString("suppressed_day",now.toLocalDate().toString()).apply()}

 /** 最近一次真的把课表导进本机的时刻（ISO 串）。横幅靠它显示「今天 08:12 已更新」。 */
 fun updatedAt(ctx:Context):String? = sp(ctx).getString("updated_at",null)

 /** 导入成功后调用：横幅立刻从「还没核对」变成「已更新」，不用等下一次进前台。 */
 fun markUpdated(ctx:Context,now:LocalDateTime=LocalDateTime.now()) {
  sp(ctx).edit().putString("updated_at",now.withNano(0).toString()).apply()
 }

 /** 今天是否已经更新过课表（按本机日期比，不跨天沿用昨天那条）。 */
 fun updatedToday(ctx:Context,now:LocalDateTime=LocalDateTime.now()):Boolean =
  updatedAt(ctx)?.startsWith(now.toLocalDate().toString()) == true

 fun schedule(ctx:Context) {
  val old=PendingIntent.getBroadcast(ctx,9301,Intent().setClassName(ctx.packageName,"top.ccbase.campus.alarm.DailyTimetableCheckReceiver"),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
  if(old!=null) { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(old);old.cancel() }
  sp(ctx).edit().remove("enabled").remove("time").apply()
 }
}
