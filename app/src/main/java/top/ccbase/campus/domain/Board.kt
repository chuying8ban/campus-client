package top.ccbase.campus.domain

import top.ccbase.campus.data.local.ChecklistItem
import top.ccbase.campus.data.local.Milestone
import top.ccbase.campus.data.local.Resource
import top.ccbase.campus.data.local.StudyStep
import top.ccbase.campus.data.local.Task
import java.time.LocalDate

/**
 * 看板页的纯逻辑：连续天数 + 总览统计 + 里程碑/清单排序。
 *
 * `streak` 照搬网页版 db.streak() 的口径（网页版是同一套规则的 Python 实现）：
 *   ① 有专注记录（minutes>0）或有任务完成的那些天算"活跃"
 *   ② **今天还没开始不算断** —— 从昨天往前数
 *   ③ 遇到第一个空缺就停
 * 第②条最容易被"优化"掉：如果改成"必须今天也活跃"，用户每天早上打开
 * App 都会看到连续天数归零，那个挫败感足以让人卸载。
 */

/** 活跃天集合 → 连续天数 */
fun streak(activeDays: Set<String>, today: LocalDate): Int {
    var n = 0
    var cur = today
    if (cur.toString() !in activeDays) cur = cur.minusDays(1)   // 今天还没开始，不算断
    while (cur.toString() in activeDays) {
        n++
        cur = cur.minusDays(1)
    }
    return n
}

data class BoardStats(
    val taskTotal: Int,
    val activeTasks: Int,
    val stepsDone: Int,
    val stepsTotal: Int,
    val resourceTotal: Int,
    val streak: Int,
    /** 有步骤的任务里，已经全部打完勾的数量 —— 比"步骤数"更能说明推进 */
    val tasksFinished: Int,
)

fun boardStats(
    tasks: List<Task>,
    steps: List<StudyStep>,
    resources: List<Resource>,
    activeDays: Set<String>,
    today: LocalDate,
): BoardStats {
    val byTask = steps.groupBy { it.task_id }
    return BoardStats(
        taskTotal = tasks.size,
        activeTasks = tasks.count { it.active == 1 },
        stepsDone = steps.count { it.done_day != null },
        stepsTotal = steps.size,
        resourceTotal = resources.size,
        streak = streak(activeDays, today),
        tasksFinished = byTask.count { (_, list) -> list.isNotEmpty() && list.all { it.done_day != null } },
    )
}

/**
 * 里程碑按 sort 排 —— 这就是一条"四年时间线"，
 * 所以顺序绝不能靠字符串或 id 排，必须用数据里作者写好的 sort。
 */
fun orderedMilestones(rows: List<Milestone>): List<Milestone> =
    rows.sortedWith(compareBy({ it.sort }, { it.id }))

fun orderedChecklist(rows: List<ChecklistItem>): List<ChecklistItem> =
    rows.sortedWith(compareBy({ it.sort }, { it.id }))
