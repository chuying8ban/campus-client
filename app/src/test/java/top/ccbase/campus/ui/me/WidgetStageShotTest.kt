package top.ccbase.campus.ui.me

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.ccbase.campus.widget.WidgetModel
import top.ccbase.campus.widget.WidgetThemes

/**
 * 把合并后的「预览 = 自检」那张卡**画成 PNG**（2026-10-19 用户："做完先别发布"——
 * 他要先看效果，我又拿不到他手机的 adb，就自己先看一眼）。
 *
 * 卡走的是真 RemoteViews，所以在 NATIVE 光栅化下画出来的就是桌面那张的样子。
 * 产物：/Users/a29285/ChenC/闪传/widget_stage_preview.png
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h1100dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetStageShotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val model = WidgetModel(
        status = "自习中",
        dateLine = "10月19日 周一",
        week = "第 8 周",
        headline = "自习",
        meta = "20:30-22:05 · C5 楼 1 区 301",
        rows = emptyList(),
        footer = "第 8 周 · 周一",
    )

    private fun stage() = WidgetStageInfo(
        model = model, deskW = 320f, deskH = 161f, rows = 2, wPx = 880, hPx = 443,
        instances = emptyList(),
    )

    @Test
    fun `四类主题的圆角对照`() {
        // 2026-10-19 用户："我希望小组件的所有主题的圆角跟这个一样"（对标小米天气那块卡）。
        // 四类主题画在同一张图上，一眼看出圆角是不是真的统一了。
        val specs = listOf(
            "纯色" to WidgetThemes.presets.first().id,
            "随包图片" to WidgetThemes.imageThemes.first().id,
            "另一张图片" to WidgetThemes.imageThemes.last().id,
            "动态主题" to top.ccbase.campus.ui.theme.DYN_PRESETS.first().spec,
        )
        rule.setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF141018)).padding(14.dp)) {
                Text(
                    "四类主题 · 同一个圆角（桌面那份 RemoteViews 真渲染）",
                    color = Color(0xFFBBA9FF), fontSize = 12.sp,
                )
                specs.forEach { (label, spec) ->
                    Spacer(Modifier.height(10.dp))
                    Text(label, color = Color(0xFF7E7794), fontSize = 10.sp)
                    Spacer(Modifier.height(4.dp))
                    WidgetStageCard(stage(), spec = spec, alpha = 1f)
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(800)
        rule.waitForIdle()

        val decor = rule.activity.window.decorView
        val w = decor.width.coerceAtLeast(1)
        val h = decor.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).let { decor.draw(it) }

        val out = File("/Users/a29285/ChenC/闪传/widget_radius_compare.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("截图没写出来", out.isFile && out.length() > 1000)
        println("SHOT ${out.absolutePath} ${out.length()}B ${w}x$h")
    }
}
