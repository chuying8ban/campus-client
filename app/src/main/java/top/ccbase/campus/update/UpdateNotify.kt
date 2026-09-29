package top.ccbase.campus.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import top.ccbase.campus.MainActivity

/**
 * 「有新版本」的通知栏提醒。
 *
 * 为什么要有它（用户明确要求：进入 App 会收到更新通知）：
 *   App 里弹的浮层只在用户正看着屏幕时才有用 —— 他很可能一进来就去干别的了，
 *   等浮层弹出来时人已经切走。通知栏这条会一直躺在那里，回来还点得开。
 *
 * 频率：一个版本只发一次、点过「以后再说」的版本不发（判定在 [UpdateLogic.shouldNotify]）。
 * 通知 id 固定：同一个版本重复发只会覆盖同一条，不会在通知栏堆一排。
 */
object UpdateNotify {

    const val CHANNEL = "update_alert"
    private const val TAG = "UpdateNotify"

    /** 固定 id：同一条通知反复更新即可（避开课前静音的时间戳 id 和抢课提醒的 20000+） */
    private const val ID = 30_001

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "新版本提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "有新版本时由本机提醒，点开就能更新"
            }
        )
    }

    /**
     * 发一条「有新版本」提醒。
     *
     * @return true = 真的发出去了。**没通知权限时返回 false**，调用方据此决定要不要记「已提醒」——
     *   如果这里骗调用方说发成功了，那个版本就再也不会提醒，等于静默丢掉一次升级机会。
     */
    fun post(ctx: Context, m: UpdateManifest, currentName: String): Boolean {
        ensureChannel(ctx)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            Log.w(TAG, "通知权限没开，更新提醒发不出去")
            return false
        }
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val size = if (m.size > 0) " · ${UpdateLogic.humanSize(m.size)}" else ""
        val body = "当前 $currentName → v${m.versionName}$size。点开就能更新。"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("校园助手有新版本 v${m.versionName}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        return try {
            NotificationManagerCompat.from(ctx).notify(ID, n)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "没有通知权限", e)
            false
        }
    }

    /** 更新成功后把提醒撤掉，别让过期信息一直躺在通知栏 */
    fun clear(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(ID) }
    }
}
