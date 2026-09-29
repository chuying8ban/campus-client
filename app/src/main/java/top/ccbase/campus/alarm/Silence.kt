package top.ccbase.campus.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * ★ 整个需求的核心，就这几行 ★
 *
 * 网页 / PWA / TWA 做不到「自动静音」，不是因为技术不够聪明，
 * 而是因为浏览器沙箱**故意**不给任何能碰系统设置的 API。
 * 只有装进系统的 App（声明了 WRITE_SETTINGS 且用户手动授权）才允许。
 */
object Silence {

    /** 用户有没有在「系统设置 → 修改系统设置」里给这个 App 开权限 */
    fun canWrite(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    /** 精确闹钟权限（Android 12+ 要单独给，14 起默认拒绝） */
    fun canExactAlarm(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** 当前铃声模式，给界面显示用 */
    fun ringerName(ctx: Context): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "静音"
            AudioManager.RINGER_MODE_VIBRATE -> "震动"
            else -> "正常"
        }
    }

    /** 真正改铃声。返回 false = 没权限，得让用户去设置里开 */
    fun silence(ctx: Context): Boolean {
        if (!canWrite(ctx)) return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = AudioManager.RINGER_MODE_SILENT
        return true
    }

    fun unsilence(ctx: Context): Boolean {
        if (!canWrite(ctx)) return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        return true
    }
}

/**
 * 发通知。原生发通知**不需要经过任何服务器** —— 手机自己就能弹。
 *
 * 这也是"提醒要从 App 里来、别人也能用"的落点：一条本地通知不需要谁的机器人账号，
 * 谁装上这个 App、谁就能收到自己的课前提醒。
 *
 * 两条通道分开（用户可以单独静音其中一类）：
 *  * remind  —— 课前提醒（上课前 N 分钟，只念课名/节次/教室/时间）
 *  * silence —— 自动静音的执行结果（已静音 / 已恢复铃声 / 静音失败）
 */
object Notify {

    private const val CH = "silence"
    private const val CH_REMIND = "remind"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "课前静音", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "到点提醒并静音"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "课前提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "上课前弹一条通知（本机发出，不经服务器）"
            }
        )
    }

    /** 通知权限有没有给（Android 13+ 要用户点过"允许"）。界面用它决定要不要提示去授权。 */
    fun allowed(ctx: Context): Boolean =
        runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(false)

    fun post(ctx: Context, title: String, body: String) =
        postOn(ctx, CH, android.R.drawable.ic_lock_silent_mode, title, body)

    /** 课前提醒那条（用另一条通道，图标也不同，锁屏上一眼能分清是"上课提醒"还是"静音结果"） */
    fun postRemind(ctx: Context, title: String, body: String) =
        postOn(ctx, CH_REMIND, android.R.drawable.ic_popup_reminder, title, body)

    private fun postOn(ctx: Context, channel: String, icon: Int, title: String, body: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(System.currentTimeMillis().toInt(), n)
        } catch (e: SecurityException) {
            // Android 13+ 没给通知权限，忽略（不影响静音本身）
        }
    }
}
