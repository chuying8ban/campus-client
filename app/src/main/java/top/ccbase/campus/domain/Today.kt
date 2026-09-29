package top.ccbase.campus.domain

import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.SelfStudy
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.data.local.Task
import top.ccbase.campus.data.local.TaskDone
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 「今天该显示什么」—— 纯函数：不碰数据库、不读系统时钟（now 当参数传）。
 *
 * 为什么非要写成纯函数：
 * ① 这是整个 App 最核心的规则（今天几节课、哪些任务该做、现在是第几周）；
 * ② 纯函数能在 JVM 上**真跑**验证（Robolectric），而本机没有模拟器 ——
 *    这是唯一能验证它的途径；
 * ③ **必须与网页版口径完全一致。** 网页版是同规则的 Python 实现，
 *    两份实现只要有一点偏差，就会出现"App 和网页版显示不一样"这种最难查的问题。
 *    所以下面每条分支都逐行对照 `main.py` 的 `/api/today` + `build_today_tasks()`，
 *    并用网页版算出的基准值做交叉验证（见 TodayLogicTest）。
 *
 * ⚠️ 网页版有些行为看起来"怪"（例如 `once` 任务的显示条件、待办项的 done 恒为 false），
 *    这里**照搬不改**。口径一致优先于我觉得更合理 —— 要改就两边一起改。
 */

private val WD_CN = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 学期第几周：第 1 周周一 = semesterStart（同 db.week_no） */
fun weekNo(semesterStart: String, d: LocalDate): Int {
    val start = LocalDate.parse(semesterStart)
    return (ChronoUnit.DAYS.between(start, d) / 7).toInt() + 1
}

data class ClassNow(
    val slotId: Int,
    val courseId: Int?,
    val name: String,
    val short: String?,
    val teacher: String?,
    val isFocus: Boolean,
    val timeText: String,
    val room: String?,
    /** done / now / todo */
    val status: String,
)

data class SelfStudyNow(
    val kind: String,
    val start: String,
    val end: String,
    val place: String?,
    val status: String,
)

data class TaskNow(
    val id: Int,
    val title: String,
    val detail: String?,
    val deliverable: String?,
    val track: String?,
    val priority: Int,
    /** 今天为什么显示它：每日 / 今天有这门课 / 周三固定 / 本周未完成 / 待办事项 */
    val why: String,
    val doneToday: Boolean,
)

data class TodayView(
    val date: String,
    val weekday: Int,
    val weekdayCn: String,
    val weekNo: Int,
    val semesterName: String?,
    val termWeeks: Int,
    val classes: List<ClassNow>,
    val selfstudy: List<SelfStudyNow>,
    val tasks: List<TaskNow>,
    val standing: List<TaskNow>,
    /**
     * 今晚为什么没有晚自习 —— 空串=照常有。
     *
     * 只讲**晚**自习：早自习不受假期规则影响（用户明确要求）。
     * 文案由 [Holidays.eveningOff] 给（与服务端 `selfstudy_off` 同一句），
     * 界面上直接显示，别让晚自习"静默消失" —— 那看着像 bug。
     */
    val selfStudyOff: String = "",
)

/**
 * 距离**下一个整分**还有多少毫秒。
 *
 * 为什么不是固定 `delay(60_000)`：那样节拍会从"打开页面的那一刻"起步，
 * 永远和真实时钟错开一个零头（09:19:37 打开 → 每次都在 :37 醒），
 * 而状态恰恰是在**整分**那一刻翻转的，于是最长能晚 59 秒才更新。
 * 对齐整分后，09:15 一到页面就翻，误差只在一个调度抖动内。
 *
 * 下限 50ms：恰好卡在 :59.999 时算出来是 1ms，不能拿 1ms 去忙等。
 */
internal fun msToNextMinute(now: LocalDateTime): Long {
    val next = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
    return Duration.between(now, next).toMillis().coerceAtLeast(50)
}

/**
 * @param done task_done 表的全部行（App 里按 uid + 近 30 天取，够算本周与今日）
 */
fun computeToday(
    slots: List<Slot>,
    courses: List<Course>,
    selfstudy: List<SelfStudy>,
    tasks: List<Task>,
    done: List<TaskDone>,
    semesterStart: String,
    semesterName: String?,
    termWeeks: Int,
    now: LocalDateTime,
): TodayView {
    val d = now.toLocalDate()
    val wd = d.dayOfWeek.value                    // 1=周一 … 7=周日（同 Python isoweekday）
    val wk = weekNo(semesterStart, d)
    val today = d.toString()
    val nowHm = now.format(HM)

    val courseById = courses.associateBy { it.id }

    // ---- 今天的课：星期对上 且 当前周落在课的周次区间内 ----
    val todaySlots = slots
        .filter { it.weekday == wd && it.inWeek(wk) }
        .sortedWith(compareBy({ it.p_start ?: 0 }, { it.sort }, { it.id }))

    val cls = todaySlots.map { s ->
        // 时间从节次查作息表算起 —— 线上 time_text 是「周一 1-2节」，
        // 老写法 substringBefore("-") 切出来是「周一 1」，于是「进行中/已完成」全错。
        val span = slotSpan(s)
        val start = span?.first?.let { hhmm(it) }.orEmpty()
        val end = span?.second?.let { hhmm(it) }.orEmpty()
        val status =
            if (end.isNotEmpty() && end < nowHm) "done"
            else if (start.isNotEmpty() && start <= nowHm && nowHm <= end) "now"
            else "todo"
        val c = courseById[s.course_id]
        ClassNow(
            slotId = s.id,
            courseId = s.course_id,
            name = c?.name ?: "（未知课程）",
            short = c?.short,
            teacher = c?.teacher,
            isFocus = (c?.is_focus ?: 0) == 1,
            timeText = slotTimeLabel(s),
            room = s.room,
            status = status,
        )
    }

    // ---- 早晚自习：按星期几，但**遇假期要让位** ----
    // 光看星期几不够：同一个「周三」，2026-04-29 有晚自习，而
    // 2026-04-30（劳动节前一天）没有。「前一天 / 最后一天」是**日期**概念，
    // 只能按日期算 —— 规则见 Holidays（与服务端 holidays.py 同一份）。
    // 早自习一个字节都不动：isEvening() 只认「晚」。
    val selfStudyOff = Holidays.eveningOff(d)
    val ss = selfstudy
        .filter { it.weekday == wd && !(selfStudyOff.isNotEmpty() && Holidays.isEvening(it.kind)) }
        .sortedBy { it.start }
        .map { s ->
            val status =
                if (s.end < nowHm) "done"
                else if (s.start <= nowHm && nowHm <= s.end) "now"
                else "todo"
            SelfStudyNow(s.kind, s.start, s.end, s.place, status)
        }

    // ---- 今日任务 / 常驻待办 ----
    val todaysCourseIds = todaySlots.mapNotNull { it.course_id }.toSet()
    val weekStart = d.minusDays((wd - 1).toLong()).toString()
    val doneTodayIds = done.filter { it.day == today }.mapNotNull { it.task_id }.toSet()
    val doneWeekIds = done.filter { it.day >= weekStart }.mapNotNull { it.task_id }.toSet()

    val todayList = ArrayList<TaskNow>()
    val standing = ArrayList<TaskNow>()

    for (t in tasks.filter { it.active == 1 }.sortedWith(compareBy({ it.priority }, { it.sort }))) {
        val done = t.id in doneTodayIds
        when (t.cadence) {
            // 网页版：本周一件都没完成时，这些"一次性"任务才作为常驻待办露出来（照搬）
            "once" ->
                if (doneWeekIds.isEmpty()) {
                    standing.add(TaskNow(t.id, t.title, t.detail, t.deliverable, t.track, t.priority, "待办事项", false))
                }

            "daily" ->
                todayList.add(TaskNow(t.id, t.title, t.detail, t.deliverable, t.track, t.priority, "每日", done))

            "per-class" ->
                if (t.course_id != null && t.course_id in todaysCourseIds) {
                    todayList.add(TaskNow(t.id, t.title, t.detail, t.deliverable, t.track, t.priority, "今天有这门课", done))
                }

            "weekly" -> {
                if (t.weekday != null && t.weekday == wd) {
                    todayList.add(TaskNow(t.id, t.title, t.detail, t.deliverable, t.track, t.priority, WD_CN[wd] + "固定", done))
                } else if (t.weekday == null) {
                    // 没指定星期几的周任务：本周还没做就一直提醒
                    if (t.id !in doneWeekIds) {
                        todayList.add(TaskNow(t.id, t.title, t.detail, t.deliverable, t.track, t.priority, "本周未完成", done))
                    }
                }
            }
        }
    }

    // 同一条任务可能被两个来源各建一份（内置种子 seed.json + 技能包 plan_templates.json），
    // 标题相同、id 不同，于是今日列表里会**挨着出现两行** —— 用户看到的就是"这条怎么两遍"。
    // 按标题去重：屏幕前的人看到的一条就是一条。
    val tasksShown = todayList.distinctBy { it.title }
    val standingShown = standing.distinctBy { it.title }

    return TodayView(
        date = today,
        weekday = wd,
        weekdayCn = WD_CN[wd],
        weekNo = wk,
        semesterName = semesterName,
        termWeeks = termWeeks,
        classes = cls,
        selfstudy = ss,
        tasks = tasksShown,
        standing = standingShown,
        selfStudyOff = selfStudyOff,
    )
}
