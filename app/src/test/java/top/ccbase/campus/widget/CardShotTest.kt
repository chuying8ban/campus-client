package top.ccbase.campus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 把整张卡（底色 + 帧 + 文字）真渲成一张 PNG，用来人眼验收排版。
 *
 * 为什么值得单独做一张图：方案 02 §2 说"小组件的贵感一半来自排版"，
 * 但排版没法用断言描述（行高、字距、留白、玻璃底）。有了这张图，
 * 每次改 `widget_today*.xml` 都能在 Mac 上看结果，不必等真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardShotTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `导出整卡预览图`() {
        val out = File("build/preview").apply { mkdirs() }
        val w = 900
        val h = 400
        listOf(
            "aurora" to WidgetDyn.spec(DynPainter.Kind.AURORA, 0xFFA06BFF.toInt()),
            "default" to "default",
        ).forEach { (name, spec) ->
            val model = WidgetModel(
                status = "正在上课",
                dateLine = "10月20日 周二",
                week = "第8周",
                headline = "C语言程序设计",
                meta = "11:25–13:50 · 教A301",
                rows = listOf(
                    WidgetRow("13:50", "线性代数", "教B202"),
                    WidgetRow("15:10", "大学物理", "实验楼 305"),
                ),
                footer = "下一节 15:10 · 大学物理",
            )
            // preview = true：帧走小位图，不依赖 content:// 与桌面进程
            val rv = TodayWidgetProvider.viewsSized(ctx, model, w, h, spec, preview = true)
            val host = FrameLayout(ctx)
            val v: View = rv.apply(ctx, host) ?: return@forEach
            v.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(0xFF1B1630.toInt())   // 模拟桌面深色壁纸
            v.draw(c)
            File(out, "card_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
    }
}
