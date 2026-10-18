package top.ccbase.campus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
 * 而这里只需要几行文字 + 一个点击入口。RemoteViews 零新依赖，升级也不受牵连。
 *
 * 视觉（2026-10-18 重做，用户："太简陋了"）：
 *  · 底不是一块纯色，而是按主题色**现画**的位图（渐变 + 左上柔光 + 细描边 + 圆角，见 [WidgetArt]）；
 *  · 位图按每个实例的**实际格子尺寸**画，不按设计稿 —— 拉宽/拉高都清晰，也不会被裁掉边；
 *  · 文字分四级：状态（色点+小字）> 课程名（唯一大字）> 时间地点 > 后几节 > 页脚。
 *
 * 数据来自**同一个** [loadToday]：小组件和 App 首页看到的是同一份判断（正在上/下一节），
 * 不另写一套"今天有哪些课"的逻辑去和首页对不上。
 *
 * 刷新时机：
 *  · 系统每 30 分钟一次（`widget_today_info.xml` 的 updatePeriodMillis）；
 *  · 课表导入成功后 / App 每次打开时由 [refresh] 推一次；
 *  · 用户点小组件 → 打开 App 首页。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, appWidgetIds, null)
    }

    /** 从桌面拉大小改格子尺寸：立刻按新尺寸重画底图，不然渐变会拉花。 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        render(context, intArrayOf(appWidgetId), null)
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
        private const val FALLBACK_W_DP = 250
        private const val FALLBACK_H_DP = 110

        /** 实例的实际像素尺寸（launcher 给的 dp 值 × 密度）。 */
        private fun sizePx(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val opt = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
            val wDp = opt?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, FALLBACK_W_DP) ?: FALLBACK_W_DP
            val hDp = opt?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, FALLBACK_H_DP) ?: FALLBACK_H_DP
            val d = ctx.resources.displayMetrics.density
            // 兜底：某些 launcher 给 0 或荒谬值，夹一下免得画出 1px 或 8K 位图
            val w = (wDp.coerceIn(120, 900) * d).toInt().coerceAtLeast(200)
            val h = (hDp.coerceIn(60, 500) * d).toInt().coerceAtLeast(120)
            return w to h
        }

        /**
         * 上色 + 铺底。**每次刷新都重新读一次主题**（不缓存）：
         * 用户在外观页换了色，回到桌面那一下就该是新的，不用等系统 30 分钟。
         */
        private fun applyTheme(ctx: Context, rv: RemoteViews, wPx: Int, hPx: Int) {
            val theme = WidgetThemes.of(WidgetThemes.spec(ctx))
            val art = WidgetThemes.imageBitmap(ctx, theme)
            val density = ctx.resources.displayMetrics.density

            if (art != null) {
                // 图片主题：图缩放铺满 + 压一层暗蒙版，保证字读得清
                rv.setViewVisibility(R.id.widget_bg_img, View.VISIBLE)
                rv.setImageViewBitmap(R.id.widget_bg_img, scaleToFill(art, wPx, hPx))
                rv.setViewVisibility(R.id.widget_scrim, View.VISIBLE)
            } else {
                // 纯色 / DIY：现画一张渐变底，比原来的"一整块纯色"有质感得多
                val bmp = WidgetArt.background(
                    w = wPx,
                    h = hPx,
                    base = theme.bgColor,
                    accent = theme.accentColor,
                    light = theme.light,
                    radiusPx = WidgetArt.radiusFor(hPx, density),
                )
                rv.setViewVisibility(R.id.widget_bg_img, View.VISIBLE)
                rv.setImageViewBitmap(R.id.widget_bg_img, bmp)
                rv.setViewVisibility(R.id.widget_scrim, View.GONE)
            }

            rv.setTextColor(R.id.widget_status, theme.accentColor)
            rv.setTextColor(R.id.widget_head, theme.headColor)
            rv.setTextColor(R.id.widget_date, theme.dimColor)
            rv.setTextColor(R.id.widget_week, theme.dimColor)
            rv.setTextColor(R.id.widget_meta, theme.bodyColor)
            rv.setTextColor(R.id.widget_row1, theme.bodyColor)
            rv.setTextColor(R.id.widget_row2, theme.bodyColor)
            rv.setTextColor(R.id.widget_row3, theme.bodyColor)
            rv.setTextColor(R.id.widget_foot, theme.dimColor)
        }

        /** 按目标宽高做 centerCrop 缩放；原图小就不放大（放大只会糊）。 */
        private fun scaleToFill(src: android.graphics.Bitmap, w: Int, h: Int): android.graphics.Bitmap {
            val sx = w.toFloat() / src.width
            val sy = h.toFloat() / src.height
            val s = maxOf(sx, sy)
            if (s <= 1f) return src
            val out = android.graphics.Bitmap.createScaledBitmap(
                src, (src.width * s).toInt(), (src.height * s).toInt(), true,
            )
            val dx = ((out.width - w) / 2).coerceAtLeast(0)
            val dy = ((out.height - h) / 2).coerceAtLeast(0)
            val cropped = android.graphics.Bitmap.createBitmap(
                out, dx, dy, minOf(w, out.width), minOf(h, out.height),
            )
            if (cropped != out) out.recycle()
            return cropped
        }

        /** 课表导入成功后 / App 打开时调一次：小组件立刻跟着变，不用等 30 分钟。 */
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
                        ids.forEach { mgr.updateAppWidget(it, views(ctx, mgr, it, model)) }
                    }
                } catch (e: Exception) {
                    // 读库失败不该让桌面留一块白：把最后一句"打开看看"写上，也不崩
                    android.util.Log.e("TodayWidget", "刷新失败", e)
                    withContext(Dispatchers.Main) {
                        val mgr = AppWidgetManager.getInstance(ctx)
                        val fallback = WidgetModel(
                            status = "课表",
                            dateLine = "",
                            week = "",
                            headline = "暂时读不出来",
                            meta = "点开 App 看看",
                            rows = emptyList(),
                            footer = "",
                        )
                        ids.forEach { mgr.updateAppWidget(it, views(ctx, mgr, it, fallback)) }
                    }
                } finally {
                    pending?.finish()
                }
            }
        }

        private fun views(ctx: Context, mgr: AppWidgetManager, widgetId: Int, model: WidgetModel): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_today)
            rv.setTextViewText(R.id.widget_status, model.status)
            rv.setTextViewText(R.id.widget_date, model.dateLine)
            rv.setTextViewText(R.id.widget_week, model.week)
            rv.setTextViewText(R.id.widget_head, model.headline)
            rv.setTextViewText(R.id.widget_meta, model.meta)

            val rowIds = intArrayOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3)
            rowIds.forEachIndexed { i, id ->
                val row = model.rows.getOrNull(i)
                if (row == null) {
                    rv.setViewVisibility(id, View.GONE)
                } else {
                    rv.setViewVisibility(id, View.VISIBLE)
                    val text = buildString {
                        append(row.time)
                        append("　")
                        append(row.name)
                        if (row.room.isNotBlank()) {
                            append(" · ")
                            append(row.room)
                        }
                    }
                    rv.setTextViewText(id, text)
                }
            }
            rv.setTextViewText(R.id.widget_foot, model.footer)

            val (w, h) = sizePx(ctx, mgr, widgetId)

            // 高度吃紧时主动砍行 —— RemoteViews 不能测量，只能按 launcher 给的高度算。
            // 4×2 的格子只有 140~160dp，硬塞三行＋页脚就会被裁成半行字，比少显示更难看出问题。
            val hDp = h / ctx.resources.displayMetrics.density
            val maxVisible = when {
                hDp >= 175f -> 3
                hDp >= 145f -> 2
                else -> 1
            }
            rowIds.drop(maxVisible).forEach { rv.setViewVisibility(it, View.GONE) }
            // 没课时不画那条横线：线上方是"今天没有课"，线下面什么都没有，看着像没渲染完
            if (model.rows.isEmpty()) rv.setViewVisibility(R.id.widget_divider, View.GONE)
            if (hDp < 132f) rv.setViewVisibility(R.id.widget_foot, View.GONE)

            applyTheme(ctx, rv, w, h)

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
