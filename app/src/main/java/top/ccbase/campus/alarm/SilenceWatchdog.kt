package top.ccbase.campus.alarm

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * 静音状态的看门狗（15 分钟一次，系统调度器托管）。
 *
 * ## 为什么必须有它
 *
 * 2026-10-21 用户报「下课后静音没有自动恢复」。根因已经修了（恢复铃声那几条闹钟之前被
 * 60 条上限整批截掉，见 [top.ccbase.campus.domain.fitActions]），但**只靠闹钟仍然是单点**：
 *  · 国产 ROM 清后台时会连带清掉我们自己排的闹钟（HyperOS 尤其爱干这个）；
 *  · 用户手动"强行停止"过 App；
 *  · 系统在省电模式下延后 setAlarmClock 的唤醒。
 * 这三种情况下"恢复铃声"那条闹钟就是不来了 —— 手机静着过一整天，漏掉的可能是家里打来的电话。
 *
 * 所以加这一层：**不依赖任何自定义闹钟**，由系统的 JobScheduler 定期把我们叫起来，
 * 检查一次"我们是不是还按着静音、而这一段早该结束了"。真实的静音/恢复仍然只由
 * [Silence] 一个人做决定，这里只负责"该恢复的时候一定要有人来恢复"。
 *
 * ## 为什么用 JobScheduler 而不是再排一个 AlarmManager 闹钟
 *
 * 同一种机制出问题会一起出问题。JobScheduler 是系统自己养的任务队列：
 *  · `setPersisted(true)` + RECEIVE_BOOT_COMPLETED → 重启后自动还在，不用我们再补；
 *  · 周期任务由系统合并唤醒，比 15 分钟一条精确闹钟省电得多（精确闹钟会唤醒整机）；
 *  · 跑任务期间进程**不会被冻结**（这也是更新下载兜底用的同一条性质）。
 *
 * 15 分钟是系统允许的最小周期。意味着最坏情况下铃声会晚 15 分钟恢复 ——
 * 明确写在界面文案里，不假装是"立刻"。
 */
class SilenceWatchdog : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val ctx = applicationContext
        Thread({
            try {
                runOnce(ctx)
            } catch (t: Throwable) {
                Log.w(TAG, "看门狗巡检失败", t)
            } finally {
                jobFinished(params, false)
            }
        }, "silence-watchdog").start()
        return true     // 有工作在跑：跑完自己调 jobFinished
    }

    /** 系统要求重试（进程被杀）—— 让它重来一次，巡检是幂等的 */
    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val TAG = "SilenceWatchdog"

        /**
         * 巡检一次（抽出来是为了能在单测里直接跑，不用造一个 [JobParameters]）。
         *
         * @return 这次是否真的动手恢复了铃声
         */
        fun runOnce(ctx: Context): Boolean {
            val healed = runCatching { Silence.healIfStuck(ctx) }.getOrDefault(false)
            if (healed) {
                Log.i(TAG, "看门狗：发现铃声还静着但早该结束，已恢复")
                DiagLog.append(
                    ctx,
                    Attempt(DiagLog.now(), "看门狗恢复", true, "闹钟丢了，靠定时巡检把铃声还回来"),
                )
                runCatching {
                    Notify.post(
                        ctx,
                        "🔔 已恢复铃声",
                        "之前那段静音本该结束，铃声已还回来（定时巡检补的）",
                    )
                }
            }
            // 闹钟被系统清掉 / 过期没续：顺手重排一次。只在"真的没有未来闹钟"时做，
            // 每 15 分钟全量重排既没必要也会让系统把我们当成滥用。
            if (NudgePrefs.anyEnabled(ctx) && AlarmScheduler.isStale(ctx)) {
                Log.i(TAG, "看门狗：没排上未来的闹钟，重排一次")
                runCatching { Rescheduler.request(ctx, reason = "watchdog") }
            }
            return healed
        }
        private const val JOB_ID = 7301
        private const val INTERVAL_MS = 15 * 60 * 1000L

        /**
         * 保证看门狗在跑。App 每次启动都调一次（幂等：同 id 再排等于更新参数）。
         *
         * 不主动调 [JobScheduler.cancel] —— 关掉自动静音时留着它也无害（它会发现没有静音标记、
         * 也不重排闹钟），反而省掉"关了又开"时的一堆边界。
         */
        fun ensure(ctx: Context) {
            runCatching {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, SilenceWatchdog::class.java))
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)                 // 重启后系统自己接着排
                    .setRequiresBatteryNotLow(false)
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .build()
                js.schedule(info)
            }.onFailure { Log.w(TAG, "排不上静音看门狗（不影响闹钟本身）", it) }
        }

        /** 自检页用：这个任务排上了没有 */
        fun scheduled(ctx: Context): Boolean = runCatching {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.allPendingJobs.any { it.id == JOB_ID }
        }.getOrDefault(false)
    }
}
