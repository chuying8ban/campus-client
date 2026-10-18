package top.ccbase.campus.widget

import top.ccbase.campus.domain.TodayView
import java.time.LocalDate

/**
 * 桌面小组件要显示的那几行字 —— 纯函数，不碰 Android，能直接单测。
 *
 * 取舍：桌面这块地方小，**不做完整课表**，只回答"今天还有什么课"：
 *  · 顶部一行：日期 + 周几 + 教学周；
 *  · 中间一行大字：正在上的那节，或下一节，都没有就说清楚"今天没有课 / 都上完了"；
 *  · 再往下最多三节，超出就写"还有 N 节"。
 */
data class WidgetRow(val time: String, val name: String, val room: String)

data class WidgetModel(
    val dateLine: String,
    val headline: String,
    val rows: List<WidgetRow>,
    val footer: String,
)

fun buildWidgetModel(view: TodayView, maxRows: Int = 3): WidgetModel {
    val date = runCatching { LocalDate.parse(view.date) }.getOrNull()
    val dateLine = buildString {
        if (date != null) append("${date.monthValue}月${date.dayOfMonth}日 ")
        append(view.weekdayCn)
        if (view.weekNo > 0) append(" · 第${view.weekNo}周")
    }

    val now = view.classes.firstOrNull { it.status == "now" }
    val next = view.classes.firstOrNull { it.status == "todo" }
    val headline = when {
        now != null -> "正在上　${now.name}　${now.timeText}"
        next != null -> "下一节　${next.timeText}　${next.name}"
        view.classes.isEmpty() -> "今天没有课"
        else -> "今天的课都上完了"
    }

    val rows = view.classes.take(maxRows).map {
        WidgetRow(time = it.timeText, name = it.name, room = it.room.orEmpty())
    }
    val footer = when {
        view.classes.isEmpty() && view.tasks.isNotEmpty() -> "还有 ${view.tasks.size} 件任务 · 点开看"
        view.classes.size > maxRows -> "还有 ${view.classes.size - maxRows} 节 · 点开看全部"
        else -> "点开看完整课表"
    }
    return WidgetModel(dateLine, headline, rows, footer)
}
