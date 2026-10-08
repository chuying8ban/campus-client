package top.ccbase.campus.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** 仅用于升级后取消旧闹钟；无请求、轮询或通知能力。 */
object RetiredMonitorCleanup {
    fun cancel(ctx: Context) {
        ctx.getSharedPreferences("grab_watch", Context.MODE_PRIVATE).edit().putBoolean("on", false).apply()
        val intent = Intent("top.ccbase.campus.GRAB_WATCH")
            .setClassName(ctx.packageName, "top.ccbase.campus.alarm.GrabWatchReceiver")
        val old = PendingIntent.getBroadcast(ctx, 9101, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (old != null) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(old)
            old.cancel()
        }
    }
}
