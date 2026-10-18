package top.ccbase.campus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 小组件底图到底有没有生成（2026-10-19 用户："现在小控件出问题了，主题显示不上"）。
 *
 * 真机上那张卡是 launcher 画的，App 里看不到；但底图是我们在 App 进程里画的，
 * 可以直接断言：有没有图、图多大、边角是否透明。Robolectric 的 `RemoteViews.apply`
 * 不会真正执行 `setImageViewBitmap`（drawable 恒为 null），所以这里测产图这一段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // 要读像素，必须真光栅化
class WidgetThemeApplyTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun saveUserImage() {
        val dir = WidgetThemes.userImageFile(ctx).parentFile!!
        dir.mkdirs()
        val src = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        Canvas(src).apply {
            drawColor(Color.rgb(240, 170, 200))
            drawCircle(300f, 200f, 120f, Paint().apply { color = Color.rgb(120, 40, 90) })
        }
        java.io.FileOutputStream(WidgetThemes.userImageFile(ctx)).use {
            src.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
    }

    @Test
    fun `自己的图片主题_必须铺出位图而不是透明卡`() {
        saveUserImage()
        val bmp = TodayWidgetProvider.backgroundBitmapFor(ctx, WidgetThemes.USER_IMAGE_SPEC, 900, 400)
        assertNotNull("图片主题必须画出底图，画不出就成了「主题显示不上」", bmp)
        bmp!!
        // 桌面卡片只有几百 dp；位图超过 20 万像素 MIUI 会整张丢掉（卡片只剩文字）
        assertTrue("位图太大（${bmp.width}x${bmp.height}）容易被 launcher 丢掉", bmp.width * bmp.height <= 220_000)
        // 图片必须真的铺上去：中心不能是透明，且不能是纯色渐变（那是"没图"时的兜底）
        val center = bmp.getPixel(bmp.width / 2, bmp.height / 2)
        assertTrue("底图中心不透明", Color.alpha(center) == 255)
        // 用户那张图是粉色（240,170,200）压了暗蒙版：红通道明显大于蓝绿差异，不是中性深紫渐变
        assertTrue("中心像素应带图片的红调：${Integer.toHexString(center)}", Color.red(center) > Color.blue(center))
    }

    @Test
    fun `图片文件丢了也不能没有底图_退回渐变`() {
        WidgetThemes.userImageFile(ctx).delete()
        val bmp = TodayWidgetProvider.backgroundBitmapFor(ctx, WidgetThemes.USER_IMAGE_SPEC, 900, 400)
        assertNotNull("图片没了也必须给渐变底，不能整张透明", bmp)
        assertTrue(Color.alpha(bmp!!.getPixel(bmp.width / 2, bmp.height / 2)) > 200)
    }

    @Test
    fun `预置配色也能画出底图`() {
        val bmp = TodayWidgetProvider.backgroundBitmapFor(ctx, "navy", 900, 400)
        assertNotNull(bmp)
        assertTrue(bmp!!.width * bmp.height <= 220_000)
    }
}
