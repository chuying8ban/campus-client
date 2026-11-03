package top.ccbase.campus.ui.me

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我希望可以同时下载多个素材」（用户 2026-11-03 原话）。
 *
 * 以前的 MediaJob 是**单个** id/pct/bytes，图库里也用 `busyId` 挡着：
 * 一件在下，点别的格子直接被 `return`。用户想连点三张一起下，只能等。
 *
 * 这条钉的是"多件同时在跑也不会互相串"：
 *  ① 两件可以同时 start，都在 active 里；
 *  ② 进度各算各的（一件 80%、一件 20%，加起来不是任意一件的数）；
 *  ③ 一件 finish 不影响另一件；
 *  ④ 汇总百分比只在两边都知道总量时才算，否则 -1（画不确定进度条）。
 */
class MediaJobTest {

    @After
    fun tearDown() {
        MediaJob.active.forEach { MediaJob.finish(it.id) }
    }

    @Test
    fun `两件素材可以同时在下`() {
        MediaJob.start("a", "图 A", 1000)
        MediaJob.start("b", "图 B", 2000)
        assertEquals(2, MediaJob.count)
        assertTrue(MediaJob.isRunning("a") && MediaJob.isRunning("b"))
    }

    @Test
    fun `进度各算各的`() {
        MediaJob.start("a", "图 A", 1000)
        MediaJob.start("b", "图 B", 2000)
        MediaJob.progress("a", 800, 1000)
        MediaJob.progress("b", 400, 2000)
        assertEquals(80, MediaJob.pct("a"))
        assertEquals(20, MediaJob.pct("b"))
        assertEquals(800L, MediaJob.bytes("a"))
        assertEquals(400L, MediaJob.bytes("b"))
    }

    @Test
    fun `一件下完不影响另一件`() {
        MediaJob.start("a", "图 A", 100)
        MediaJob.start("b", "图 B", 100)
        MediaJob.progress("b", 50, 100)
        MediaJob.finish("a")
        assertFalse(MediaJob.isRunning("a"))
        assertTrue("另一件必须还在下", MediaJob.isRunning("b"))
        assertEquals(50, MediaJob.pct("b"))
    }

    @Test
    fun `汇总进度：两边都知道总量才算百分比`() {
        MediaJob.start("a", "图 A", 1000)
        MediaJob.start("b", "图 B", 3000)
        MediaJob.progress("a", 1000, 1000)
        MediaJob.progress("b", 1000, 3000)
        // (1000+1000) / (1000+3000) = 50%
        assertEquals(50, MediaJob.aggregatePct())
        assertEquals(2000L, MediaJob.aggregateBytes())

        // 有一件总量未知 → 不给假百分比
        MediaJob.progress("b", 1200, 0)
        assertEquals(-1, MediaJob.aggregatePct())
    }

    @Test
    fun `不认识的总量给已下载字节，不给百分比`() {
        MediaJob.start("c", "动图", 0)
        MediaJob.progress("c", 512_000, 0)   // 512000 B = 500 KB
        assertEquals(-1, MediaJob.pct("c"))
        assertEquals("500 KB", MediaJob.humanBytes(MediaJob.bytes("c")))
    }
}
