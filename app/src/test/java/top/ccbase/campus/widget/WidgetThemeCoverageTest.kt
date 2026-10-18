package top.ccbase.campus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import top.ccbase.campus.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「每一个小组件主题都画得出来」（2026-10-19 用户："小组件主题还是不能切换"、
 * "务必要让这些功能能够正常使用"）。
 *
 * 逐条扫过所有规格：纯色预置 / 随包图片 / 时间主题 / 每种动效。
 * 只要某一个规格产出的底图是空的、或者整张黑得看不出颜色，这里就红。
 * 这类"个别主题用不了"的毛病，靠用户一个个点太慢，必须让机器扫一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetThemeCoverageTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun brightness(b: Bitmap): Int {
        var sum = 0L
        var n = 0
        for (y in b.height / 4 until b.height * 3 / 4 step 4) {
            for (x in b.width / 4 until b.width * 3 / 4 step 4) {
                val p = b.getPixel(x, y)
                sum += (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                n++
            }
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    @Test
    fun `纯色与图片预置都要画得出来且不是纯黑`() {
        val bad = mutableListOf<String>()
        (WidgetThemes.presets + WidgetThemes.imageThemes + WidgetThemes.dynamic()).forEach { t ->
            val bmp = TodayWidgetProvider.backgroundBitmapFor(ctx, t.id, 900, 400)
            if (bmp == null) {
                bad += "${t.id}: 没出图"
            } else {
                val b = brightness(bmp)
                if (b < 8) bad += "${t.id}: 亮度 $b"
            }
        }
        assertTrue("这些主题画不出来/黑成一片：${bad.joinToString()}", bad.isEmpty())
    }

    @Test
    fun `每种动效都要出帧_且帧不是纯黑`() {
        val bad = mutableListOf<String>()
        val accent = 0xFFA06BFF.toInt()
        DynPainter.Kind.entries.forEach { k ->
            val spec = WidgetDyn.spec(k, accent)
            val frames = WidgetFrames.forSpec(ctx, spec, 900, 400)
            if (frames.isNullOrEmpty()) {
                bad += "${k.id}: 没出帧"
            } else if (brightness(frames[0]) < 8) {
                bad += "${k.id}: 亮度 ${brightness(frames[0])}"
            }
        }
        assertTrue("这些动效在小组件上是空的/黑的：${bad.joinToString()}", bad.isEmpty())
    }

    /**
     * 帧位数量必须等于帧数（2026-10-19 的坑：布局 12 个帧位只灌 4 帧，
     * ViewAnimator 不看可见性、按子 View 轮播，78% 的时间翻到空白 → 卡片看着像纯黑）。
     */
    @Test
    fun `轮播帧位数量必须等于实际帧数`() {
        val root = android.view.LayoutInflater.from(ctx)
            .inflate(R.layout.widget_today, android.widget.FrameLayout(ctx))
        val flip = root.findViewById<android.widget.ViewFlipper>(R.id.widget_flip)
        assertTrue(
            "帧位 ${flip.childCount} 个，但实际帧数是 ${WidgetFrames.FRAMES} —— 多出来的位子会翻成空白",
            flip.childCount == WidgetFrames.FRAMES,
        )
    }

    @Test
    fun `图片读不出来时_不许把主题落地`() {
        // 先清掉用户图，再试图应用 `user`：必须被拦下，并且原来的主题不被覆盖
        runCatching { WidgetThemes.userImageFile(ctx).delete() }
        WidgetThemes.set(ctx, "default")
        val err = WidgetThemes.apply(ctx, WidgetThemes.USER_IMAGE_SPEC)
        assertNotNull("没有图的时候必须拒绝并给出提示", err)
        assertTrue("被拒绝时不能改掉当前主题", WidgetThemes.spec(ctx) == "default")
    }
}
