package top.ccbase.campus.ui.me

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.Transport
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「我的」页出图 —— 删功能（看板/密码）之后要**看图核对**，不能只看源码 grep 过。
 * 产物：/tmp/me_mid.png（提醒那一带）、/tmp/me_bottom.png（关于那一带）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeScreenShotTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: CampusDb

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<CampusApplication>()
        db = Room.inMemoryDatabaseBuilder(app, CampusDb::class.java).allowMainThreadQueries().build()
        rule.activity.setContent {
            CampusTheme {
                MeScreen(
                    ctx = rule.activity,
                    db = db,
                    api = CampusApi(transport = Transport { _, _, _, _ -> HttpReply(200, "{}") }),
                    version = "3.4.71",
                    onLogin = {},
                )
            }
        }
        runCatching { rule.waitUntil(8_000) { rule.onAllNodesWithText("提醒").fetchSemanticsNodes().isNotEmpty() } }
    }

    @After
    fun tearDown() = db.close()

    private fun shot(name: String, anchor: String) {
        rule.onNodeWithText(anchor).performScrollTo()
        Thread.sleep(400)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File("/tmp/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT $name")
    }

    @Test
    fun `提醒之后直接是建议与外观和关于_没有看板和密码`() {
        shot("me_mid", "提醒")
    }

    @Test
    fun `关于那一节`() {
        shot("me_bottom", "关于")
    }
}
