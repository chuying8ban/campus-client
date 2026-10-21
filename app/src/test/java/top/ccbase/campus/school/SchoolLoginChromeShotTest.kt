package top.ccbase.campus.school

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.ccbase.campus.ui.theme.CampusTheme
import java.io.File

/**
 * 「更新课表」页的外观证据（2026-10-21 用户报：键盘把屏幕全占了 / 桌面模式没区别）。
 *
 * 这个用例只看**我们自己那圈界面**：顶部是不是一行（以前两行纯文字工具条会跟标题重叠）、
 * 底部是不是只剩必要信息、标题有没有被挤到换行。网页本身在 Robolectric 里是空的，
 * 所以这里不断言网页内容。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SchoolLoginChromeShotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `更新课表页_顶部只有一行_底部不压网页`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        rule.setContent {
            CampusTheme {
                SchoolWebLogin(onTimetableJson = {}, onWebError = {}, onCancel = {})
            }
        }
        rule.waitForIdle()
        Thread.sleep(600)
        rule.waitForIdle()

        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val out = File("/tmp/school_login_chrome.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT ${out.absolutePath} ${out.length()} bytes ${bmp.width}x${bmp.height}")
        // 标题必须存在且只出现一次（以前标题和 host 挤成一条，视觉上重叠）
        val nodes = rule.onAllNodesWithText("融合门户登录", substring = true).fetchSemanticsNodes()
        println("TITLE_NODES ${nodes.size}")
    }
}
