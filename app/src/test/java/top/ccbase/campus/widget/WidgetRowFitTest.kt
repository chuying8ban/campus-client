package top.ccbase.campus.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.R
import kotlin.math.abs

/**
 * 小组件"放得下几行"这件事，用**量出来的高度**守，不靠估。
 *
 * 背景（2026-10-19）：用户截图里桌面卡片最后一行被裁掉半行。根源是 [rowsFor] 的阈值
 * 比真实排版需要的高度松（报 161dp 的格子实际只剩一百二十出头），于是按"能放 2~3 行"
 * 画了，物理上却装不下。
 *
 * 这里用 Robolectric 把 `widget_today.xml` 真的 inflate 一遍、真的 measure 一次，
 * 得到每一档行数需要的高度，再断言 [rowsFor] 在任意高度下都不会要求超过它。
 * 以后改字号/间距/行高，这个用例会直接红 —— 不用等用户在桌面上看到半行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetRowFitTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val d get() = ctx.resources.displayMetrics.density

    /** 真的画一遍，量出"画 n 行"需要多少 dp。 */
    private fun needDp(rows: Int, wDp: Int = 320): Float {
        val v = LayoutInflater.from(ctx).inflate(R.layout.widget_today, null)
        (v.findViewById<TextView>(R.id.widget_status)).text = "正在上课"
        (v.findViewById<TextView>(R.id.widget_date)).text = "10月19日 周一"
        (v.findViewById<TextView>(R.id.widget_week)).text = "第8周"
        (v.findViewById<TextView>(R.id.widget_head)).text = "大学体育Ⅰ（必修项目）"
        (v.findViewById<TextView>(R.id.widget_meta)).text = "8-9 节 · 17:55-19:30 · 足球场"
        val ids = intArrayOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3)
        ids.forEachIndexed { i, id ->
            val t = v.findViewById<TextView>(id)
            if (i < rows) {
                t.visibility = View.VISIBLE
                t.text = "日程 · 20:00　去c5拿名单 · C5 楼 1 区 Z01"
            } else t.visibility = View.GONE
        }
        (v.findViewById<TextView>(R.id.widget_foot)).text = "下一节 20:30"
        v.measure(
            View.MeasureSpec.makeMeasureSpec((wDp * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return v.measuredHeight / d
    }

    @Test
    fun `任何高度下都不会要求画出装不下的行`() {
        val need = FloatArray(4) { needDp(it) }
        var checked = 0
        for (h in 80..280) {
            val rows = TodayWidgetProvider.rowsFor(TodayWidgetProvider.usableHDp(h.toFloat()))
            if (rows > 0) {
                assertTrue(
                    "报 ${h}dp 时按 rowsFor 会画 $rows 行，但画 $rows 行需要 ${need[rows]}dp —— 会被裁",
                    need[rows] <= h + 0.5f,
                )
                checked++
            }
        }
        assertTrue("至少要覆盖一段有行的高度区间，否则这个用例等于没测", checked > 40)
    }

    @Test
    fun `安全边距真的把行数压下来了（用户那台 161dp 的格子）`() {
        // MIUI 4×2 报 161dp，实际高度不够画 2 行（2 行要 139dp，但宿主还会再吃掉一点）
        val rows = TodayWidgetProvider.rowsFor(TodayWidgetProvider.usableHDp(161f))
        assertTrue("161dp 的格子不该再按 2 行以上去画，实际 rows=$rows", rows <= 2)
        // 而且要保证 1 行真能放下（否则就是"什么都不显示"）
        assertTrue("至少得放得下 1 行", rows >= 1)
    }

    @Test
    fun `高度报得很小的时候宁可不画行`() {
        assertTrue(TodayWidgetProvider.rowsFor(TodayWidgetProvider.usableHDp(100f)) == 0)
    }

    @Test
    fun `预览与自检用的是同一套尺寸口径`() {
        // deskSizeDp 在桌面上没有实例时给 320×161（典型 4×2），这个值要和阈值自洽：
        // 161 → 至少 1 行、不超过 2 行，预览和桌面才不会一个三行一个一行。
        val (w, h) = TodayWidgetProvider.deskSizeDp(ctx)
        assertTrue("兜底宽度不合理：$w", abs(w - 320f) < 0.5f)
        assertTrue("兜底高度不合理：$h", abs(h - 161f) < 0.5f)
        val rows = TodayWidgetProvider.rowsFor(TodayWidgetProvider.usableHDp(h))
        assertTrue("兜底尺寸下算出的行数不合理：$rows", rows in 1..2)
    }
}
