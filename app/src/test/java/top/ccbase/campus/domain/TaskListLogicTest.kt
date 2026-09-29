package top.ccbase.campus.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.seed.SeedImporter
import top.ccbase.campus.data.seed.SeedLoader

/**
 * 任务页分组与进度的交叉验证。
 * 基准值取自 study.db 真实统计：
 *   阶段 7 个（本学期14 / 寒假3 / 大一下5 / 大一暑假5 / 大二上4 / 大二下4 / 大三4）
 *   有步骤的任务 18 个 / 68 步；有资源的任务 33 个 / 165 条
 *   资源类型：doc 57 / practice 48 / video 36 / course 24
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskListLogicTest {

    private lateinit var db: CampusDb
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun sections() = runBlocking {
        SeedImporter.import(db, SeedLoader.load(ctx))
        groupTasks(
            tasks = db.dao().tasks().first(),
            steps = db.dao().allSteps().first(),
            resources = db.dao().resources().first(),
        )
    }

    @Test
    fun `阶段数与每阶段任务数与基准一致`() {
        val s = sections()
        assertEquals(7, s.size)
        // 2026-09-16 内容扩充：本学期 14→22（8 门公共课任务）
        assertEquals(listOf(22, 3, 5, 5, 4, 4, 4), s.map { it.rows.size })
        assertEquals(47, s.sumOf { it.rows.size })
    }

    @Test
    fun `阶段顺序按时间线而不是字符串`() {
        val s = sections()
        assertEquals(
            listOf("本学期", "寒假", "大一下", "大一暑假", "大二上", "大二下", "大三"),
            s.map { it.phase },
        )
    }

    @Test
    fun `全局步骤总数与基准一致且初始都没打勾`() {
        val s = sections()
        assertEquals(68, s.sumOf { it.rows.sumOf { r -> r.progress.total } })
        assertEquals(0, s.sumOf { it.rows.sumOf { r -> r.progress.done } })
        assertEquals(18, s.sumOf { it.rows.count { r -> r.progress.total > 0 } })
    }

    @Test
    fun `资源数与基准一致`() {
        val s = sections()
        assertEquals(211, s.sumOf { it.rows.sumOf { r -> r.resourceCount } })
        // 「有资源的任务数」33→41：新增的 8 门公共课任务各配了资源
        assertEquals(41, s.sumOf { it.rows.count { r -> r.resourceCount > 0 } })
    }

    @Test
    fun `打勾一个步骤后进度真的变了`() = runBlocking {
        sections()
        val step = db.dao().allSteps().first().first()
        db.dao().markStep(step.id, "2026-09-16")
        val after = groupTasks(db.dao().tasks().first(), db.dao().allSteps().first(), emptyList())
        assertEquals(1, after.sumOf { it.rows.sumOf { r -> r.progress.done } })
        db.dao().unmarkStep(step.id)
        val back = groupTasks(db.dao().tasks().first(), db.dao().allSteps().first(), emptyList())
        assertEquals(0, back.sumOf { it.rows.sumOf { r -> r.progress.done } })
    }

    @Test
    fun `分钟数文案说人话`() {
        assertEquals("", minutesText(0))
        assertEquals("35 分钟", minutesText(35))
        assertEquals("1 小时", minutesText(60))
        assertEquals("3 小时 30 分", minutesText(210))
    }

    @Test
    fun `步骤与资源类型都有中文标签不露内部值`() {
        // 名单必须从**真数据**里来。老版本只枚举了我记得的 watch/practice/read/do，
        // 而数据里真实存在 32 条 `produce`（数学分析（I）"抄错题本"、C 语言"产出 .c 文件"那些步骤）
        // —— 不在名单里，于是界面上一直露着英文 "produce"，直到 2026-09-18 我把真机数据摊开才看见。
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val steps = mutableSetOf<String>()
        val resources = mutableSetOf<String>()

        fun collect(o: JsonElement, path: String) {
            when (o) {
                is JsonObject -> for ((k, v) in o) {
                    val prim = v as? JsonPrimitive
                    if (k == "kind" && prim != null && prim.isString) {
                        when {
                            "steps" in path -> steps += prim.content
                            "resources" in path -> resources += prim.content
                        }
                    } else collect(v, "$path/$k")
                }
                is JsonArray -> for (v in o) collect(v, "$path[]")
                else -> Unit
            }
        }
        for (name in listOf("plan_templates.json", "seed.json")) {
            collect(Json.parseToJsonElement(ctx.assets.open(name).readBytes().decodeToString()), "")
        }

        assertTrue(
            "assets 里没扫到步骤/资源的 kind —— 扫描逻辑或数据形状变了（steps=${steps.size} resources=${resources.size}）",
            steps.isNotEmpty() && resources.isNotEmpty(),
        )
        for (k in steps) {
            assertTrue("步骤类型 $k 表里没有中文词，界面会露出内部值", stepKindLabel(k) != k && stepKindLabel(k).isNotEmpty())
        }
        for (k in resources) {
            assertTrue("资源类型 $k 表里没有中文词，界面会露出内部值", resourceKindLabel(k) != k && resourceKindLabel(k).isNotEmpty())
        }
        // 服务端词表（study-app/load_steps.py）：watch 看 / read 读 / practice 练 / produce 产出
        assertEquals("产出", stepKindLabel("produce"))
    }
}
