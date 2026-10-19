package top.ccbase.campus.widget

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 让桌面小组件**真的动起来**。
 *
 * 背景（2026-10-19 用户："小组件的动画仍然是卡顿的，一点也不流畅"）：
 * 小组件是 `RemoteViews`，系统只在 `onUpdate`（最短 30 分钟一次）时给它新内容，
 * 所以两次刷新之间它是一张死图 —— 4 帧主题看起来"卡顿"，本质是**几乎不动**。
 * `RemoteViews` 做不到 60 帧（每帧都要跨进程传位图 + 桌面自己还会节流）；
 * 真 60 帧只有动态壁纸（见 `DynWallpaperService`）。
 *
 * 这条通道是把"几乎不动"抬到"能动"：屏幕亮着时每 [STEP_MS] 推一帧，
 * 熄屏/到点立即停，不常驻、不占唤醒锁。
 *
 * 代价与取舍：
 * - 只压 4 帧的位图引用（同一组位图，不重新渲染），每次只换 `setImageViewBitmap`；
 * - 连续最多跑 [MAX_RUN_MS]（默认 10 分钟），之后交回系统 30 分钟节奏，避免耗电；
 * - 进程被系统回收就自然停，不追求"永远在动"。
 */
object WidgetTicker {

    // 一帧的步长默认 = WidgetFrames.STEP_SEC（约 0.32s），推得比这更稀就会跳帧
    private const val STEP_MS = 250L
    // 2026-10-19 用户："动图不动" —— 原来 5 分钟就停，之后要等下一次熄屏/亮屏才重新开始，
    // 看起来就是"看着看着不动了"。抬到 20 分钟（熄屏仍然立即停，不常驻、不占唤醒锁）。
    private const val MAX_RUN_MS = 20 * 60 * 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var startedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.elapsedRealtime() - startedAt > MAX_RUN_MS) {
                stop()
                return
            }
            // 用户正在用 App 时桌面不可见，推帧纯属白烧（每秒 4 次主线程 + 跨进程）。
            // 这不是"省电优化"，是他明确感受到的卡顿来源。
            if (AppForeground.visible) {
                handler.postDelayed(this, STEP_MS)
                return
            }
            val ctx = appCtx ?: run { stop(); return }
            // 动态主题现在是**桌面自己轮播**（布局里 autoStart + flipInterval + 交叉淡入）。
            // 我们再推一次 RemoteViews，就等于把 ViewFlipper 打回第 0 帧、动画从头开始 ——
            // 用户看到的就是"一顿一顿地跳"。所以动态主题期间这条通道彻底让开。
            val spec = runCatching { WidgetThemes.spec(ctx) }.getOrDefault("")
            if (WidgetFrames.isDynamic(spec)) {
                handler.postDelayed(this, 1000L)
                return
            }
            runCatching {
                ctx.sendBroadcast(
                    Intent(ctx, TodayWidgetProvider::class.java)
                        .setAction(TodayWidgetProvider.ACTION_TICK)
                )
            }
            handler.postDelayed(this, STEP_MS)
        }
    }

    private var appCtx: Context? = null

    /** 屏幕亮起时调用；重复调用无副作用 */
    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (running) {
            startedAt = SystemClock.elapsedRealtime()   // 亮的次数多，重新计时
            return
        }
        running = true
        startedAt = SystemClock.elapsedRealtime()
        handler.postDelayed(tick, STEP_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }
}

/**
 * 只做一件事：盯着屏幕开关。注册在 `Application.onCreate`，
 * 熄屏即停 —— 不能因为"桌面好看"在口袋里一直烧电。
 */
class ScreenWatcher : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> WidgetTicker.start(ctx)
            Intent.ACTION_SCREEN_OFF -> WidgetTicker.stop()
        }
    }

    companion object {
        fun register(app: Application) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(Companion.INSTANCE, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    app.registerReceiver(Companion.INSTANCE, filter)
                }
            }
        }

        private val INSTANCE = ScreenWatcher()
    }
}
