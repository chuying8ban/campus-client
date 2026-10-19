package top.ccbase.campus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 临时：把动态主题第 0 帧拼成一张对照图，人眼验收"是不是还是很廉价"。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewSheetTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `拼一张对照图`() {
        val out = File("build/preview").apply { mkdirs() }
        val accent = 0xFFA06BFF.toInt()
        val t0 = System.nanoTime()
        val timing = WidgetFrames.forSpec(ctx, WidgetDyn.spec(DynPainter.Kind.AURORA, accent), 480, 270)
        println("TIMING 40 帧 480x270: ${(System.nanoTime() - t0) / 1_000_000} ms (frames=${timing?.size})")
        val kinds = listOf(
            DynPainter.Kind.AURORA, DynPainter.Kind.NEBULA, DynPainter.Kind.BOKEH,
            DynPainter.Kind.SILK, DynPainter.Kind.BREATHE, DynPainter.Kind.WAVES,
        )
        val cw = 300
        val ch = 150
        val sheet = Bitmap.createBitmap(cw * 3, ch * 2, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet)
        sc.drawColor(Color.BLACK)
        kinds.forEachIndexed { i, k ->
            val spec = WidgetDyn.spec(k, accent)
            val frames = WidgetFrames.forSpec(ctx, spec, cw, ch)!!
            val f = frames[0]
            sc.drawBitmap(f, ((i % 3) * cw).toFloat(), ((i / 3) * ch).toFloat(), Paint())
            File(out, "${k.id}.png").outputStream().use { f.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(out, "sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
