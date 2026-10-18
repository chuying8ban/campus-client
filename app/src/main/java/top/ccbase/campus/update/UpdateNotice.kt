package top.ccbase.campus.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import top.ccbase.campus.BuildConfig
import top.ccbase.campus.MainActivity
import top.ccbase.campus.R

/**
 * 更新说明通知（2026-10-18 用户："现在每次更新后要放一则更新内容的通知，让用户知道更新了什么"）。
 *
 * 数据源是随包发布的 `assets/changelog.tsv`（`版本号 TAB 正文`，一行一条）。
 * 为什么放 assets 而不是服务端：更新说明必须**跟着这个包装在一起**，装的是哪版就说哪版；
 * 走网络的话，版本没匹配上就会推错内容，还得处理请求失败。
 *
 * 触发点：每次进程启动调一次 [maybeNotify]。用 SharedPreferences 记住"最后一版已经提示过"，
 * 所以同一版只弹一次；用户从 3.0.1 直接升到 3.2.0 也能看到 3.2.0 这一条（不需要逐版补弹）。
 */
object UpdateNotice {

    private const val CH = "update"
    private const val PREF = "update_notice"
    private const val K_SEEN = "seen_version"

    /** 从 assets 里查这一版的说明；找不到返回 null（不弹空通知） */
    fun notesFor(ctx: Context, version: String): String? = runCatching {
        ctx.assets.open("changelog.tsv").bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.split('\t', limit = 2) }
                .firstOrNull { it.size == 2 && it[0].trim() == version }
                ?.get(1)
                ?.trim()
                ?.replace("\\n", "\n")
        }
    }.getOrNull()

    /** App 启动时调一次：这一版没提示过就发一条通知 */
    fun maybeNotify(ctx: Context) {
        val version = BuildConfig.VERSION_NAME
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (sp.getString(K_SEEN, null) == version) return
        val body = notesFor(ctx, version)
        // 先记下"这一版处理过了"，无论有没有说明 —— 否则没写说明的版本每次启动都会重算一遍
        sp.edit().putString(K_SEEN, version).apply()
        if (body.isNullOrBlank()) return
        post(ctx, version, body)
    }

    private fun post(ctx: Context, version: String, body: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "版本更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "每次更新后说明改了什么"
                },
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("校园助手已更新到 $version")
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(0x0EA7, n) } // 固定 id：新版本会覆盖旧的那条，不堆一串
    }
}
