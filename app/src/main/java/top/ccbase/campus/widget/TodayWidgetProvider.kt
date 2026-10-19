package top.ccbase.campus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
import top.ccbase.campus.ui.theme.TextTint

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
        // 进程此刻活着 → 动态主题立刻开始走帧，不必等用户再开一次 App
        runCatching { WidgetTicker.start(context) }
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
        if (intent.action == ACTION_REFRESH || intent.action == ACTION_TICK) {
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

        /**
         * 动画帧步进（2026-10-19 用户："小组件的动画仍然是卡顿的，一点也不流畅"）。
         * 由 [WidgetTicker] 每秒推一次，只为了让帧真的往前走；
         * 数据（课表/计划）走 12 秒缓存，不做无意义的重复查库。
         */
        const val ACTION_TICK = "top.ccbase.campus.WIDGET_TICK"

        /** 数据缓存：tick 频率高，别每次都为同一天的课表查库 */
        @Volatile private var cachedModel: WidgetModel? = null
        @Volatile private var cachedAtMs: Long = 0L

        /** requestPinAppWidget 的 successCallback 走这个 action（钉到桌面成功那一刻） */
        const val ACTION_PINNED = "top.ccbase.campus.WIDGET_PINNED"

        /**
         * 布局里 ViewFlipper 的帧位，**数量必须等于 [WidgetFrames.FRAMES]**。
         *
         * ⚠️ 2026-10-19：这里原来是 12 个，只灌 4 帧、其余 `setViewVisibility(GONE)`。
         * ViewAnimator 是按**子 View 下标**轮播的，不看可见性 —— 轮到一个空白 ImageView 就整卡空白。
         * 12 个位子只有 4 个有图 = 卡片 2/3 的时间是空的，用户看到的就是"动态主题一片黑"。
         */
        private val FRAME_IDS = intArrayOf(R.id.f0, R.id.f1, R.id.f2, R.id.f3)
        private const val MAX_ROWS = 3
        private const val FALLBACK_W_DP = 250
        private const val FALLBACK_H_DP = 110

        /**
         * 各种格子高度下能放几行（0 = 只留状态+课名）。
         * 抽成纯函数是为了能测：真机尺寸千奇百怪，靠肉眼试不过来。
         */
        fun rowsFor(hDp: Float): Int = when {
            hDp >= 176f -> 3
            hDp >= 146f -> 2
            hDp >= 126f -> 1
            else -> 0
        }

        /**
         * 给 launcher 报的格子尺寸留出的**安全边距**（dp）。
         *
         * 2026-10-19 用户："为什么小控件自检这个做不到和预览的一样"——那张截图里桌面上
         * 第二行被裁掉一半。量过（见 `WidgetMeasureProbe`）：
         * 只画课名+状态要 99.7dp，加 1 行 120.3dp，2 行 139.0dp，3 行 157.7dp。
         * 而 launcher 报的 MIN_HEIGHT 常常**比真正给到的高度大**（MIUI 4×2 报 161dp，
         * 实际只剩一百二十出头），按报来的数字算行数就必然裁掉最后一行。
         * 所以算行数前先扣掉这一段，宁可少放一行，也不许把一行切成两半。
         */
        const val HEIGHT_SAFETY_DP = 18f

        /** 真正用于排版的高度：报来的高度扣掉安全边距。 */
        fun usableHDp(hDp: Float): Float = hDp - HEIGHT_SAFETY_DP

        /**
         * 桌面上那个实例的尺寸（dp），预览和自检都按它画。
         *
         * 2026-10-19 用户："为什么小控件自检这个做不到和预览的一样"。
         * 原因有两个，都是"拿了个假尺寸"：
         *   ① 预览写死 2:1、自检写死 900×400px —— 而桌面格子往往不是这个比例；
         *   ② 自检把 900×400 塞进固定 150dp 高的框里，比例被拉歪，还裁掉最后一行。
         * 这里统一成"读桌面真实实例"，两边就都对得上了。
         */
        fun deskSizeDp(ctx: Context): Pair<Float, Float> {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = runCatching {
                mgr.getAppWidgetIds(android.content.ComponentName(ctx, TodayWidgetProvider::class.java))
            }.getOrDefault(IntArray(0))
            val opt = ids.firstOrNull()?.let { runCatching { mgr.getAppWidgetOptions(it) }.getOrNull() }
            val w = opt?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val h = opt?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            if (w in 100..900 && h in 80..500) return w.toFloat() to h.toFloat()
            // 桌面上还没有实例时给个"典型 4×2"：320×161dp（MIUI 上最常见的那一档）。
            return 320f to 161f
        }

        /** 窄格子（2×2、3×2 拉扁）要收字号、砍掉日期周次。 */
        fun isNarrow(wDp: Float): Boolean = wDp < 210f

        /** 实例的实际像素尺寸（launcher 给的 dp 值 × 密度）。 */
        private fun sizePx(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val opt = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
            val wDp = opt?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, FALLBACK_W_DP) ?: FALLBACK_W_DP
            val hDp = opt?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, FALLBACK_H_DP) ?: FALLBACK_H_DP
            val d = ctx.resources.displayMetrics.density
            // 兜底：某些 launcher 给 0 或荒谬值，夹一下免得画出 1px 或 8K 位图。
            // **必须夹像素、不能只夹 dp**：MIUI 的 opt 常报 900dp，×2.75 密度 = 2475×1375
            // ≈ 13.6MB 一张位图 —— 就是"主题显示不上"的真凶（2026-10-19）。
            val w = (wDp.coerceIn(120, 900) * d).toInt().coerceIn(200, 1200)
            val h = (hDp.coerceIn(60, 500) * d).toInt().coerceIn(120, 600)
            return w to h
        }

        /**
         * 在 App 里**提前把帧算好**（后台线程调）。
         *
         * 用户换完主题马上回桌面，provider 那一下要是还在抽帧，他就得盯着"正在加载"。
         * 这里用和 provider 完全相同的尺寸口径（[sizePx]）先把帧算出来并落盘缓存，
         * provider 再进来就是读文件。算不出来也不影响 —— provider 自己还会再试。
         */
        fun warmFrames(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = runCatching {
                mgr.getAppWidgetIds(android.content.ComponentName(ctx, TodayWidgetProvider::class.java))
            }.getOrDefault(IntArray(0))
            if (ids.isEmpty()) return
            val spec = WidgetThemes.spec(ctx)
            ids.forEach { id ->
                val (w, h) = sizePx(ctx, mgr, id)
                runCatching { WidgetFrames.forSpec(ctx, spec, w, h) }
            }
        }

        /**
         * 测试钩子：只按主题拼一张 RemoteViews（不查课表、不依赖 launcher）。
         * 用来验证"主题到底有没有铺上去"——真机上看不到的画面，测试里能一项项断言。
         */
        internal fun themeViews(ctx: Context, wPx: Int, hPx: Int, specOverride: String? = null): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_today)
            applyTheme(ctx, rv, wPx, hPx, specOverride)
            return rv
        }

        /**
         * 上色 + 铺底。**每次刷新都重新读一次主题**（不缓存）：
         * 用户在外观页换了色，回到桌面那一下就该是新的，不用等系统 30 分钟。
         */
        private fun applyTheme(ctx: Context, rv: RemoteViews, wPx: Int, hPx: Int, specOverride: String? = null) {
            val spec = specOverride ?: WidgetThemes.spec(ctx)
            val theme = WidgetThemes.of(spec)
            // 主题透明度（用户 2026-10-19 要的滑块；1f = 原样）
            val wAlpha = runCatching { WidgetThemes.alpha(ctx) }.getOrDefault(1f)
            // 根容器必须是**透明**的：圆角是通过位图的透明角落做出来的，
            // 底下要是垫一层不透明底色，那圈透明角落就被它填成黑色方块 ——
            // 用户 2026-10-19 的原话："没有实现圆角，外边有黑色的矩形"。
            // 只在连位图都画不出来时才退回不透明底色（那时至少还能看见字）。
            rv.setInt(R.id.widget_root, "setBackgroundColor", android.graphics.Color.TRANSPARENT)

            // 动态主题：12 帧灌进布局里的 ViewFlipper（它自己按 flipInterval 翻，不需要 Service）。
            // 拿不到帧（没选图 / 解码失败）就退回静态分支 —— 绝不能整张卡 blank。
            val speed = runCatching { WidgetThemes.speed(ctx) }.getOrDefault(1f)
            val frames = runCatching { WidgetFrames.forSpec(ctx, spec, wPx, hPx, speed) }.getOrNull()
            val dyn = frames != null

            // 可读性幕：只在"图片/动图"这类底板内容不受我们控制的主题上，且用户挑过字色时才有值。
            // 注意：**必须画进位图**（见 WidgetArt.veil），不能再用布局里那块矩形 scrim。
            val imgTheme = dyn || spec.startsWith("photo") || spec.startsWith("media") ||
                spec.startsWith("animphoto")
            val wScrim = if (imgTheme)
                TextTint.scrimArgbFor(TextTint.widgetState.value, theme.bgColor) else 0
            val density = ctx.resources.displayMetrics.density
            val veilRadius = WidgetArt.radiusFor(hPx, density)
            if (frames != null) {
                rv.setViewVisibility(R.id.widget_flip, View.VISIBLE)
                rv.setViewVisibility(R.id.widget_bg_img, View.GONE)
                // 只灌实际生成的帧，多出来的 ImageView 直接 GONE：
                // ViewFlipper 会跳过 GONE 的子视图，既不会空白一帧，也不会白塞位图
                // 帧不够就**重复最后一帧**填满，绝不留空位：ViewAnimator 会把空位也翻出来。
                // 高画质：把帧文件交给桌面自己读（setImageViewUri），不把像素塞进 binder。
                // 2026-10-19 用户："小控件的动态主题画质太糊了" —— 糊正是因为位图预算
                // 把 880×443 的帧压到 300 多像素宽，桌面再放大 2~3 倍。
                // 有可读性幕时必须走位图（幕要画进图里）；没幕才用 URI 高画质。
                val hdFiles = if (wScrim == 0 &&
                    runCatching { WidgetThemes.hdFrames(ctx) }.getOrDefault(true)
                ) {
                    runCatching { WidgetFrames.frameFiles(ctx, spec, wPx, hPx, speed) }.getOrNull()
                } else null
                val hdOk = hdFiles != null && hdFiles.size >= FRAME_IDS.size
                if (hdOk) {
                    // 兜底：万一某个桌面读不了 content URI，底下还压着一张静态底图，
                    // 至少不会变成"只有文字、图没了"。
                    runCatching {
                        backgroundBitmapFor(ctx, spec, wPx, hPx)?.let { still ->
                            rv.setImageViewBitmap(R.id.widget_bg_img, WidgetArt.fade(still, wAlpha))
                            rv.setViewVisibility(R.id.widget_bg_img, View.VISIBLE)
                        }
                    }
                    val authority = ctx.packageName + ".fileprovider"
                    FRAME_IDS.forEachIndexed { i, id ->
                        val f = hdFiles!![i.coerceAtMost(hdFiles.size - 1)]
                        runCatching {
                            rv.setImageViewUri(
                                id,
                                androidx.core.content.FileProvider.getUriForFile(ctx, authority, f),
                            )
                            // 透明度：走 URI 后不能在位图上做，交给 ImageView 自己的 alpha
                            runCatching {
                                rv.setInt(id, "setImageAlpha", (wAlpha.coerceIn(0f, 1f) * 255).toInt())
                            }
                            rv.setViewVisibility(id, View.VISIBLE)
                        }.onFailure { rv.setViewVisibility(id, View.GONE) }
                    }
                } else {
                    FRAME_IDS.forEachIndexed { i, id ->
                        // 透明度在这里落地（a=1 时 fade 原样返回，不复制位图）
                        val fr = frames[i.coerceAtMost(frames.size - 1)]
                        val veiled = if (wScrim != 0) {
                            WidgetArt.veil(fr, wScrim, veilRadius * (fr.width.toFloat() / wPx))
                        } else fr
                        rv.setImageViewBitmap(id, WidgetArt.fade(veiled, wAlpha))
                        rv.setViewVisibility(id, View.VISIBLE)
                    }
                }
                // 2026-10-19 真正的根因（自检里终于露出原话）：
                //   android.widget.RemoteViews$ActionException: view: android.widget.ViewFlipper
                //   can't use method with RemoteViews: setAutoStart(boolean)
                // `setAutoStart` / `setFlipInterval` 都不是 @RemotableViewMethod，一下就把**整份**
                // RemoteViews 打成异常 —— 主题、文字、底图全都没送到桌面，所以"换了主题没反应"。
                // ViewAnimator.setDisplayedChild(int) 是允许的，用它把帧定在某一帧上。
                // 桌面自己不会翻页（翻页必须靠 Service 或反复 updateAppWidget），
                // 这里按时间挑帧：每次刷新/重绘看到的是不同一帧，至少不是死图。
                // 播放速度：帧序列不变，只是"多久轮到下一帧"按 speed 缩放
                val stepMs = ((WidgetFrames.STEP_SEC * 1000) / speed.coerceAtLeast(0.2f)).toLong()
                    .coerceAtLeast(60L)
                val idx = ((System.currentTimeMillis() / stepMs) % frames.size).toInt()
                runCatching { rv.setInt(R.id.widget_flip, "setDisplayedChild", idx) }
            } else {
                rv.setViewVisibility(R.id.widget_flip, View.GONE)
            }
            // ⚠️ 这里原来写的是 `if (dyn == null)` —— dyn 是 Boolean，`== null` 恒为 false，
            // 于是**静态分支从来没执行过**：纯色/图片/我的图全都没有底图，只剩文字和深色底。
            // 这就是"静止时总是不显示，点微动就有了"的根因（微动走的是上面 frames 那条路）。
            if (!dyn) {
                // 静态底：图片主题 / 媒体封面帧 / 纯色渐变，统一在 backgroundBitmapFor 里挑
                val bmp = backgroundBitmapFor(ctx, spec, wPx, hPx)
                if (bmp != null) {
                    rv.setViewVisibility(R.id.widget_bg_img, View.VISIBLE)
                    val veiled = if (wScrim != 0) {
                        WidgetArt.veil(bmp, wScrim, veilRadius * (bmp.width.toFloat() / wPx))
                    } else bmp
                    rv.setImageViewBitmap(R.id.widget_bg_img, WidgetArt.fade(veiled, wAlpha))
                } else {
                    // 连渐变都画不出来（几乎不可能）：至少别让卡片透明
                    rv.setViewVisibility(R.id.widget_bg_img, View.GONE)
                    rv.setInt(R.id.widget_root, "setBackgroundColor", opaque(theme.bgColor))
                }
            }

            // 布局里那块矩形 scrim 永远隐藏：它铺的是直角矩形，会把四个圆角填平
            // （2026-10-19 用户："圆角有时会消失"）。幕已经画进位图了。
            rv.setViewVisibility(R.id.widget_scrim, View.GONE)

            // 文字颜色跟 App 同源（用户在「外观」里挑的那支）。
            // 只动字的颜色，底色/强调色不跟着变 —— 那会变成另一套主题。
            val tint = TextTint.currentWidgetArgb()   // 只认"小组件"那一份，跟 App 的字色无关
            val headC = TextTint.apply(tint, theme.headColor, theme.bgColor, 0)
            val bodyC = TextTint.apply(tint, theme.bodyColor, theme.bgColor, 1)
            val dimC = TextTint.apply(tint, theme.dimColor, theme.bgColor, 2)
            rv.setTextColor(R.id.widget_status, theme.accentColor)
            rv.setTextColor(R.id.widget_head, headC)
            rv.setTextColor(R.id.widget_date, dimC)
            rv.setTextColor(R.id.widget_week, dimC)
            rv.setTextColor(R.id.widget_meta, bodyC)
            rv.setTextColor(R.id.widget_row1, bodyC)
            rv.setTextColor(R.id.widget_row2, bodyC)
            rv.setTextColor(R.id.widget_row3, bodyC)
            rv.setTextColor(R.id.widget_foot, dimC)
        }

        /**
         * 静态底图：媒体封面帧 > 图片主题 > 纯色渐变。失败返回 null（调用方退回不透明底色）。
         *
         * 抽成一个函数是为了能测——真机上这张图到底有没有生成、是不是大得被 launcher 丢掉，
         * 只能靠测试断言，肉眼看不出原因。
         */
        internal fun backgroundBitmapFor(ctx: Context, spec: String, wPx: Int, hPx: Int): android.graphics.Bitmap? {
            val theme = WidgetThemes.of(spec)
            val density = ctx.resources.displayMetrics.density
            // 先按预算把画布尺寸定好（等比例），别"先画 340 万像素再缩"——
            // 峰值内存 13MB 在广播里就是 OOM，OOM 的后果是整张卡退化成纯色。
            val fitted = fitTo(BG_MAX_PX.toLong(), wPx, hPx)
            val bw = fitted.first
            val bh = fitted.second
            runCatching {
                if (top.ccbase.campus.ui.theme.UserMedia.parse(spec) != null) {
                    val still = top.ccbase.campus.ui.theme.UserMedia.stillBitmap(
                        ctx, top.ccbase.campus.ui.theme.UserMedia.Slot.WIDGET,
                    )
                    // 老图（提亮逻辑上线前存的）也抬一下，不用用户重选一次
                    if (still != null) return bgBitmap(ImgLift.lift(still), bw, bh, density, 0x33000000, 0x66000000)
                }
                val art = WidgetThemes.imageBitmap(ctx, theme)
                // 遮罩别压太狠：0x66/0x99（40%→60% 黑）会把偏暗的照片直接压成黑块。
                // 文字有描边/底色衬着，20%→40% 足够读。
                if (art != null) return bgBitmap(art, bw, bh, density, 0x33000000, 0x66000000)
            }
            val bmp = runCatching {
                WidgetArt.background(
                    w = bw,
                    h = bh,
                    base = theme.bgColor,
                    accent = theme.accentColor,
                    light = theme.light,
                    radiusPx = WidgetArt.radiusFor(hPx, density),
                )
            }.getOrNull()
            return bmp?.let { capPixels(it) }
        }

        /** 等比例装进预算：返回不超过 px 个像素的 (w, h)。 */
        internal fun fitTo(px: Long, w: Int, h: Int): Pair<Int, Int> {
            val cur = w.toLong() * h
            if (cur <= px || cur <= 0L) return w to h
            val s = kotlin.math.sqrt(px.toFloat() / cur)
            return kotlin.math.max(1, (w * s).toInt()) to kotlin.math.max(1, (h * s).toInt())
        }

        /** 位图预算：RemoteViews 走 binder，位图一大 MIUI 会静默丢掉整张图（卡片只剩文字）。
         *  20 万像素 ≈ 800×250，ARGB 约 800KB，桌面 4×2 也够看。
         */
        // 120_000 像素（≈490KB ARGB）—— 比 binder 1MB 上限再留一半余量：
        // MIUI 到底按哪个数丢图没有文档，宁可糊一点也要它显示出来。
        // 2026-10-19：120k 像素 = 480KB(ARGB)，叠上帧就逼近 binder 的 ~1MB 上限，
        // MIUI 会**静默整块丢掉** → 桌面只剩黑底和文字。压到 60k（≈240KB）留足余量。
        private const val BG_MAX_PX = 90_000

        /** 缩放铺满 + 圆角蒙版，一步成型。失败返回 null，由调用方退回纯色底。 */
        private fun bgBitmap(
            src: android.graphics.Bitmap,
            wPx: Int,
            hPx: Int,
            density: Float,
            topAlpha: Int,
            bottomAlpha: Int,
        ): android.graphics.Bitmap? = runCatching {
            var filled = scaleToFill(src, wPx, hPx)
            val px = filled.width.toLong() * filled.height
            if (px > BG_MAX_PX) {
                val s = kotlin.math.sqrt(BG_MAX_PX.toFloat() / px)
                filled = android.graphics.Bitmap.createScaledBitmap(
                    filled,
                    (filled.width * s).toInt().coerceAtLeast(1),
                    (filled.height * s).toInt().coerceAtLeast(1),
                    true,
                )
            }
            val ratio = filled.width.toFloat() / wPx
            WidgetArt.roundedWithScrim(
                filled,
                WidgetArt.radiusFor(hPx, density) * ratio,
                topAlpha,
                bottomAlpha,
            )
        }.getOrNull()

        /** 压到预算内：900×400 的渐变底是 36 万像素 = 1.4MB，够 launcher 丢掉的了 */
        internal fun capPixels(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
            val px = bmp.width.toLong() * bmp.height
            if (px <= BG_MAX_PX) return bmp
            val s = kotlin.math.sqrt(BG_MAX_PX.toFloat() / px)
            return android.graphics.Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * s).toInt().coerceAtLeast(1),
                (bmp.height * s).toInt().coerceAtLeast(1),
                true,
            )
        }

        /** 底色永远给不透明版本：宁可深一点，也不能让桌面卡片变透明 */
        private fun opaque(color: Int): Int {
            val a = android.graphics.Color.alpha(color)
            return if (a >= 0xF0) color else android.graphics.Color.argb(
                0xF7, android.graphics.Color.red(color),
                android.graphics.Color.green(color), android.graphics.Color.blue(color),
            )
        }

        /** 按目标宽高做 centerCrop；**不 recycle 源图**（可能来自共享缓存）。 */
        private fun scaleToFill(src: android.graphics.Bitmap, w: Int, h: Int): android.graphics.Bitmap {
            val s = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
            val sw = (src.width * s).toInt().coerceAtLeast(1)
            val sh = (src.height * s).toInt().coerceAtLeast(1)
            val scaled = if (sw == src.width && sh == src.height) src
            else android.graphics.Bitmap.createScaledBitmap(src, sw, sh, true)
            val dx = ((scaled.width - w) / 2).coerceAtLeast(0)
            val dy = ((scaled.height - h) / 2).coerceAtLeast(0)
            return android.graphics.Bitmap.createBitmap(
                scaled, dx, dy, minOf(w, scaled.width), minOf(h, scaled.height),
            )
        }

        /** 课表导入成功后 / App 打开时调一次：小组件立刻跟着变，不用等 30 分钟。 */
        fun refresh(ctx: Context) {
            // 先在后台把帧备好（换主题的那几秒就是花在这儿），再让 provider 刷新：
            // provider 进来时帧已经在内存/磁盘里，桌面立刻就是新画面。
            // ① 当前进程里直接把新卡片推给桌面（不赌广播：MIUI 会拦自家后台广播，
            //    用户看到的就是"换了主题没有反应"）；
            // ② 广播再发一次，兜住"进程刚好被系统拉起"的情况。
            CoroutineScope(Dispatchers.IO).launch {
                // ① 先立刻推一版，不等预热 —— 换主题后桌面应该几百毫秒内就变
                //    （用户 2026-10-19："小控件切换主题后的显示不够实时"）。
                runCatching {
                    val mgr = AppWidgetManager.getInstance(ctx)
                    val ids = mgr.getAppWidgetIds(ComponentName(ctx, TodayWidgetProvider::class.java))
                    if (ids.isNotEmpty()) render(ctx, ids, null)
                }
                // ② 再把帧/底图预热好，推第二版（动态主题的首帧需要解码，慢一点，但第二版更稳）
                runCatching { warmFrames(ctx) }
                runCatching {
                    val mgr = AppWidgetManager.getInstance(ctx)
                    val ids = mgr.getAppWidgetIds(ComponentName(ctx, TodayWidgetProvider::class.java))
                    if (ids.isNotEmpty()) render(ctx, ids, null)
                }
            }
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
                    val now = SystemClock.elapsedRealtime()
                    val fresh = cachedModel?.takeIf { now - cachedAtMs < 12_000L }
                    val model = fresh ?: run {
                        val day = java.time.LocalDate.now().toString()
                        val plans = runCatching {
                            app.db.dao().plansOnce(day).map { it.toPlanNow() }
                        }.getOrDefault(emptyList())
                        buildWidgetModel(loadToday(app.db), MAX_ROWS, plans).also {
                            cachedModel = it
                            cachedAtMs = now
                        }
                    }
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

        private fun views(
            ctx: Context,
            mgr: AppWidgetManager,
            widgetId: Int,
            model: WidgetModel,
            specOverride: String? = null,
        ): RemoteViews {
            val (w, h) = sizePx(ctx, mgr, widgetId)
            return viewsSized(ctx, model, w, h, specOverride)
        }

        /**
         * 尺寸定下来之后的全部绘制（2026-10-19 拆出来）。
         *
         * 「小组件自检」以前直接调 `themeViews` —— 那条路只贴主题、不填文字，于是自检里
         * 永远是一个没有文字的黑框，用户据此判断「小组件还是没有主题」。现在自检和桌面
         * 走**同一个** [viewsSized]，自检里看到的就是桌面上的样子。
         */
        fun viewsSized(
            ctx: Context,
            model: WidgetModel,
            w: Int,
            h: Int,
            specOverride: String?,
        ): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_today)
            val theme = WidgetThemes.of(specOverride ?: WidgetThemes.spec(ctx))
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

            // 尺寸自适应：RemoteViews 不能测量，只能按 launcher 报的格子尺寸算。
            // 不同 ROM 的格子差很多（MIUI 4×2≈155dp，原生 4×2≈170dp，某些桌面 2×2 只有 110dp），
            // 所以**宽、高各分档**，不是只砍行。
            val density = ctx.resources.displayMetrics.density
            val hDp = h / density
            val wDp = w / density

            // ① 高度：砍行 + 砍页脚
            val maxVisible = rowsFor(usableHDp(hDp))
            rowIds.drop(maxVisible).forEach { rv.setViewVisibility(it, View.GONE) }
            if (maxVisible == 0) {
                rv.setViewVisibility(R.id.widget_divider, View.GONE)
                rv.setViewVisibility(R.id.widget_meta, View.GONE)
            }
            // 没课时不画那条横线：线上方是"今天没有课"，线下面什么都没有，看着像没渲染完
            if (model.rows.isEmpty()) rv.setViewVisibility(R.id.widget_divider, View.GONE)
            if (usableHDp(hDp) < 132f) rv.setViewVisibility(R.id.widget_foot, View.GONE)

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

            applyTheme(ctx, rv, w, h, specOverride)

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
