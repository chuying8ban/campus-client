package top.ccbase.campus.domain

import java.time.LocalTime
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.PeriodTableStore
import top.ccbase.campus.data.local.Slot

/**
 * 周视图要用的纯逻辑 —— 单独抽出来，因为"某周上哪些课"这条规则
 * **今日页、列表、课前提醒、静默窗都要用**。四处各写一遍必然漂移，
 * 最后表现成"今日页说今天有课、课表页说这天没课"这种最难查的矛盾。
 *
 * 判定逻辑本身在 [Slot.inWeek]：有 `weeks`（精确周次）只认它，没有才退回区间。
 * 这条边界照搬网页版，不要"优化"：
 *   week_from / week_to 可能为 null → 当作 0 / 99，即"全学期都上"。
 *   写成 `week in week_from..week_to` 而不管 null，会在 Kotlin 里直接崩。
 */
fun slotsInWeek(slots: List<Slot>, week: Int): List<Slot> = slots.filter { it.inWeek(week) }

/** `"3,4,5,8,10,11"` → `{3,4,5,8,10,11}`；没写/写坏（解析出来是空的）返回 null = 退回区间 */
fun weekSet(weeks: String?): Set<Int>? {
    val raw = weeks?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val nums = raw.split(",", "，", " ")
        .mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() }?.toIntOrNull() }
        .filter { it > 0 }
        .toSet()
    return nums.ifEmpty { null }
}

/**
 * 周次文案（详情页用）：`{3,4,5,8,10,11}` → `3-5、8、10、11 周`。
 * 连续的周压成区间 —— 一行 17 个数字没人看，同学只想知道"哪几周要来"。
 */
fun weeksLabel(slot: Slot): String {
    val set = weekSet(slot.weeks) ?: run {
        val a = slot.week_from
        val b = slot.week_to
        return when {
            a == null && b == null -> "全学期"
            a == null -> "$b 周止"
            b == null -> "$a 周起"
            a == b -> "$a 周"
            else -> "$a-$b 周"
        }
    }
    val sorted = set.sorted()
    val parts = mutableListOf<String>()
    var start = sorted.first()
    var prev = start
    for (w in sorted.drop(1)) {
        if (w == prev + 1) { prev = w; continue }
        parts += if (start == prev) "$start" else "$start-$prev"
        start = w; prev = w
    }
    parts += if (start == prev) "$start" else "$start-$prev"
    return parts.joinToString("、") + " 周"
}

/** 课表的固定时间行（按开始时间排序去重）—— 同一时间多个班次不重复占行 */
/**
 * 周视图的一行 = 一个节次段（1-2、3-5 …）。行标签只写节次。
 */
data class GridRow(val start: Int, val end: Int, val label: String)

/**
 * 周视图的行，**按节次**排。
 *
 * 这里踩过一个真错误（用户截图发现的）：以前按 `time_text` 分行，而线上数据的
 * `time_text` 是「周一 1-2节」——**星期混在"时间"里**。于是每一行只对应一个课时：
 * 一行最多亮一个格子，整页成了一条斜线加四十来个空框，格子小到把「通用英语」
 * 压成四行、教室截成「A楼…」。
 *
 * 单测没抓住它，因为测试读的是 App 自带模板 seed，那里 `time_text` 是
 * 「09:30-11:05」——**和服务端产出的形状根本不是一回事**。教训：种子数据必须
 * 和线上形状一致，否则测试在替一个不存在的世界把关。
 *
 * 行只能由节次（p_start/p_end）定，星期是列。两种形状都有 p_start，
 * 所以这样写对两种都对。
 */
fun timeRows(slots: List<Slot>): List<GridRow> =
    slots.mapNotNull { s -> s.p_start?.let { it to (s.p_end ?: it) } }
        .distinct()
        .sortedBy { it.first }
        .map { (a, b) -> GridRow(a, b, if (a == b) "$a 节" else "$a-$b 节") }

/**
 * 一个时段的时钟起止。
 *
 * 顺序（2026-09-17 调过）：**先解析 time_text**，再查节次作息表。
 * 服务端现在把官方课表的 `startTime/endTime`（09:30-11:05）直接写进 time_text，
 * 那是教务自己的钟点，比任何本地换算都准；time_text 解析不出来（老数据是
 * 「周一 1-2节」）才退回 [top.ccbase.campus.data.local.PeriodTableStore] 里的作息表。
 *
 * 为什么这条兜底必须存在：`silenceWindows` 全靠它算课前静音与课后恢复。
 * 兜底断了，日志只会写「排了 0 个闹钟」—— 功能静默消失，最难发现。
 */
fun slotSpan(s: Slot): Pair<LocalTime, LocalTime>? {
    parseSpan(s.time_text)?.let { return it }
    val a = s.p_start ?: return null
    val (from, to) = PeriodTableStore.span(a, s.p_end ?: a) ?: return null
    return runCatching { LocalTime.parse(from) to LocalTime.parse(to) }.getOrNull()
}

/** HH:mm（与 time_text 里的时间格式一致，方便直接比较） */
fun hhmm(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

/** 显示用：优先「1-2 节 · 09:30-11:05」；没有时钟时间时只给节次 */
fun slotTimeLabel(s: Slot): String {
    val lab = slotLabel(s)
    val span = slotSpan(s) ?: return lab
    return lab + " · " + hhmm(span.first) + "-" + hhmm(span.second)
}

/**
 * 一个时段的显示文字（如「1-2 节」）。**不读 time_text**：模板 seed 和服务端
 * 给它的形状不一样，读它就等于把两个数据源的差异摊到界面上。
 */
fun slotLabel(s: Slot): String {
    val a = s.p_start ?: return s.time_text.orEmpty().trim()
    val b = s.p_end ?: a
    return if (a == b) "$a 节" else "$a-$b 节"
}

/** 整学期有哪些周有课（用于"这一周是不是空的"判断与周次范围提示） */
fun weeksWithClass(slots: List<Slot>): IntRange {
    val from = slots.map { it.week_from ?: 1 }.minOrNull() ?: 1
    val to = slots.map { it.week_to ?: 1 }.maxOrNull() ?: 1
    return from..to
}

/**
 * 「这一周上不上这个时段」——**全 App 唯一的判据**（今日页、周视图、列表、
 * 课前提醒、静默窗全走它）。两处各写一遍必然漂移，最后表现成
 * "今日页说今天有课、课表页说这天没课"这种最难查的矛盾。
 *
 * 判据优先级（2026-09-17 改过一次，之前只比区间）：
 *   ① `weeks` 有值 → **只认这个集合**。教务的周次不只是区间：
 *      `3~5,8~10(双),11`（程序设计基础（B）周五在 I区212）和 `6~7,9,12~16`（同一门课换到
 *      II区308机房）都是真实存在的。只比区间会告诉同学"第 6 周在 I区212"，
 *      他走过去才发现当天在机房 —— 课表"看着有课、地点是错的"比没显示更坑。
 *   ② 没有 `weeks`（老数据 / 内置种子）→ 退回区间判定，null 当 0 / 99（全学期）。
 */
fun Slot.inWeek(week: Int): Boolean {
    val set = weekSet(weeks)
    if (set != null) return week in set
    return (week_from ?: 0) <= week && week <= (week_to ?: 99)
}

/** 某周某天的课（今日页与周视图共用同一判据） */
fun slotsAt(slots: List<Slot>, week: Int, weekday: Int): List<Slot> =
    slotsInWeek(slots, week).filter { it.weekday == weekday }
        .sortedWith(compareBy({ it.p_start ?: 0 }, { it.sort }, { it.id }))

/** 课程配色：按课程 id 取稳定色，保证同一门课在今日页和周视图里颜色一致 */
fun courseColorIndex(courseId: Int?): Int = Math.floorMod(courseId ?: 0, 6)

fun courseOf(courses: List<Course>, id: Int?): Course? = courses.firstOrNull { it.id == id }
