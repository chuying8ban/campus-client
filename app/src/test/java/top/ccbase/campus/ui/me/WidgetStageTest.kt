package top.ccbase.campus.ui.me

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.R
import top.ccbase.campus.widget.TodayWidgetProvider
import top.ccbase.campus.widget.WidgetModel
import top.ccbase.campus.widget.WidgetRow
import top.ccbase.campus.widget.WidgetThemes

/**
 * 预览与自检**必须是同一套**（2026-10-19 用户："我希望预览和自检用同一套系统，把它们合并一下"）。
 *
 * 合并前预览是 Compose 手画的一张"像桌面"的卡，自检才是真 RemoteViews ——
 * 于是同一张卡在 App 里有两个样子，桌面真出问题时预览还在骗人。
 *
 * 这里守三件事，任何一条挂了就说明"又分家了"：
 *   ① 卡里画的是**真 RemoteViews**（能找到布局里的 view id 和真实课表文字），不是仿的；
 *   ② 尺寸口径 = launcher 报的格子 dp × 密度（预览与自检共用一个 [WidgetStageInfo]）；
 *   ③ 透明度和桌面走同一个位图衰减路径（而不是在 Compose 层盖一层 alpha）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) // 要读像素，必须真光栅化
class WidgetStageTest {

    @get:Rule
    val compose = androidx.compose.ui.test.junit4.createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val model = WidgetModel(
        status = "下一节",
        dateLine = "10月19日 周一",
        week = "第 8 周",
        headline = "高等数学 A",
        meta = "10:10–11:45 · 教A301",
        rows = listOf(WidgetRow("14:00", "大学物理", "教B205")),
        footer = "第 8 周 · 周一",
    )

    private fun stage(wPx: Int = 880, hPx: Int = 443) = WidgetStageInfo(
        model = model,
        deskW = 320f,
        deskH = 161f,
        rows = 2,
        wPx = wPx,
        hPx = hPx,
        instances = emptyList(),
    )

    @Test
    fun `卡片里挂的是桌面那份 RemoteViews`() {
        val host = FrameLayout(ctx)
        mountWidgetViews(host, ctx, stage(), WidgetThemes.presets.first().id, 1f)

        assertEquals("卡片里应该只有一个子 View（RemoteViews 解出来的那棵）", 1, host.childCount)
        val root = host.getChildAt(0)
        // 布局里的真 id 都在 ⇒ 这是 widget_today.xml 解出来的，不是 Compose 仿画的
        assertNotNull("没找到 widget_head —— 挂的不是真布局", root.findViewById<TextView>(R.id.widget_head))
        assertEquals(
            "课名必须来自真实课表 model", "高等数学 A",
            root.findViewById<TextView>(R.id.widget_head).text.toString(),
        )
        assertNotNull("没生成底图", root.findViewById<ImageView>(R.id.widget_bg_img).drawable)
    }

    @Test
    fun `自检只剩读数 —— 不再自己画第二张卡`() {
        // 合并前：预览一张（Compose 仿画）、自检一张（真 RemoteViews），两张对不上。
        // 现在自检面板里不该再有画面，只有读数；点开才展开。
        compose.setContent {
            top.ccbase.campus.ui.theme.CampusTheme {
                WidgetDiagPanel(
                    stage(),
                    spec = WidgetThemes.presets.first().id,
                    savedSpec = WidgetThemes.presets.first().id,
                    onTrySpec = { },
                )
            }
        }
        // 折叠态：只有一行标题，明说"上面那张卡就是桌面那份"
        compose.onNodeWithText("小组件自检").assertIsDisplayed()
        compose.onNodeWithText("上面那张卡就是桌面那份 · 320×161dp").assertIsDisplayed()
        // 读数默认不展开
        compose.onAllNodesWithText("桌面实例", substring = true).assertCountEquals(0)

        compose.onNodeWithText("小组件自检").performClick()
        compose.waitForIdle()
        // 展开后能看到读数（没有实例时给的是添加指引）
        compose.onAllNodesWithText("桌面上还没有这个小组件", substring = true).assertCountEquals(1)
        // 关键：面板里不再有第二个渲染容器 —— 说明画面只有上面那一张
        compose.onAllNodesWithText("RemoteViews 渲染失败", substring = true).assertCountEquals(0)
    }

    @Test
    fun `预览卡片取的就是自检那份尺寸 —— 只有一个来源`() {
        // 把预览卡片真渲染一遍，抓它拿到的 WidgetStageInfo，和 launcher 报的格子对账。
        // 以前预览写死 2:1、自检写死 900×400，两边各算一遍，才会"看着不一样"。
        var captured: WidgetStageInfo? = null
        compose.setContent {
            val stage = rememberWidgetStageInfo(ctx, refreshKey = 0)
            captured = stage
            WidgetStageCard(stage, spec = WidgetThemes.presets.first().id, alpha = 1f)
        }
        compose.waitForIdle()

        val info = captured ?: throw AssertionError("预览卡片没取到 WidgetStageInfo")
        val density = ctx.resources.displayMetrics.density
        val desk = TodayWidgetProvider.deskSizeDp(ctx)
        assertEquals(desk.first, info.deskW, 0.01f)
        assertEquals(desk.second, info.deskH, 0.01f)
        assertEquals((desk.first * density).toInt().coerceAtLeast(200), info.wPx)
        assertEquals((desk.second * density).toInt().coerceAtLeast(120), info.hPx)
        assertEquals(TodayWidgetProvider.rowsFor(TodayWidgetProvider.usableHDp(desk.second)), info.rows)
    }


    @Test
    fun `透明度走位图的真路径，不是 Compose 层假透明`() {
        val spec = WidgetThemes.presets.first().id

        fun bgAvgAlpha(a: Float): Int {
            val host = FrameLayout(ctx)
            mountWidgetViews(host, ctx, stage(), spec, a)
            val d = host.getChildAt(0).findViewById<ImageView>(R.id.widget_bg_img).drawable
            val bmp = (d as? BitmapDrawable)?.bitmap
                ?: throw AssertionError("底图不是位图 —— 走错分支了")
            // 只采中间那片：四个圆角本来就是透明的，采进来会误判
            val x0 = (bmp.width * 0.2f).toInt(); val x1 = (bmp.width * 0.8f).toInt()
            val y0 = (bmp.height * 0.3f).toInt(); val y1 = (bmp.height * 0.7f).toInt()
            var sum = 0L; var n = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    sum += bmp.getPixel(x, y) ushr 24; n++
                    x += ((x1 - x0) / 10).coerceAtLeast(1)
                }
                y += ((y1 - y0) / 10).coerceAtLeast(1)
            }
            return (sum / n).toInt()
        }

        val opaque = bgAvgAlpha(1f)
        val half = bgAvgAlpha(0.5f)
        assertTrue("alpha=1 时底图不该是半透明的：平均 alpha=$opaque", opaque >= 240)
        assertTrue("alphaOverride 没生效：平均 alpha=$half（应约为 128）", half in 90..170)
        assertTrue("alphaOverride 方向不对", half < opaque)
    }
}
