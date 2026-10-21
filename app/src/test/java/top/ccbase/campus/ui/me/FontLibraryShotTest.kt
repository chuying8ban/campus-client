package top.ccbase.campus.ui.me

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import top.ccbase.campus.data.local.RemoteFontStore
import top.ccbase.campus.data.remote.FontCatalog
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 字体库两个板块出图。
 *
 * 为什么这几张图非出不可：这一版的验收点是「本地字体库 / 在线字体下载」两个板块
 * 和「下载后进本地库」——布局挤不挤、样张有没有遮住名字，只有图能看出来。
 * 产物：/tmp/font_local.png、/tmp/font_online.png
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontLibraryShotTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = ApplicationProvider.getApplicationContext<top.ccbase.campus.CampusApplication>()

    @Before
    fun setUp() {
        File(ctx.filesDir, RemoteFontStore.DIR).deleteRecursively()
        RemoteFontStore.resetForTest()
        FontCatalog.loadCached(ctx)
    }

    @org.junit.After
    fun tearDown() {
        // 留下文件会让 Robolectric 在销毁临时目录时刷一条 DirectoryNotEmptyException（红得冤）
        File(ctx.filesDir, RemoteFontStore.DIR).deleteRecursively()
        RemoteFontStore.resetForTest()
    }

    private fun shot(name: String) {
        Thread.sleep(700)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File("/tmp/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT $name")
    }

    /**
     * 「已下载」那一段的实图。
     *
     * 需要一个真 ttf 才有意义（样张要用它自己的字体渲染），本机字体流水线的产物在
     * `~/ChenC/projects/fontpipe/dist/`；换台机器跑就跳过，不硬依赖。
     */
    @Test
    fun `已下载板块_有真字体时才出图`() {
        val fixture = File(System.getProperty("user.home") + "/ChenC/projects/fontpipe/dist/alibaba.ttf")
        org.junit.Assume.assumeTrue("本机没有字体流水线产物，跳过", fixture.exists())
        fixture.copyTo(File(ctx.filesDir, RemoteFontStore.DIR + "/alibaba.ttf").also { it.parentFile.mkdirs() }, overwrite = true)
        RemoteFontStore.resetForTest()
        RemoteFontStore.refresh(ctx)

        rule.activity.setContent { CampusTheme { FontLibraryScreen(target = "app", onClose = {}) } }
        runCatching {
            rule.waitUntil(10_000) { rule.onAllNodesWithText("已下载", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
        shot("font_local_downloaded")
    }

    @Test
    fun `两个板块都能出图`() {
        rule.activity.setContent { CampusTheme { FontLibraryScreen(target = "app", onClose = {}) } }
        runCatching {
            rule.waitUntil(10_000) { rule.onAllNodesWithText("本地字体库", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
        shot("font_local")

        rule.onNodeWithTag("fontTab:online").performClick()
        runCatching { rule.waitUntil(10_000) { rule.onAllNodesWithText("在线字体下载", substring = true).fetchSemanticsNodes().isNotEmpty() } }
        shot("font_online")
        // 往下滚一屏，看清单里的分组和中段卡片
        runCatching { rule.onNodeWithTag("online:lxgw_wenkai").performScrollTo() }
        shot("font_online_2")
    }
}
