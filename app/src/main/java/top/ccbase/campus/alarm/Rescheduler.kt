package top.ccbase.campus.alarm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.domain.nudgeWindows
import top.ccbase.campus.domain.planReminders
import top.ccbase.campus.domain.planSilence
import java.time.LocalDateTime

/**
 * 重排闹钟的统一入口。
 *
 * 什么时候需要重排（这几个时机漏一个，用户就会遇到"明明开了却没提醒"）：
 * ① App 打开时（设置可能被别的设备改过、系统可能清过闹钟）
 * ② 开关被切换时
 * ③ 提前量被改动时
 * ④ **设备重启后**（AlarmManager 会被清空）
 * ⑤ 课表更新后（每天刷课表，课变了闹钟必须跟着变）
 *
 * 排在"未来 7 天"而不是整个学期：原生闹钟数量有上限，
 * 而且课表本身会变 —— 排一小段、每次进 App 续上，比一次排满更稳。
 *
 * 课表来源是**用户自己**的那一份（Room 里的 slots/courses）——
 * 所以提醒天然是"每个人的自己的课表"，不需要服务器替谁代推。
 */
object Rescheduler {

    private const val TAG = "Rescheduler"
    private const val DAYS_AHEAD = 7

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun request(ctx: Context, reason: String) {
        val app = ctx.applicationContext as? CampusApplication ?: return
        scope.launch {
            try {
                app.seedJob.join()          // 首次启动时种子可能还在导，先等
                val db = app.db
                val remind = NudgePrefs.remindEnabled(app)
                val silence = NudgePrefs.silenceEnabled(app)
                if (!remind && !silence) {
                    AlarmScheduler.cancelAll(app)
                    Log.i(TAG, "($reason) 两个开关都是关的，已清掉所有闹钟")
                    return@launch
                }
                val slots = db.dao().slots().first()
                val courses = db.dao().courses().first()
                // 早晚自习也要提醒（每周固定：早自习 08:45、晚自习 20:30）——
                // 用户截图里最早的一条提醒就是"1 分钟后早自习"，漏了它等于漏掉一天的头一件事
                val selfstudy = db.dao().selfStudy().first()
                val semesterStart = db.dao().metaGet("semester_start") ?: "2026-08-31"
                val lead = NudgePrefs.leadMinutes(app)

                val nameOf = courses.associate { it.id to (it.short ?: it.name ?: "") }
                val windows = nudgeWindows(
                    slots = slots,
                    semesterStart = semesterStart,
                    from = LocalDateTime.now(),
                    daysAhead = DAYS_AHEAD,
                    leadMinutes = lead,
                    nameOf = { s -> nameOf[s.course_id] ?: "" },
                    selfstudy = selfstudy,
                )
                // 提醒按项排；静音按**合并后的时段**排 —— 自习时段会叠在一起，
                // 逐项排会出现"21:00 恢复铃声"卡在晚自习中间的错（手机后半程响着）
                val rem = planReminders(windows, remind)
                val acts = rem + planSilence(windows, silence, rem.map { it.at }.toSet())
                val n = AlarmScheduler.schedule(app, acts)
                Log.i(
                    TAG,
                    "($reason) 提醒=$remind 静音=$silence 窗口=${windows.size} 排了 $n 个闹钟，" +
                        "下一个 ${AlarmScheduler.ourNextAlarmText(app)}",
                )
            } catch (e: Exception) {
                // 排闹钟失败不该让 App 崩 —— 记日志，界面上的"下一个闹钟"会显示成"无"
                Log.e(TAG, "($reason) 重排失败：${e.javaClass.simpleName} ${e.message}", e)
            }
        }
    }
}
