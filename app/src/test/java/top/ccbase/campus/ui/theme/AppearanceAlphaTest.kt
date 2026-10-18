package top.ccbase.campus.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 换背景时"浓淡"的默认档（2026-10-18 用户："浓淡默认为 50"）。
 *
 * 之前浓淡是全局一个值：把某张照片调到 15% 之后，再换动态主题，新背景一上来就淡到看不清，
 * 用户只会觉得"动态没生效"。所以跨**类别**换背景时拉回 50%，同类别之间来回换尊重他手调的值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearanceAlphaTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `换到另一类背景时浓淡回到百分之五十`() {
        AppearanceStore.setBgAlpha(ctx, 0.15f)
        AppearanceStore.setBackground(ctx, ANIM_PREFIX + "aurora:ff112233")
        assertEquals(0.5f, AppearanceStore.bgAlphaOf(ctx), 0.001f)
    }

    @Test
    fun `同一类背景之间换_保留用户自己调的浓淡`() {
        AppearanceStore.setBackground(ctx, ANIM_PREFIX + "aurora:ff112233")
        AppearanceStore.setBgAlpha(ctx, 0.30f)
        AppearanceStore.setBackground(ctx, ANIM_PREFIX + "snow:ff88aaff")
        assertEquals(0.30f, AppearanceStore.bgAlphaOf(ctx), 0.001f)
    }

    @Test
    fun `默认值就是百分之五十`() {
        ctx.getSharedPreferences("campus_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(0.5f, AppearanceStore.bgAlphaOf(ctx), 0.001f)
    }
}
