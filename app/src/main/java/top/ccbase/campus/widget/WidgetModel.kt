package top.ccbase.campus.widget

import top.ccbase.campus.domain.PlanNow
import top.ccbase.campus.domain.TodayView
import java.time.LocalDate
import java.time.LocalTime

/**
 * 桌面小组件要显示的那几行字 —— 纯函数，不碰 Android，能直接单测。
 *
 * 2026-10-18 重排版（用户原话："太简陋了"）：原来是一行行平铺的句子，桌面上一块黑，
 * 信息没有层次。现在拆成四级：
 *   状态（正在上课 / 下一节 / 今天）→ 课程名（唯一的大字）→ 时间·地点 → 后几节 → 页脚。
 * 每条信息各占一个字段，怎么摆由 XML 决定；这里只负责"说什么"。
 *
 * 2026-10-18 晚补（用户：早晚自习也要放进小组件；自建日程要和课程区分开）：
 * 三样东西进同一条时间线，靠 [WidgetRow.kind] 区分 —— 课程 course / 自习 self / 日程 plan。
 * 小组件里日程用强调色 + "日程"前缀，课程保持正文色，谁也别挤谁。
 */
data class WidgetRow(
    val time: String,
    val name: String,
    val room: String,
    /** course（课）/ self（自习）/ plan（自己的日程） */
    val kind: String = KIND_COURSE,
) {
    companion object {
        const val KIND_COURSE = "course"
        const val KIND_SELF = "self"
        const val KIND_PLAN = "plan"
    }
}

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

/** 时间线里的一项（课程 / 自习 / 日程）。内部用，只为排序和挑焦点。 */
private data class Entry(
    val startMin: Int,
    val endMin: Int,
    val time: String,
    val name: String,
    val place: String,
    val kind: String,
    val status: String, // now / todo / done
    val order: Int,
)

private fun toMin(hhmm: String): Int {
    val t = hhmm.trim()
    val h = t.substringBefore(":").toIntOrNull() ?: return -1
    val m = t.substringAfter(":", "0").take(2).toIntOrNull() ?: 0
    return h * 60 + m
}

/** `08:00-09:35` / `08:00–09:35` 两种连接符都认 */
private fun splitRange(text: String): Pair<String, String> {
    val norm = text.replace('–', '-')
    return norm.substringBefore('-').trim() to norm.substringAfter('-', "").trim()
}

fun buildWidgetModel(
    view: TodayView,
    maxRows: Int = 3,
    plans: List<PlanNow> = emptyList(),
    nowClock: LocalTime = LocalTime.now(),
): WidgetModel {
    val date = runCatching { LocalDate.parse(view.date) }.getOrNull()
    val dateLine = buildString {
        if (date != null) append("${date.monthValue}月${date.dayOfMonth}日 ")
        append(view.weekdayCn)
    }
    val week = if (view.weekNo > 0) "第${view.weekNo}周" else ""
    val nowMin = nowClock.hour * 60 + nowClock.minute

    val entries = ArrayList<Entry>()

    view.classes.forEachIndexed { i, c ->
        val (s, e) = splitRange(c.timeText)
        entries += Entry(
            startMin = toMin(s), endMin = toMin(e),
            time = c.timeText, name = c.name,
            place = c.room.orEmpty(),
            kind = WidgetRow.KIND_COURSE, status = c.status, order = i,
        )
    }
    view.selfstudy.forEachIndexed { i, sd ->
        entries += Entry(
            startMin = toMin(sd.start), endMin = toMin(sd.end),
            time = "${sd.start}–${sd.end}",
            name = when (sd.kind) {
                "morning" -> "早自习"
                "evening" -> "晚自习"
                else -> "自习"
            },
            place = sd.place.orEmpty(),
            kind = WidgetRow.KIND_SELF, status = sd.status, order = 100 + i,
        )
    }
    plans.forEachIndexed { i, p ->
        val s = toMin(p.start)
        val e = toMin(p.end.ifBlank { p.start })
        val st = when {
            nowMin in s until e -> "now"
            nowMin < s -> "todo"
            else -> "done"
        }
        entries += Entry(
            startMin = s, endMin = e,
            time = if (p.end.isBlank()) p.start else "${p.start}–${p.end}",
            name = p.title, place = p.note.orEmpty(),
            kind = WidgetRow.KIND_PLAN, status = st, order = 200 + i,
        )
    }

    entries.sortWith(compareBy({ it.startMin }, { it.order }))

    val nowEntry = entries.firstOrNull { it.status == "now" }
    val nextEntry = entries.firstOrNull { it.status == "todo" }
    val focus = nowEntry ?: nextEntry
    val anyCourse = view.classes.isNotEmpty()

    val status = when {
        nowEntry?.kind == WidgetRow.KIND_COURSE -> "正在上课"
        nowEntry != null -> if (nowEntry.kind == WidgetRow.KIND_PLAN) "日程进行中" else "自习中"
        nextEntry?.kind == WidgetRow.KIND_COURSE -> "下一节"
        nextEntry != null -> if (nextEntry.kind == WidgetRow.KIND_PLAN) "下一个日程" else "接下来自习"
        anyCourse -> "已上完"
        else -> "今天"
    }
    val headline = when {
        focus != null -> focus.name
        anyCourse -> "今天的课都上完了"
        else -> "今天没有课"
    }
    val meta = when {
        focus != null -> listOfNotNull(focus.time, focus.place.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        anyCourse -> "出门走走，别一直坐着"
        else -> "好好休息，或者去图书馆坐坐"
    }

    // 焦点之后的待办（不含焦点本身），最多 maxRows 条
    val rest = entries.filter { it !== focus && it.status == "todo" }.take(maxRows)
    val rows = rest.map { WidgetRow(time = it.time, name = it.name, room = it.place, kind = it.kind) }

    val upcoming = entries.count { it.status == "now" || it.status == "todo" }
    val hidden = (upcoming - if (focus != null) 1 else 0).coerceAtLeast(0) - rows.size
    // 任务系统已经取消（用户确认不再用），小组件不许再冒出"还有 N 件任务"。
    val footer = if (hidden > 0) "还有 $hidden 项" else "点开看完整课表"

    return WidgetModel(status, dateLine, week, headline, meta, rows, footer)
}
