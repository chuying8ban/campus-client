package top.ccbase.campus.domain

import kotlinx.serialization.Serializable
import java.time.LocalTime

/**
 * 作息时间表（2026-10-21 用户要求「支持更改课表时间表」）。
 *
 * 为什么要有它：以前全部节次钟点是 `Week.kt` 里手抄的 `UNIT_TIMES` 常量，
 * 学校一调作息（或同学换到另一个校区/另一所学校），整张表就全错，而且改不了 ——
 * 用户原话要能改「每节课时间、课间时间、大课间时间、哪些是大课间、上午第一节、
 * 下午第一节、早晚自习时间（可以填无早或晚自习）」。
 *
 * 模型刻意只有「三段 + 一个节长 + 两个课间」：
 *   上午 first 开始、连续 N 节；下午 first 开始、连续 M 节；晚上 first 开始、连续 K 节。
 *   每节 classMinutes 分钟，节间 breakMinutes，命中 [bigBreakAfter] 的节后面换成 bigBreakMinutes。
 * 这样默认值能**一字不差**地复现官方作息（1..12 节，09:30→22:55），
 * 而用户改一个数字就能得到任何一套作息。段与段之间的空白（午休、晚饭）不用单独建模 ——
 * 它天然等于「下一段的第一节时间」减「上一段最后一节下课」。
 */
@Serializable
data class StudyWindow(
    /** false = 时间表里没有这一档（「无早自习」/「无晚自习」） */
    val enabled: Boolean = true,
    val start: String = "08:45",
    val end: String = "09:15",
)

@Serializable
data class PeriodTable(
    val morningStart: String = "09:30",
    val morningCount: Int = 5,
    val afternoonStart: String = "16:00",
    val afternoonCount: Int = 4,
    val eveningStart: String = "20:30",
    val eveningCount: Int = 3,
    val classMinutes: Int = 45,
    val breakMinutes: Int = 5,
    /** 大课间在**第几节之后**（全局节号，如 2 = 第 2 节下课那一段） */
    val bigBreakAfter: List<Int> = listOf(2, 7),
    val bigBreakMinutes: Int = 20,
    val morningStudy: StudyWindow = StudyWindow(true, "08:45", "09:15"),
    val eveningStudy: StudyWindow = StudyWindow(true, "20:30", "22:05"),
) {
    val totalUnits: Int get() = morningCount + afternoonCount + eveningCount

    /** 一节一行的时间轴（节号从 1 连续编到底，跨段不断号） */
    fun units(): List<PeriodUnit> {
        val out = mutableListOf<PeriodUnit>()
        var index = 0
        fun segment(start: String, count: Int, name: String) {
            if (count <= 0) return
            var cursor = parseTime(start) ?: return
            repeat(count) {
                index += 1
                val from = cursor
                val to = from.plusMinutes(classMinutes.coerceAtLeast(1).toLong())
                out += PeriodUnit(index, hhmm(from), hhmm(to), name)
                val rest = if (index in bigBreakAfter) bigBreakMinutes else breakMinutes
                cursor = to.plusMinutes(rest.coerceAtLeast(0).toLong())
            }
        }
        segment(morningStart, morningCount, "上午")
        segment(afternoonStart, afternoonCount, "下午")
        segment(eveningStart, eveningCount, "晚上")
        return out
    }

    fun unit(index: Int): PeriodUnit? = units().firstOrNull { it.index == index }

    /** 从第 [from] 节到第 [to] 节的起止钟点（跨节次上课要整段） */
    fun span(from: Int, to: Int): Pair<String, String>? {
        val list = units()
        val a = list.firstOrNull { it.index == from } ?: return null
        val b = list.firstOrNull { it.index == to } ?: a
        return a.start to (if (b.index >= a.index) b.end else a.end)
    }

    /** 早自习 / 晚自习的时间窗；kind 不认识返回 null */
    fun study(kind: String): StudyWindow? = when (kind) {
        MORNING_STUDY -> morningStudy
        EVENING_STUDY -> eveningStudy
        else -> null
    }

    /** 这一档自习在时间表里是不是开着 */
    fun studyEnabled(kind: String): Boolean = study(kind)?.enabled ?: false

    /**
     * 保存前的校验。返回给用户看的一句话；null = 没问题。
     * 这些边界不是洁癖：节数 0 会让整张表变空、节长 0 会让「下课」和「上课」同一分钟，
     * 静音窗与课前提醒都会因此算不出东西（静默失效那种最难发现的坑）。
     */
    fun validate(): String? {
        if (totalUnits <= 0) return "至少要有 1 节课"
        if (totalUnits > 20) return "一天最多 20 节"
        if (classMinutes !in 10..180) return "每节课时长请填 10~180 分钟"
        if (breakMinutes !in 0..90) return "课间请填 0~90 分钟"
        if (bigBreakAfter.any { it !in 1 until totalUnits }) return "大课间要选「第几节之后」（不能是最后一节）"
        if (bigBreakMinutes !in 0..180) return "大课间时长请填 0~180 分钟"
        for ((label, t) in listOf("上午" to morningStart, "下午" to afternoonStart, "晚上" to eveningStart)) {
            if (parseTime(t) == null) return "$label 第一节时间要写成 HH:mm"
        }
        listOf("早自习" to morningStudy, "晚自习" to eveningStudy).forEach { (label, w) ->
            if (w.enabled && !validRange(w.start, w.end)) return "$label 时间要写成 HH:mm，且结束晚于开始"
        }
        return null
    }

    /** 把越界的输入夹回合法范围（输入框边输边存，不能让它先炸了） */
    fun normalized(): PeriodTable = copy(
        morningCount = morningCount.coerceIn(0, 12),
        afternoonCount = afternoonCount.coerceIn(0, 12),
        eveningCount = eveningCount.coerceIn(0, 12),
        classMinutes = classMinutes.coerceIn(10, 180),
        breakMinutes = breakMinutes.coerceIn(0, 90),
        bigBreakMinutes = bigBreakMinutes.coerceIn(0, 180),
        bigBreakAfter = bigBreakAfter.filter { it >= 1 }.distinct().sorted(),
    )

    companion object {
        const val MORNING_STUDY = "早自习"
        const val EVENING_STUDY = "晚自习"
    }
}

/** 时间轴上的一节 */
data class PeriodUnit(
    val index: Int,
    val start: String,
    val end: String,
    /** 上午 / 下午 / 晚上 */
    val segment: String,
)

private fun parseTime(raw: String): LocalTime? =
    runCatching { LocalTime.parse(raw.trim().let { if (it.length == 4 && it[1] == ':') "0$it" else it }) }.getOrNull()

private fun validRange(start: String, end: String): Boolean {
    val a = parseTime(start) ?: return false
    val b = parseTime(end) ?: return false
    return b.isAfter(a)
}
