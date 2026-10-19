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
import top.ccbase.campus.widget.WidgetRow
import top.ccbase.campus.widget.WidgetThemes

/**
 * 把合并后的「预览 = 自检」那张卡**画成 PNG**（2026-10-19 用户："做完先别发布"——
 * 他要先看效果，我又拿不到他手机的 adb，就自己先看一眼）。
 *
 * 卡走的是真 RemoteViews，所以在 NATIVE 光栅化下画出来的就是桌面那张的样子。
 * 产物：/Users/a29285/ChenC/闪传/widget_stage_preview.png
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xhdpi")
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
    fun `画一张预览卡`() {
        val spec = WidgetThemes.presets.first().id
        rule.setContent {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF141018)).padding(16.dp),
            ) {
                Text("同一条路：预览 = 桌面那份 RemoteViews", color = Color(0xFFBBA9FF), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                WidgetStageCard(stage(), spec = spec, alpha = 1f)
                Spacer(Modifier.height(18.dp))
                Text("透明度 45%（同一条 fade 路径）", color = Color(0xFF7E7794), fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                WidgetStageCard(stage(), spec = spec, alpha = 0.45f)
                Spacer(Modifier.height(18.dp))
                WidgetDiagPanel(stage(), spec = spec, savedSpec = spec, onTrySpec = { })
            }
        }
        rule.waitForIdle()
        Thread.sleep(600)
        rule.waitForIdle()

        val decor = rule.activity.window.decorView
        val w = decor.width.coerceAtLeast(1)
        val h = decor.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).let { decor.draw(it) }

        val out = File("/Users/a29285/ChenC/闪传/widget_stage_preview.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("截图没写出来", out.isFile && out.length() > 1000)
        println("SHOT ${out.absolutePath} ${out.length()}B ${w}x$h")
    }
}
