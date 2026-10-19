package top.ccbase.campus.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 在线图库选的图和用户自己上传的图必须分开存。
 *
 * 以前图库选图是直接盖 `widget_theme.jpg`（也就是「我的图」那一份），
 * 于是"只是去图库里逛一圈挑个主题"，自己上传的那张就被顶掉了 —— 真机测试里丢过一张。
 * 现在图库走 `widget_art.jpg` + 规格 `art`。
 */
@RunWith(RobolectricTestRunner::class)
class WidgetImageSlotTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun 图库图和我自己的图是两个不同的文件() {
        assertNotEquals(WidgetThemes.userImageFile(ctx).name, WidgetThemes.artImageFile(ctx).name)
    }

    @Test
    fun art规格是图库图不是我的图() {
        val t = WidgetThemes.of(WidgetThemes.ART_IMAGE_SPEC)
        assertTrue(t.artImage)
        assertFalse(t.userImage)
        assertTrue(t.hasImage)
    }

    @Test
    fun user规格仍然是我的图() {
        val t = WidgetThemes.of(WidgetThemes.USER_IMAGE_SPEC)
        assertTrue(t.userImage)
        assertFalse(t.artImage)
    }
}
