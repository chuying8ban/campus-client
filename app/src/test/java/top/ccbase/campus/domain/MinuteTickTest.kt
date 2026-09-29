package top.ccbase.campus.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * 整分节拍的边界算法。
 *
 * 为什么值得单测：这段是「今天页实时刷新」唯一有真逻辑的部分 ——
 * 其它都是接线（`repeatOnLifecycle` + `tick++`），接错了会当场看见，
 * 但**边界算错不会报错，只会偶尔晚 59 秒才更新**，那种错没人查得出来。
 *
 * 状态本身（now/done/todo）另有 TodayLogicTest 覆盖，这里只管"多久醒一次"。
 */
class MinuteTickTest {

    @Test
    fun `整分整秒 → 等满一分钟`() {
        assertEquals(60_000L, msToNextMinute(LocalDateTime.of(2026, 9, 24, 9, 19, 0)))
    }

    @Test
    fun `过了半分钟 → 等剩下的`() {
        assertEquals(30_000L, msToNextMinute(LocalDateTime.of(2026, 9, 24, 9, 19, 30)))
    }

    /** 最要命的一格：9:19:59 打开页面，1 秒后就该翻，不能傻等 60 秒。 */
    @Test
    fun `差一秒到整分 → 只等一秒`() {
        assertEquals(1_000L, msToNextMinute(LocalDateTime.of(2026, 9, 24, 9, 19, 59)))
    }

    /** 卡在 59.999 时算出来是 1ms —— 拿 1ms 去忙等就是空转烧电。 */
    @Test
    fun `贴着边界 → 兜底 50ms 不忙等`() {
        assertEquals(50L, msToNextMinute(LocalDateTime.of(2026, 9, 24, 9, 19, 59, 999_000_000)))
    }

    /** 23:59 那次必须跨到 00:00，不能算出负数或 0。 */
    @Test
    fun `跨午夜不越界`() {
        assertEquals(30_000L, msToNextMinute(LocalDateTime.of(2026, 9, 24, 23, 59, 30)))
    }

    /** 任何时刻都必须是正数：delay(0)/delay(负数) 会变成死循环空转。 */
    @Test
    fun `任何时刻都返回正数`() {
        for (m in 0..59) {
            for (s in intArrayOf(0, 1, 30, 58, 59)) {
                val ms = msToNextMinute(LocalDateTime.of(2026, 9, 24, 9, m, s))
                assertEquals("9:$m:$s 算出来不是正数", true, ms > 0)
            }
        }
    }
}
