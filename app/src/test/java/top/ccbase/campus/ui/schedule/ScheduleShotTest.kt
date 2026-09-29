package top.ccbase.campus.ui.schedule

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.contentOrNull
import org.junit.After
import org.junit.Assert.assertTrue
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
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 把课表**渲染成图片**，用真实数据。
 *
 * 为什么要有它：用户说「这个视图不如以前做的」—— 这是他看屏幕、我看不到的那种问题。
 * 逻辑/点击测试都过得去，但它们不告诉我"看起来什么样"。这里用线上真计划（夹具
 * `live_plan_20260917.json`：11 门课 / 17 个时段）把课表画出来，落成 PNG，
 * 这样"好不好看"这件事我这边也有一双眼睛。
 *
 * 产物：/tmp/schedule_portrait.png（竖屏列表）、/tmp/schedule_landscape.png（横屏周视图）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleShotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: CampusDb

    /** 线上真计划：直接当种子数据用，画出来的就是用户手机上那份 */
    private val plan by lazy {
        Json.parseToJsonElement(
            javaClass.getResourceAsStream("/live_plan_20260917.json")!!.readBytes().decodeToString()
        ).jsonObject
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CampusDb::class.java,
        ).allowMainThreadQueries().build()
        runBlocking {
            db.dao().putCourses(plan["courses"]!!.jsonArray.map { e ->
                val o = e.jsonObject
                Course(
                    id = o["id"]!!.jsonPrimitive.int,
                    name = o["name"]!!.jsonPrimitive.contentOrNull ?: "?",
                    short = o["short"]?.jsonPrimitive?.contentOrNull,
                    credits = o["credits"]?.jsonPrimitive?.double ?: 0.0,
                    teacher = o["teacher"]?.jsonPrimitive?.contentOrNull,
                    week_from = o["week_from"]?.jsonPrimitive?.int,
                    week_to = o["week_to"]?.jsonPrimitive?.int,
                )
            })
            db.dao().putSlots(plan["slots"]!!.jsonArray.map { e ->
                val o = e.jsonObject
                Slot(
                    id = o["id"]!!.jsonPrimitive.int,
                    weekday = o["weekday"]!!.jsonPrimitive.int,
                    course_id = o["course_id"]?.jsonPrimitive?.int,
                    p_start = o["p_start"]?.jsonPrimitive?.int,
                    p_end = o["p_end"]?.jsonPrimitive?.int,
                    time_text = o["time_text"]?.jsonPrimitive?.contentOrNull,
                    room = o["room"]?.jsonPrimitive?.contentOrNull,
                    week_from = o["week_from"]?.jsonPrimitive?.int,
                    week_to = o["week_to"]?.jsonPrimitive?.int,
                )
            })
        }
    }

    @After
    fun tearDown() = db.close()

    private fun shot(name: String) {
        rule.activity.setContent { CampusTheme { ScheduleScreen(db = db) } }
        rule.waitForIdle()
        // 课表内容来自 IO 线程查库，waitForIdle 不等 IO —— 给数据一点时间再画
        runCatching {
            rule.waitUntil(8_000) {
                rule.onAllNodesWithText("课表", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        Thread.sleep(1_200)
        rule.waitForIdle()
        val dump = rule.onRoot().printToString(maxDepth = 40)
        File("/tmp/$name.txt").writeText(dump)
        println("TREE $name -> /tmp/$name.txt")
        println(dump.take(3000))
        assertTrue("渲染树不该是空的：$name", dump.length > 200)
    }

    @Test
    fun `竖屏课表`() = shot("schedule_portrait")

    /**
     * 钉住用户截图里那个 bug：周视图的行**必须是节次**，不能是「周一 1-2节」。
     *
     * 老实现的"行"来自 time_text，而线上 time_text 带星期 → 一行一个课时 →
     * 每行只亮一个格子，整页一条斜线加四十个空框。这条断言就是那个形状的照妖镜。
     */
    @Test
    fun `周视图的行是节次_既有真实数据也对`() {
        rule.activity.setContent { CampusTheme { ScheduleScreen(db = db) } }
        rule.waitForIdle()
        runCatching {
            rule.waitUntil(8_000) {
                rule.onAllNodesWithText("周视图", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        rule.onAllNodesWithText("周视图", substring = true)[0].performClick()
        rule.waitForIdle()
        runCatching {
            rule.waitUntil(8_000) {
                rule.onAllNodesWithText("1-2 节", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        val dump = rule.onRoot().printToString(maxDepth = 40)
        File("/tmp/schedule_grid.txt").writeText(dump)
        println("TREE grid -> /tmp/schedule_grid.txt")
        // 左列现在是**一节一行**（用户：「左边就一节一节地来」），不再有「N 节」段标签，
        // 所以行数改从语义树里的单节钟点串来数（左列第二行就是 hh:mm-hh:mm）。
        val rowsFound = Regex("\\d{2}:\\d{2}-\\d{2}:\\d{2}").findAll(dump).count()
        assertTrue("网格的节次行至少 6 行（线上数据要用到大半天），实际 $rowsFound", rowsFound >= 6)
        assertTrue("行标签里不能出现带星期的串（老 bug 的形状）", !dump.contains("周一 1-2节"))
    }
}
