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

    private const val P = "silence_mark"
    private const val K_OURS = "ours"        // 现在这个静音是不是我们按的
    private const val K_PREV = "prev"        // 按之前用户自己的模式（静音/震动/正常）
    private const val K_UNTIL = "until"      // 这一段什么时候结束（epoch ms）
    private const val K_START = "start"      // 这一段什么时候按下去的（没结束时间时用它兜底）

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    /**
     * 真正改铃声。返回 false = 没权限，得让用户去设置里开。
     *
     * @param untilMs 这一段静音的结束时刻。恢复由闹钟负责，这个值只用于**兜底自愈**：
     *   App 下次启动/收到任何闹钟时，如果发现"我们在静音、而且已经过了结束时间"，
     *   就顺手把铃声还回去。用户 2026-10-18 要求"课和自习结束后自动取消静音"——
     *   只靠一个闹钟不可靠（被系统清掉、手机重启、用户手动清了后台都会丢），必须有这道保险。
     */
    fun silence(ctx: Context, untilMs: Long = 0L): Boolean {
        if (!canWrite(ctx)) return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sp = prefs(ctx)
        // 只在"第一次由我们静音"时记住用户原来的模式：连续两节课之间不要把他自己的震动覆盖掉
        if (!sp.getBoolean(K_OURS, false) || am.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            sp.edit()
                .putBoolean(K_OURS, true)
                .putInt(K_PREV, am.ringerMode)
                .putLong(K_UNTIL, untilMs)
                .putLong(K_START, System.currentTimeMillis())
                .apply()
        } else if (untilMs > 0L) {
            // 同一段没断：把结束时间往后推（下一节更晚下课时）
            sp.edit().putLong(K_UNTIL, maxOf(untilMs, sp.getLong(K_UNTIL, 0L))).apply()
        }
        am.ringerMode = AudioManager.RINGER_MODE_SILENT
        return true
    }

    /**
     * 下课恢复。**还回用户原来的模式**，不是一律"正常"：
     * 他平时手机就是震动的人，上完课突然变响铃，比不恢复还烦。
     */
    fun unsilence(ctx: Context): Boolean {
        if (!canWrite(ctx)) return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prev = prefs(ctx).getInt(K_PREV, AudioManager.RINGER_MODE_NORMAL)
        am.ringerMode = when (prev) {
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_SILENT -> AudioManager.RINGER_MODE_NORMAL // 本来就是静音，别再闷着
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        prefs(ctx).edit().putBoolean(K_OURS, false).putLong(K_UNTIL, 0L).apply()
        return true
    }

    /**
     * 当前这段静音该在什么时候恢复（epoch → LocalDateTime）。
     *
     * 给重排用：重排会 `cancelAll` 再按新课表排，如果这段课已经不在这批窗口里，
     * 恢复闹钟就会跟着消失 —— 拿着这个时刻补一条回去（见 `ensureRestore`）。
     * null = 现在不是我们在静音（或者不知道结束时间）。
     */
    fun oursUntil(ctx: Context): java.time.LocalDateTime? {
        val sp = prefs(ctx)
        if (!sp.getBoolean(K_OURS, false)) return null
        val until = sp.getLong(K_UNTIL, 0L)
        if (until <= 0L) return null
        return java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(until),
            java.time.ZoneId.systemDefault(),
        )
    }

    /**
     * 兜底自愈：如果我们还按着静音、而这一段早该结束了 → 立刻恢复。
     * 返回 true 表示"这次真的动手恢复了"（调用方可以补一条通知）。
     */
    fun healIfStuck(ctx: Context): Boolean {
        val sp = prefs(ctx)
        if (!sp.getBoolean(K_OURS, false)) return false
        val until = sp.getLong(K_UNTIL, 0L)
        // until=0（老版本留下的标记）＝ 不知道什么时候结束，只要已过 1 分钟就放行
        // 不知道结束时间（老标记/提醒顺手静音）时，按"最多静 3 小时"兜底 ——
        // 用"1 分钟"会在一节课中途把铃声放回来，比不恢复更糟。
        val due = if (until > 0L) until + 30_000L else sp.getLong(K_START, 0L) + 3 * 3600_000L
        if (System.currentTimeMillis() < due) return false
        return unsilence(ctx)
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
