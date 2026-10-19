package top.ccbase.campus.ui.me

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import top.ccbase.campus.MainActivity

/**
 * 图库素材下载的进度通知（2026-10-19 用户：
 * "我希望下载时可以点击关闭后仍然在后台继续下载（要有状态栏提示…）"）。
 *
 * 动图原片动辄几 MB，校园网要好几秒。以前这段下载挂在页面的协程上，一退出就没了；
 * 现在它跑在页面之外的 scope 里，这条通知就是"还在下"的凭证 —— 关掉 App 也看得见。
 */
object MediaNotify {

    private const val CHANNEL = "media_dl"
    private const val ID = 30_010

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "素材下载", NotificationManager.IMPORTANCE_LOW).apply {
                description = "从在线图库保存动图/图片时的进度"
                setShowBadge(false)
            },
        )
    }

    fun start(ctx: Context, what: String, title: String) {
        ensureChannel(ctx)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            ctx, 3,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(what)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID, n) }
    }

    fun done(ctx: Context, text: String) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID, n) }
    }

    fun cancel(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(ID) }
    }
}
