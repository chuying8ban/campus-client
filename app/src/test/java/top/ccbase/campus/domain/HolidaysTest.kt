package top.ccbase.campus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 节假日晚自习规则 —— **与 Python 版（`study-app/holidays.py`）对账**。
 *
 * 为什么这份测试是这个形状：客户端的 [Holidays] 是服务端规则的**第二份实现**
 * （离线优先，必须自带，理由见 Holidays 的注释）。两份实现最怕的就是漂移 ——
 * 而这次要修的 bug 恰恰就是"服务端算对了、客户端没跟上"。
 *
 * 所以下面的 [GOLDEN] **不是我想出来的**：是把 `holidays.py` 跑一遍、
 * 把它对每个关键日期的输出逐字抄下来的。服务端改规则而客户端没跟上 →
 * 这张表就红，而不是等用户发现"手机还在响晚自习"。
 *
 * 重新生成：[study-app] 下跑本文件末尾注释里那段脚本。
 */
class HolidaysTest {

    private fun d(s: String) = LocalDate.parse(s)

    /**
     * 服务端 `holidays.py` 的权威输出：日期 → (有没有晚自习, 原因)。
     *
     * 挑的都是**边界**（规则真正较劲的地方）：每个假期段的
     * 前一天 / 首日 / 中间 / 末日 / 次日，外加几个平常日做对照。
     * 末日那一行就是用户要的那条：**假期的最后一天有晚自习**。
     */
    private val GOLDEN: List<Triple<String, Boolean, String>> = listOf(
        Triple("2025-12-31", false, "元旦假期的前一天（当晚没有晚自习）"),
        Triple("2026-01-01", false, "元旦假期中（当晚没有晚自习）"),
        Triple("2026-01-03", true, ""),          // 元旦末日（周六，本来就无晚自习）
        Triple("2026-01-04", true, ""),          // 元旦次日（调休上班）
        Triple("2026-02-14", false, "春节假期的前一天（当晚没有晚自习）"),
        Triple("2026-02-15", false, "春节假期中（当晚没有晚自习）"),
        Triple("2026-02-19", false, "春节假期中（当晚没有晚自习）"),
        Triple("2026-02-23", true, ""),          // 春节末日（周一）→ 照常
        Triple("2026-02-24", true, ""),
        Triple("2026-04-03", false, "清明节假期的前一天（当晚没有晚自习）"),
        Triple("2026-04-04", false, "清明节假期中（当晚没有晚自习）"),
        Triple("2026-04-06", true, ""),          // 清明末日（周一）→ 照常
        Triple("2026-04-07", true, ""),
        Triple("2026-04-30", false, "劳动节假期的前一天（当晚没有晚自习）"),
        Triple("2026-05-01", false, "劳动节假期中（当晚没有晚自习）"),
        Triple("2026-05-03", false, "劳动节假期中（当晚没有晚自习）"),
        Triple("2026-05-05", true, ""),          // 劳动末日（周二）→ 照常
        Triple("2026-05-06", true, ""),
        Triple("2026-06-18", false, "端午节假期的前一天（当晚没有晚自习）"),
        Triple("2026-06-19", false, "端午节假期中（当晚没有晚自习）"),
        Triple("2026-06-21", true, ""),          // 端午末日（周日）→ 照常
        Triple("2026-06-22", true, ""),
        Triple("2026-09-22", true, ""),          // 平常日（周二）
        Triple("2026-09-23", true, ""),          // 平常日（周三）
        Triple("2026-09-24", false, "中秋节假期的前一天（当晚没有晚自习）"),
        Triple("2026-09-25", false, "中秋节假期中（当晚没有晚自习）"),
        Triple("2026-09-27", true, ""),          // 中秋末日（周日）→ 照常 ← 用户点名
        Triple("2026-09-28", true, ""),
        Triple("2026-09-30", false, "国庆节假期的前一天（当晚没有晚自习）"),
        Triple("2026-10-01", false, "国庆节假期中（当晚没有晚自习）"),
        Triple("2026-10-04", false, "国庆节假期中（当晚没有晚自习）"),
        Triple("2026-10-07", true, ""),          // 国庆末日（周三）→ 照常
        Triple("2026-10-08", true, ""),
        Triple("2026-10-11", true, ""),          // 平常日（周日）→ 照常
        Triple("2026-10-12", true, ""),
    )

    @Test
    fun `每个关键日期的判定都与服务端一致`() {
        for ((day, hasEvening, reason) in GOLDEN) {
            val off = Holidays.eveningOff(d(day))
            if (hasEvening) {
                assertEquals("$day 当晚应照常有晚自习，却被判为没有（${off}）", "", off)
            } else {
                assertTrue("$day 当晚应没有晚自习，却判为照常", off.isNotEmpty())
                assertEquals("$day 的原因文案与服务端不一致", reason, off)
            }
        }
    }

    /** 用户原话：「节假日的最后一天有晚自习」。单独拎出来，改坏了第一时间知道。 */
    @Test
    fun `假期最后一天有晚自习`() {
        for (day in listOf(
            "2026-02-23",   // 春节最后一天（周一）
            "2026-04-06",   // 清明最后一天（周一）
            "2026-05-05",   // 劳动最后一天（周二）
            "2026-10-07",   // 国庆最后一天（周三）
            "2026-09-27",   // 中秋最后一天（周日）← 用户这次说的就是这个
        )) {
            assertTrue("$day 是假期最后一天，次日就开学了，当晚必须照常有晚自习",
                Holidays.isLastHolidayDay(d(day)))
            assertEquals("$day 是假期最后一天，不该被判成没有晚自习", "", Holidays.eveningOff(d(day)))
        }
    }

    /** 用户原话：「节假日的前一天晚上没有晚自习」。 */
    @Test
    fun `假期的前一天晚上没有晚自习`() {
        for (day in listOf("2026-09-24", "2026-09-30", "2026-04-30", "2026-06-18", "2026-02-14")) {
            assertFalse("$day 是假期前一天，当晚不该有晚自习", Holidays.eveningOff(d(day)).isEmpty())
        }
    }

    /** 用户明确要求：**早自习一律不受影响**。 */
    @Test
    fun `早自习永远不算晚自习`() {
        assertFalse(Holidays.isEvening("早自习"))
        assertTrue(Holidays.isEvening("晚自习"))
        // 假期前一天的早上照样有早自习 —— 规则只动晚上
        assertTrue(Holidays.eveningOff(d("2026-09-24")).isNotEmpty())
    }

    /** 周末由星期表管，这张表只管法定假期 —— 两边不能打架。 */
    @Test
    fun `普通周末不算假期`() {
        for (day in listOf("2026-09-26", "2026-10-11", "2026-10-17", "2026-10-18")) {
            // 9/26 是中秋连休（算假期），其余是普通周末（不算）
            val expectHoliday = day == "2026-09-26"
            assertEquals("$day 是否法定假期判断错了", expectHoliday, Holidays.isHoliday(d(day)))
        }
    }

    /** 调休上班的周末照常（周日按星期表本来就有晚自习，不能被当成假期砍掉）。 */
    @Test
    fun `调休上班日晚上照常`() {
        for (day in listOf("2026-09-20", "2026-10-10", "2026-02-28", "2026-05-09")) {
            assertEquals("$day 是调休上班日，当晚应照常", "", Holidays.eveningOff(d(day)))
        }
    }
}

/*
 * 重新生成 GOLDEN（在 study-app 目录下）：
 *
 *   ./.venv/bin/python - <<'PY'
 *   import holidays as H
 *   from datetime import date, timedelta
 *   spans = [("元旦","2026-01-01","2026-01-03"),("春节","2026-02-15","2026-02-23"),
 *            ("清明","2026-04-04","2026-04-06"),("劳动","2026-05-01","2026-05-05"),
 *            ("端午","2026-06-19","2026-06-21"),("中秋","2026-09-25","2026-09-27"),
 *            ("国庆","2026-10-01","2026-10-07")]
 *   for n,s,e in spans:
 *       s_d,e_d = date.fromisoformat(s), date.fromisoformat(e)
 *       for d in [s_d-timedelta(1), s_d, s_d+timedelta((e_d-s_d).days//2), e_d, e_d+timedelta(1)]:
 *           r = H.evening_off(d)
 *           print('        Triple("%s", %s, "%s"),' % (d, "true" if not r else "false", r))
 *   PY
 *
 * 换年时：holidays.py 和 Holidays.kt 两张表都要加，然后重跑上面这段刷新 GOLDEN。
 */
