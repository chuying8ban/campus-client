package top.ccbase.campus.data.seed

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约测试：**真实线上响应**必须能被 App 的 DTO 解析出东西。
 *
 * 为什么非要有它：2026-09-17 用户手机上出现「更新失败：计划数据无法解析」。
 * 我用 Python 解析同一份线上响应是**成功**的 —— 因为 Python 很宽松；
 * kotlinx 严格得多：字符串塞进 Int 字段、null 塞进非空字段、字段名差一个下划线，
 * 都会直接抛异常。所以「Python 能解」证明不了「App 能解」。
 *
 * 夹具 `live_plan_20260917.json` 是带 App 的请求头从
 * https://api.example.com/api/v2/plan 取下来的**原始明文**（85KB，已确认不含学号/token）。
 * 这里用 App 里那套真实的 Json 配置去解，等于在电脑上替用户把这一步跑一遍。
 */
class SeedContractTest {

    private fun fixture(): String =
        javaClass.getResourceAsStream("/live_plan_20260917.json")!!
            .readBytes().decodeToString()

    @Test
    fun `真实线上响应能被 Seed 解析`() {
        val seed = SeedLoader.json.decodeFromString<Seed>(fixture())
        assertEquals("课程数不对（合并后应为 11）", 11, seed.courses.size)
        assertEquals("时段数不对（合并后应为 17）", 17, seed.slots.size)
        assertEquals("任务数不对（合并后应为 65）", 65, seed.tasks.size)
        assertEquals("资源数不对（合并后应为 145）", 145, seed.resources.size)
        assertEquals(68, seed.study_steps.size)
        assertEquals(11, seed.milestones.size)
    }

    @Test
    fun `解析出来的课表要能对上课名和教室_不是一堆空壳`() {
        val seed = SeedLoader.json.decodeFromString<Seed>(fixture())
        assertTrue("课程名全空，说明字段名对不上（解析出来的都是默认值）",
            seed.courses.any { it.name.isNotBlank() })
        assertTrue("一个时段都没有星期/节次，字段名可能变了",
            seed.slots.any { (it.weekday ?: 0) > 0 && (it.p_start ?: 0) > 0 })
        assertTrue("任务标题全空", seed.tasks.any { it.title.isNotBlank() })
    }

    @Test
    fun `合集日期这类关键字必须真的读进来`() {
        val seed = SeedLoader.json.decodeFromString<Seed>(fixture())
        assertEquals("2026-08-31", seed.meta["semester_start"])
    }

    @Test
    fun `服务端把数字字段写成空字符串时_不能整份计划都解析不出来`() {
        // 2026-09-17 的真实事故：courses[0].target 是 ""，kotlinx 直接抛
        // 「Expected numeric literal at path: $.courses[0].target」，
        // 于是整份计划解析失败，用户屏幕上只剩一行「更新失败：计划数据无法解析」，
        // 课表/任务/资源全都看不见了 —— 而他什么都没做错。
        // 现在的约定：客户端兜住这个脏值（宁可少一个目标分），服务端那头由契约测试盯着。
        val dirty = javaClass.getResourceAsStream("/dirty_target_string.json")!!
            .readBytes().decodeToString()
        val seed = SeedLoader.json.decodeFromString<Seed>(dirty)
        assertEquals("整个计划不能被一个脏字段带走", 11, seed.courses.size)
        assertEquals(65, seed.tasks.size)
        assertNull("空字符串该当成「没有目标分」，不能瞎猜个数字", seed.courses[0].target)
        assertTrue("同一次解析里的其他课也要完好", seed.courses.drop(1).any { it.target != null })
    }
}
