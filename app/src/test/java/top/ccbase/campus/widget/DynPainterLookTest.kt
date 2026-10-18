package top.ccbase.campus.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 把每种动效渲成一张图，用来**人眼验收**"是不是一片黑"
 * （2026-10-19 用户："很多动态主题根本用不了，用了之后主题就变成纯黑色"）。
 *
 * 图落在 `build/dyn-preview/`，跑完可以直接看。断言只有一条：整屏平均亮度不能低到像纯黑。
 * 阈值 12/255 是"肉眼能看出有颜色"的下限，不是美术标准。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DynPainterLookTest {

    @Test
    fun `每种动效都不能黑成一片`() {
        val out = File("build/dyn-preview").apply { mkdirs() }
        val accent = 0xFFA06BFF.toInt()
        val bad = mutableListOf<String>()
        DynPainter.Kind.entries.forEach { kind ->
            val bmp = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            DynPainter.frame(c, kind, 3.5f, 360f, 720f, accent)
            File(out, "${kind.id}.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            // 平均亮度（取中间那块，避开边缘的压暗带）
            var sum = 0L
            var n = 0
            for (y in 200 until 520 step 4) for (x in 60 until 300 step 4) {
                val p = bmp.getPixel(x, y)
                sum += (android.graphics.Color.red(p) * 299 + android.graphics.Color.green(p) * 587 +
                    android.graphics.Color.blue(p) * 114) / 1000
                n++
            }
            val avg = (sum / n).toInt()
            println("${kind.id}: 平均亮度 $avg/255")
            if (avg < 12) bad += "${kind.id}($avg)"
        }
        assertTrue("这些动效黑得像没生效：${bad.joinToString()}", bad.isEmpty())
    }
}
