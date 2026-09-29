package top.ccbase.campus.domain

import java.time.LocalDate

/**
 * 节假日「按日期」规则 —— **节假日前一天晚上没有晚自习；假期的最后一天有晚自习**。
 *
 * ## 为什么客户端要自带这份规则（而不是问服务端要）
 *
 * 这套逻辑和服务端 `study-app/holidays.py` 是**同一份规则**，两边必须一致。
 * 之所以在客户端再实现一遍，不是偷懒，是架构决定的：
 *
 *  1. **App 是离线优先的** —— 课表、自习、闹钟全在本地库里、本地算
 *     （`TodayLoader` / `Rescheduler` 都只读本地）。教室里没信号也得算对。
 *  2. **同步通道靠不住** —— `PlanRefresh.checkAndRefresh` 只在**课表版本号变了**
 *     才落地数据（版本一样就"什么都不动"）。把假期表塞进 plan 的 meta 里，
 *     改了也永远同步不到手机上。
 *
 * ⚠ **换年时要改两处**：`holidays.py` 和这里。漏一处就会出现
 *   "服务端说没有、手机还在响" 这种两边不一致 —— 这正是本次要修的 bug。
 *
 * ## 规则（与服务端 `evening_off()` 逐条对应）
 *
 *  1. 假期**前一天**：次日入假 → 当晚没有
 *  2. 假期**中间**：当天是假期、且次日**还是**假期 → 当晚没有
 *     但**假期的最后一天不算** —— 次日就开学了，晚自习照常
 *     （国庆 10/7 周三、春节 2/23 周一、中秋 9/27 周日 都有晚自习）
 *  3. 一句话记法：**看次日上不上学 —— 次日是假期，当晚就没有晚自习**
 *  4. **早自习一律不受影响**（用户明确要求）
 *
 * 周末**不在**这张表里：周末由星期表管（周日~周四有晚自习），
 * 「周五晚无、周日晚有」本来就是对的，这里只管法定假期，两边不打架。
 *
 * 出处：国办发明电〔2025〕7号（2025-11-04），与 `holidays.py` 同一来源。
 */
object Holidays {

    // ------------------------------------------------------------ 节假日表

    /** date(ISO) -> 节日名。**唯一数据源**，与 holidays.py 的 `_HOLIDAY_NAMES` 一一对应。 */
    private val NAMES: Map<String, String> = buildMap {
        for (d in listOf("2026-01-01", "2026-01-02", "2026-01-03")) put(d, "元旦")
        span(2026, 2, 15, 23, "春节")            // 2/15（腊月二十八）~ 2/23（正月初七），共 9 天
        span(2026, 4, 4, 6, "清明节")             // 共 3 天
        span(2026, 5, 1, 5, "劳动节")             // 共 5 天
        span(2026, 6, 19, 21, "端午节")           // 共 3 天
        span(2026, 9, 25, 27, "中秋节")           // 共 3 天
        span(2026, 10, 1, 7, "国庆节")            // 共 7 天
    }

    /** 把「M月d1日至d2日放假」展开成日期串（对应 holidays.py 的 `_span`）。 */
    private fun MutableMap<String, String>.span(y: Int, m: Int, d1: Int, d2: Int, name: String) {
        var d = LocalDate.of(y, m, d1)
        val end = LocalDate.of(y, m, d2)
        while (!d.isAfter(end)) {
            put(d.toString(), name)
            d = d.plusDays(1)
        }
    }

    // ------------------------------------------------------------ 基础判断

    /** 当天是什么法定节假日（连休里的每一天都算）。不是假期返回 ""。 */
    fun holidayName(day: LocalDate): String = NAMES[day.toString()] ?: ""

    /** 法定节假日（含调休连休日）。 */
    fun isHoliday(day: LocalDate): Boolean = NAMES.containsKey(day.toString())

    /**
     * 这一天是不是**所在假期段的最后一天**（次日不再是假期）。
     *
     * 判定看「明天是否还是假期」，不依赖假期表里哪天开始 ——
     * 所以调休连休、跨月的假期都不用特殊处理。
     */
    fun isLastHolidayDay(day: LocalDate): Boolean =
        isHoliday(day) && !isHoliday(day.plusDays(1))

    /**
     * 这一条自习是不是**晚**自习。
     *
     * 按种类名判断（含"晚"且不含"早"），不是按时间 —— 时间以后可能调，名字是语义。
     * 「早自习」永远 false：用户明确要求早自习不受影响。
     */
    fun isEvening(kind: String): Boolean = kind.contains("晚") && !kind.contains("早")

    // ------------------------------------------------------------ 主规则

    /**
     * 当晚有没有晚自习。返回**原因**（人话，可直接显示给用户），照常则返回空串。
     *
     * 与 `holidays.py` 的 `evening_off()` 完全同序：
     * 先看是不是假期最后一天（照常），再看当天/次日是不是假期。
     */
    fun eveningOff(day: LocalDate): String {
        // 假期最后一天：次日就开学了，当晚照常 —— 不能当"假期中"砍掉
        if (isLastHolidayDay(day)) return ""

        holidayName(day).takeIf { it.isNotEmpty() }?.let {
            return "${it}假期中（当晚没有晚自习）"
        }
        holidayName(day.plusDays(1)).takeIf { it.isNotEmpty() }?.let {
            return "${it}假期的前一天（当晚没有晚自习）"
        }
        return ""
    }

    /** 当晚是不是没有晚自习（[eveningOff] 的布尔版，调用点更好读）。 */
    fun isEveningOff(day: LocalDate): Boolean = eveningOff(day).isNotEmpty()
}
