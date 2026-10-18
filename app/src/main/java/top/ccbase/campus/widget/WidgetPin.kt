package top.ccbase.campus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

/**
 * 「把小组件放到桌面」这件事的入口。
 *
 * 为什么不只写一句"长按桌面自己加"：绝大多数人不知道手机有小组件这回事，
 * 说了也不会去点。Android 8+ 支持让 App **直接弹系统确认框**（requestPinAppWidget），
 * 用户点一下「添加」就落到桌面上了 —— 这才是能被用起来的路径。
 *
 * 也不做假动作：系统不支持（部分国产 ROM 关了）或版本太老时返回 false，
 * 界面据此改成"长按桌面添加"的文字说明，而不是给一个点了没反应的按钮。
 */
object WidgetPin {

    fun component(ctx: Context) = ComponentName(ctx, TodayWidgetProvider::class.java)

    /** 桌面上已经放了几个「今日课表」——用来决定还要不要摆那张邀请卡。 */
    fun placedCount(ctx: Context): Int = runCatching {
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(component(ctx)).size
    }.getOrDefault(0)

    /** 系统是否支持"一键钉到桌面"。 */
    fun supported(ctx: Context): Boolean = runCatching {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            AppWidgetManager.getInstance(ctx).isRequestPinAppWidgetSupported
    }.getOrDefault(false)

    /** 弹系统的"添加到主屏幕"确认框；返回 false 表示系统不支持/被 ROM 关掉。 */
    fun requestPin(ctx: Context): Boolean {
        if (!supported(ctx)) return false
        return runCatching {
            @Suppress("DEPRECATION")
            val success = PendingIntent.getBroadcast(
                ctx, 0, android.content.Intent(ctx, TodayWidgetProvider::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            AppWidgetManager.getInstance(ctx)
                .requestPinAppWidget(component(ctx), null, success)
        }.getOrDefault(false)
    }
}
