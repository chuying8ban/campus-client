package top.ccbase.campus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import top.ccbase.campus.data.local.Task
import top.ccbase.campus.ui.tasks.trackColor

/**
 * 用户要求：「把任务做一下分类，每种任务用不同的颜色表示，并且支持用户分类查看」。
 * 这几条钉的是分类的三件事：颜色唯一、筛选只列真实存在的分类、筛完真的只剩那一类。
 */
@RunWith(RobolectricTestRunner::class)
class TaskTrackTest {

    private fun t(id: Int, track: String?) = Task(
        id = id, phase = "本学期", phase_order = 1, title = "任务$id", track = track,
    )

    @Test
    fun `六类任务各有各的颜色_没有两类撞色`() {
        val colors = TRACK_ORDER.map { trackColor(it) }
        assertEquals("颜色数要和分类数一致", TRACK_ORDER.size, colors.size)
        assertEquals("有分类共用了同一个颜色（用户要求'每种任务用不同颜色'）",
            TRACK_ORDER.size, colors.toSet().size)
        // 未知分类走「其他」那一档，颜色不能和任何已知类撞
        val other = trackColor("服务端以后新加的类")
        assertFalse("未知分类的颜色不该和已知类相同", colors.contains(other))
        assertEquals("未知分类和「其他」同色", trackColor("其他"), other)
    }

    @Test
    fun `同一个分类在任何地方都是同一个颜色_不随列表顺序变`() {
        assertEquals(trackColor("课程"), trackColor("课程"))
        assertEquals("带空格的 track 要归一", trackColor(" 自学 "), trackColor("自学"))
        assertEquals("空 track 归到其他", trackColor(null), trackColor("其他"))
    }

    @Test
    fun `筛选项只列数据里真出现过的分类_顺序固定`() {
        val tasks = listOf(t(1, "课程"), t(2, "自学"), t(3, "课程"), t(4, null))
        val filters = trackFilters(tasks)
        assertEquals(listOf("课程", "自学", "其他"), filters)
        assertFalse("没出现过的分类不该出现在筛选里", filters.contains("考试"))

        val onlyCourse = trackFilters(listOf(t(1, "课程")))
        assertEquals(listOf("课程"), onlyCourse)
    }

    @Test
    fun `按分类筛选_只留这一类_null 表示全部`() {
        val tasks = listOf(t(1, "课程"), t(2, "自学"), t(3, null))
        assertEquals(3, tasks.count { matchesTrack(it, null) })
        assertEquals(1, tasks.count { matchesTrack(it, "课程") })
        assertEquals(1, tasks.count { matchesTrack(it, "自学") })
        assertEquals("「其他」要能筛出没分类的", 1, tasks.count { matchesTrack(it, "其他") })
        assertEquals("不存在的分类筛完是空的", 0, tasks.count { matchesTrack(it, "考试") })
        assertTrue("track 带空格也要能筛中", matchesTrack(t(4, " 课程 "), "课程"))
    }
}
