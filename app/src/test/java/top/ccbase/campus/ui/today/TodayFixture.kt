package top.ccbase.campus.ui.today

import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Meta
import top.ccbase.campus.data.local.Task
import top.ccbase.campus.data.remote.PlanApplier

/**
 * 今日页那些界面用例的**确定性夹具**。
 *
 * 为什么必须有它（2026-09-19 核实）：`loadToday` 只在
 * `meta['plan_source'] == "remote"`（"这是这位同学自己同步下来的计划"）时才把任务
 * 算成今日/待办（见 TodayLoader.kt 第 23 行），而内置种子的 meta 里**根本没有**
 * 这个键 —— 于是 `loadToday(...).tasks` 恒为空，几条用例靠 `assumeTrue` 整条跳过。
 *
 * **跳过 = 没验**：闸门上是"绿"的，实际这几条一次都没跑过。所以夹具在，跳过就不在。
 *
 * 落一条 `cadence = "daily"` 的任务：`computeToday` 里 daily 与星期几、第几周**都无关**，
 * 无条件进"今日任务" —— 所以随便哪天跑都成立，不再看日历。同时把 plan_source 设成
 * remote（App 正常同步完课表就是这个状态），任务才真的被算进来。
 *
 * ⚠️ 用例侧不许再用 `assumeTrue` / `if (xxx == null) return` 兜底：查不到夹具任务就
 * **断言失败** —— 夹具坏了必须红，红了才知道要修夹具。
 */
object TodayFixture {

    /** 用 9xxxxx 段，避开种子（1 号段）与同步下来的数据 */
    const val TASK_ID = 990001

    const val TITLE = "夹具任务：今天要做的这一条"

    /** 落夹具，返回任务 id */
    suspend fun seed(db: CampusDb): Int {
        db.dao().putMeta(listOf(Meta(PlanApplier.K_SOURCE, PlanApplier.REMOTE)))
        db.dao().putTasks(
            listOf(
                Task(
                    id = TASK_ID,
                    phase = "夹具",
                    phase_order = -1,
                    title = TITLE,
                    track = "自学",
                    priority = 0,
                    cadence = "daily",
                    active = 1,
                    sort = -1000,
                ),
            ),
        )
        return TASK_ID
    }
}
