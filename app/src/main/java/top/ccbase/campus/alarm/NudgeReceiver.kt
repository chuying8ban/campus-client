package top.ccbase.campus.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import top.ccbase.campus.domain.fireRemind

/**
 * 闹钟到点被系统唤醒的组件 —— 「课前提醒」和「自动静音」都落在这里。
 *
 * 它**没有界面**：系统在后台把进程拉起来执行 onReceive()，干完就结束。
 * 这正是网页做不到这件事的根本原因（网页必须有个活着的页面、还得在亮屏）。
 *
 * 三种动作：
 *  * ACTION_REMIND    —— 课前提醒（通知）。**不需要任何系统权限**，人人可用。
 *                        如果用户顺手开了自动静音，就在同一条通知里带上结果，
 *                        而不是再弹一条"已静音"（锁屏上两条通知就是噪音）。
 *  * ACTION_SILENCE   —— 只开了静音、没开提醒时，静音本身也要说一声。
 *  * ACTION_UNSILENCE —— 下课恢复铃声。只静音不恢复 = 手机一直静音下去，
 *                        漏掉重要来电，比"忘了静音"更严重。
 */
class NudgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "校园"
        val body = intent.getStringExtra("body").orEmpty()
        val atMs = intent.getLongExtra("at", 0L)

        // 每一次实际触发都落一条记录（成功也记）。
        // 理由：用户没法目视、也没法连 logcat，"到底响没响"必须能从文件里读出来。
        val at = DiagLog.now()
        val label = { prefix: String -> if (body.isBlank()) prefix else "$prefix·$body" }

        when (intent.action) {
            AlarmScheduler.ACTION_REMIND -> {
                val left = if (atMs > 0L) (atMs - System.currentTimeMillis()) / 1000 else 60L
                val what = intent.getStringExtra("what").orEmpty().ifBlank { "上课" }
                // 用户顺手开了静音就在同一条通知里报结果（不再单发一条"已静音"）
                val wantSilence = NudgePrefs.silenceEnabled(context)
                val silFail = if (!wantSilence || Silence.silence(context)) null else failReason(context)
                val f = fireRemind(left, what, body, wantSilence, silFail, Notify.allowed(context))
                Notify.postRemind(context, f.title, f.body)
                DiagLog.append(context, Attempt(at, label("课前提醒"), f.ok, f.reason))
                Log.i(TAG, "课前提醒：${f.title} · ${f.body}")
            }

            AlarmScheduler.ACTION_SILENCE -> {
                val ok = Silence.silence(context)
                DiagLog.append(context, Attempt(at, label("课前静音"), ok, if (ok) "" else failReason(context)))
                if (ok) {
                    Notify.post(context, title, if (body.isEmpty()) "即将开始" else "$body 即将开始")
                    Log.i(TAG, "已静音：$body")
                } else {
                    // 权限没给是**可预期**的情况，不是崩溃：明确告诉用户去开
                    Notify.post(context, "没能自动静音", explainOr(failReason(context)))
                    Log.w(TAG, "静音失败：${failReason(context)}")
                }
            }

            AlarmScheduler.ACTION_UNSILENCE -> {
                val ok = Silence.unsilence(context)
                DiagLog.append(context, Attempt(at, label("下课恢复"), ok, if (ok) "" else failReason(context)))
                if (ok) {
                    Notify.post(context, title, if (body.isEmpty()) "已下课" else "$body 已下课")
                    Log.i(TAG, "已恢复铃声：$body")
                } else {
                    Notify.post(context, "没能恢复铃声", explainOr(failReason(context)))
                    Log.w(TAG, "恢复失败：${failReason(context)}")
                }
            }
        }
    }

    /** 失败原因要具体到"哪一项权限"，不是笼统的"失败了"。 */
    private fun failReason(context: Context): String {
        val canWrite = runCatching { Settings.System.canWrite(context) }.getOrDefault(false)
        return if (!canWrite) "no_write_settings" else "exception"
    }

    private fun explainOr(code: String): String = SilenceDiag.explain(code)

    private companion object {
        const val TAG = "NudgeReceiver"
    }
}

/**
 * 开机后重排闹钟。
 *
 * 为什么需要它：**设备重启会清空 AlarmManager 里所有闹钟**。
 * 网页 App 根本遇不到这个问题（它没有"系统闹钟"这个概念），
 * 但原生 App 必须自己处理 —— 这类"系统生命周期"是原生的日常开销。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // 抢课提醒（手机直接弹通知）：重启后系统会清空闹钟，这里补上。
        // 和下面提醒/静音是两套独立开关，所以放在那个判断**之前**。
        RetiredMonitorCleanup.cancel(context)
        if (!NudgePrefs.anyEnabled(context)) return
        // 真正的重排放在 Rescheduler 里（它要读数据库算窗口），这里只负责触发
        Rescheduler.request(context, reason = "boot")
    }
}
