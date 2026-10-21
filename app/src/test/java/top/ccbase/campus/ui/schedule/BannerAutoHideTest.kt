package top.ccbase.campus.ui.schedule

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶部核对条「下滑自动隐藏」（用户 2026-10-21）。
 *
 * 这里守三件容易做错的事：
 *  1. 手指抖一下不能就消失（阈值 + 累计，不是单次增量）；
 *  2. 往回翻要能重新露出来，否则用户想点「更新 ›」得先把页面拉回最顶；
 *  3. 父级只**旁观**手势、不消费 —— 消费掉的话页面就滚不动了（比提示条不消失严重得多）。
 */
class BannerAutoHideTest {

    private fun down(state: BannerAutoHideState, px: Float) {
        // 手指上滑 = available.y 为负 = 内容往上走 = 正在往后看
        state.onUserScroll(-px)
    }

    private fun up(state: BannerAutoHideState, px: Float) {
        state.onUserScroll(px)
    }

    @Test
    fun `往下看超过阈值就收起`() {
        val s = BannerAutoHideState()
        assertFalse(s.hidden)
        down(s, 20f)
        assertFalse("才滑 20px 就收起太灵敏了", s.hidden)
        down(s, 30f)
        assertTrue("累计 50px，该收起了", s.hidden)
    }

    @Test
    fun `小幅来回抖动不会闪来闪去`() {
        val s = BannerAutoHideState()
        down(s, 45f)
        assertTrue(s.hidden)
        up(s, 15f)                    // 只回了一点点
        assertTrue("回翻 15px 就弹出来会像闪屏", s.hidden)
        up(s, 40f)                    // 退回到起点之前
        assertFalse("退回去了就该露出来", s.hidden)
    }

    @Test
    fun `复位后回到初始态`() {
        val s = BannerAutoHideState()
        down(s, 200f)
        assertTrue(s.hidden)
        s.reset()
        assertFalse(s.hidden)
        down(s, 30f)
        assertFalse("reset 要把累计也清掉，不然残留量会让它立刻又收起", s.hidden)
    }

    @Test
    fun `父级只旁观不消费_否则页面滚不动`() {
        val s = BannerAutoHideState()
        val consumed = s.connection.onPreScroll(
            Offset(0f, -120f), NestedScrollSource.UserInput,
        )
        assertEquals("必须原样还回去", Offset.Zero, consumed)
        assertTrue("位移照样喂给状态", s.hidden)
    }

    @Test
    fun `惯性与代码滚动不算_进页面自动滚一下不该把提示条弄没`() {
        val s = BannerAutoHideState()
        s.connection.onPreScroll(Offset(0f, -200f), NestedScrollSource.SideEffect)
        assertFalse(s.hidden)
    }
}
