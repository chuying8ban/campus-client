package top.ccbase.campus.widget

import top.ccbase.campus.domain.TodayView
import java.time.LocalDate

/**
 * 桌面小组件要显示的那几行字 —— 纯函数，不碰 Android，能直接单测。
 *
 * 2026-10-18 重排版（用户原话："太简陋了"）：原来是一行行平铺的句子，桌面上一块黑，
 * 信息没有层次。现在拆成四级：
 *   状态（正在上课 / 下一节 / 今天）→ 课程名（唯一的大字）→ 时间·地点 → 后几节 → 页脚。
 * 每条信息各占一个字段，怎么摆由 XML 决定；这里只负责"说什么"。
 */
data class WidgetRow(val time: String, val name: String, val room: String)

data class WidgetModel(
    /** 顶部状态徽标：「正在上课」「下一节」「今天」「已上完」 */
    val status: String,
    /** 日期行：「10月19日 周一」 */
    val dateLine: String,
    /** 教学周：「第8周」；无学期时为空 */
    val week: String,
    /** 大字：课程名；没课时是一句话 */
    val headline: String,
    /** 时间 · 地点：`10:10–11:45 · 教A301`；没课时给一句提示 */
    val meta: String,
    val rows: List<WidgetRow>,
    val footer: String,
)

fun buildWidgetModel(view: TodayView, maxRows: Int = 3): WidgetModel {
    val date = runCatching { LocalDate.parse(view.date) }.getOrNull()
    val dateLine = buildString {
        if (date != null) append("${date.monthValue}月${date.dayOfMonth}日 ")
        append(view.weekdayCn)
    }
    val week = if (view.weekNo > 0) "第${view.weekNo}周" else ""

    val now = view.classes.firstOrNull { it.status == "now" }
    val next = view.classes.firstOrNull { it.status == "todo" }
    val focus = now ?: next
    val allDone = view.classes.isNotEmpty() && focus == null

    val status = when {
        now != null -> "正在上课"
        next != null -> "下一节"
        allDone -> "已上完"
        else -> "今天"
    }
    val headline = when {
        focus != null -> focus.name
        view.classes.isEmpty() -> "今天没有课"
        else -> "今天的课都上完了"
    }
    val meta = when {
        focus != null -> listOfNotNull(focus.timeText, focus.room?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        view.classes.isEmpty() -> "好好休息，或者去图书馆坐坐"
        else -> "出门走走，别一直坐着"
    }

    // 后几节：焦点之后还没上的课（不含焦点本身），最多 maxRows 条
    val rest = view.classes.filter { it !== focus && it.status != "done" }.take(maxRows)
    val rows = rest.map { WidgetRow(time = it.timeText, name = it.name, room = it.room.orEmpty()) }

    val upcoming = view.classes.count { it.status == "now" || it.status == "todo" }
    val hidden = (upcoming - if (focus != null) 1 else 0).coerceAtLeast(0) - rows.size
    val footer = when {
        view.classes.isEmpty() && view.tasks.isNotEmpty() -> "还有 ${view.tasks.size} 件任务"
        hidden > 0 -> "还有 $hidden 节"
        else -> "点开看完整课表"
    }
    return WidgetModel(status, dateLine, week, headline, meta, rows, footer)
}
