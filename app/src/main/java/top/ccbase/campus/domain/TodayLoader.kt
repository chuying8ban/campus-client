package top.ccbase.campus.domain

import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.remote.PlanApplier
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

/**
 * 从本地库把「今日」所需的东西取出来，交给纯函数算。
 *
 * 单独抽出来是为了它也能被测 —— 纯函数是核心，但"取哪些数据、取多少天"
 * 同样是会出错的地方（取少了会让"本周做过"判断错误）。
 */
suspend fun loadToday(db: CampusDb, now: LocalDateTime = LocalDateTime.now()): TodayView {
    val dao = db.dao()
    // 完成记录只需近 40 天：够算"今天"和"本周"；全表拉会随时间越来越慢
    val from = now.toLocalDate().minusDays(40).toString()
    return computeToday(
        slots = dao.slots().first(),
        courses = dao.courses().first(),
        selfstudy = dao.selfStudy().first(),
        // 只认「你自己的计划」：不是 remote 就当没有计划（理由同 TasksScreen.rememberTasksSections）
        tasks = if (dao.metaGet(PlanApplier.K_SOURCE) == PlanApplier.REMOTE) dao.visibleTasks().first() else emptyList(),
        done = dao.doneSince(from),
        semesterStart = dao.metaGet("semester_start") ?: "2026-08-31",
        semesterName = dao.metaGet("semester_name"),
        termWeeks = dao.metaGet("term_weeks")?.toIntOrNull() ?: 20,
        now = now,
    )
}
