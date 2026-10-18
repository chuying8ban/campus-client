package top.ccbase.campus.domain

import top.ccbase.campus.data.local.PlanEvent

/**
 * 用户自建的一条日程（纯数据，给小组件与界面共用）。
 *
 * 为什么不直接传 Room 实体：小组件模型是纯函数、要能单测，不能拖 Room 注解进来。
 */
data class PlanNow(
    val id: Int,
    val day: String,
    /** HH:mm */
    val start: String,
    /** HH:mm；空串 = 没设结束 */
    val end: String,
    val title: String,
    val note: String?,
    val done: Boolean,
)

fun PlanEvent.toPlanNow() = PlanNow(
    id = id,
    day = day,
    start = start,
    end = end,
    title = title,
    note = note,
    done = done != 0,
)
