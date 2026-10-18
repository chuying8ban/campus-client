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
import top.ccbase.campus.domain.toPlanNow

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
        // 用户通过系统弹框把卡片钉到桌面成功：给他一句明确的"成了"，并立刻渲染一次
        // （刚钉上去的实例还没被 onUpdate 覆盖到，会短暂显示默认样式）。
        if (intent.action == ACTION_PINNED) {
            android.widget.Toast
                .makeText(context, "小组件已添加到桌面", android.widget.Toast.LENGTH_SHORT)
                .show()
            refresh(context)
        }
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

        /** requestPinAppWidget 的 successCallback 走这个 action（钉到桌面成功那一刻） */
        const val ACTION_PINNED = "top.ccbase.campus.WIDGET_PINNED"

        /** 布局里 ViewFlipper 的 12 个帧位（顺序就是播放顺序） */
        private val FRAME_IDS = intArrayOf(
            R.id.f0, R.id.f1, R.id.f2, R.id.f3, R.id.f4, R.id.f5,
            R.id.f6, R.id.f7, R.id.f8, R.id.f9, R.id.f10, R.id.f11,
        )
        private const val MAX_ROWS = 3
        private const val FALLBACK_W_DP = 250
        private const val FALLBACK_H_DP = 110

        /**
         * 各种格子高度下能放几行（0 = 只留状态+课名）。
         * 抽成纯函数是为了能测：真机尺寸千奇百怪，靠肉眼试不过来。
         */
        fun rowsFor(hDp: Float): Int = when {
            hDp >= 175f -> 3
            hDp >= 145f -> 2
            hDp >= 118f -> 1
            else -> 0
        }

        /** 窄格子（2×2、3×2 拉扁）要收字号、砍掉日期周次。 */
        fun isNarrow(wDp: Float): Boolean = wDp < 210f

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
            val spec = WidgetThemes.spec(ctx)
            val theme = WidgetThemes.of(spec)

            // 动态主题：12 帧灌进布局里的 ViewFlipper（它自己按 flipInterval 翻，不需要 Service）。
            // 拿不到帧（没选图 / 解码失败）就退回静态分支 —— 绝不能整张卡 blank。
            val frames = runCatching { WidgetFrames.forSpec(ctx, spec, wPx, hPx) }.getOrNull()
            val dyn = frames != null
            if (frames != null) {
                rv.setViewVisibility(R.id.widget_flip, View.VISIBLE)
                rv.setViewVisibility(R.id.widget_bg_img, View.GONE)
                rv.setViewVisibility(R.id.widget_scrim, View.GONE)
                FRAME_IDS.forEachIndexed { i, id ->
                    rv.setImageViewBitmap(id, frames[i % frames.size])
                }
                rv.setInt(R.id.widget_flip, "setFlipInterval", 120)
                rv.setBoolean(R.id.widget_flip, "setAutoStart", true)
            } else {
                rv.setViewVisibility(R.id.widget_flip, View.GONE)
            }
            val art = WidgetThemes.imageBitmap(ctx, theme)
            val density = ctx.resources.displayMetrics.density

            if (dyn != null) {
                // 已经用帧轮播铺底了，不要再盖静态图
            } else if (art != null) {
                // 图片主题：图缩放铺满 + 压一层暗蒙版，保证字读得清
                rv.setViewVisibility(R.id.widget_bg_img, View.VISIBLE)
                // 圆角必须画进位图：RemoteViews 里的 ImageView 没有圆角属性（用户截图：直角）
                // 蒙版直接画进圆角位图：布局里那层矩形 scrim 会盖住圆角（2026-10-18 "没有做到完全圆角"）
                rv.setImageViewBitmap(
                    R.id.widget_bg_img,
                    WidgetArt.roundedWithScrim(
                        scaleToFill(art, wPx, hPx),
                        WidgetArt.radiusFor(hPx, density),
                        topAlpha = 0x66000000.toInt(),
                        bottomAlpha = 0x99000000.toInt(),
                    ),
                )
                rv.setViewVisibility(R.id.widget_scrim, View.GONE)
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
                    val day = java.time.LocalDate.now().toString()
                    val plans = runCatching {
                        app.db.dao().plansOnce(day).map { it.toPlanNow() }
                    }.getOrDefault(emptyList())
                    val model = buildWidgetModel(loadToday(app.db), MAX_ROWS, plans)
                    withContext(Dispatchers.Main) {
                        val mgr = AppWidgetManager.getInstance(ctx)
                        // 逐个实例兜底：某个 launcher 给的尺寸/主题让这一只画挂了，
                        // 不该把同一桌面上的其它实例一起拖下水（updateAppWidget 抛异常会中断整个循环）
                        ids.forEach { id ->
                            runCatching { mgr.updateAppWidget(id, views(ctx, mgr, id, model)) }
                                .onFailure { android.util.Log.w("TodayWidget", "实例 $id 刷新失败", it) }
                        }
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
            val theme = WidgetThemes.of(WidgetThemes.spec(ctx))
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
                        // 日程和课程挤在一行会看不出来是谁，这里给日程加前缀，
                        // 颜色也换成主题色（下面 setTextViewTextColor 一起处理）。
                        if (row.kind == WidgetRow.KIND_PLAN) append("日程 · ")
                        append(row.time)
                        append("　")
                        append(row.name)
                        if (row.room.isNotBlank()) {
                            append(" · ")
                            append(row.room)
                        }
                    }
                    rv.setTextViewText(id, text)
                    rv.setTextColor(
                        id,
                        if (row.kind == WidgetRow.KIND_PLAN) theme.accentColor else theme.bodyColor,
                    )
                }
            }
            rv.setTextViewText(R.id.widget_foot, model.footer)

            val (w, h) = sizePx(ctx, mgr, widgetId)

            // 尺寸自适应：RemoteViews 不能测量，只能按 launcher 报的格子尺寸算。
            // 不同 ROM 的格子差很多（MIUI 4×2≈155dp，原生 4×2≈170dp，某些桌面 2×2 只有 110dp），
            // 所以**宽、高各分档**，不是只砍行。
            val density = ctx.resources.displayMetrics.density
            val hDp = h / density
            val wDp = w / density

            // ① 高度：砍行 + 砍页脚
            val maxVisible = rowsFor(hDp)
            rowIds.drop(maxVisible).forEach { rv.setViewVisibility(it, View.GONE) }
            if (maxVisible == 0) {
                rv.setViewVisibility(R.id.widget_divider, View.GONE)
                rv.setViewVisibility(R.id.widget_meta, View.GONE)
            }
            // 没课时不画那条横线：线上方是"今天没有课"，线下面什么都没有，看着像没渲染完
            if (model.rows.isEmpty()) rv.setViewVisibility(R.id.widget_divider, View.GONE)
            if (hDp < 132f) rv.setViewVisibility(R.id.widget_foot, View.GONE)

            // ② 宽度：窄格子先牺牲右侧的日期/周次（它们最可省），再收字号
            if (wDp < 230f) rv.setViewVisibility(R.id.widget_week, View.GONE)
            if (wDp < 195f) rv.setViewVisibility(R.id.widget_date, View.GONE)
            if (isNarrow(wDp)) {
                // 小格子字号统一降一档，保证课名不被折成两行
                rv.setTextViewTextSize(R.id.widget_head, android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                rv.setTextViewTextSize(R.id.widget_status, android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f)
                rv.setTextViewTextSize(R.id.widget_row1, android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f)
                rv.setTextViewTextSize(R.id.widget_row2, android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f)
                rv.setTextViewTextSize(R.id.widget_row3, android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f)
                rv.setViewPadding(R.id.widget_content, (12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (9 * density).toInt())
            }

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
