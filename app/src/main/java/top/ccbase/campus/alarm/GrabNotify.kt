package top.ccbase.campus.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import top.ccbase.campus.MainActivity
import top.ccbase.campus.net.GrabLog

/**
 * 把提醒弹到**这台手机**的通知栏。
 *
 * 为什么要自己弹：原来的提醒是服务端推给作者个人的飞书/QQ 机器人 ——
 * 那是作者一个人的通道，换个同学装 App 什么都收不到。
 * 通知由手机本地发，谁登录谁收，和别人的机器人无关。
 *
 * 权限提醒：Android 13+ 没给 POST_NOTIFICATIONS 时 `notify` 会被静默丢弃，
 * 所以这里先查 [NotificationManagerCompat.areNotificationsEnabled]，没开就写进诊断记录，
 * 而不是让用户以为"监控没在跑"。
 */
object GrabNotify {

    const val CHANNEL = "grab_alert"
    private const val TAG = "GrabNotify"

    /** 通知 id 基数：避开课前静音那条（它用时间戳当 id） */
    private const val ID_BASE = 20_000

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, GrabWatchLogic.channelName(), NotificationManager.IMPORTANCE_HIGH).apply {
                description = "监控到余位时由本机直接提醒，不经过任何人的机器人"
                enableVibration(true)
            }
        )
    }

    /** @return 是否真的弹出去了 */
    fun alert(ctx: Context, log: GrabLog): Boolean = post(
        ctx, id = ID_BASE + log.id,
        title = GrabWatchLogic.notifyTitle(), body = GrabWatchLogic.notifyBody(log.msg),
    )

    fun summary(ctx: Context, extra: Int, at: Long = System.currentTimeMillis()): Boolean = post(
        ctx, id = ID_BASE, title = GrabWatchLogic.notifyTitle(),
        body = GrabWatchLogic.overflowBody(extra),
    )

    private fun post(ctx: Context, id: Int, title: String, body: String): Boolean {
        ensureChannel(ctx)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            Log.w(TAG, "通知权限没开，提醒被系统丢弃")
            GrabWatch.note(ctx, "通知权限没开，提醒发不出去")
            return false
        }
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        return try {
            NotificationManagerCompat.from(ctx).notify(id, n)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "没有通知权限", e)
            false
        }
    }
}
