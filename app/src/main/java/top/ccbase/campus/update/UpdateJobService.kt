package top.ccbase.campus.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * 后台静默更新的"保命壳"（系统 JobScheduler）。
 *
 * ## 为什么前台服务还不够
 *
 * 2026-10-21 用户报「有些用户下载新更新时并没有做到后台下载」。前台服务本身是对的，
 * 但它有一个绕不过去的门槛：**Android 12+ 不许后台应用启动前台服务**。
 * 静默更新是**闹钟**叫起来的（那时 App 在后台），于是：
 *  · 用户没把本应用加进电池白名单 → `startForegroundService` 被系统拒 → 退回进程内下载；
 *  · 进程一进缓存态就被冻结（socket 不再有数据，看起来"卡死"），国产 ROM 更直接清后台。
 * 结果就是"更新下到一半没了"，用户得重下 29MB。
 *
 * ## 这一层的性质
 *
 * JobScheduler 的任务在**系统托管的执行窗口**里跑：任务没结束前进程不会被冻结。
 * 所以这里不再要求"起得了一个前台服务"，只要求"这件事有人在跑"。
 * 上限是系统给的（一次性任务大约 10 分钟），超过就靠 `.part` 断点，下次接着下 ——
 * 这一点是**明确接受**的取舍，不假装无限长。
 *
 * 用法：静默更新开启时 [ensure] 排一个 6 小时的周期任务（`setPersisted` 重启后还在）；
 * 到点由系统拉起本服务，跑一次检查/续传，等到下载结束（或超时）再收工。
 */
class UpdateJobService : JobService() {

    @Volatile
    private var cancelled = false

    override fun onStartJob(params: JobParameters?): Boolean {
        cancelled = false
        val ctx = applicationContext
        Thread({
            try {
                // 检查和"开下"这两步必须在**本任务的线程**里跑完（见类注释）
                AutoUpdate.runBody(ctx, reason = "job")
                // 下载本身在 UpdateGate 的线程里；这里等到它收尾为止，
                // 好让 JobScheduler 一直护着这个进程。
                val deadline = System.currentTimeMillis() + HOLD_MS
                while (!cancelled && System.currentTimeMillis() < deadline &&
                    UpdateGate.state is UpdateGate.S.Working
                ) {
                    Thread.sleep(1_000)
                }
                if (UpdateGate.state is UpdateGate.S.Working) {
                    // 超时：剩下的部分是半包，下一次（闹钟/进 App/下个周期任务）接着下
                    Log.i(TAG, "任务窗口用完，下载留到下次续传")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "静默更新任务失败", t)
            } finally {
                runCatching { jobFinished(params, false) }
            }
        }, "update-job").start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        cancelled = true
        // 系统收走任务（多半是省电/超时）：我们的下载是被动的，不要求立即重排
        return false
    }

    companion object {
        private const val TAG = "UpdateJobService"
        private const val JOB_ID = 7302

        /** 6 小时一次，和闹钟那条路同频 */
        private const val PERIOD_MS = 6 * 60 * 60 * 1000L

        /** 一个任务窗口里最多占这么久（系统给一次性任务约 10 分钟，留点余量） */
        private const val HOLD_MS = 9 * 60 * 1000L

        fun ensure(ctx: Context) {
            if (!AutoUpdate.enabled(ctx)) return
            runCatching {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJobService::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)                     // 重启后系统自己接着排
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    // 只在真需要时跑：Wi-Fi 门槛在 AutoUpdate 里判（这里不硬卡，
                    // 否则用户开了"允许用流量更新"时任务永远不跑）
                    .setRequiresBatteryNotLow(false)
                    .build()
                js.schedule(info)
            }.onFailure { Log.w(TAG, "排不上静默更新任务（闹钟那条路还在）", it) }
        }

        fun cancel(ctx: Context) {
            runCatching {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                js.cancel(JOB_ID)
            }
        }

        /** 自检页用 */
        fun scheduled(ctx: Context): Boolean = runCatching {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.allPendingJobs.any { it.id == JOB_ID }
        }.getOrDefault(false)
    }
}
