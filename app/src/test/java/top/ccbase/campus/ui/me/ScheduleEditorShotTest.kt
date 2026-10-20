package top.ccbase.campus.ui.me

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.ManualSchedule
import top.ccbase.campus.data.local.PeriodTableStore
import top.ccbase.campus.data.local.SelfStudy
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 把「编辑课表」渲染成 PNG —— 好不好的事我不能只看语义树。
 * 产物：/tmp/editor_day.png、/tmp/editor_table.png、/tmp/editor_form.png
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleEditorShotTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: CampusDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CampusDb::class.java,
        ).allowMainThreadQueries().build()
        PeriodTableStore.resetForTest()
        runBlocking {
            ManualSchedule.save(
                db,
                Seed(
                    courses = listOf(
                        Course(101, "高等数学A", teacher = "李明", short = "高数"),
                        Course(102, "大学英语", teacher = "王芳", short = "英语"),
                        Course(103, "程序设计基础（B）", teacher = "张伟", short = "程设"),
                    ),
                    slots = listOf(
                        Slot(1001, 1, 101, p_start = 1, p_end = 2, room = "A楼 I区212", weeks = "1,2,3,4,5"),
                        Slot(1002, 1, 102, p_start = 3, p_end = 4, room = "B楼 305", weeks = "1,2,3"),
                        Slot(1003, 2, 103, p_start = 1, p_end = 2, room = "II区308机房", weeks = "1,2,3"),
                        Slot(1004, 3, 101, p_start = 6, p_end = 7, room = "A楼 I区212", weeks = "1-8"),
                    ),
                    selfstudy = listOf(
                        SelfStudy(1, 1, "早自习", "08:45", "09:15", "A楼 I区301"),
                        SelfStudy(7, 1, "晚自习", "20:30", "22:05", "A楼 I区301"),
                    ),
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    /**
     * Robolectric 下 `captureToImage()` 会卡在 forceRedraw（拿不到窗口帧），
     * 改成**直接把 decorView 画进 Bitmap** —— 这是 Robolectric NATIVE 图形下可行的那条路。
     */
    private fun shot(name: String) {
        Thread.sleep(600)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(android.graphics.Canvas(bmp))
        val out = File("/tmp/$name.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT $name -> ${out.absolutePath} ${bmp.width}x${bmp.height}")
    }

    @Test
    fun `按天编辑页`() {
        rule.activity.setContent { CampusTheme { ScheduleEditor(rule.activity, db, {}) } }
        runCatching {
            rule.waitUntil(8_000) { rule.onAllNodesWithText("按天编辑").fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onNodeWithTag("day:1").performClick()
        shot("editor_day")
    }

    @Test
    fun `作息时间表页`() {
        rule.activity.setContent { CampusTheme { ScheduleEditor(rule.activity, db, {}) } }
        runCatching {
            rule.waitUntil(8_000) { rule.onAllNodesWithText("作息时间表").fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onNodeWithText("作息时间表").performClick()
        shot("editor_table")
    }

    @Test
    fun `作息时间表下半`() {
        rule.activity.setContent { CampusTheme { ScheduleEditor(rule.activity, db, {}) } }
        runCatching {
            rule.waitUntil(8_000) { rule.onAllNodesWithText("作息时间表").fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onNodeWithText("作息时间表").performClick()
        rule.onNodeWithText("保存作息表").performScrollTo()
        shot("editor_table_bottom")
    }

    @Test
    fun `按天页底部`() {
        rule.activity.setContent { CampusTheme { ScheduleEditor(rule.activity, db, {}) } }
        runCatching {
            rule.waitUntil(8_000) { rule.onAllNodesWithText("按天编辑").fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onNodeWithTag("day:1").performClick()
        rule.onNodeWithText("＋ 自定义时间的课（不按节次）").performScrollTo()
        shot("editor_day_bottom")
    }

    @Test
    fun `单条编辑表单`() {
        rule.activity.setContent { CampusTheme { ScheduleEditor(rule.activity, db, {}) } }
        runCatching {
            rule.waitUntil(8_000) { rule.onAllNodesWithText("按天编辑").fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onNodeWithTag("day:1").performClick()
        rule.onNodeWithTag("slot:1001").performScrollTo().performClick()
        shot("editor_form")
    }
}
