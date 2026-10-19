package top.ccbase.campus.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 毒鸡汤：用户要求"每次进入 App 换一条，尽量不要重复"。
 * 用洗牌队列，不靠随机 —— 这条用例盯的就是"连续发完一整轮之内不许重样"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoupTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `同一轮里不重复，且下一轮开头不等于结尾`() {
        val n = Soup.all.size
        val first = mutableListOf<String>()
        repeat(n) { first += Soup.advance(ctx) }
        assertEquals("一轮应当把全部条目发完", n, first.toSet().size)

        // 队列耗尽后的第一句，不许等于刚刚那句
        val tail = first.last()
        val nextRoundFirst = Soup.advance(ctx)
        assertNotEquals("换轮的第一句不许和上一句重复", tail, nextRoundFirst)
    }

    @Test
    fun `队列跨进程保留（读的是同一个 pref）`() {
        val a = Soup.advance(ctx)
        assertEquals("current 应等于最后一次 advance 的结果", a, Soup.current(ctx))
    }

    @Test
    fun `条目都是非空且长度可控（卡片上放得下）`() {
        Soup.all.forEach {
            assertTrue("空条目", it.isNotBlank())
            assertTrue("太长：$it", it.length <= 40)
        }
        assertTrue("库太小，轮一圈就重复", Soup.all.size >= 40)
    }
}
