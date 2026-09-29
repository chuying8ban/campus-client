package top.ccbase.campus.domain

import top.ccbase.campus.data.local.SelfStudy
import top.ccbase.campus.data.local.Slot
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 课前提醒 / 自动静音的排程逻辑 —— 纯函数，可在 JVM 上真跑验证。
 *
 * 覆盖两类"到点要提醒"的项：
 *  * **课**（`slots`）：按周次过滤（第 3~19 周那种），开始时间从 `time_text`/节次表推。
 *  * **早晚自习**（`selfstudy`）：只看星期几，每周都一样（早自习 08:45-09:15、
 *    晚自习 20:30-22:05）。**别把它漏掉** —— 用户截图里最早的一条提醒就是
 *    "1 分钟后早自习 08:45 A楼 I区301"，那是他真实的一天。
 *
 * 为什么这块必须测：闹钟算错是"静默失败"—— 它不会崩、不会报错，
 * 只会表现为"上课时手机没静音"或者更糟的"下课后还是静音、错过重要来电"。
 * 而且本机没模拟器，我没法靠点界面发现，只能靠把逻辑下沉成纯函数来对账。
 *
 * 关键的三条规则（容易写错的地方）：
 * ① **周次过滤**：某天的课必须落在该课的 week_from~week_to 区间内；
 *    第 1、2 周没课，不能按"每周固定有课"排闹钟。自习不受周次约束。
 * ② **跨周查找**：搜索要能跨到下周、甚至下个月（学期初没课时），不能只看本周。
 * ③ **结束时间也要排**：只静音不恢复 = 手机会一直静音下去。
 */

/** 一条提醒窗口：课前 [leadMinutes] 分钟开始，下课/下自习时间结束 */
data class NudgeWindow(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val slotId: Int,
    val courseId: Int?,
    /** 通知正文：课程 · 第 N-M 节 · 起点时刻 · 教室（空项自动跳过） */
    val body: String = "",
    /** 标题里的名词：上课 / 早自习 / 晚自习 —— 话要说得对（"1 分钟后早自习"） */
    val what: String = "上课",
)

/** 到点要发生的一个动作 */
enum class NudgeKind { REMIND, SILENCE, UNSILENCE }

data class NudgeAction(
    val at: LocalDateTime,
    val kind: NudgeKind,
    val title: String,
    val body: String = "",
    /** 提醒类动作带上：标题里说"什么"（上课/早自习/晚自习） */
    val what: String = "",
)

/** 下一个到点项（课或自习）—— 内部用，两种来源统一成一种形状 */
internal data class NextItem(
    val at: LocalDateTime,
    val end: LocalTime,
    val what: String,
    val body: String,
    val slot: Slot? = null,
    val self: SelfStudy? = null,
)

/** 解析 "09:30-11:05" → (09:30, 11:05)；格式不对返回 null 而不是抛异常 */
fun parseSpan(timeText: String?): Pair<LocalTime, LocalTime>? {
    val t = timeText?.trim().orEmpty()
    val i = t.indexOf('-')
    if (i <= 0 || i >= t.length - 1) return null
    return try {
        val a = LocalTime.parse(t.substring(0, i).trim().padStart(5, '0'))
        val b = LocalTime.parse(t.substring(i + 1).trim().padStart(5, '0'))
        if (b <= a) null else a to b
    } catch (_: Exception) {
        null
    }
}

/** 提醒正文：课程 · 第1-2节 · 09:30 · A楼406 —— 空的部分自动跳过 */
fun remindBody(courseName: String?, slot: Slot, span: Pair<LocalTime, LocalTime>?): String {
    val parts = ArrayList<String>(4)
    val name = courseName?.trim().orEmpty()
    if (name.isNotEmpty()) parts += name
    val a = slot.p_start
    if (a != null) {
        val b = slot.p_end ?: a
        parts += if (a == b) "第${a}节" else "第${a}-${b}节"
    }
    span?.let { parts += hhmm(it.first) }
    slot.room?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
    return if (parts.isEmpty()) "该上课了" else parts.joinToString(" · ")
}

/** 自习正文：08:45-09:15 · A楼 I区301（有起止就给范围，没有就只给起点 —— 别编） */
fun selfStudyBody(ss: SelfStudy, span: Pair<LocalTime, LocalTime>?): String {
    val parts = ArrayList<String>(2)
    span?.let { parts += "${hhmm(it.first)}-${hhmm(it.second)}" }
    ss.place?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
    return if (parts.isEmpty()) "该去自习了" else parts.joinToString(" · ")
}

/**
 * 提醒标题按**真实剩余时间**说话。
 *
 * 为什么不能排程时就把文案写死：闹钟可能晚响 —— Doze 打盹、用户刚把权限给上、
 * 或者服务重启后重排。这时候还说"1 分钟后上课"就是错的。
 */
fun remindTitle(leftSeconds: Long, what: String = "上课"): String {
    if (leftSeconds <= 30) return "马上$what"
    val m = ((leftSeconds + 30) / 60).toInt().coerceAtLeast(1)
    return "$m 分钟后$what"
}

/**
 * 从 [from] 起往后找最近的课（含当天）。
 *
 * @param horizonDays 最多往后找多少天 —— 学期初（第 1~2 周没课）时靠它跨到开课那周。
 *                    不设上限会在"所有课都结课了"时死循环。
 */
fun nextClassAt(
    slots: List<Slot>,
    semesterStart: String,
    from: LocalDateTime,
    horizonDays: Int = 120,
): Pair<LocalDateTime, Slot>? {
    val n = nextItemAt(slots, emptyList(), semesterStart, from, horizonDays = horizonDays) ?: return null
    val s = n.slot ?: return null
    return n.at to s
}

/**
 * 从 [from] 起往后找最近的**到点项**：课（按周次）或早晚自习（按星期几）。
 *
 * 两种来源必须在同一次搜索里比时间 —— 分开找再拼会出错：
 * 早自习 08:45 与第 1-2 节课 09:30 是同一天的前后两件事，
 * 分开算就是"要么漏掉自习、要么顺序错"。
 */
internal fun nextItemAt(
    slots: List<Slot>,
    selfstudy: List<SelfStudy>,
    semesterStart: String,
    from: LocalDateTime,
    nameOf: (Slot) -> String = { "" },
    horizonDays: Int = 120,
): NextItem? {
    var day = from.toLocalDate()
    repeat(horizonDays + 1) {
        val week = weekNo(semesterStart, day)
        val wd = day.dayOfWeek.value
        val cands = ArrayList<NextItem>(4)

        for (s in slots) {
            if (s.weekday != wd || !s.inWeek(week)) continue
            val span = slotSpan(s) ?: continue
            cands += NextItem(
                at = LocalDateTime.of(day, span.first),
                end = span.second,
                what = "上课",
                body = remindBody(nameOf(s), s, span),
                slot = s,
            )
        }
        // 晚自习遇假期要让位 —— 按**日期**算（见 Holidays）。同一个周三，
        // 2026-04-29 有晚自习、2026-04-30（劳动节前一天）没有，
        // 拿星期几永远表达不出来。早自习不受影响（isEvening 只认「晚」）。
        val eveningOff = Holidays.isEveningOff(day)
        for (ss in selfstudy) {
            if (ss.weekday != wd) continue
            if (eveningOff && Holidays.isEvening(ss.kind)) continue
            val span = parseSpan(ss.start + "-" + ss.end) ?: continue
            cands += NextItem(
                at = LocalDateTime.of(day, span.first),
                end = span.second,
                what = ss.kind.ifBlank { "自习" },     // 早自习 / 晚自习
                body = selfStudyBody(ss, span),
                self = ss,
            )
        }

        cands.filter { it.at.isAfter(from) }.minByOrNull { it.at }?.let { return it }
        day = day.plusDays(1)
    }
    return null
}

/**
 * 静音结果的一句话（接在提醒正文后面）—— 纯函数，好在 JVM 上对账。
 * 失败时要把**缺哪一项**说出来：只写"静音失败"用户在设置里翻不到地方。
 */
fun silenceNote(ok: Boolean): String =
    if (ok) "已静音" else "静音未生效（缺「修改系统设置」权限）"

/** 闹钟到点时：这条通知说什么、记录记什么。ok=false 表示"这一条没真正送达"。 */
data class FireResult(val title: String, val body: String, val ok: Boolean, val reason: String)

/**
 * 课前提醒到点时的**全部判断**（纯函数）。
 *
 * 为什么连失败分支也要放在这里：真机上最常见的两种失败 —— 通知权限没给、
 * 「修改系统设置」没给 —— 在 Robolectric 里复现不出来（系统的 canWrite 由影子环境决定）。
 * 下沉成纯函数之后，这两条分支在 JVM 上也能真的跑一遍，而不是"看起来写了"。
 *
 * @param silFailReason 顺手静音失败的原因码；null = 静音成功或用户没开静音
 */
fun fireRemind(
    leftSeconds: Long,
    what: String,
    body: String,
    wantSilence: Boolean,
    silFailReason: String?,
    notifOk: Boolean,
): FireResult {
    val text = when {
        !wantSilence -> body
        silFailReason == null -> "$body · ${silenceNote(true)}"
        else -> "$body · ${silenceNote(false)}"
    }
    val reason = listOfNotNull(
        if (!notifOk) "no_notification_permission" else null,
        silFailReason,
    ).joinToString(",")
    return FireResult(remindTitle(leftSeconds, what), text, notifOk && silFailReason == null, reason)
}

/**
 * 未来的提醒窗口列表（按时间升序）。
 *
 * @param daysAhead 排多少天 —— 原生闹钟有数量上限（AlarmManager 单 App 上限约 500 个），
 *                  所以是"排一小段、用掉再续"，而不是一次把整个学期排满。
 * @param selfstudy 早晚自习；不受周次约束，只看星期几
 */
fun nudgeWindows(
    slots: List<Slot>,
    semesterStart: String,
    from: LocalDateTime,
    daysAhead: Int = 7,
    leadMinutes: Int = 1,
    nameOf: (Slot) -> String = { "" },
    selfstudy: List<SelfStudy> = emptyList(),
): List<NudgeWindow> {
    val stop = from.plusDays(daysAhead.toLong())
    val out = ArrayList<NudgeWindow>()
    var cursor = from
    while (true) {
        val n = nextItemAt(slots, selfstudy, semesterStart, cursor, nameOf) ?: break
        if (!n.at.isBefore(stop)) break
        out.add(
            NudgeWindow(
                start = n.at.minusMinutes(leadMinutes.toLong()),
                end = n.at.toLocalDate().atTime(n.end),
                slotId = n.slot?.id ?: -(n.self?.id ?: 0),
                courseId = n.slot?.course_id,
                body = n.body,
                what = n.what,
            )
        )
        // 从这一项之后继续找（+1 分钟避免原地打转）
        cursor = n.at.plusMinutes(1)
    }
    return out
}

/**
 * 把重叠/相接的窗并成"该静音的时段"。
 *
 * 为什么必须有这一步：静音是**系统状态**，不是"每个项一个开关"。真实课表里
 * 自习时段会叠在一起（周一 19:00-21:00 图书馆自习、20:30-22:05 晚自习），
 * 逐项排就会出现"21:00 恢复铃声"卡在晚自习中间 —— 晚自习后半程手机是响的。
 * 静音时段必须先合并：从最早一个开始，一直静到最后一件结束。
 */
fun silenceSpans(windows: List<NudgeWindow>): List<Pair<LocalDateTime, LocalDateTime>> {
    val out = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
    for (w in windows.sortedBy { it.start }) {
        val last = out.lastOrNull()
        if (last != null && !w.start.isAfter(last.second)) {
            if (w.end.isAfter(last.second)) out[out.size - 1] = last.first to w.end
        } else {
            out += w.start to w.end
        }
    }
    return out
}

/** 提醒：**每个**到点项一条（自习和课各算一条，不能合并） */
fun planReminders(windows: List<NudgeWindow>, remind: Boolean): List<NudgeAction> {
    if (!remind) return emptyList()
    return windows.map { NudgeAction(it.start, NudgeKind.REMIND, "", it.body, it.what) }
}

/**
 * 静音/恢复：按合并后的时段排，落在同一段里的项只留一条"已静音"。
 *
 * @param remindInstants 已经排了提醒的时刻 —— **同一时刻不再排静音闹钟**：
 *        提醒那条通知到点时会顺手静音（正文里带"已静音"），
 *        再排一个动作就是同一分钟弹两条通知（"上课了" + "已静音"），锁屏上纯噪音。
 */
fun planSilence(
    windows: List<NudgeWindow>,
    silence: Boolean,
    remindInstants: Set<LocalDateTime> = emptySet(),
): List<NudgeAction> {
    if (!silence) return emptyList()
    val out = ArrayList<NudgeAction>(4)
    for ((a, b) in silenceSpans(windows)) {
        val inside = windows
            .filter { !it.end.isBefore(a) && !it.start.isAfter(b) }
            .map { it.body }
            .filter { it.isNotBlank() }
            .distinct()
        val body = inside.joinToString(" / ")
        if (a !in remindInstants) out += NudgeAction(a, NudgeKind.SILENCE, "🔕 已自动静音", body)
        out += NudgeAction(b, NudgeKind.UNSILENCE, "🔔 已恢复铃声", "")
    }
    return out
}

/**
 * 一条窗口该排哪些闹钟 —— 单条窗口的便捷入口（四种开关组合在 JVM 上全都能对账）。
 *
 * 关键取舍：提醒和静音在**同一个时刻**只出一条通知。
 * 两个闹钟同时弹两条（一条"上课了"、一条"已静音"）在锁屏上就是噪音，
 * 所以静音状态由提醒那条通知自己带上（"… · 已静音"）。
 */
fun planActions(w: NudgeWindow, remind: Boolean, silence: Boolean): List<NudgeAction> {
    val rem = planReminders(listOf(w), remind)
    val sil = planSilence(listOf(w), silence, rem.map { it.at }.toSet())
    return (rem + sil).sortedBy { it.at }
}
