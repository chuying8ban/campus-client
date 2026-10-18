package top.ccbase.campus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「自己上传的那张图」到底有没有画进小组件底图（2026-10-19 用户截图：
 * 自检面板写着 `user · 底图 519×230`，但那张底图是黑的）。
 *
 * 这条把用户手上的路径整条走一遍：存图 → 设主题 → 出底图 → 读中心像素。
 * 只要中间哪一步悄悄退化成渐变，中心像素就不会带图里的颜色。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetUserImageTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `自己选的图必须真的画进底图_而不是退回渐变`() {
        val src = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        Canvas(src).drawColor(Color.rgb(220, 40, 40))          // 纯红，任何缩放都不该变成黑
        assertTrue("存图失败", WidgetThemes.saveUserImage(ctx, src))
        WidgetThemes.set(ctx, WidgetThemes.USER_IMAGE_SPEC)

        val bmp = TodayWidgetProvider.backgroundBitmapFor(ctx, WidgetThemes.USER_IMAGE_SPEC, 900, 400)
        assertNotNull("底图要出得来", bmp)

        val c = bmp!!.getPixel(bmp.width / 2, bmp.height / 2)
        println("中心像素 = #${Integer.toHexString(c)}  尺寸=${bmp.width}x${bmp.height}")
        assertTrue(
            "中心像素应该是红的（图上盖了暗蒙版，但红通道仍应明显高于蓝）：#${Integer.toHexString(c)}",
            Color.red(c) > Color.blue(c) + 20,
        )
    }
}
