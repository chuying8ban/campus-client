package top.ccbase.campus.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 闹钟到点 → 查一次 → 需要就弹通知。
 *
 * 为什么用 goAsync()：`onReceive` 返回后进程随时可能被杀，
 * 而这里要发一次网络请求（几百毫秒到几秒）。goAsync() 让系统知道"活还没干完"，
 * 干完必须调 `finish()`，否则系统会按"接收器卡死"处理。
 */
class GrabWatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GrabWatch.ACTION) return
        val appCtx = context.applicationContext
        if (!GrabWatch.isOn(appCtx)) return
        val pending = goAsync()
        Thread {
            try {
                GrabWatch.runOnce(appCtx)
            } catch (t: Throwable) {
                // 后台任务里任何异常都不该让 App 崩（用户看不到崩溃，只会觉得"提醒不灵")
                GrabWatch.note(appCtx, "提醒任务异常：${t.javaClass.simpleName}")
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }
}
