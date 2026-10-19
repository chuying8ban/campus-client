package top.ccbase.campus.widget

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * "用户正在用 App"的最轻量判据。
 *
 * 为什么需要：小组件靠 [WidgetTicker] 每 250ms 推一帧来"动"。如果 App 在前台，
 * 桌面根本看不见，那些广播/RemoteViews 构建就全是白烧 —— **每秒 4 次主线程活**，
 * 用户感受到的就是"App 明显卡顿"（2026-10-19 的原话）。
 * 所以前台时不推帧，只保留 App 内预览自己的动画。
 */
object AppForeground {

    @Volatile
    var visible: Boolean = false
        private set

    private var started = 0

    fun register(app: Application) {
        val appCtx = app.applicationContext
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val wasAway = started == 0
                started++
                visible = true
                // 2026-10-19 用户："每次进入 app 在今日的最下面随机放一个（毒鸡汤），尽量不要重复"。
                // 放在这里而不是页面里：页面重组/tab 切换都不该换句子，只有"真的回到 App"才换一句。
                if (wasAway) runCatching { top.ccbase.campus.domain.Soup.advance(appCtx) }
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) visible = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
