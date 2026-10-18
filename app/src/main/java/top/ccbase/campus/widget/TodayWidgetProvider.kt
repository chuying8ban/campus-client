package top.ccbase.campus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.MainActivity
import top.ccbase.campus.R
import top.ccbase.campus.domain.loadToday

/**
 * 桌面「今日课表」小组件。
 *
 * 为什么用 RemoteViews 而不是 Glance：这是全 App 唯一一处平台自绘界面，
 * 加 Glance 会给整包塞进一整套 Compose-for-widgets 依赖与它自己的版本约束，
 * 而这里只需要四行文字 + 一个点击入口。RemoteViews 零新依赖，升级也不受牵连。
 *
 * 数据来自**同一个** [loadToday]：小组件和 App 首页看到的是同一份判断（正在上/下一节），
 * 不另写一套"今天有哪些课"的逻辑去和首页对不上。
 *
 * 刷新时机：
 *  · 系统每 30 分钟一次（`widget_today_info.xml` 的 updatePeriodMillis）；
 *  · 课表真的导入成功后由 [refresh] 立刻推一次（见 SchoolImportFlow）；
 *  · 用户点小组件 → 打开 App 首页。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, appWidgetIds, null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                // goAsync 让系统在异步读完库之前不把进程收掉（widget 更新常常发生在后台）
                render(context, ids, goAsync())
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "top.ccbase.campus.WIDGET_REFRESH"
        private const val MAX_ROWS = 3

        /** 课表导入成功后调一次：小组件立刻跟着变，不用等 30 分钟。 */
        fun refresh(ctx: Context) {
            val intent = Intent(ctx, TodayWidgetProvider::class.java).setAction(ACTION_REFRESH)
            runCatching { ctx.sendBroadcast(intent) }
        }

        private fun render(ctx: Context, ids: IntArray, pending: android.content.BroadcastReceiver.PendingResult?) {
            val app = ctx.applicationContext as? CampusApplication
            if (app == null) {
                pending?.finish()
                return
            }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val model = buildWidgetModel(loadToday(app.db), MAX_ROWS)
                    withContext(Dispatchers.Main) {
                        val mgr = AppWidgetManager.getInstance(ctx)
                        ids.forEach { mgr.updateAppWidget(it, views(ctx, model)) }
                    }
                } catch (e: Exception) {
                    // 读库失败不该让桌面留一块白：把最后一句"打开看看"写上，也不崩
                    android.util.Log.e("TodayWidget", "刷新失败", e)
                    withContext(Dispatchers.Main) {
                        val mgr = AppWidgetManager.getInstance(ctx)
                        ids.forEach {
                            mgr.updateAppWidget(
                                it,
                                views(ctx, WidgetModel("今天", "课表暂时读不出来", emptyList(), "点开 App 看看")),
                            )
                        }
                    }
                } finally {
                    pending?.finish()
                }
            }
        }

        private fun views(ctx: Context, model: WidgetModel): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_today)
            rv.setTextViewText(R.id.widget_date, model.dateLine)
            rv.setTextViewText(R.id.widget_head, model.headline)

            val rowIds = intArrayOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3)
            rowIds.forEachIndexed { i, id ->
                val row = model.rows.getOrNull(i)
                if (row == null) {
                    rv.setViewVisibility(id, View.GONE)
                } else {
                    rv.setViewVisibility(id, View.VISIBLE)
                    val text = if (row.room.isBlank()) "${row.time}　${row.name}"
                    else "${row.time}　${row.name} · ${row.room}"
                    rv.setTextViewText(id, text)
                }
            }
            rv.setTextViewText(R.id.widget_foot, model.footer)

            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(R.id.widget_root, open)
            return rv
        }
    }
}
